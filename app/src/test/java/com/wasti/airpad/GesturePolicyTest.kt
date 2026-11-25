package com.wasti.airpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePolicyTest {
    @Test
    fun tapRequiresShortDurationAndLowMovement() {
        assertTrue(GesturePolicy.isTap(100L, 10f, 1f))
        assertFalse(GesturePolicy.isTap(250L, 1f, 1f))
        assertFalse(GesturePolicy.isTap(100L, 24f, 1f))
        assertFalse(GesturePolicy.isTap(-1L, 1f, 1f))
    }

    @Test
    fun pinchMustDominateVerticalMovementAndExceedDensityThreshold() {
        assertTrue(GesturePolicy.isPinch(20f, 5f, 1f))
        assertFalse(GesturePolicy.isPinch(2f, 5f, 1f))
        assertFalse(GesturePolicy.isPinch(1f, 0f, 1f))
        assertFalse(GesturePolicy.isPinch(2f, 0f, 2f))
    }

    @Test
    fun swipeThresholdScalesWithDisplayDensity() {
        assertTrue(GesturePolicy.isSwipe(0f, -101f, 1f))
        assertFalse(GesturePolicy.isSwipe(100f, 0f, 1f))
        assertTrue(GesturePolicy.isSwipe(0f, 201f, 2f))
    }
}
