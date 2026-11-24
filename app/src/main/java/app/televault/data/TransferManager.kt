package app.televault.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Runs uploads/downloads in the background (two at a time) and publishes their progress. */
object TransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val tokens = ConcurrentHashMap<String, CancelToken>()

    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items.asStateFlow()

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    @Volatile
    private var backupJob: Job? = null

    // ------------------------------------------------------------------ API

    fun enqueueUploads(ctx: Context, uris: List<Uri>, folder: String) {
        if (uris.isEmpty()) return
        for (uri in uris) {
            val id = newId()
            _items.update { it + TransferItem(id, uri.lastPathSegment ?: "file", true, TState.QUEUED, 0L, -1L) }
            launchJob(id, afterSuccess = { scheduleBackup() }) { token ->
                Vault.upload(
                    uri = uri,
                    folder = folder,
                    cancel = token,
                    onMeta = { name, size -> update(id) { it.copy(title = name, total = size) } },
                    onProgress = progressCallback(id),
                )
                _events.tryEmit(TransferEvent.Info("Uploaded ${titleOf(id)}"))
            }
        }
        TransferService.start(ctx.applicationContext)
    }

    fun enqueueSave(ctx: Context, file: VaultFile, dest: Uri) {
        val id = newId()
        _items.update { it + TransferItem(id, file.name, false, TState.QUEUED, 0L, file.size) }
        launchJob(id) { token ->
            Vault.openOutput(dest).use { out ->
                Vault.download(file, out, token, progressCallback(id))
            }
            _events.tryEmit(TransferEvent.Info("Saved ${file.name}"))
        }
        TransferService.start(ctx.applicationContext)
    }

    fun enqueueOpen(ctx: Context, file: VaultFile) {
        val id = newId()
        _items.update { it + TransferItem(id, file.name, false, TState.QUEUED, 0L, file.size) }
        launchJob(id) { token ->
            val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(Vault.openCacheDir(), "${file.id}_$safe")
            if (!(target.exists() && target.length() == file.size)) {
                val part = File(target.path + ".part")
                try {
                    FileOutputStream(part).use { out ->
                        Vault.download(file, out, token, progressCallback(id))
                    }
                    target.delete()
                    part.renameTo(target)
                } finally {
                    part.delete()
                }
            }
            _events.tryEmit(TransferEvent.OpenFile(target, file.mime))
        }
        TransferService.start(ctx.applicationContext)
    }

    fun cancel(id: String) {
        tokens[id]?.cancel()
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.active } }
    }

    fun activeCount(): Int = _items.value.count { it.active }

    // ------------------------------------------------------------- internals

    private fun newId(): String = UUID.randomUUID().toString()

    private fun titleOf(id: String): String = _items.value.firstOrNull { it.id == id }?.title ?: "file"

    private fun update(id: String, f: (TransferItem) -> TransferItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun progressCallback(id: String): (Long, Long) -> Unit {
        var last = 0L
        return { done, total ->
            val now = SystemClock.elapsedRealtime()
            if (now - last > 150L || (total > 0 && done >= total)) {
                last = now
                update(id) { it.copy(done = done, total = if (total > 0) total else it.total) }
            }
        }
    }

    private fun launchJob(
        id: String,
        afterSuccess: (() -> Unit)? = null,
        block: suspend (CancelToken) -> Unit,
    ) {
        val token = CancelToken()
        tokens[id] = token
        scope.launch {
            gate.withPermit {
                if (token.cancelled) {
                    update(id) { it.copy(state = TState.CANCELLED) }
                } else {
                    update(id) { it.copy(state = TState.RUNNING) }
                    try {
                        block(token)
                        update(id) { it.copy(state = TState.DONE, done = if (it.total > 0) it.total else it.done) }
                        afterSuccess?.invoke()
                    } catch (e: TransferCancelled) {
                        update(id) { it.copy(state = TState.CANCELLED) }
                    } catch (e: Exception) {
                        if (token.cancelled) {
                            update(id) { it.copy(state = TState.CANCELLED) }
                        } else {
                            val msg = e.message ?: e.javaClass.simpleName
                            update(id) { it.copy(state = TState.FAILED, error = msg) }
                        }
                    }
                }
            }
            tokens.remove(id)
        }
    }

    /** After a burst of uploads, push a fresh copy of the index to Telegram (if the user wants that). */
    private fun scheduleBackup() {
        backupJob?.cancel()
        backupJob = scope.launch {
            delay(5000L)
            val uploading = _items.value.any { it.isUpload && it.active }
            if (!uploading) {
                try {
                    Vault.backupIndex()
                } catch (e: Exception) {
                    // not fatal – the user can retry from Settings
                }
            }
        }
    }
}
