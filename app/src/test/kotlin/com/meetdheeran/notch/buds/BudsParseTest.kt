package com.meetdheeran.notch.buds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudsParseTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `pairing-mode model id has no battery`() = assertNull(BudsAdvert.parse(bytes(0x12, 0x34, 0x56)))

    @Test fun `account key, salt, then battery`() {
        // flags, account-key filter (len 4, type 0), salt (len 1, type 1), battery shown (len 3, type 3):
        // left 80 %, right 75 % charging, case unknown.
        val b = BudsAdvert.parse(bytes(0x00, 0x40, 1, 2, 3, 4, 0x11, 0x99, 0x33, 80, 0x80 or 75, 0x7F))!!
        assertEquals(80, b.left)
        assertEquals(75, b.right)
        assertTrue(b.rightCharging)
        assertNull(b.case)
    }

    @Test fun `no battery field`() = assertNull(BudsAdvert.parse(bytes(0x00, 0x40, 1, 2, 3, 4, 0x11, 0x99)))
}
