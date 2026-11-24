package app.televault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.MainViewModel
import app.televault.data.Paths
import app.televault.data.SortMode
import app.televault.data.Vault
import app.televault.data.VaultFile

private sealed interface Dlg {
    data object NewFolder : Dlg
    data object DeepScan : Dlg
    data class MoveAll(val from: String) : Dlg
    data class Rename(val f: VaultFile) : Dlg
    data class Move(val f: VaultFile) : Dlg
    data class DelFile(val f: VaultFile) : Dlg
    data class DelFolder(val path: String) : Dlg
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    vm: MainViewModel,
    onPickFiles: () -> Unit,
    onSaveAs: (VaultFile) -> Unit,
    onOpen: (VaultFile) -> Unit,
) {
    val ctx = LocalContext.current
    val index by Vault.index.collectAsStateWithLifecycle()
    val folder = vm.folder
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = vm.searching || folder != Paths.ROOT) {
        if (vm.searching) { vm.searching = false; vm.query = "" } else vm.goUp()
    }

    val q = vm.query.trim()
    val subfolders = if (q.isNotEmpty()) emptyList() else
        index.folders.filter { Paths.parent(it) == folder }.sortedBy { it.lowercase() }
    val base = if (q.isEmpty()) index.files.filter { it.folder == folder }
    else index.files.filter { it.name.contains(q, ignoreCase = true) }
    val files = when (vm.sort) {
        SortMode.NAME -> base.sortedBy { it.name.lowercase() }
        SortMode.DATE -> base.sortedByDescending { it.createdAt }
        SortMode.SIZE -> base.sortedByDescending { it.size }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (vm.searching || folder != Paths.ROOT) {
                        IconButton(onClick = {
                            if (vm.searching) { vm.searching = false; vm.query = "" } else vm.goUp()
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                title = {
                    if (vm.searching) {
                        TextField(
                            value = vm.query,
                            onValueChange = { vm.query = it },
                            placeholder = { Text("Search files") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Column {
                            Text(if (folder == Paths.ROOT) "TeleVault" else Paths.name(folder))
                            if (folder != Paths.ROOT) Text(folder, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    if (!vm.searching) {
                        IconButton(onClick = { vm.searching = true }) { Icon(Icons.Default.Search, "Search") }
                    } else if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "Clear") }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for (m in SortMode.entries) {
                                DropdownMenuItem(
                                    text = { Text((if (vm.sort == m) "✓ " else "") + "Sort by ${m.label}") },
                                    onClick = { vm.sort = m; sortMenu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Scan chat for new files") },
                                enabled = !vm.scanning,
                                onClick = { sortMenu = false; vm.scanNewPosts() },
                            )
                            DropdownMenuItem(
                                text = { Text("Deep scan (older files)") },
                                enabled = !vm.scanning,
                                onClick = { sortMenu = false; dlg = Dlg.DeepScan },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { dlg = Dlg.NewFolder }) { Text("📁") }
                ExtendedFloatingActionButton(
                    onClick = onPickFiles,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Upload") },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
        if (vm.scanning) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        vm.scanStatus,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { vm.stopScan() }) { Text("Stop") }
                }
            }
        }
        if (subfolders.isEmpty() && files.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (q.isEmpty()) "☁️" else "🔍", style = MaterialTheme.typography.displayMedium)
                Text(
                    if (q.isEmpty()) "Nothing here yet.\nTap Upload, or share files to TeleVault from any app."
                    else "No files match “$q”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
                items(subfolders, key = { "d:$it" }) { path ->
                    val n = index.files.count { Paths.isInside(it.folder, path) }
                    Row2(
                        emoji = "📁",
                        title = Paths.name(path),
                        sub = "$n file(s)",
                        onClick = { vm.openFolder(path) },
                        menu = listOf(
                            "Move all files to…" to { dlg = Dlg.MoveAll(path) },
                            "Delete folder" to { dlg = Dlg.DelFolder(path) },
                        ),
                    )
                }
                items(files, key = { "f:${it.id}" }) { f ->
                    Row2(
                        emoji = fileEmoji(f.name, f.mime),
                        title = f.name,
                        sub = formatSize(ctx, f.size) + " • " + formatDate(f.createdAt) +
                            (if (q.isNotEmpty()) " • " + f.folder else "") +
                            (if (f.chunks.size > 1) " • ${f.chunks.size} parts" else "") +
                            (if (f.chunks.size == 1 && f.size > Vault.BOT_DOWNLOAD_LIMIT) " • too big for the bot to download" else ""),
                        onClick = { onOpen(f) },
                        menu = listOf(
                            "Open" to { onOpen(f) },
                            "Save to device" to { onSaveAs(f) },
                            "Rename" to { dlg = Dlg.Rename(f) },
                            "Move" to { dlg = Dlg.Move(f) },
                            "Delete" to { dlg = Dlg.DelFile(f) },
                        ),
                    )
                }
            }
        }
        }
    }

    when (val d = dlg) {
        null -> {}
        Dlg.NewFolder -> TextDialog("New folder", "Folder name", "", "Create", { dlg = null }) {
            vm.createFolder(it); dlg = null
        }
        is Dlg.Rename -> TextDialog("Rename", "Name", d.f.name, "Save", { dlg = null }) {
            vm.rename(d.f, it); dlg = null
        }
        is Dlg.Move -> AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("Move to…") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for (p in listOf(Paths.ROOT) + index.folders.sorted()) {
                        Text(
                            if (p == Paths.ROOT) "Home" else p,
                            modifier = Modifier.fillMaxWidth().clickable { vm.move(d.f, p); dlg = null }.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } },
        )
        is Dlg.MoveAll -> AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("Move all files in “${Paths.name(d.from)}” to…") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for (p in listOf(Paths.ROOT) + index.folders.sorted()) {
                        if (p == d.from) continue
                        Text(
                            if (p == Paths.ROOT) "Home" else p,
                            modifier = Modifier.fillMaxWidth().clickable { vm.moveAll(d.from, p); dlg = null }.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } },
        )
        Dlg.DeepScan -> ConfirmDialog(
            "Scan older messages?",
            "Telegram bots can't list old messages, so TeleVault checks them one by one by briefly forwarding each to your private chat with the bot and deleting the copy. " +
                "It can take a while for big channels, can be stopped, and resumes where it left off. " +
                "You need to have sent /start to your bot once.",
            { dlg = null },
        ) { vm.deepScan(); dlg = null }
        is Dlg.DelFile -> ConfirmDialog("Delete “${d.f.name}”?", "It will also be removed from your Telegram chat.", { dlg = null }) {
            vm.deleteFile(d.f); dlg = null
        }
        is Dlg.DelFolder -> ConfirmDialog("Delete folder?", "All files inside are deleted too.", { dlg = null }) {
            vm.deleteFolder(d.path); dlg = null
        }
    }
}

@Composable
private fun Row2(emoji: String, title: String, sub: String, onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { EmojiBadge(emoji) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for ((label, action) in menu) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                    }
                }
            }
        },
    )
}

@Composable
private fun TextDialog(title: String, label: String, initial: String, ok: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onOk) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
