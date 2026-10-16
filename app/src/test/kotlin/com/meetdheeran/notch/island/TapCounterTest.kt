package com.meetdheeran.notch.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapCounterTest {
    @Test fun `four quick taps knock`() {
        val c = TapCounter()
        assertFalse(c.tap(0)); assertFalse(c.tap(200)); assertFalse(c.tap(400))
        assertTrue(c.tap(600))
    }

    @Test fun `slow taps never knock`() {
        val c = TapCounter()
        assertFalse(c.tap(0)); assertFalse(c.tap(800)); assertFalse(c.tap(1_600)); assertFalse(c.tap(2_400))
    }

    @Test fun `count starts over after a knock`() {
        val c = TapCounter()
        repeat(3) { c.tap(it * 100L) }
        assertTrue(c.tap(300))
        assertFalse(c.tap(400))
    }

    @Test fun `three taps for the face retry`() {
        val c = TapCounter(needed = 3)
        assertFalse(c.tap(0)); assertFalse(c.tap(150))
        assertTrue(c.tap(300))
    }
}
