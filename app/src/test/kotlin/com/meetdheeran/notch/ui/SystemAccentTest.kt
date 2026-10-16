package com.meetdheeran.notch.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemAccentTest {
    @Test fun `reads the OnePlus 7 value`() = assertEquals(Color(0xFF4F67D2), SystemAccent.parse("#ff4f67d2"))
    @Test fun `six digit hex is opaque`() = assertEquals(Color(0xFF4F67D2), SystemAccent.parse("#4f67d2"))
    @Test fun `junk is null`() { assertNull(SystemAccent.parse("blue")); assertNull(SystemAccent.parse(null)); assertNull(SystemAccent.parse("#12")) }
}
