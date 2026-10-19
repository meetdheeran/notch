package com.meetdheeran.notch

import android.app.Application
import com.meetdheeran.notch.core.Prefs
import com.meetdheeran.notch.shizuku.ShizukuBridge
import com.meetdheeran.notch.ui.SystemAccent
import com.meetdheeran.notch.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NotchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ShizukuBridge.init(this)
        SystemAccent.start(this)
        Vault.install(this)
    }

    companion object {
        lateinit var prefs: Prefs
            private set

        /** App-wide work that outlives a screen (saving a setting after the screen closed). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
