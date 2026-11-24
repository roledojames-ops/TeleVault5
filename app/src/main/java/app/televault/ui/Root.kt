package app.televault.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.MainViewModel
import app.televault.data.Paths
import app.televault.data.TransferManager

@Composable
fun AppRoot(vm: MainViewModel, requestUnlock: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snack.showSnackbar(it) }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                !vm.configured -> SetupScreen(vm)
                vm.locked -> LockScreen(requestUnlock)
                else -> MainScaffold(vm)
            }
            SnackbarHost(
                hostState = snack,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (vm.configured && !vm.locked) 88.dp else 24.dp),
            )
        }
    }
}

@Composable
private fun LockScreen(requestUnlock: () -> Unit) {
    LaunchedEffect(Unit) { requestUnlock() }
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔒", fontSize = 56.sp)
        Text("TeleVault is locked", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = requestUnlock, modifier = Modifier.padding(top = 24.dp)) { Text("Unlock") }
    }
}

@Composable
private fun MainScaffold(vm: MainViewModel) {
    val ctx = LocalContext.current
    val transfers by TransferManager.items.collectAsStateWithLifecycle()
    val activeCount = transfers.count { it.active }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            TransferManager.enqueueUploads(ctx, uris, vm.folder)
            vm.toast("Uploading ${uris.size} file(s)…")
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = vm.pendingSave
        if (uri != null && f != null) {
            TransferManager.enqueueSave(ctx, f, uri)
            vm.toast("Saving ${f.name}…")
        }
        vm.pendingSave = null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.tab == 0,
                    onClick = { vm.tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Files") },
                )
                NavigationBarItem(
                    selected = vm.tab == 1,
                    onClick = { vm.tab = 1 },
                    icon = {
                        BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                            Icon(Icons.Default.Send, contentDescription = null)
                        }
                    },
                    label = { Text("Transfers") },
                )
                NavigationBarItem(
                    selected = vm.tab == 2,
                    onClick = { vm.tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (vm.tab) {
                0 -> FilesScreen(
                    vm = vm,
                    onPickFiles = {
                        askNotifications()
                        pick.launch(arrayOf("*/*"))
                    },
                    onSaveAs = { f ->
                        askNotifications()
                        vm.pendingSave = f
                        save.launch(f.name)
                    },
                    onOpen = { f ->
                        askNotifications()
                        TransferManager.enqueueOpen(ctx, f)
                        vm.toast("Downloading ${f.name}…")
                    },
                )
                1 -> TransfersScreen()
                else -> SettingsScreen(vm)
            }
        }
    }

    if (vm.sharedUris.isNotEmpty()) {
        val n = vm.sharedUris.size
        AlertDialog(
            onDismissRequest = { vm.sharedUris = emptyList() },
            title = { Text("Upload shared files?") },
            text = { Text("Upload $n file(s) to “${Paths.name(vm.folder)}”?") },
            confirmButton = {
                TextButton(onClick = {
                    askNotifications()
                    TransferManager.enqueueUploads(ctx, vm.sharedUris, vm.folder)
                    vm.sharedUris = emptyList()
                    vm.tab = 1
                }) { Text("Upload") }
            },
            dismissButton = { TextButton(onClick = { vm.sharedUris = emptyList() }) { Text("Cancel") } },
        )
    }
}
