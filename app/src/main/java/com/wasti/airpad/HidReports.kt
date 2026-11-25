package com.wasti.airpad

internal object HidReports {
    const val MOUSE_REPORT_ID = 1
    const val KEYBOARD_REPORT_ID = 2
    const val MOD_LEFT_CTRL = 0x01
    const val PROTOCOL_BOOT_MODE: Byte = 0
    const val PROTOCOL_REPORT_MODE: Byte = 1
    const val BOOT_MOUSE_REPORT_ID = 0
    const val BOOT_KEYBOARD_REPORT_ID = 0
    val EMPTY_KEYBOARD = ByteArray(8)

    val DESCRIPTOR: ByteArray = (
        "05010902A10185010901A10005091901290315002501950375018102950175058103" +
        "05010930093109381581257F750895038106C0C0" +
        "05010906A1018502050719E029E715002501750195088102950175088103" +
        "95067508150025650507190029658100" +
        "05081901290515002501950575019102950175039103C0"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte()
    )

    fun bootMouse(buttons: Int, dx: Int, dy: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte()
    )

    fun keyboard(modifiers: Int, keyCode: Int): ByteArray = byteArrayOf(
        (modifiers and 0xFF).toByte(), 0, keyCode.toByte(), 0, 0, 0, 0, 0
    )
}
