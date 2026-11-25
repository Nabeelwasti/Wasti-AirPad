package com.wasti.airpad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {
    @Test
    fun mouseReportHasFourBytesAndClampsValues() {
        val report = HidReports.mouse(buttons = 0xFF, dx = -200, dy = 200, wheel = 1)
        assertEquals(4, report.size)
        assertArrayEquals(byteArrayOf(0x07, 0x81.toByte(), 0x7F, 0x01), report)
    }

    @Test
    fun keyboardReportHasEightBytesAndCorrectModifier() {
        val report = HidReports.keyboard(HidReports.MOD_LEFT_CTRL, 0x2B)
        assertEquals(8, report.size)
        assertEquals(1, report[0].toInt())
        assertEquals(0x2B, report[2].toInt())
        assertArrayEquals(ByteArray(8), HidReports.EMPTY_KEYBOARD)
    }

    @Test
    fun descriptorContainsMouseAndKeyboardReportIdentifiers() {
        val descriptor = HidReports.DESCRIPTOR
        assertTrue(descriptor.size > 40)
        assertTrue(descriptor.containsSequence(byteArrayOf(0x85.toByte(), 0x01)))
        assertTrue(descriptor.containsSequence(byteArrayOf(0x85.toByte(), 0x02)))
    }

    @Test
    fun mouseReportMasksUnsupportedButtonsAndClampsEveryAxis() {
        val report = HidReports.mouse(buttons = 0xF8 or 0x05, dx = -999, dy = 999, wheel = -999)
        assertArrayEquals(byteArrayOf(0x05, 0x81.toByte(), 0x7F, 0x81.toByte()), report)
    }

    @Test
    fun neutralMouseReportReleasesAllButtonsAndMovement() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), HidReports.mouse(0, 0, 0, 0))
    }

    @Test
    fun keyboardReportMasksModifierAndKeyToHidByteRange() {
        val report = HidReports.keyboard(0x1FF, 0x12B)
        assertEquals(0xFF, report[0].toInt() and 0xFF)
        assertEquals(0x2B, report[2].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0), report.copyOfRange(3, 8))
    }

    @Test
    fun descriptorContainsExpectedReportIdsAndMouseWheelUsage() {
        val descriptor = HidReports.DESCRIPTOR
        assertTrue(descriptor.containsSequence(byteArrayOf(0x85.toByte(), HidReports.MOUSE_REPORT_ID.toByte())))
        assertTrue(descriptor.containsSequence(byteArrayOf(0x85.toByte(), HidReports.KEYBOARD_REPORT_ID.toByte())))
        assertTrue(descriptor.containsSequence(byteArrayOf(0x09, 0x38)))
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
        indices.any { start ->
            start + sequence.size <= size &&
                sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
        }
}
