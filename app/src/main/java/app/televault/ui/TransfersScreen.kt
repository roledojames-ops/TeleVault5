package app.televault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.data.TState
import app.televault.data.TransferManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen() {
    val ctx = LocalContext.current
    val items by TransferManager.items.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Transfers") }, actions = {
            TextButton(onClick = { TransferManager.clearFinished() }) { Text("Clear finished") }
        })
    }) { pad ->
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("✈️", style = MaterialTheme.typography.displayMedium)
                Text("No transfers yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(items.asReversed(), key = { it.id }) { t ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { EmojiBadge(if (t.isUpload) "⬆️" else "⬇️") },
                        headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val label = when (t.state) {
                                    TState.QUEUED -> "Waiting…"
                                    TState.RUNNING -> if (t.total > 0) "${formatSize(ctx, t.done)} / ${formatSize(ctx, t.total)}" else formatSize(ctx, t.done)
                                    TState.DONE -> "Done"
                                    TState.CANCELLED -> "Cancelled"
                                    TState.FAILED -> "Failed: ${t.error ?: "unknown error"}"
                                }
                                Text(label, color = if (t.state == TState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (t.state == TState.RUNNING) {
                                    if (t.total > 0) {
                                        LinearProgressIndicator(progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        },
                        trailingContent = {
                            if (t.active) IconButton(onClick = { TransferManager.cancel(t.id) }) { Icon(Icons.Default.Close, "Cancel") }
                        },
                    )
                }
            }
        }
    }
}
