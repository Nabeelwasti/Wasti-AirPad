package com.wasti.airpad

import kotlin.math.abs
import kotlin.math.max

/** Pure gesture classification rules shared by the touch surface and deterministic tests. */
internal object GesturePolicy {
    fun isTap(durationMs: Long, totalMotionPx: Float, density: Float): Boolean =
        durationMs in 0L..249L && totalMotionPx < 24f * density

    fun isPinch(pinchDeltaPx: Float, verticalDeltaPx: Float, density: Float): Boolean =
        abs(pinchDeltaPx) > abs(verticalDeltaPx) * 0.65f &&
            abs(pinchDeltaPx) > 1.5f * density

    fun isSwipe(gestureX: Float, gestureY: Float, density: Float): Boolean =
        max(abs(gestureX), abs(gestureY)) > 100f * density
}
