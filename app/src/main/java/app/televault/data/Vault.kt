package app.televault.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/**
 * The "drive". Files live in your own Telegram chat/channel as ordinary documents
 * (big files are split into chunks); the folder tree and chunk list live in a small
 * JSON index that is kept on the phone and can be backed up to Telegram.
 */
object Vault {
    /** Telegram bots can only download files up to 20 MB, so stay safely below that. */
    const val CHUNK_SIZE = 18L * 1024 * 1024

    const val INDEX_NAME = "televault-index.json"
    private const val INDEX_TAG = "#televault_index"

    /** Files found in the chat that TeleVault didn't upload land here until you move them. */
    const val IMPORT_FOLDER = "/Imported"

    /** Telegram bots can't download anything bigger than this. */
    const val BOT_DOWNLOAD_LIMIT = 20L * 1024 * 1024

    private val PART_RE = Regex("""^TeleVault \u2022 (.+) \u2022 part (\d+)/(\d+)$""")

    private lateinit var app: Context

    lateinit var settings: Settings
        private set

    private val _index = MutableStateFlow(VaultIndex())
    val index: StateFlow<VaultIndex> = _index.asStateFlow()

    private val fileLock = Any()
    private val indexFile: File get() = File(app.filesDir, "index.json")

    fun init(context: Context) {
        app = context.applicationContext
        settings = Settings(app)
        File(app.cacheDir, "open").deleteRecursively()
        File(app.cacheDir, "chunks").deleteRecursively()
        _index.value = try {
            if (indexFile.exists()) IndexJson.fromJson(indexFile.readText()) else VaultIndex()
        } catch (e: Exception) {
            VaultIndex()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun api(): TelegramApi = TelegramApi(settings.botToken)

    private fun cacheSub(name: String): File = File(app.cacheDir, name).apply { mkdirs() }

    fun openCacheDir(): File = cacheSub("open")

    fun openOutput(uri: Uri): OutputStream =
        app.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Can't write to that location")

    private fun persist() {
        synchronized(fileLock) {
            val tmp = File(app.filesDir, "index.json.tmp")
            tmp.writeText(IndexJson.toJson(_index.value))
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val finalName = (name ?: uri.lastPathSegment ?: "file").replace('/', '_')
        return finalName to size
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uniqueName(index: VaultIndex, folder: String, wanted: String): String {
        val taken = index.files.filter { it.folder == folder }.map { it.name.lowercase(Locale.ROOT) }.toHashSet()
        return uniqueIn(taken, wanted)
    }

    /** Picks "name", "name (1)", … that isn't in [taken] (lower-cased) and reserves it there. */
    private fun uniqueIn(taken: MutableSet<String>, wanted: String): String {
        var result = wanted
        if (wanted.lowercase(Locale.ROOT) in taken) {
            val dot = wanted.lastIndexOf('.')
            val base = if (dot > 0) wanted.substring(0, dot) else wanted
            val ext = if (dot > 0) wanted.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$base ($n)$ext"
                if (candidate.lowercase(Locale.ROOT) !in taken) { result = candidate; break }
                n++
            }
        }
        taken.add(result.lowercase(Locale.ROOT))
        return result
    }

    private fun shorten(s: String): String = if (s.length > 180) s.take(177) + "…" else s

    private fun sleepCancelable(ms: Long, cancel: CancelToken) {
        var left = ms
        while (left > 0) {
            if (cancel.cancelled) throw TransferCancelled()
            val step = minOf(200L, left)
            Thread.sleep(step)
            left -= step
        }
    }

    // ---------------------------------------------------------------- upload

    private fun sendWithRetry(
        api: TelegramApi,
        chat: String,
        file: File,
        name: String,
        caption: String,
        cancel: CancelToken,
        onSent: (Long) -> Unit,
    ): SentDoc {
        var attempt = 0
        while (true) {
            var sentThisTry = 0L
            try {
                return api.sendDocument(chat, file, name, caption, cancel) { n ->
                    sentThisTry += n
                    onSent(sentThisTry)
                }
            } catch (e: TelegramException) {
                if (cancel.cancelled) throw TransferCancelled()
                attempt++
                if (e.retryAfter > 0 && attempt <= 6) {
                    sleepCancelable(e.retryAfter * 1000L + 500L, cancel)
                    continue
                }
                throw e
            } catch (e: IOException) {
                if (cancel.cancelled) throw TransferCancelled()
                attempt++
                if (attempt > 4) throw e
                sleepCancelable(1500L * attempt, cancel)
            }
        }
    }

    suspend fun upload(
        uri: Uri,
        folder: String,
        cancel: CancelToken,
        onMeta: (String, Long) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val (name, size) = queryMeta(uri)
            onMeta(name, size)
            if (size == 0L) throw IOException("\"$name\" is empty")
            val mime = app.contentResolver.getType(uri) ?: guessMime(name)
            val api = api()
            val chat = settings.chatId
            val fileId = UUID.randomUUID().toString().replace("-", "").take(12)
            val tmp = File(cacheSub("chunks"), "up_$fileId.tmp")
            val done = mutableListOf<Chunk>()
            var ok = false
            try {
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't open \"$name\"")
                val parts = if (size > 0) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt() else -1
                input.use { ins ->
                    var base = 0L
                    var idx = 0
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancel.cancelled) throw TransferCancelled()
                        var written = 0L
                        tmp.outputStream().use { out ->
                            while (written < CHUNK_SIZE) {
                                val want = minOf(buf.size.toLong(), CHUNK_SIZE - written).toInt()
                                val n = ins.read(buf, 0, want)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                            }
                        }
                        if (written == 0L) break
                        val single = parts == 1
                        val partName = if (single) name else "$name.part" + (idx + 1).toString().padStart(3, '0')
                        val label = shorten(name)
                        val caption = if (single) {
                            "TeleVault • $label"
                        } else {
                            "TeleVault • $label • part ${idx + 1}" + (if (parts > 0) "/$parts" else "")
                        }
                        val baseNow = base
                        val sent = sendWithRetry(api, chat, tmp, partName, caption, cancel) { s ->
                            onProgress(baseNow + s, size)
                        }
                        done.add(Chunk(idx, sent.messageId, sent.fileId, written))
                        base += written
                        idx++
                        onProgress(base, size)
                    }
                }
                if (done.isEmpty()) throw IOException("\"$name\" is empty")
                val realSize = done.sumOf { it.size }
                val newFile = VaultFile(
                    id = fileId,
                    name = name,
                    size = realSize,
                    mime = mime,
                    folder = folder,
                    createdAt = System.currentTimeMillis(),
                    chunks = done.toList(),
                )
                _index.update { cur ->
                    val unique = uniqueName(cur, folder, name)
                    cur.copy(files = cur.files + newFile.copy(name = unique))
                }
                persist()
                ok = true
            } finally {
                tmp.delete()
                if (!ok && done.isNotEmpty()) {
                    for (c in done) {
                        try {
                            api.deleteMessage(chat, c.messageId)
                        } catch (e: Exception) {
                            // best effort clean-up of a half-finished upload
                        }
                    }
                }
            }
            Unit
        }
    }

    // -------------------------------------------------------------- download

    private fun downloadChunk(
        api: TelegramApi,
        chunk: Chunk,
        dest: File,
        cancel: CancelToken,
        onBytes: (Long) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            try {
                val path = api.getFilePath(chunk.fileId, cancel)
                var got = 0L
                dest.outputStream().use { o ->
                    api.download(path, o, cancel) { n ->
                        got += n
                        onBytes(got)
                    }
                }
                return
            } catch (e: TelegramException) {
                if (cancel.cancelled) throw TransferCancelled()
                attempt++
                if (e.retryAfter > 0 && attempt <= 6) {
                    sleepCancelable(e.retryAfter * 1000L + 500L, cancel)
                    continue
                }
                throw e
            } catch (e: IOException) {
                if (cancel.cancelled) throw TransferCancelled()
                attempt++
                if (attempt > 3) throw e
                sleepCancelable(1500L * attempt, cancel)
            }
        }
    }

    suspend fun download(
        file: VaultFile,
        out: OutputStream,
        cancel: CancelToken,
        onProgress: (Long, Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val api = api()
            val dir = cacheSub("chunks")
            var base = 0L
            for (c in file.chunks.sortedBy { it.index }) {
                if (cancel.cancelled) throw TransferCancelled()
                val tmp = File(dir, "dl_${file.id}_${c.index}.tmp")
                try {
                    val baseNow = base
                    downloadChunk(api, c, tmp, cancel) { n -> onProgress(baseNow + n, file.size) }
                    tmp.inputStream().use { it.copyTo(out) }
                    base += c.size
                } finally {
                    tmp.delete()
                }
            }
            out.flush()
        }
    }

    // ------------------------------------------------------- index operations

    fun createFolder(parent: String, rawName: String): String? {
        val name = rawName.trim().replace("/", "")
        if (name.isEmpty()) return "Folder name can't be empty"
        val path = Paths.child(parent, name)
        if (_index.value.folders.any { it.equals(path, ignoreCase = true) }) return "That folder already exists"
        _index.update { it.copy(folders = it.folders + path) }
        persist()
        return null
    }

    fun renameFile(id: String, rawName: String): String? {
        val name = rawName.trim().replace("/", "")
        if (name.isEmpty()) return "Name can't be empty"
        _index.update { cur ->
            cur.copy(files = cur.files.map { if (it.id == id) it.copy(name = name) else it })
        }
        persist()
        return null
    }

    fun moveFile(id: String, folder: String) {
        _index.update { cur ->
            val target = cur.files.firstOrNull { it.id == id }
            if (target == null) {
                cur
            } else {
                val unique = uniqueName(cur, folder, target.name)
                cur.copy(files = cur.files.map { if (it.id == id) it.copy(folder = folder, name = unique) else it })
            }
        }
        persist()
    }

    /** Moves every file that sits directly in [from] into [folder]. Returns how many were moved. */
    fun moveAll(from: String, folder: String): Int {
        var moved = 0
        _index.update { cur ->
            val taken = cur.files.filter { it.folder == folder }.map { it.name.lowercase(Locale.ROOT) }.toHashSet()
            val files = cur.files.map { f ->
                if (f.folder == from) f.copy(folder = folder, name = uniqueIn(taken, f.name)) else f
            }
            moved = cur.files.count { it.folder == from }
            cur.copy(files = files)
        }
        persist()
        return moved
    }

    /**
     * [blanked]: messages Telegram refused to delete (usually older than 48 h) whose file was
     * replaced by a tiny placeholder instead. [failed]: messages that are still untouched in the chat.
     */
    data class DeleteReport(val blanked: Int = 0, val failed: Int = 0)

    private fun deleteMessages(files: List<VaultFile>): DeleteReport {
        val api = api()
        val chat = settings.chatId
        var blanked = 0
        var failed = 0
        for (f in files) {
            for (c in f.chunks) {
                try {
                    api.deleteMessage(chat, c.messageId)
                } catch (e: Exception) {
                    try {
                        api.blankMessage(chat, c.messageId)
                        blanked++
                    } catch (e2: Exception) {
                        failed++
                    }
                }
            }
        }
        return DeleteReport(blanked, failed)
    }

    suspend fun deleteFile(id: String): DeleteReport = withContext(Dispatchers.IO) {
        val f = _index.value.files.firstOrNull { it.id == id } ?: return@withContext DeleteReport()
        val report = deleteMessages(listOf(f))
        settings.deletedIds = settings.deletedIds + id
        _index.update { cur -> cur.copy(files = cur.files.filter { it.id != id }) }
        persist()
        report
    }

    suspend fun deleteFolder(path: String): DeleteReport = withContext(Dispatchers.IO) {
        if (path == Paths.ROOT) return@withContext DeleteReport()
        val doomed = _index.value.files.filter { Paths.isInside(it.folder, path) }
        val report = deleteMessages(doomed)
        val ids = doomed.map { it.id }.toSet()
        settings.deletedIds = settings.deletedIds + ids
        _index.update { cur ->
            cur.copy(
                folders = cur.folders.filter { !Paths.isInside(it, path) }.toSet(),
                files = cur.files.filter { it.id !in ids },
            )
        }
        persist()
        report
    }

    // --------------------------------------------------- import existing chat files

    private fun isOwnHousekeeping(c: ChatFile): Boolean =
        c.name == INDEX_NAME || c.caption.startsWith(INDEX_TAG) || c.caption.contains("removed by TeleVault")

    /**
     * Adds files that exist in the chat but not in the index. Parts that TeleVault itself uploaded
     * ("TeleVault • name • part 2/3") are stitched back into one file when all parts are present.
     * Returns how many files were added.
     */
    private fun addImported(found: List<ChatFile>): Int {
        if (found.isEmpty()) return 0
        var added = 0
        _index.update { cur ->
            val known = HashSet<Long>()
            for (f in cur.files) for (c in f.chunks) known.add(c.messageId)
            val fresh = found.filter { it.messageId !in known && !isOwnHousekeeping(it) }
                .distinctBy { it.messageId }
            if (fresh.isEmpty()) {
                added = 0
                return@update cur
            }

            val groups = LinkedHashMap<Pair<String, Int>, MutableList<Pair<Int, ChatFile>>>()
            val singles = ArrayList<ChatFile>()
            for (c in fresh) {
                val m = PART_RE.matchEntire(c.caption.trim())
                if (m == null) {
                    singles.add(c)
                } else {
                    val key = m.groupValues[1] to m.groupValues[3].toInt()
                    groups.getOrPut(key) { ArrayList() }.add(m.groupValues[2].toInt() to c)
                }
            }

            val taken = cur.files.filter { it.folder == IMPORT_FOLDER }
                .map { it.name.lowercase(Locale.ROOT) }.toHashSet()
            val out = ArrayList<VaultFile>()

            fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

            for ((key, list) in groups) {
                val (name, total) = key
                val sorted = list.sortedBy { it.first }
                val complete = sorted.map { it.first } == (1..total).toList()
                if (complete) {
                    val chunks = sorted.mapIndexed { i, (_, c) -> Chunk(i, c.messageId, c.fileId, c.size) }
                    out.add(
                        VaultFile(
                            id = newId(),
                            name = uniqueIn(taken, name),
                            size = chunks.sumOf { it.size },
                            mime = guessMime(name),
                            folder = IMPORT_FOLDER,
                            createdAt = sorted.first().second.dateMs,
                            chunks = chunks,
                        )
                    )
                } else {
                    singles.addAll(sorted.map { it.second })
                }
            }
            for (c in singles) {
                out.add(
                    VaultFile(
                        id = newId(),
                        name = uniqueIn(taken, c.name),
                        size = c.size,
                        mime = if (c.mime == "application/octet-stream") guessMime(c.name) else c.mime,
                        folder = IMPORT_FOLDER,
                        createdAt = c.dateMs,
                        chunks = listOf(Chunk(0, c.messageId, c.fileId, c.size)),
                    )
                )
            }
            added = out.size
            cur.copy(folders = cur.folders + IMPORT_FOLDER, files = cur.files + out)
        }
        if (added > 0) persist()
        return added
    }

    private fun vaultChatNumericId(api: TelegramApi): Long {
        var id = settings.chatNumId
        if (id == 0L) {
            id = api.getChat(settings.chatId).id
            settings.chatNumId = id
        }
        return id
    }

    /**
     * Quick scan: reads what Telegram has queued for the bot (new posts in the chat, kept for 24 h)
     * and imports any files that aren't in the index yet. Returns the number of new files.
     */
    suspend fun scanNewPosts(): Int = withContext(Dispatchers.IO) {
        val api = api()
        val chatNum = vaultChatNumericId(api)
        var offset = settings.updateOffset
        val found = ArrayList<ChatFile>()
        var rounds = 0
        while (rounds++ < 50) {
            val arr = api.getUpdates(offset)
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i)
                offset = maxOf(offset, u.optLong("update_id") + 1)
                val msg = u.optJSONObject("channel_post")
                    ?: u.optJSONObject("edited_channel_post")
                    ?: u.optJSONObject("message")
                    ?: continue
                val chat = msg.optJSONObject("chat") ?: continue
                val cid = chat.optLong("id")
                if (cid != chatNum) {
                    // Remember a private chat with the bot: the deep scan needs somewhere to forward to.
                    if (chat.optString("type") == "private" && settings.scanChat.isBlank()) {
                        settings.scanChat = cid.toString()
                    }
                    continue
                }
                api.parseFile(msg, msg.optLong("message_id"))?.let { found.add(it) }
            }
            if (arr.length() < 100) break
        }
        val added = addImported(found)
        settings.updateOffset = offset
        added
    }

    data class DeepScanResult(val added: Int, val scanned: Long, val finished: Boolean)

    private fun scanDestination(): String? {
        if (settings.scanChat.isNotBlank()) return settings.scanChat
        // If the vault chat itself is a private chat with the bot, it can be used directly.
        return settings.chatId.takeIf { !it.startsWith("-") && !it.startsWith("@") }
    }

    /**
     * Deep scan: the Bot API can't list old messages, so this walks the message ids of the chat and
     * forwards each one to a private chat with the bot just to read its file, deleting the copy right
     * away. Resumes where the last scan stopped.
     */
    suspend fun deepScan(
        cancel: CancelToken,
        onProgress: (done: Long, total: Long, found: Int) -> Unit,
    ): DeepScanResult = withContext(Dispatchers.IO) {
        val api = api()
        vaultChatNumericId(api)
        val chat = settings.chatId
        val dest = scanDestination()
            ?: throw IOException("Open a private chat with your bot, send it /start, then run the scan again.")

        val top = api.probeLatestMessageId(chat)
        val known = HashSet<Long>()
        for (f in _index.value.files) for (c in f.chunks) known.add(c.messageId)

        val found = ArrayList<ChatFile>()
        var id = settings.scanCursor + 1
        var lastDone = settings.scanCursor
        var finished = false
        try {
            while (id <= top) {
                if (cancel.cancelled) break
                if (id !in known) {
                    val file = forwardOne(api, dest, chat, id, cancel)
                    if (file != null) found.add(file)
                    sleepCancelable(if (file != null) 350L else 60L, cancel)
                }
                lastDone = id
                id++
                onProgress(lastDone, top, found.size)
            }
            finished = id > top
        } catch (e: TransferCancelled) {
            // stopped by the user – keep what we have
        } catch (e: IOException) {
            if (!cancel.cancelled) {
                val added = addImported(found)
                settings.scanCursor = lastDone
                throw IOException("Stopped at message $lastDone ($added files added): ${e.message}")
            }
        }
        val added = addImported(found)
        settings.scanCursor = lastDone
        DeepScanResult(added, lastDone, finished)
    }

    /** Forwards message [id] to [dest], reads its file and removes the copy. Null = nothing to import. */
    private fun forwardOne(api: TelegramApi, dest: String, chat: String, id: Long, cancel: CancelToken): ChatFile? {
        var attempt = 0
        while (true) {
            try {
                val copy = api.forwardMessage(dest, chat, id, cancel)
                val file = api.parseFile(copy, id)
                try {
                    api.deleteMessage(dest, copy.getLong("message_id"))
                } catch (e: Exception) {
                    // a stray copy in your private chat is harmless
                }
                return file
            } catch (e: TelegramException) {
                if (cancel.cancelled) throw TransferCancelled()
                if (e.retryAfter > 0 && attempt++ < 8) {
                    sleepCancelable(e.retryAfter * 1000L + 500L, cancel)
                    continue
                }
                // deleted messages, service messages and protected content can't be forwarded: skip them
                if (e.code == 400 && e.message.orEmpty().contains("forward", ignoreCase = true)) return null
                throw e
            } catch (e: IOException) {
                if (cancel.cancelled) throw TransferCancelled()
                if (attempt++ >= 3) throw e
                sleepCancelable(1500L * attempt, cancel)
            }
        }
    }

    // ------------------------------------------------------------ index backup

    /** Uploads the index to Telegram and pins it. Returns a warning text if pinning failed. */
    suspend fun backupIndex(): String? = withContext(Dispatchers.IO) {
        val api = api()
        val chat = settings.chatId
        val tmp = File(cacheSub("chunks"), "index_backup.json")
        tmp.writeText(IndexJson.toJson(_index.value))
        try {
            val count = _index.value.files.size
            val sent = api.sendDocument(chat, tmp, INDEX_NAME, "$INDEX_TAG • $count files", null) { }
            var warning: String? = null
            var pinned = false
            try {
                api.pinMessage(chat, sent.messageId)
                pinned = true
            } catch (e: Exception) {
                warning = "Backup uploaded, but it couldn't be pinned (${e.message}). " +
                    "Give the bot permission to pin/edit messages so a reinstall can restore your files."
            }
            val old = settings.lastBackupMsgId
            if (pinned && old != 0L) {
                try {
                    api.deleteMessage(chat, old)
                } catch (e: Exception) {
                    // old backup may be older than 48 h – harmless
                }
            }
            settings.lastBackupMsgId = sent.messageId
            warning
        } finally {
            tmp.delete()
        }
    }

    /** Replaces the local index with the pinned backup from the chat. Returns the number of files. */
    suspend fun restoreIndex(): Int = withContext(Dispatchers.IO) {
        val api = api()
        val chat = settings.chatId
        val fileId = api.getPinnedDocumentId(chat, INDEX_NAME)
            ?: throw IOException("No index backup is pinned in this chat yet")
        val tmp = File(cacheSub("chunks"), "index_restore.json")
        try {
            val path = api.getFilePath(fileId, null)
            tmp.outputStream().use { o -> api.download(path, o, null) { } }
            val restored = IndexJson.fromJson(tmp.readText())
            _index.value = restored
            persist()
            restored.files.size
        } finally {
            tmp.delete()
        }
    }

    /** Adds files/folders from the pinned backup that are missing locally. Never overwrites or removes anything. */
    suspend fun mergeRestoreIndex(): Int = withContext(Dispatchers.IO) {
        val api = api()
        val chat = settings.chatId
        val fileId = api.getPinnedDocumentId(chat, INDEX_NAME) ?: return@withContext 0
        val tmp = File(cacheSub("chunks"), "index_restore.json")
        try {
            val path = api.getFilePath(fileId, null)
            tmp.outputStream().use { o -> api.download(path, o, null) { } }
            val remote = IndexJson.fromJson(tmp.readText())
            val deleted = settings.deletedIds
            var added = 0
            _index.update { cur ->
                val haveIds = cur.files.map { it.id }.toSet()
                val haveMsgs = cur.files.flatMap { f -> f.chunks.map { it.messageId } }.toSet()
                val extra = remote.files.filter { f ->
                    f.id !in haveIds && f.id !in deleted &&
                        f.chunks.none { it.messageId in haveMsgs }
                }
                added = extra.size
                cur.copy(folders = cur.folders + remote.folders, files = cur.files + extra)
            }
            if (added > 0) persist()
            added
        } finally {
            tmp.delete()
        }
    }

    /** Backs up the index only if it changed since the last backup (and isn't empty). */
    suspend fun backupIndexIfChanged() {
        val idx = _index.value
        if (idx.files.isEmpty() && idx.folders.isEmpty()) return
        val h = IndexJson.toJson(idx).hashCode()
        if (h == settings.lastBackupHash) return
        backupIndex()
        settings.lastBackupHash = h
    }

    fun resetAll() {
        settings.clear()
        _index.value = VaultIndex()
        indexFile.delete()
    }
}
