package com.wasti.airpad

import android.content.res.Configuration

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : Activity() {
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var profileRequested = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private lateinit var status: TextView
    private lateinit var pad: PadView

    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    }

    private fun withBluetoothPermissions(action: () -> Unit) {
        if (hasBluetoothPermissions()) {
            action()
            return
        }
        pendingPermissionAction = action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                REQUEST_BLUETOOTH_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) {
            (action ?: { initializeHid() }).invoke()
        } else {
            setStatus("Bluetooth permissions are required. Grant Nearby devices access in Android Settings.")
            Toast.makeText(this, "Bluetooth permissions were not granted", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && hasBluetoothPermissions() && hid == null && !profileRequested) {
            try {
                if (bluetoothAdapter?.isEnabled == true) initializeHid()
            } catch (_: SecurityException) {
                setStatus("Bluetooth permission is missing.")
            }
        }
    }

    override fun onPause() {
        if (::pad.isInitialized) pad.releaseLocalState()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        withBluetoothPermissions { initializeHid() }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 6, 15))
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        status = TextView(this).apply {
            text = "Starting AirPad…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), dp(12), dp(8), dp(12))
            contentDescription = "Bluetooth connection status"
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Pair mode"
            contentDescription = "Make this phone discoverable for Bluetooth pairing"
            setOnClickListener {
                withBluetoothPermissions {
                    initializeHid()
                    requestDiscoverable()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(Button(this).apply {
            text = "Connect"
            contentDescription = "Connect to a paired Bluetooth host"
            setOnClickListener {
                withBluetoothPermissions {
                    initializeHid()
                    chooseHost()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        pad = PadView(this)
        val sensitivityLabel = TextView(this).apply {
            text = "Pointer sensitivity"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        val sensitivity = SeekBar(this).apply {
            max = 100
            progress = 45
            contentDescription = "Pointer sensitivity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    pad.sensitivity = 0.5f + progress / 40f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val naturalScroll = CheckBox(this).apply {
            text = "Natural scrolling"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> pad.naturalScroll = checked }
        }

        root.addView(status)
        root.addView(controls)
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(sensitivityLabel)
        root.addView(sensitivity)
        root.addView(naturalScroll)
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun initializeHid() {
        if (profileRequested || hid != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setStatus("Bluetooth HID requires Android 9 or newer.")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("This device does not have Bluetooth hardware.")
            return
        }
        try {
            if (!adapter.isEnabled) {
                setStatus("Turn on Bluetooth, then return to AirPad.")
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                return
            }
            profileRequested = true
            val accepted = adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val deviceProfile = proxy as? BluetoothHidDevice
                    if (deviceProfile == null) {
                        profileRequested = false
                        setStatus("This device does not provide the Bluetooth HID Device profile.")
                        return
                    }
                    hid = deviceProfile
                    try {
                        val registrationAccepted = deviceProfile.registerApp(
                            BluetoothHidDeviceAppSdpSettings(
                                "Wasti AirPad",
                                "Bluetooth wireless touchpad",
                                "Wasti AirPad",
                                BluetoothHidDevice.SUBCLASS1_COMBO,
                                HidReports.DESCRIPTOR
                            ),
                            null,
                            null,
                            mainExecutor,
                            object : BluetoothHidDevice.Callback() {
                                override fun onAppStatusChanged(
                                    pluggedDevice: BluetoothDevice?,
                                    registered: Boolean
                                ) {
                                    setStatus(
                                        if (registered) "Ready. Pair this phone with your computer."
                                        else "HID registration failed. Check device compatibility."
                                    )
                                }

                                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                                    if (state == BluetoothProfile.STATE_CONNECTED) {
                                        host = device
                                        setStatus("Connected to ${safeDeviceName(device)}")
                                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                        if (host?.address == device.address) host = null
                                        setStatus("Not connected")
                                        pad.releaseLocalState()
                                    }
                                }

                                override fun onVirtualCableUnplug(device: BluetoothDevice) {
                                    if (host?.address == device.address) host = null
                                    setStatus("Bluetooth host disconnected")
                                    pad.releaseLocalState()
                                }
                            }
                        )
                        if (!registrationAccepted) {
                            setStatus("Android rejected HID registration. This device may not support HID peripheral mode.")
                        }
                    } catch (security: SecurityException) {
                        setStatus("Bluetooth permission is missing. Grant Nearby devices access in Settings.")
                    } catch (error: RuntimeException) {
                        setStatus("Could not register Bluetooth HID: ${error.localizedMessage ?: "unknown error"}")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    hid = null
                    host = null
                    profileRequested = false
                    setStatus("Bluetooth HID service disconnected. Reopen AirPad to reconnect.")
                    pad.releaseLocalState()
                }
            }, BluetoothProfile.HID_DEVICE)
            if (!accepted) {
                profileRequested = false
                setStatus("Android could not open the Bluetooth HID service on this device.")
            } else {
                setStatus("Opening Bluetooth HID service…")
            }
        } catch (security: SecurityException) {
            profileRequested = false
            setStatus("Bluetooth permission is missing. Grant Nearby devices access in Settings.")
        } catch (error: RuntimeException) {
            profileRequested = false
            setStatus("Bluetooth initialization failed: ${error.localizedMessage ?: "unknown error"}")
        }
    }

    private fun requestDiscoverable() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("Bluetooth hardware is unavailable.")
            return
        }
        try {
            if (!adapter.isEnabled) {
                setStatus("Turn on Bluetooth first.")
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                return
            }
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
            )
        } catch (_: SecurityException) {
            setStatus("Android denied discoverability. Check Nearby devices permissions.")
        }
    }

    private fun chooseHost() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("Bluetooth hardware is unavailable.")
            return
        }
        try {
            if (!adapter.isEnabled) {
                setStatus("Turn on Bluetooth first.")
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                return
            }
            val devices = adapter.bondedDevices.sortedBy { safeDeviceName(it) }
            if (devices.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("No paired devices")
                    .setMessage("Pair this phone with your computer in Bluetooth settings first. Then return and tap Connect.")
                    .setPositiveButton("Bluetooth settings") { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            val labels = devices.map { safeDeviceName(it) }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Choose your computer")
                .setItems(labels) { _, index ->
                    val target = devices.getOrNull(index) ?: return@setItems
                    try {
                        val profile = hid
                        if (profile == null) {
                            setStatus("Bluetooth HID is still starting. Wait a moment and tap Connect again.")
                            return@setItems
                        }
                        val accepted = profile.connect(target)
                        setStatus(if (accepted) "Connecting to ${labels[index]}…" else "Connection request was not accepted.")
                    } catch (_: SecurityException) {
                        setStatus("Bluetooth permission is missing.")
                    } catch (error: RuntimeException) {
                        setStatus("Could not connect: ${error.localizedMessage ?: "unknown error"}")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: SecurityException) {
            setStatus("Bluetooth permission is missing. Grant Nearby devices access in Settings.")
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String =
        try { device.name?.takeIf { it.isNotBlank() } ?: device.address }
        catch (_: SecurityException) { "Paired device" }

    private fun send(reportId: Int, report: ByteArray) {
        val target = host ?: return
        val profile = hid ?: return
        try {
            profile.sendReport(target, reportId, report)
        } catch (_: SecurityException) {
            setStatus("Bluetooth permission was revoked.")
        } catch (_: RuntimeException) {
            setStatus("Bluetooth report failed. Reconnect to your computer.")
        }
    }

    internal fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        send(HidReports.MOUSE_REPORT_ID, HidReports.mouse(buttons, dx, dy, wheel))
    }

    internal fun sendKey(modifiers: Int, keyCode: Int) {
        send(HidReports.KEYBOARD_REPORT_ID, HidReports.keyboard(modifiers, keyCode))
        send(HidReports.KEYBOARD_REPORT_ID, HidReports.EMPTY_KEYBOARD)
    }

    internal fun sendCtrlWheel(direction: Int) {
        send(HidReports.KEYBOARD_REPORT_ID, HidReports.keyboard(HidReports.MOD_LEFT_CTRL, 0))
        sendMouse(0, 0, 0, direction)
        send(HidReports.KEYBOARD_REPORT_ID, HidReports.EMPTY_KEYBOARD)
    }

    private fun setStatus(message: String) {
        if (!::status.isInitialized) return
        runOnUiThread { status.text = message }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        if (::pad.isInitialized) pad.releaseLocalState()
        try { hid?.unregisterApp() } catch (_: SecurityException) { } catch (_: RuntimeException) { }
        hid?.let { profile ->
            try { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, profile) }
            catch (_: SecurityException) { } catch (_: RuntimeException) { }
        }
        hid = null
        host = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 4102
    }
}

internal object HidReports {
    const val MOUSE_REPORT_ID = 1
    const val KEYBOARD_REPORT_ID = 2
    const val MOD_LEFT_CTRL = 0x01
    val EMPTY_KEYBOARD = ByteArray(8)

    val DESCRIPTOR: ByteArray = (
        "05010902A10185010901A10005091901290315002501950375018102950175058103" +
        "05010930093109381581257F750895038106C0C0" +
        "05010906A1018502050719E029E715002501750195088102950175088103" +
        "95067508150025650507190029658100C0"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte()
    )

    fun keyboard(modifiers: Int, keyCode: Int): ByteArray = byteArrayOf(
        (modifiers and 0xFF).toByte(), 0, keyCode.toByte(), 0, 0, 0, 0, 0
    )
}

private class PadView(context: android.content.Context) : View(context) {
    private val density = resources.displayMetrics.density
    var sensitivity = 1.625f
    var naturalScroll = false

    private data class Trail(var x: Float, var y: Float, var radius: Float, var alpha: Float, val hue: Float)
    private val trails = ArrayList<Trail>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var hue = 0f
    private var lastX = Float.NaN
    private var lastY = 0f
    private var maxPointers = 0
    private var totalMotion = 0f
    private var downTime = 0L
    private var lastTapTime = 0L
    private var dragging = false
    private var edgeScroll = false
    private var remainderX = 0f
    private var remainderY = 0f
    private var scrollAccumulator = 0f
    private var zoomAccumulator = 0f
    private var lastPinchDistance = 0f
    private var gestureX = 0f
    private var gestureY = 0f

    init {
        isFocusable = true
        contentDescription = "AirPad touch surface. One finger moves the pointer, tap to click, two fingers scroll, and three or four fingers trigger shortcuts."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(5, 6, 15))
        val iterator = trails.iterator()
        while (iterator.hasNext()) {
            val trail = iterator.next()
            trail.radius += 1.2f
            trail.alpha -= 0.025f
            trail.y -= 0.3f
            if (trail.alpha <= 0f) {
                iterator.remove()
                continue
            }
            paint.shader = RadialGradient(
                trail.x, trail.y, trail.radius,
                Color.HSVToColor((trail.alpha * 255).toInt().coerceIn(0, 255), floatArrayOf(trail.hue, 0.8f, 1f)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(trail.x, trail.y, trail.radius, paint)
        }
        if (trails.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun haptic() = performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun click(button: Int) {
        (context as? MainActivity)?.sendMouse(button, 0, 0, 0)
        (context as? MainActivity)?.sendMouse(0, 0, 0, 0)
        haptic()
    }

    private fun pinchDistance(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else hypot(
            event.getX(0) - event.getX(1),
            event.getY(0) - event.getY(1)
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val activity = context as? MainActivity ?: return false
        val count = event.pointerCount.coerceAtLeast(1)
        var centerX = 0f
        var centerY = 0f
        for (index in 0 until count) {
            centerX += event.getX(index)
            centerY += event.getY(index)
        }
        centerX /= count
        centerY /= count

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                maxPointers = 1
                totalMotion = 0f
                gestureX = 0f
                gestureY = 0f
                remainderX = 0f
                remainderY = 0f
                zoomAccumulator = 0f
                scrollAccumulator = 0f
                lastX = centerX
                lastY = centerY
                edgeScroll = centerX >= width * 0.90f
                if (lastTapTime > 0L && event.eventTime - lastTapTime in 1..300) {
                    dragging = true
                    lastTapTime = 0L
                    activity.sendMouse(1, 0, 0, 0)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastTapTime = 0L
                maxPointers = max(maxPointers, count)
                lastX = Float.NaN
                if (count == 2) lastPinchDistance = pinchDistance(event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                maxPointers = max(maxPointers, count)
                lastX = Float.NaN
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (lastX.isNaN()) {
                    lastX = centerX
                    lastY = centerY
                    if (count == 2) lastPinchDistance = pinchDistance(event)
                    return true
                }
                val dx = centerX - lastX
                val dy = centerY - lastY
                lastX = centerX
                lastY = centerY
                totalMotion += hypot(dx, dy)
                trails.add(Trail(centerX, centerY, 34f, 0.55f, hue))
                hue = (hue + 4f) % 360f
                if (trails.size > 100) trails.removeAt(0)
                postInvalidateOnAnimation()

                when {
                    count == 1 && edgeScroll -> scroll(dy)
                    count == 1 -> {
                        remainderX += dx * sensitivity
                        remainderY += dy * sensitivity
                        val moveX = remainderX.toInt()
                        val moveY = remainderY.toInt()
                        remainderX -= moveX
                        remainderY -= moveY
                        activity.sendMouse(if (dragging) 1 else 0, moveX, moveY, 0)
                    }
                    count == 2 -> {
                        val currentDistance = pinchDistance(event)
                        val pinchDelta = currentDistance - lastPinchDistance
                        lastPinchDistance = currentDistance
                        if (abs(pinchDelta) > abs(dy) * 0.65f && abs(pinchDelta) > 1.5f * density) {
                            zoomAccumulator += pinchDelta
                            if (abs(zoomAccumulator) >= 18f * density) {
                                activity.sendCtrlWheel(if (zoomAccumulator > 0f) 1 else -1)
                                zoomAccumulator = 0f
                            }
                        } else {
                            scroll(dy)
                        }
                    }
                    else -> {
                        gestureX += dx
                        gestureY += dy
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val isTap = event.eventTime - downTime < 250L && totalMotion < 24f * density
                if (dragging) {
                    activity.sendMouse(0, 0, 0, 0)
                    dragging = false
                    lastTapTime = 0L
                } else if (isTap) {
                    when (maxPointers) {
                        1 -> { click(1); lastTapTime = event.eventTime }
                        2 -> click(2)
                        3 -> { activity.sendKey(0x08, 0x16); haptic() }
                        4 -> { activity.sendKey(0x08, 0x04); haptic() }
                    }
                } else if (maxPointers >= 3 && max(abs(gestureX), abs(gestureY)) > 100f * density) {
                    val horizontal = abs(gestureX) > abs(gestureY)
                    if (maxPointers == 3) {
                        when {
                            !horizontal && gestureY < 0f -> activity.sendKey(0x08, 0x2B) // Win+Tab
                            !horizontal -> activity.sendKey(0x08, 0x07) // Win+D
                            gestureX < 0f -> activity.sendKey(0x04, 0x2B) // Alt+Tab
                            else -> activity.sendKey(0x06, 0x2B) // Shift+Alt+Tab
                        }
                    } else {
                        when {
                            horizontal && gestureX < 0f -> activity.sendKey(0x09, 0x50) // Ctrl+Win+Left
                            horizontal -> activity.sendKey(0x09, 0x4F) // Ctrl+Win+Right
                            gestureY < 0f -> activity.sendKey(0x08, 0x2B)
                            else -> activity.sendKey(0x08, 0x07)
                        }
                    }
                    haptic()
                }
                resetGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseLocalState()
                return true
            }
        }
        return true
    }

    private fun scroll(deltaY: Float) {
        scrollAccumulator += if (naturalScroll) deltaY else -deltaY
        while (abs(scrollAccumulator) >= 10f * density) {
            val direction = if (scrollAccumulator > 0f) 1 else -1
            (context as? MainActivity)?.sendMouse(0, 0, 0, direction)
            scrollAccumulator -= 10f * direction
        }
    }

    private fun resetGesture() {
        lastX = Float.NaN
        maxPointers = 0
        totalMotion = 0f
        gestureX = 0f
        gestureY = 0f
        edgeScroll = false
        zoomAccumulator = 0f
    }

    fun releaseLocalState() {
        if (dragging) (context as? MainActivity)?.sendMouse(0, 0, 0, 0)
        dragging = false
        lastTapTime = 0L
        resetGesture()
    }
}
