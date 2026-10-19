package com.meetdheeran.notch.vault

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * The PIN pad behind the face: the phone's own screen-lock credential (PIN, pattern or password) through Android's
 * prompt. Notch never sees or stores it; Android only says yes or no.
 */
object PinLock {
    private const val AUTH = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** A screen lock is set, so there's something to check against. */
    fun available(ctx: Context): Boolean =
        ctx.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    /** Opens the PIN pad; [onResult] gets true once it says yes, false when it's cancelled or gives up. */
    fun ask(activity: Activity, title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(AUTH)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
            },
        )
    }
}
