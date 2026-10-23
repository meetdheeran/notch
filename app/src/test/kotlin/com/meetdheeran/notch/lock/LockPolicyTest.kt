package com.meetdheeran.notch.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    private val A = "com.app.a"
    private val B = "com.app.b"
    private val PLAIN = "com.app.plain"

    private fun policy(grace: Long = 0L) = LockPolicy(grace).apply { setLocked(setOf(A, B)) }

    @Test fun `a locked app in front is gated`() {
        assertEquals(LockPolicy.Decision.GATE, policy().foreground(A, 0))
    }

    @Test fun `an unlocked app goes straight through`() {
        assertEquals(LockPolicy.Decision.ALLOW, policy().foreground(PLAIN, 0))
    }

    @Test fun `once unlocked it stays open while in front`() {
        val p = policy()
        assertEquals(LockPolicy.Decision.GATE, p.foreground(A, 0))
        p.unlocked(A, 10)
        assertEquals(LockPolicy.Decision.ALLOW, p.foreground(A, 20))
        assertEquals(LockPolicy.Decision.ALLOW, p.foreground(A, 30))
    }

    @Test fun `leaving a locked app locks it again (no grace)`() {
        val p = policy()
        p.foreground(A, 0); p.unlocked(A, 0)
        assertEquals(LockPolicy.Decision.ALLOW, p.foreground(PLAIN, 100)) // left A
        assertEquals(LockPolicy.Decision.GATE, p.foreground(A, 200))      // back to A -> face again
    }

    @Test fun `the gate and system windows don't count as leaving`() {
        val p = policy()
        p.foreground(A, 0); p.unlocked(A, 0)
        assertEquals(LockPolicy.Decision.IGNORE, p.foreground("com.meetdheeran.notch", 10))     // the gate itself
        assertEquals(LockPolicy.Decision.IGNORE, p.foreground("com.android.permissioncontroller", 20))
        assertEquals(LockPolicy.Decision.ALLOW, p.foreground(A, 30)) // still open
    }

    @Test fun `a second locked app is gated on its own`() {
        val p = policy()
        p.foreground(A, 0); p.unlocked(A, 0)
        assertEquals(LockPolicy.Decision.GATE, p.foreground(B, 100)) // switching to B asks for B
    }

    @Test fun `a denied gate leaves the app locked`() {
        val p = policy()
        p.foreground(A, 0)
        p.denied(A)
        assertEquals(LockPolicy.Decision.GATE, p.foreground(A, 100))
        assertFalse(p.hasOpen)
    }

    @Test fun `with a grace window, stepping out and back doesn't ask again`() {
        val p = policy(grace = 30_000)
        p.foreground(A, 0); p.unlocked(A, 0)
        assertEquals(LockPolicy.Decision.ALLOW, p.foreground(PLAIN, 5_000))  // stepped out
        assertEquals(LockPolicy.Decision.ALLOW, p.foreground(A, 10_000))     // back within the window
    }

    @Test fun `with a grace window, returning after it asks again`() {
        val p = policy(grace = 30_000)
        p.foreground(A, 0); p.unlocked(A, 0)
        p.foreground(PLAIN, 5_000)
        assertEquals(LockPolicy.Decision.GATE, p.foreground(A, 60_000)) // window passed
    }

    @Test fun `unlocking an app no longer locked is forgotten`() {
        val p = policy()
        p.foreground(A, 0); p.unlocked(A, 0)
        p.setLocked(setOf(B)) // A unlocked from the list
        assertEquals(LockPolicy.Decision.ALLOW, p.foreground(A, 10)) // now a plain app
        assertFalse(p.hasOpen)
    }

    @Test fun `hasOpen tracks the open app`() {
        val p = policy()
        assertFalse(p.hasOpen)
        p.foreground(A, 0); p.unlocked(A, 0)
        assertTrue(p.hasOpen)
        p.foreground(PLAIN, 100) // left A
        assertFalse(p.hasOpen)
    }
}
