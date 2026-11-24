package app.televault

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.televault.data.CancelToken
import app.televault.data.ChatInfo
import app.televault.data.Paths
import app.televault.data.SortMode
import app.televault.data.TelegramApi
import app.televault.data.Vault
import app.televault.data.VaultFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings get() = Vault.settings

    var configured by mutableStateOf(Vault.settings.isConfigured)
    var locked by mutableStateOf(false)
    var tab by mutableStateOf(0)

    var folder by mutableStateOf(Paths.ROOT)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var sort by mutableStateOf(SortMode.NAME)

    var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    var pendingSave: VaultFile? = null

    var busy by mutableStateOf(false)
    var setupError by mutableStateOf<String?>(null)
    var detected by mutableStateOf<List<ChatInfo>>(emptyList())

    var lockEnabled by mutableStateOf(Vault.settings.lockEnabled)
    var botName by mutableStateOf(Vault.settings.botName)
    var chatTitle by mutableStateOf(Vault.settings.chatTitle)

    var scanning by mutableStateOf(false)
    var scanStatus by mutableStateOf("")
    private var scanToken: CancelToken? = null

    init {
        if (configured) autoSync()
    }

    /** Runs silently in the background on every app start: scan chat, merge-restore index, back up index. */
    private fun autoSync() {
        viewModelScope.launch {
            scanning = true
            scanStatus = "Syncing…"
            try {
                try {
                    val n = Vault.scanNewPosts()
                    if (n > 0) toast("Found $n file(s) in your chat – see the Imported folder")
                } catch (e: Exception) { }
                try {
                    val n = Vault.mergeRestoreIndex()
                    if (n > 0) toast("Restored $n file(s) from your Telegram backup")
                } catch (e: Exception) { }
                try {
                    Vault.backupIndexIfChanged()
                } catch (e: Exception) { }
            } finally {
                scanning = false
            }
        }
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(text: String) {
        _messages.tryEmit(text)
    }

    private fun friendly(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"

    // ------------------------------------------------------------ setup

    fun detectChats(token: String) {
        if (token.isBlank()) {
            setupError = "Paste your bot token first"
            return
        }
        viewModelScope.launch {
            busy = true
            setupError = null
            try {
                val found = withContext(Dispatchers.IO) { TelegramApi(token.trim()).detectChats() }
                detected = found
                if (found.isEmpty()) {
                    setupError = "No chats found yet. Message your bot (or post in the channel where it is admin), then tap Detect again."
                }
            } catch (e: Exception) {
                setupError = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun connect(token: String, chat: String) {
        val t = token.trim()
        val c = chat.trim()
        if (t.isEmpty() || c.isEmpty()) {
            setupError = "Enter both the bot token and the chat"
            return
        }
        viewModelScope.launch {
            busy = true
            setupError = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val api = TelegramApi(t)
                    val bot = api.getMe()
                    val ch = api.getChat(c)
                    bot to ch
                }
                Vault.settings.botToken = t
                Vault.settings.chatId = c
                Vault.settings.botName = result.first.username
                Vault.settings.chatTitle = result.second.title
                Vault.settings.chatNumId = result.second.id
                Vault.settings.scanCursor = 0L
                botName = result.first.username
                chatTitle = result.second.title
                configured = true
                if (Vault.index.value.files.isEmpty()) {
                    try {
                        val n = Vault.restoreIndex()
                        if (n > 0) toast("Restored $n files from your Telegram backup")
                    } catch (e: Exception) {
                        // no backup yet – totally fine
                    }
                }
                scanNewPosts(silent = true)
            } catch (e: Exception) {
                setupError = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        Vault.resetAll()
        configured = false
        lockEnabled = false
        folder = Paths.ROOT
        detected = emptyList()
        tab = 0
    }

    // ------------------------------------------------------- navigation

    fun openFolder(path: String) {
        folder = path
        searching = false
        query = ""
    }

    fun goUp() {
        folder = Paths.parent(folder)
    }

    // ------------------------------------------------- import chat files

    /** Quick scan: files posted to the chat since the last scan (Telegram keeps them 24 h). */
    fun scanNewPosts(silent: Boolean = false) {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            scanStatus = "Checking your chat…"
            try {
                val n = Vault.scanNewPosts()
                if (n > 0) toast("Found $n file(s) in your chat – see the Imported folder")
                else if (!silent) toast("No new files found. For older files, use Deep scan.")
            } catch (e: Exception) {
                if (!silent) toast("Scan failed: ${friendly(e)}")
            } finally {
                scanning = false
            }
        }
    }

    /** Deep scan: walks the whole chat history (slow, can be stopped and resumed). */
    fun deepScan() {
        if (scanning) return
        val token = CancelToken()
        scanToken = token
        viewModelScope.launch {
            scanning = true
            scanStatus = "Starting deep scan…"
            try {
                val r = Vault.deepScan(token) { done, total, found ->
                    scanStatus = "Scanning old messages… $done / $total • $found files"
                }
                when {
                    !r.finished -> toast("Scan paused at message ${r.scanned} (${r.added} files added). Run it again to continue.")
                    r.added > 0 -> toast("Found ${r.added} file(s) – see the Imported folder")
                    else -> toast("Scan finished – no other files found")
                }
            } catch (e: Exception) {
                toast("Scan failed: ${friendly(e)}")
            } finally {
                scanning = false
                scanToken = null
            }
        }
    }

    fun stopScan() {
        scanToken?.cancel()
    }

    // ---------------------------------------------------------- settings

    fun setLock(on: Boolean) {
        lockEnabled = on
        Vault.settings.lockEnabled = on
    }

    fun backupNow() {
        viewModelScope.launch {
            toast("Backing up index…")
            try {
                val warn = Vault.backupIndex()
                toast(warn ?: "Index backed up and pinned in your chat")
            } catch (e: Exception) {
                toast("Backup failed: ${friendly(e)}")
            }
        }
    }

    fun restoreNow() {
        viewModelScope.launch {
            try {
                val n = Vault.restoreIndex()
                toast("Restored $n files")
            } catch (e: Exception) {
                toast("Restore failed: ${friendly(e)}")
            }
        }
    }

    // ------------------------------------------------------ file actions

    fun createFolder(name: String) {
        val err = Vault.createFolder(folder, name)
        if (err != null) toast(err)
    }

    fun rename(file: VaultFile, name: String) {
        val err = Vault.renameFile(file.id, name)
        if (err != null) toast(err)
    }

    fun move(file: VaultFile, target: String) {
        Vault.moveFile(file.id, target)
    }

    fun moveAll(from: String, target: String) {
        val n = Vault.moveAll(from, target)
        toast("Moved $n file(s) to ${if (target == Paths.ROOT) "Home" else target}")
    }

    private fun deleteMessage(ok: String, r: Vault.DeleteReport): String = when {
        r.failed > 0 -> "Removed from TeleVault, but ${r.failed} Telegram message(s) are still in the chat " +
            "(Telegram refuses to delete messages older than 48h). Make the bot an admin with " +
            "\"Delete messages\" permission, or delete them in the chat yourself."
        r.blanked > 0 -> "$ok. ${r.blanked} old message(s) couldn't be deleted, so their files were replaced " +
            "with a tiny placeholder – you can clear those in the chat."
        else -> ok
    }

    fun deleteFile(file: VaultFile) {
        viewModelScope.launch {
            try {
                toast(deleteMessage("Deleted ${file.name}", Vault.deleteFile(file.id)))
            } catch (e: Exception) {
                toast("Delete failed: ${friendly(e)}")
            }
        }
    }

    fun deleteFolder(path: String) {
        viewModelScope.launch {
            try {
                toast(deleteMessage("Folder deleted", Vault.deleteFolder(path)))
            } catch (e: Exception) {
                toast("Delete failed: ${friendly(e)}")
            }
        }
    }
}
