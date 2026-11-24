package app.televault

import android.app.Application
import app.televault.data.Vault

class TeleVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vault.init(this)
    }
}
