package app.televault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.televault.MainViewModel

@Composable
fun SetupScreen(vm: MainViewModel) {
    var token by rememberSaveable { mutableStateOf("") }
    var chat by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("☁️✈️", fontSize = 48.sp)
        Text("TeleVault", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your own cloud drive, stored in your own Telegram. Files are kept as documents in a private chat or channel that only you control.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Quick setup", style = MaterialTheme.typography.titleMedium)
                Text("1. In Telegram open @BotFather, send /newbot and copy the token.")
                Text("2. Create a private channel, add your bot as an admin (allow posting, pinning and deleting messages). Or simply message your bot.")
                Text("3. Post anything there, paste the token below and tap Detect.")
                TextButton(onClick = {
                    try { uri.openUri("https://t.me/BotFather") } catch (e: Exception) { }
                }) { Text("Open @BotFather") }
            }
        }

        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Bot token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
        )
        OutlinedTextField(
            value = chat,
            onValueChange = { chat = it.trim() },
            label = { Text("Chat / channel ID (e.g. -100123… or @name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = { vm.detectChats(token) },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Detect my chat") }

        for (c in vm.detected) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().clickable { chat = c.id.toString() },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall)
                    Text("${c.type} • ${c.id}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val err = vm.setupError
        if (err != null) Text(err, color = MaterialTheme.colorScheme.error)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.connect(token, chat) }, enabled = !vm.busy) { Text("Connect") }
            if (vm.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }
        Text(
            "Your token stays on this device and is only used to talk to api.telegram.org.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
