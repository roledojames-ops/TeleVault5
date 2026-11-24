package app.televault.data

import android.content.Context

/** Small wrapper around app-private SharedPreferences. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("televault", Context.MODE_PRIVATE)

    var botToken: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }

    var chatId: String
        get() = sp.getString("chat", "") ?: ""
        set(v) { sp.edit().putString("chat", v).apply() }

    var botName: String
        get() = sp.getString("botName", "") ?: ""
        set(v) { sp.edit().putString("botName", v).apply() }

    var chatTitle: String
        get() = sp.getString("chatTitle", "") ?: ""
        set(v) { sp.edit().putString("chatTitle", v).apply() }

    var lockEnabled: Boolean
        get() = sp.getBoolean("lock", false)
        set(v) { sp.edit().putBoolean("lock", v).apply() }

    /** Hash of the last index that was backed up, so we only back up when something changed. */
    var lastBackupHash: Int
        get() = sp.getInt("lastBackupHash", 0)
        set(v) { sp.edit().putInt("lastBackupHash", v).apply() }

    /** Ids of files deleted locally, so an old backup never brings them back. */
    var deletedIds: Set<String>
        get() = sp.getStringSet("deletedIds", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("deletedIds", v).apply() }

    var lastBackupMsgId: Long
        get() = sp.getLong("lastBackupMsg", 0L)
        set(v) { sp.edit().putLong("lastBackupMsg", v).apply() }

    /** Numeric id of the vault chat (the setting above may be an @username). */
    var chatNumId: Long
        get() = sp.getLong("chatNumId", 0L)
        set(v) { sp.edit().putLong("chatNumId", v).apply() }

    /** Next getUpdates offset, so already-imported updates aren't fetched again. */
    var updateOffset: Long
        get() = sp.getLong("updateOffset", 0L)
        set(v) { sp.edit().putLong("updateOffset", v).apply() }

    /** Private chat with the bot, used as scratch space by the deep scan. */
    var scanChat: String
        get() = sp.getString("scanChat", "") ?: ""
        set(v) { sp.edit().putString("scanChat", v).apply() }

    /** Highest message id the deep scan has already looked at. */
    var scanCursor: Long
        get() = sp.getLong("scanCursor", 0L)
        set(v) { sp.edit().putLong("scanCursor", v).apply() }

    val isConfigured: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()

    fun clear() {
        sp.edit().clear().apply()
    }
}
