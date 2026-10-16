package com.meetdheeran.notch.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.meetdheeran.notch.NotchApp
import kotlinx.coroutines.runBlocking

/** Starts the island again after a reboot or an update, if the person left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val on = runBlocking { NotchApp.prefs.current().enabled }
        if (on) IslandService.start(context)
    }
}
