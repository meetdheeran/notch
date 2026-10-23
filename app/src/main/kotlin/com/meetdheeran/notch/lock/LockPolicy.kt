package com.meetdheeran.notch.lock

/**
 * When to demand the face, pure so it's unit-tested. Locked apps stay on the launcher, visible; the moment one comes
 * to the front it's gated behind the face check (then the PIN), the same way a hidden app's knock is. Once you're in,
 * that app stays open while it's in front; leave it and it locks again (or, with a grace window, only after the window
 * passes). The OS side — noticing the app in front, putting the gate up, sending you home on a fail — is wired on top
 * of this in [AppLock]; the decisions live here.
 */
class LockPolicy(private val graceMs: Long = 0L) {
    enum class Decision {
        /** A locked app came to the front and isn't open yet: put the face check up. */
        GATE,

        /** Let this through — not locked, or already unlocked and still in front (or within its grace window). */
        ALLOW,

        /** A window that isn't really "an app in front": the gate itself, a permission dialog, the share sheet. */
        IGNORE,
    }

    private var locked: Set<String> = emptySet()

    /** The locked app that's been unlocked for this visit, and until when (for the grace window). */
    private var openPkg: String? = null
    private var openUntil: Long = 0L

    /** The locked apps, from the store. Dropping one that's open forgets it's open. */
    fun setLocked(pkgs: Set<String>) {
        locked = pkgs
        if (openPkg != null && openPkg !in pkgs) openPkg = null
    }

    fun isLocked(pkg: String): Boolean = pkg in locked

    /** [pkg] just came to the front at [now]. */
    fun foreground(pkg: String, now: Long): Decision {
        // The gate, system dialogs and the like aren't "leaving" the app under them.
        if (pkg in PASSING) return Decision.IGNORE

        val open = openPkg
        if (open != null && pkg != open) {
            // Left the open app. With no grace it locks at once; with grace it stays open until the window passes,
            // so stepping out to copy a code and coming straight back doesn't ask again.
            if (graceMs <= 0L || now >= openUntil) openPkg = null
        }

        if (pkg !in locked) return Decision.ALLOW

        // A locked app. Open (still, or within grace) → through; otherwise the face.
        if (pkg == openPkg && (graceMs <= 0L || now < openUntil)) {
            if (graceMs > 0L) openUntil = now + graceMs // keep the window rolling while it's in front
            return Decision.ALLOW
        }
        return Decision.GATE
    }

    /** The face (or the PIN) said yes for [pkg] at [now]: it's open now. */
    fun unlocked(pkg: String, now: Long) {
        openPkg = pkg
        openUntil = if (graceMs > 0L) now + graceMs else Long.MAX_VALUE
    }

    /** The gate was cancelled or failed for [pkg]: it stays locked. */
    fun denied(pkg: String) {
        if (openPkg == pkg) openPkg = null
    }

    /** Whether a locked app is open right now (nothing to gate). */
    val hasOpen: Boolean get() = openPkg != null

    companion object {
        /** Windows that come up over an app without it being "left": the gate, permission prompts, the share sheet. */
        val PASSING = setOf(
            "com.meetdheeran.notch",
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.documentsui", "com.google.android.documentsui",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )
    }
}
