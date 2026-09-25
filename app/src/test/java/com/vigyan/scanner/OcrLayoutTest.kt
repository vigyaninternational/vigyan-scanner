package com.vigyan.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLayoutTest {
    @Test
    fun labelAndValueJoinOnOneRow() {
        val lines = listOf(
            OcrLine("Ravi Kumar", 400, 102, 600, 128),
            OcrLine("Name", 50, 100, 150, 130),
            OcrLine("Mobile", 50, 200, 160, 230),
            OcrLine("9437012345", 400, 203, 620, 229),
        )
        assertEquals("Name   Ravi Kumar\nMobile   9437012345", OcrLayout.rows(lines))
    }
}
