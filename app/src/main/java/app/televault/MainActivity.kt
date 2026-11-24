package app.televault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.televault.data.TransferEvent
import app.televault.data.TransferManager
import app.televault.ui.AppRoot
import app.televault.ui.TeleVaultTheme
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private var stoppedAt = 0L
    private var authBusy = false

    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && vm.settings.lockEnabled) vm.locked = true
        handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TransferManager.events.collect { e ->
                    when (e) {
                        is TransferEvent.OpenFile -> openFile(e.file, e.mime)
                        is TransferEvent.Info -> vm.toast(e.text)
                    }
                }
            }
        }

        setContent {
            TeleVaultTheme {
                AppRoot(vm = vm, requestUnlock = { unlock() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!authBusy) stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (vm.settings.lockEnabled && !vm.locked && !authBusy && stoppedAt != 0L &&
            SystemClock.elapsedRealtime() - stoppedAt > 30_000L
        ) {
            vm.locked = true
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.toList()
                    ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            if (vm.configured) vm.sharedUris = uris else vm.toast("Connect Telegram first, then share again")
            i.action = null
        }
    }

    private fun unlock() {
        if (authBusy) return
        val status = BiometricManager.from(this).canAuthenticate(authenticators)
        if (status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            vm.setLock(false)
            vm.locked = false
            vm.toast("No screen lock is set up, so app lock was turned off")
            return
        }
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            vm.toast("Authentication isn't available right now")
            return
        }
        authBusy = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authBusy = false
                    vm.locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authBusy = false
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TeleVault")
            .setSubtitle("Confirm it's you")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    private fun openFile(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            vm.toast("No app can open this file. Use “Save to device” instead.")
        }
    }
}
