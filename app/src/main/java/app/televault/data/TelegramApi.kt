package app.televault.data

import android.webkit.MimeTypeMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the official Telegram Bot API (https://core.telegram.org/bots/api).
 * Everything here is blocking – call it from a background dispatcher.
 */
class TelegramApi(private val botToken: String) {

    companion object {
        private const val BASE = "https://api.telegram.org"
        private val MEDIA_KEYS = listOf("document", "audio", "video", "animation", "voice", "video_note")

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun call(
        method: String,
        body: RequestBody? = null,
        query: Map<String, String> = emptyMap(),
        cancel: CancelToken? = null,
    ): JSONObject {
        val url = "$BASE/bot$botToken/$method".toHttpUrl().newBuilder().apply {
            for ((k, v) in query) addQueryParameter(k, v)
        }.build()
        val req = Request.Builder().url(url).apply { if (body != null) post(body) }.build()
        val httpCall = client.newCall(req)
        cancel?.call = httpCall
        return httpCall.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw TelegramException("Unexpected response from Telegram (HTTP ${resp.code})", resp.code)
            }
            if (!json.optBoolean("ok", false)) {
                val retry = json.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
                throw TelegramException(
                    json.optString("description", "Telegram error"),
                    json.optInt("error_code", resp.code),
                    retry,
                )
            }
            json
        }
    }

    fun getMe(): BotInfo {
        val r = call("getMe").getJSONObject("result")
        return BotInfo(r.optLong("id"), r.optString("username"), r.optString("first_name"))
    }

    fun getChat(chatId: String): ChatInfo {
        val r = call("getChat", query = mapOf("chat_id" to chatId)).getJSONObject("result")
        return ChatInfo(r.optLong("id"), chatTitle(r), r.optString("type"))
    }

    /** Looks at recent updates to find chats/channels the bot has seen. */
    fun detectChats(): List<ChatInfo> {
        val arr = call("getUpdates", query = mapOf("limit" to "100", "timeout" to "0"))
            .getJSONArray("result")
        val found = LinkedHashMap<Long, ChatInfo>()
        for (i in arr.length() - 1 downTo 0) {
            val u = arr.getJSONObject(i)
            val holder = u.optJSONObject("message")
                ?: u.optJSONObject("channel_post")
                ?: u.optJSONObject("my_chat_member")
                ?: u.optJSONObject("edited_channel_post")
                ?: continue
            val chat = holder.optJSONObject("chat") ?: continue
            val id = chat.optLong("id")
            if (!found.containsKey(id)) {
                found[id] = ChatInfo(id, chatTitle(chat), chat.optString("type"))
            }
        }
        return found.values.toList()
    }

    /** Pending updates from [offset] on (confirms everything before it). */
    fun getUpdates(offset: Long): JSONArray {
        val allowed = JSONArray(listOf("message", "channel_post", "edited_channel_post", "my_chat_member"))
        return call(
            "getUpdates",
            query = mapOf(
                "offset" to offset.toString(),
                "limit" to "100",
                "timeout" to "0",
                "allowed_updates" to allowed.toString(),
            ),
        ).getJSONArray("result")
    }

    /** Sends a throw-away message and returns its id (= the newest message id in that chat). */
    fun probeLatestMessageId(chatId: String): Long {
        val r = call(
            "sendMessage",
            query = mapOf("chat_id" to chatId, "text" to "\u2026", "disable_notification" to "true"),
        ).getJSONObject("result")
        val id = r.getLong("message_id")
        try { deleteMessage(chatId, id) } catch (e: Exception) { /* best effort */ }
        return id
    }

    /** Forwards one message; the result describes its content (incl. file_id). */
    fun forwardMessage(toChat: String, fromChat: String, messageId: Long, cancel: CancelToken?): JSONObject =
        call(
            "forwardMessage",
            query = mapOf(
                "chat_id" to toChat,
                "from_chat_id" to fromChat,
                "message_id" to messageId.toString(),
                "disable_notification" to "true",
            ),
            cancel = cancel,
        ).getJSONObject("result")

    /** Extracts a downloadable file from a message, or null if it has none. */
    fun parseFile(msg: JSONObject, messageId: Long): ChatFile? {
        val origin = msg.optJSONObject("forward_origin")?.optLong("date", 0L) ?: 0L
        val legacy = msg.optLong("forward_date", 0L)
        val date = (if (origin > 0) origin else if (legacy > 0) legacy else msg.optLong("date")) * 1000L
        val caption = msg.optString("caption")

        for (key in MEDIA_KEYS) {
            val m = msg.optJSONObject(key) ?: continue
            val fileId = m.optString("file_id")
            if (fileId.isBlank()) continue
            val mime = m.optString("mime_type").ifBlank { "application/octet-stream" }
            var name = m.optString("file_name")
            if (name.isBlank()) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?: when (key) { "voice" -> "ogg"; "audio" -> "mp3"; "document" -> ""; else -> "mp4" }
                name = key + "_" + messageId + (if (ext.isNotEmpty()) ".$ext" else "")
            }
            return ChatFile(messageId, fileId, name.replace('/', '_'), m.optLong("file_size"), mime, date, caption)
        }
        val photos = msg.optJSONArray("photo")
        if (photos != null && photos.length() > 0) {
            val best = photos.getJSONObject(photos.length() - 1)
            return ChatFile(
                messageId, best.optString("file_id"), "photo_$messageId.jpg",
                best.optLong("file_size"), "image/jpeg", date, caption,
            )
        }
        return null
    }

    fun sendDocument(
        chatId: String,
        file: File,
        fileName: String,
        caption: String,
        cancel: CancelToken?,
        onBytes: (Long) -> Unit,
    ): SentDoc {
        val fileBody = ProgressBody(file, "application/octet-stream".toMediaType(), onBytes)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("disable_notification", "true")
            .addFormDataPart("document", fileName, fileBody)
            .build()
        val res = call("sendDocument", body, cancel = cancel).getJSONObject("result")
        // Telegram recognises audio/video files and may describe them as "audio", "video",
        // "animation"… instead of "document" – the file_id works with getFile either way.
        val media = MEDIA_KEYS.firstNotNullOfOrNull { res.optJSONObject(it) }
        if (media == null) {
            // The upload itself succeeded; don't leave an orphan message behind.
            try { deleteMessage(chatId, res.getLong("message_id")) } catch (e: Exception) { /* best effort */ }
            throw TelegramException("Telegram didn't return the uploaded file")
        }
        return SentDoc(res.getLong("message_id"), media.getString("file_id"), media.optLong("file_size"))
    }

    /**
     * Fallback when [deleteMessage] is refused (e.g. message older than 48 h): swap the message's
     * file for a tiny placeholder so the real data is gone from the chat. Needs the bot to be allowed
     * to edit its own messages.
     */
    fun blankMessage(chatId: String, messageId: Long) {
        val media = JSONObject()
            .put("type", "document")
            .put("media", "attach://blank")
            .put("caption", "🗑 removed by TeleVault")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("message_id", messageId.toString())
            .addFormDataPart("media", media.toString())
            .addFormDataPart(
                "blank", "removed.txt",
                "removed".toByteArray().toRequestBody("text/plain".toMediaType()),
            )
            .build()
        call("editMessageMedia", body)
    }

    fun deleteMessage(chatId: String, messageId: Long) {
        call(
            "deleteMessage",
            query = mapOf("chat_id" to chatId, "message_id" to messageId.toString()),
        )
    }

    fun pinMessage(chatId: String, messageId: Long) {
        call(
            "pinChatMessage",
            query = mapOf(
                "chat_id" to chatId,
                "message_id" to messageId.toString(),
                "disable_notification" to "true",
            ),
        )
    }

    /** Returns the file_id of the pinned document called [expectedName], if there is one. */
    fun getPinnedDocumentId(chatId: String, expectedName: String): String? {
        val result = call("getChat", query = mapOf("chat_id" to chatId)).getJSONObject("result")
        val pinned = result.optJSONObject("pinned_message") ?: return null
        val doc = pinned.optJSONObject("document") ?: return null
        return if (doc.optString("file_name") == expectedName) doc.optString("file_id") else null
    }

    fun getFilePath(fileId: String, cancel: CancelToken?): String {
        val r = call("getFile", query = mapOf("file_id" to fileId), cancel = cancel).getJSONObject("result")
        val path = r.optString("file_path")
        if (path.isBlank()) throw TelegramException("This file is no longer available on Telegram")
        return path
    }

    fun download(filePath: String, out: OutputStream, cancel: CancelToken?, onBytes: (Long) -> Unit) {
        val req = Request.Builder().url("$BASE/file/bot$botToken/$filePath".toHttpUrl()).build()
        val httpCall = client.newCall(req)
        cancel?.call = httpCall
        httpCall.execute().use { resp ->
            if (!resp.isSuccessful) throw TelegramException("Download failed (HTTP ${resp.code})", resp.code)
            val src = resp.body?.byteStream() ?: throw IOException("Empty download")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                onBytes(n.toLong())
            }
        }
    }

    private fun chatTitle(c: JSONObject): String {
        val title = c.optString("title")
        if (title.isNotBlank()) return title
        val person = listOf(c.optString("first_name"), c.optString("last_name"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (person.isNotBlank()) return person
        val user = c.optString("username")
        return if (user.isNotBlank()) "@$user" else "Chat ${c.optLong("id")}"
    }

    private class ProgressBody(
        private val file: File,
        private val type: MediaType?,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
        }
    }
}
