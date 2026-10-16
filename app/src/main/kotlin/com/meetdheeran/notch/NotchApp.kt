package com.meetdheeran.notch

import android.app.Application
import com.meetdheeran.notch.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NotchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    companion object {
        lateinit var prefs: Prefs
            private set

        /** App-wide work that outlives a screen (saving a setting after the screen closed). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
