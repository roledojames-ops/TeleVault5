package app.televault.data

import okhttp3.Call
import java.io.File
import java.io.IOException

/** One piece of a stored file. Big files are split into several Telegram documents. */
data class Chunk(
    val index: Int,
    val messageId: Long,
    val fileId: String,
    val size: Long,
)

data class VaultFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val folder: String,
    val createdAt: Long,
    val chunks: List<Chunk>,
)

/** The whole "drive": virtual folders + files. Persisted locally and backed up to Telegram. */
data class VaultIndex(
    val folders: Set<String> = emptySet(),
    val files: List<VaultFile> = emptyList(),
)

data class BotInfo(val id: Long, val username: String, val name: String)
data class ChatInfo(val id: Long, val title: String, val type: String)
data class SentDoc(val messageId: Long, val fileId: String, val size: Long)

/** A media message that already exists in the chat (not necessarily uploaded by TeleVault). */
data class ChatFile(
    val messageId: Long,
    val fileId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val dateMs: Long,
    val caption: String,
)

enum class SortMode(val label: String) {
    NAME("Name"),
    DATE("Newest"),
    SIZE("Largest"),
}

enum class TState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class TransferItem(
    val id: String,
    val title: String,
    val isUpload: Boolean,
    val state: TState,
    val done: Long,
    val total: Long,
    val error: String? = null,
) {
    val active: Boolean get() = state == TState.QUEUED || state == TState.RUNNING
}

sealed interface TransferEvent {
    data class OpenFile(val file: File, val mime: String) : TransferEvent
    data class Info(val text: String) : TransferEvent
}

class TransferCancelled : Exception("Cancelled")

/** Lets the UI abort a transfer, even in the middle of a blocking network call. */
class CancelToken {
    @Volatile
    var cancelled: Boolean = false

    @Volatile
    var call: Call? = null

    fun cancel() {
        cancelled = true
        call?.cancel()
    }
}

class TelegramException(
    message: String,
    val code: Int = 0,
    val retryAfter: Int = 0,
) : IOException(message)

/** Virtual folder paths: "/" is the root, "/Docs/Taxes" is a nested folder. */
object Paths {
    const val ROOT = "/"

    fun child(parent: String, name: String): String =
        if (parent == ROOT) "/$name" else "$parent/$name"

    fun parent(path: String): String {
        if (path == ROOT) return ROOT
        val i = path.lastIndexOf('/')
        return if (i <= 0) ROOT else path.substring(0, i)
    }

    fun name(path: String): String = if (path == ROOT) "Home" else path.substringAfterLast('/')

    /** True when [path] is [folder] itself or lives somewhere below it. [folder] must not be the root. */
    fun isInside(path: String, folder: String): Boolean =
        path == folder || path.startsWith("$folder/")
}
