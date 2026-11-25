package com.wasti.airpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePolicyTest {
    @Test
    fun tapRequiresShortDurationAndLimitedMotion() {
        assertTrue(GesturePolicy.isTap(100, 10f, 1f))
        assertFalse(GesturePolicy.isTap(300, 10f, 1f))
        assertFalse(GesturePolicy.isTap(100, 30f, 1f))
    }

    @Test
    fun tapBoundaryIsDeterministic() {
        assertTrue(GesturePolicy.isTap(0, 0f, 1f))
        assertTrue(GesturePolicy.isTap(249, 23.9f, 1f))
        assertFalse(GesturePolicy.isTap(250, 0f, 1f))
        assertFalse(GesturePolicy.isTap(100, 24f, 1f))
        assertFalse(GesturePolicy.isTap(-1, 0f, 1f))
    }

    @Test
    fun pinchRequiresDominantHorizontalDistanceChange() {
        assertTrue(GesturePolicy.isPinch(20f, 2f, 1f))
        assertFalse(GesturePolicy.isPinch(2f, 20f, 1f))
        assertFalse(GesturePolicy.isPinch(1f, 0f, 1f))
    }

    @Test
    fun pinchBoundaryAndDirectionAreSymmetric() {
        assertTrue(GesturePolicy.isPinch(-20f, 2f, 1f))
        assertFalse(GesturePolicy.isPinch(1.5f, 0f, 1f))
        assertFalse(GesturePolicy.isPinch(13f, 20f, 1f))
    }

    @Test
    fun swipeRequiresMovementBeyondThreshold() {
        assertTrue(GesturePolicy.isSwipe(120f, 0f, 1f))
        assertTrue(GesturePolicy.isSwipe(0f, -120f, 1f))
        assertFalse(GesturePolicy.isSwipe(50f, 50f, 1f))
        assertFalse(GesturePolicy.isSwipe(100f, 0f, 1f))
    }
}
