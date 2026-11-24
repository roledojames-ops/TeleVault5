package app.televault.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatSize(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes.coerceAtLeast(0L))

fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

fun fileEmoji(name: String, mime: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") -> "🖼️"
        mime.startsWith("video/") -> "🎬"
        mime.startsWith("audio/") -> "🎵"
        mime == "application/pdf" || ext == "pdf" -> "📕"
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> "🗜️"
        ext in setOf("apk", "aab") -> "🤖"
        ext in setOf("doc", "docx", "odt", "txt", "md", "rtf") -> "📝"
        ext in setOf("xls", "xlsx", "csv", "ods") -> "📊"
        ext in setOf("ppt", "pptx", "odp") -> "📽️"
        else -> "📄"
    }
}

@Composable
fun EmojiBadge(emoji: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 22.sp)
    }
}
