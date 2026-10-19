package com.meetdheeran.notch.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultFlowTest {
    @Test fun `a knock starts a two-try face check with no PIN after it`() {
        val f = VaultFlow()
        assertEquals(VaultFlow.Next.Scan(2, thenPin = false), f.knock())
        assertEquals(VaultFlow.Stage.SCANNING, f.stage)
        // A second knock while it scans does nothing.
        assertEquals(VaultFlow.Next.Nothing, f.knock())
    }

    @Test fun `taps on the island while scanning are swallowed`() {
        val f = VaultFlow()
        f.knock()
        assertEquals(true to VaultFlow.Next.Nothing, f.islandTap(0))
    }

    @Test fun `both tries failed - the face is held, a tap elsewhere clears it`() {
        val f = VaultFlow()
        f.knock()
        assertEquals(VaultFlow.Next.Hold, f.failed())
        assertEquals(VaultFlow.Stage.HELD, f.stage)
        assertEquals(VaultFlow.Next.Clear, f.outsideTap())
        assertEquals(VaultFlow.Stage.IDLE, f.stage)
    }

    @Test fun `three taps on the held face - one more try, then the PIN`() {
        val f = VaultFlow()
        f.knock()
        f.failed()
        assertEquals(true to VaultFlow.Next.Nothing, f.islandTap(0))
        assertEquals(true to VaultFlow.Next.Nothing, f.islandTap(200))
        assertEquals(true to VaultFlow.Next.Scan(1, thenPin = true), f.islandTap(400))
        assertEquals(VaultFlow.Stage.SCANNING, f.stage)
    }

    @Test fun `slow taps on the held face don't count as three`() {
        val f = VaultFlow()
        f.knock()
        f.failed()
        f.islandTap(0)
        f.islandTap(1_000)
        assertEquals(true to VaultFlow.Next.Nothing, f.islandTap(2_000))
        assertEquals(VaultFlow.Stage.HELD, f.stage)
    }

    @Test fun `idle - taps on the island are not the vault's`() {
        val f = VaultFlow()
        val (took, next) = f.islandTap(0)
        assertFalse(took)
        assertEquals(VaultFlow.Next.Nothing, next)
        assertEquals(VaultFlow.Next.Nothing, f.outsideTap())
    }

    @Test fun `getting in, or giving up, goes back to the start`() {
        val f = VaultFlow()
        f.knock()
        assertEquals(VaultFlow.Next.Clear, f.done())
        assertEquals(VaultFlow.Stage.IDLE, f.stage)
        assertTrue(f.knock() is VaultFlow.Next.Scan)
    }

    @Test fun `a check that vanished mid-scan doesn't jam the door`() {
        val f = VaultFlow()
        f.knock()
        assertEquals(VaultFlow.Next.Clear, f.lost())
        assertTrue(f.knock() is VaultFlow.Next.Scan)
        // But a held face isn't "lost": it stays until a tap elsewhere.
        f.failed()
        assertEquals(VaultFlow.Next.Nothing, f.lost())
        assertEquals(VaultFlow.Stage.HELD, f.stage)
    }
}
