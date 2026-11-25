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
import android.view.WindowInsets
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.text.InputType
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : Activity() {
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var wifiClient: WifiRemoteClient? = null
    private var wifiDiscovery: WifiHostDiscovery? = null
    private var profileRequested = false
    private var hidRegistered = false
    private var destroyed = false
    private var reportFailureShown = false
    private var hidProtocolMode: Byte = HidReports.PROTOCOL_REPORT_MODE
    private var keyboardLedState = 0
    private var lastMouseReport = HidReports.mouse(0, 0, 0, 0)
    private var lastKeyboardReport = HidReports.EMPTY_KEYBOARD.copyOf()
    private var acceptedInputReports = 0
    private var rejectedInputReports = 0
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
                else setStatus("Turn on Bluetooth, then return to AirPad.")
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
        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    private fun buildInterface() {
        window.statusBarColor = Color.rgb(5, 6, 15)
        window.navigationBarColor = Color.rgb(5, 6, 15)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 6, 15))
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val safeInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    view.setPadding(
                        dp(12) + safeInsets.left,
                        dp(8) + safeInsets.top,
                        dp(12) + safeInsets.right,
                        dp(12) + safeInsets.bottom
                    )
                } else {
                    view.setPadding(
                        dp(12) + insets.systemWindowInsetLeft,
                        dp(8) + insets.systemWindowInsetTop,
                        dp(12) + insets.systemWindowInsetRight,
                        dp(12) + insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        status = TextView(this).apply {
            text = "Starting AirPad…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), dp(12), dp(8), dp(12))
            contentDescription = "Connection status"
        }
        val preferences = getPreferences(MODE_PRIVATE)
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
                wifiClient?.close()
                wifiClient = null
                withBluetoothPermissions {
                    initializeHid()
                    chooseHost()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(Button(this).apply {
            text = "Wi-Fi"
            contentDescription = "Connect or disconnect using the same Wi-Fi network"
            setOnClickListener {
                if (wifiClient?.isConnected == true) {
                    wifiClient?.close()
                    wifiClient = null
                    setStatus("Disconnected from Wi-Fi.")
                } else {
                    showWifiConnectDialog()
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
            progress = preferences.getInt("pointer_sensitivity", 45).coerceIn(0, 100)
            pad.sensitivity = 0.5f + progress / 40f
            contentDescription = "Pointer sensitivity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    pad.sensitivity = 0.5f + progress / 40f
                    if (fromUser) preferences.edit().putInt("pointer_sensitivity", progress).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val naturalScroll = CheckBox(this).apply {
            text = "Natural scrolling"
            setTextColor(Color.WHITE)
            isChecked = preferences.getBoolean("natural_scrolling", false)
            pad.naturalScroll = isChecked
            setOnCheckedChangeListener { _, checked ->
                pad.naturalScroll = checked
                preferences.edit().putBoolean("natural_scrolling", checked).apply()
            }
        }

        val mouseButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val left = Button(this@MainActivity).apply {
                text = "Left click"
                setOnClickListener { sendMouse(1, 0, 0, 0); sendMouse(0, 0, 0, 0) }
            }
            val right = Button(this@MainActivity).apply {
                text = "Right click"
                setOnClickListener { sendMouse(2, 0, 0, 0); sendMouse(0, 0, 0, 0) }
            }
            val test = Button(this@MainActivity).apply {
                text = "Test HID"
                contentDescription = "Send a harmless mouse movement and Space key test to the connected computer"
                setOnClickListener {
                    if (wifiClient?.isConnected == true) {
                        setStatus("Disconnect Wi-Fi first to run the Bluetooth HID test.")
                        return@setOnClickListener
                    }
                    if (!hidRegistered || host == null) {
                        setStatus("Bluetooth HID is not ready. Wait for Ready, connect your computer, then test again.")
                        return@setOnClickListener
                    }
                    val acceptedBefore = acceptedInputReports
                    val rejectedBefore = rejectedInputReports
                    sendMouse(0, 24, 0, 0)
                    sendMouse(0, 0, 0, 0)
                    sendKey(0, 0x2C)
                    val accepted = acceptedInputReports - acceptedBefore
                    val rejected = rejectedInputReports - rejectedBefore
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Bluetooth HID test")
                        .setMessage(
                            "Android accepted $accepted of 4 reports and rejected $rejected. " +
                                "The test also sends a Space key press. Check whether the computer " +
                                "pointer moves and the key is received. An accepted report means " +
                                "Android queued it; it does not prove the host processed it."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            addView(left, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(right, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(test, LinearLayout.LayoutParams(0, dp(44), 1f))
        }

        root.addView(status)
        root.addView(controls)
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(mouseButtons)
        root.addView(sensitivityLabel)
        root.addView(sensitivity)
        root.addView(naturalScroll)
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun connectWifi(
        hostAddress: String,
        hostPort: Int,
        pairingCode: String,
        certificateFingerprint: String
    ) {
        getPreferences(MODE_PRIVATE).edit()
            .putString("wifi_address", hostAddress)
            .putString("wifi_port", hostPort.toString())
            .putString("wifi_fingerprint", certificateFingerprint)
            .apply()
        wifiClient?.close()
        val trustStore = WifiTrustedHostStore(this)
        val trustedToken = trustStore.getToken(certificateFingerprint)
        var tokenSaveFailed = false
        lateinit var newClient: WifiRemoteClient
        newClient = WifiRemoteClient(
            onState = { message, connected ->
                if (wifiClient === newClient) {
                    setStatus(
                        if (connected && tokenSaveFailed) {
                            "Connected, but secure device trust could not be saved. Re-pair next time."
                        } else message
                    )
                }
            },
            onPairingToken = { token ->
                tokenSaveFailed = !trustStore.storeToken(certificateFingerprint, token)
            }
        )
        wifiClient = newClient
        setStatus("Connecting securely to $hostAddress…")
        newClient.connect(hostAddress, hostPort, pairingCode, certificateFingerprint, trustedToken)
    }

    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "wastiairpad" || data.host != "pair") return
        intent.data = null
        val address = data.getQueryParameter("host") ?: return
        val port = data.getQueryParameter("port")?.toIntOrNull() ?: return
        val code = data.getQueryParameter("code") ?: return
        val fingerprint = data.getQueryParameter("fp") ?: return
        if (port !in 1..65535 || !code.matches(Regex("[0-9]{8}")) ||
            !fingerprint.matches(Regex("[0-9a-fA-F:]{64,95}"))
        ) {
            Toast.makeText(this, "This AirPad pairing QR is invalid.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with this computer?")
            .setMessage("Connect to $address over your local Wi-Fi network? Only continue if you scanned this code from your own computer's AirPad companion.")
            .setPositiveButton("Connect") { _, _ ->
                connectWifi(address, port, code, fingerprint)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWifiConnectDialog() {
        val preferences = getPreferences(MODE_PRIVATE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun field(hint: String, key: String, inputType: Int): EditText {
            return EditText(this).apply {
                this.hint = hint
                this.inputType = inputType
                setSingleLine(true)
                setText(preferences.getString(key, ""))
                contentDescription = hint
            }
        }
        val address = field("Computer local IP address", "wifi_address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Port (default 47777)", "wifi_port", InputType.TYPE_CLASS_NUMBER).apply {
            if (text.isNullOrBlank()) setText("47777")
        }
        val code = EditText(this).apply {
            hint = "8-digit pairing code (new computers only)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            contentDescription = hint
        }
        val fingerprint = field("Certificate SHA-256 fingerprint", "wifi_fingerprint",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val discoverButton = Button(this).apply {
            text = "Find computers on this Wi-Fi"
            setOnClickListener {
                wifiDiscovery?.close()
                val discovery = WifiHostDiscovery(this@MainActivity)
                wifiDiscovery = discovery
                Toast.makeText(this@MainActivity, "Searching for AirPad computers…", Toast.LENGTH_SHORT).show()
                discovery.discover { hosts ->
                    if (wifiDiscovery !== discovery) return@discover
                    wifiDiscovery = null
                    discovery.close()
                    if (hosts.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No AirPad computer found. Check the same Wi-Fi network or enter its IP manually.", Toast.LENGTH_LONG).show()
                    } else {
                        val labels = hosts.map { "${it.name} — ${it.address}:${it.port}" }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Computers found")
                            .setItems(labels) { _, index ->
                                val selected = hosts[index]
                                address.setText(selected.address)
                                port.setText(selected.port.toString())
                                fingerprint.setText(selected.fingerprint)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
        form.addView(discoverButton)
        form.addView(address)
        form.addView(port)
        form.addView(code)
        form.addView(fingerprint)
        form.addView(Button(this).apply {
            text = "Forget saved trust for this computer"
            setOnClickListener {
                val savedFingerprint = fingerprint.text.toString().trim()
                if (WifiTrustedHostStore(this@MainActivity).forget(savedFingerprint)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Phone trust removed. To revoke all old tokens on the computer, run: python host.py --forget-clients",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "No saved trust found for this fingerprint.", Toast.LENGTH_SHORT).show()
                }
            }
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect over Wi-Fi")
            .setMessage("Start the Wasti AirPad desktop companion. First pairing needs the 8-digit code and certificate fingerprint. After pairing, AirPad remembers this computer securely and the code is no longer needed on this phone.")
            .setView(form)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            wifiDiscovery?.close()
            wifiDiscovery = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hostAddress = address.text.toString().trim()
                val hostPort = port.text.toString().trim().toIntOrNull()
                val pairingCode = code.text.toString().trim()
                val certificateFingerprint = fingerprint.text.toString().trim()
                val hasSavedTrust = WifiTrustedHostStore(this).getToken(certificateFingerprint) != null
                if (hostAddress.isBlank() || hostPort == null || hostPort !in 1..65535 ||
                    (!hasSavedTrust && !pairingCode.matches(Regex("[0-9]{8}"))) ||
                    certificateFingerprint.isBlank()
                ) {
                    Toast.makeText(
                        this,
                        if (hasSavedTrust) "Enter a valid local IP, port and certificate fingerprint."
                        else "Enter a valid local IP, port, 8-digit pairing code and fingerprint.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                connectWifi(hostAddress, hostPort, pairingCode, certificateFingerprint)
                dialog.dismiss()
            }
        }
        dialog.show()
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
                    if (destroyed) {
                        try { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
                        catch (_: SecurityException) { }
                        catch (_: RuntimeException) { }
                        profileRequested = false
                        return
                    }
                    val deviceProfile = proxy as? BluetoothHidDevice
                    if (deviceProfile == null) {
                        try { bluetoothAdapter?.closeProfileProxy(profile, proxy) }
                        catch (_: SecurityException) { }
                        catch (_: RuntimeException) { }
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
                                    if (destroyed) return
                                    hidRegistered = registered
                                    if (!registered) {
                                        host = null
                                        if (::pad.isInitialized) pad.releaseLocalState()
                                        val staleProfile = hid
                                        hid = null
                                        profileRequested = false
                                        if (staleProfile != null) {
                                            try { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, staleProfile) }
                                            catch (_: SecurityException) { }
                                            catch (_: RuntimeException) { }
                                        }
                                    }
                                    setStatus(
                                        if (registered) "Ready. Pair this phone with your computer."
                                        else "HID is not registered. Keep AirPad open and retry Bluetooth setup."
                                    )
                                }

                                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                                    if (destroyed) return
                                    when (state) {
                                        BluetoothProfile.STATE_CONNECTED -> {
                                            reportFailureShown = false
                                            acceptedInputReports = 0
                                            rejectedInputReports = 0
                                            host = device
                                            setStatus("Connected to ${safeDeviceName(device)}. HID report mode is active.")
                                        }
                                        BluetoothProfile.STATE_CONNECTING -> {
                                            hidProtocolMode = HidReports.PROTOCOL_REPORT_MODE
                                            setStatus("Connecting to ${safeDeviceName(device)}…")
                                        }
                                        BluetoothProfile.STATE_DISCONNECTING -> {
                                            pad.releaseLocalState()
                                            if (host?.address == device.address) host = null
                                            setStatus("Disconnecting from ${safeDeviceName(device)}…")
                                        }
                                        BluetoothProfile.STATE_DISCONNECTED -> {
                                            if (host?.address == device.address) host = null
                                            setStatus("Not connected")
                                            pad.releaseLocalState()
                                        }
                                    }
                                }

                                override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
                                    if (destroyed || (host != null && host?.address != device.address)) return
                                    if (protocol != HidReports.PROTOCOL_BOOT_MODE &&
                                        protocol != HidReports.PROTOCOL_REPORT_MODE
                                    ) {
                                        setStatus("The computer requested an unsupported HID protocol mode.")
                                        return
                                    }
                                    hidProtocolMode = protocol
                                    if (host?.address == device.address) releaseAllInput()
                                    setStatus(
                                        if (protocol == HidReports.PROTOCOL_BOOT_MODE) {
                                            "Connected. Computer selected standard boot HID mode."
                                        } else {
                                            "Connected. Computer selected full HID report mode."
                                        }
                                    )
                                }

                                override fun onGetReport(
                                    device: BluetoothDevice,
                                    type: Byte,
                                    id: Byte,
                                    bufferSize: Int
                                ) {
                                    if (destroyed || host?.address != device.address) return
                                    if (bufferSize !in 0..64) {
                                        try {
                                            deviceProfile.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
                                        } catch (_: SecurityException) { }
                                        return
                                    }
                                    val payload = when {
                                        type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                                            hidProtocolMode == HidReports.PROTOCOL_BOOT_MODE &&
                                            id.toInt() == 0 && bufferSize <= 3 ->
                                            lastMouseReport.copyOf(3)
                                        type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                                            hidProtocolMode == HidReports.PROTOCOL_BOOT_MODE &&
                                            id.toInt() == 0 ->
                                            lastKeyboardReport.copyOf(8)
                                        type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                                            id.toInt() == HidReports.MOUSE_REPORT_ID ->
                                            lastMouseReport.copyOf(4)
                                        type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                                            id.toInt() == HidReports.KEYBOARD_REPORT_ID ->
                                            lastKeyboardReport.copyOf(8)
                                        type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                                            (id.toInt() == HidReports.KEYBOARD_REPORT_ID ||
                                                (hidProtocolMode == HidReports.PROTOCOL_BOOT_MODE && id.toInt() == 0)) ->
                                            byteArrayOf(keyboardLedState.toByte())
                                        else -> {
                                            try {
                                                deviceProfile.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                                            } catch (_: SecurityException) { }
                                            return
                                        }
                                    }
                                    try {
                                        val response = if (payload.size < bufferSize) payload.copyOf(bufferSize) else payload
                                        if (!deviceProfile.replyReport(device, type, id, response)) {
                                            setStatus("Android could not queue the requested HID report response.")
                                        }
                                    } catch (_: SecurityException) {
                                        setStatus("Android denied a Bluetooth HID report response.")
                                    } catch (_: RuntimeException) {
                                        setStatus("The computer's HID report request could not be answered.")
                                    }
                                }

                                override fun onSetReport(
                                    device: BluetoothDevice,
                                    type: Byte,
                                    id: Byte,
                                    data: ByteArray
                                ) {
                                    if (destroyed || host?.address != device.address) return
                                    if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                                        (id.toInt() == HidReports.KEYBOARD_REPORT_ID ||
                                            (hidProtocolMode == HidReports.PROTOCOL_BOOT_MODE && id.toInt() == 0)) &&
                                        data.size == 1
                                    ) {
                                        keyboardLedState = data[0].toInt() and 0x1F
                                    } else {
                                        try {
                                            deviceProfile.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
                                        } catch (_: SecurityException) { }
                                    }
                                }

                                override fun onInterruptData(
                                    device: BluetoothDevice,
                                    reportId: Byte,
                                    data: ByteArray
                                ) {
                                    if (destroyed || host?.address != device.address) return
                                    if ((reportId.toInt() == HidReports.KEYBOARD_REPORT_ID ||
                                            (hidProtocolMode == HidReports.PROTOCOL_BOOT_MODE && reportId.toInt() == 0)) &&
                                        data.size == 1
                                    ) {
                                        keyboardLedState = data[0].toInt() and 0x1F
                                    }
                                }

                                override fun onVirtualCableUnplug(device: BluetoothDevice) {
                                    if (destroyed) return
                                    if (host?.address == device.address) host = null
                                    setStatus("Bluetooth host disconnected")
                                    pad.releaseLocalState()
                                }
                            }
                        )
                        if (!registrationAccepted) {
                            hidRegistered = false
                            hid = null
                            profileRequested = false
                            try { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, deviceProfile) }
                            catch (_: SecurityException) { }
                            catch (_: RuntimeException) { }
                            setStatus("Android rejected HID registration. This device may not support HID peripheral mode.")
                        }
                    } catch (security: SecurityException) {
                        hidRegistered = false
                        hid = null
                        profileRequested = false
                        try { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, deviceProfile) }
                        catch (_: SecurityException) { }
                        catch (_: RuntimeException) { }
                        setStatus("Bluetooth permission is missing. Grant Nearby devices access in Settings.")
                    } catch (error: RuntimeException) {
                        hidRegistered = false
                        hid = null
                        profileRequested = false
                        try { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, deviceProfile) }
                        catch (_: SecurityException) { }
                        catch (_: RuntimeException) { }
                        setStatus("Could not register Bluetooth HID: ${error.localizedMessage ?: "unknown error"}")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (destroyed) return
                    hidRegistered = false
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
                        if (profile == null || !hidRegistered) {
                            setStatus(
                                if (profile == null) "Bluetooth HID is still starting. Wait a moment and tap Connect again."
                                else "Bluetooth HID registration is not ready yet. Wait for the Ready status and try again."
                            )
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

    private fun send(reportId: Int, report: ByteArray): Boolean {
        if (!hidRegistered) {
            reportRejected("HID application is not registered. Keep AirPad in the foreground and wait for Ready.")
            return false
        }
        val target = host
        if (target == null) {
            reportRejected("No Bluetooth HID host is connected. Reconnect to your computer.")
            return false
        }
        val profile = hid
        if (profile == null) {
            reportRejected("Bluetooth HID service is unavailable. Reopen AirPad to restore it.")
            return false
        }
        return try {
            val accepted = profile.sendReport(target, reportId, report)
            if (accepted) {
                val recoveringFromReportFailure = reportFailureShown
                when {
                    reportId == HidReports.MOUSE_REPORT_ID && report.size == 4 ->
                        lastMouseReport = report.copyOf()
                    reportId == HidReports.KEYBOARD_REPORT_ID && report.size == 8 ->
                        lastKeyboardReport = report.copyOf()
                    reportId == HidReports.BOOT_MOUSE_REPORT_ID && report.size == 3 ->
                        lastMouseReport = report.copyOf()
                    reportId == HidReports.BOOT_KEYBOARD_REPORT_ID && report.size == 8 ->
                        lastKeyboardReport = report.copyOf()
                }
                acceptedInputReports++
                if (recoveringFromReportFailure || acceptedInputReports == 1) {
                    setStatus("Bluetooth connected. Android accepted HID input reports.")
                }
                reportFailureShown = false
            } else {
                reportRejected("Android rejected HID report ${reportId} (${report.size} bytes). Reconnect the computer.")
            }
            accepted
        } catch (_: SecurityException) {
            reportRejected("Bluetooth permission was revoked. Grant Nearby devices access again.")
            false
        } catch (error: RuntimeException) {
            reportRejected("Bluetooth HID report failed: ${error.localizedMessage ?: "system error"}")
            false
        }
    }

    private fun reportRejected(message: String) {
        rejectedInputReports++
        if (!reportFailureShown) {
            reportFailureShown = true
            setStatus(message)
        }
    }

    private fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int): Pair<Int, ByteArray> =
        if (hidProtocolMode == HidReports.PROTOCOL_BOOT_MODE) {
            HidReports.BOOT_MOUSE_REPORT_ID to HidReports.bootMouse(buttons, dx, dy)
        } else {
            HidReports.MOUSE_REPORT_ID to HidReports.mouse(buttons, dx, dy, wheel)
        }

    private fun keyboardReport(modifiers: Int, keyCode: Int): Pair<Int, ByteArray> =
        if (hidProtocolMode == HidReports.PROTOCOL_BOOT_MODE) {
            HidReports.BOOT_KEYBOARD_REPORT_ID to HidReports.keyboard(modifiers, keyCode)
        } else {
            HidReports.KEYBOARD_REPORT_ID to HidReports.keyboard(modifiers, keyCode)
        }

    internal fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val network = wifiClient
        if (network?.isConnected == true) {
            network.sendMouse(buttons, dx, dy, wheel)
            return
        }
        val (reportId, report) = mouseReport(buttons, dx, dy, wheel)
        send(reportId, report)
    }

    internal fun releaseAllInput() {
        val network = wifiClient
        if (network?.isConnected == true) {
            network.releaseAll()
            return
        }
        sendMouse(0, 0, 0, 0)
        val (reportId, report) = keyboardReport(0, 0)
        send(reportId, report)
    }

    internal fun sendKey(modifiers: Int, keyCode: Int) {
        val network = wifiClient
        if (network?.isConnected == true) {
            network.sendKey(modifiers, keyCode)
            return
        }
        val (reportId, report) = keyboardReport(modifiers, keyCode)
        if (send(reportId, report)) {
            val (releaseId, releaseReport) = keyboardReport(0, 0)
            send(releaseId, releaseReport)
        }
    }

    internal fun sendCtrlWheel(direction: Int) {
        val network = wifiClient
        if (network?.isConnected == true) {
            network.sendKey(HidReports.MOD_LEFT_CTRL, 0)
            network.sendMouse(0, 0, 0, direction)
            return
        }
        val (reportId, report) = keyboardReport(HidReports.MOD_LEFT_CTRL, 0)
        if (send(reportId, report)) {
            sendMouse(0, 0, 0, direction)
            val (releaseId, releaseReport) = keyboardReport(0, 0)
            send(releaseId, releaseReport)
        }
    }

    private fun setStatus(message: String) {
        if (destroyed || !::status.isInitialized) return
        runOnUiThread {
            if (!destroyed && ::status.isInitialized) status.text = message
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        if (::pad.isInitialized) pad.releaseLocalState()
        destroyed = true
        hidRegistered = false
        profileRequested = false
        try { hid?.unregisterApp() } catch (_: SecurityException) { } catch (_: RuntimeException) { }
        hid?.let { profile ->
            try { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, profile) }
            catch (_: SecurityException) { } catch (_: RuntimeException) { }
        }
        hid = null
        host = null
        wifiClient?.close()
        wifiClient = null
        wifiDiscovery?.close()
        wifiDiscovery = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 4102
    }
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

    companion object {
        private const val PALM_REJECTION_TOUCH_MAJOR = 260f
    }

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

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
        for (index in 0 until event.pointerCount) {
            if (event.getTouchMajor(index) > PALM_REJECTION_TOUCH_MAJOR) {
                if (dragging) activity.sendMouse(0, 0, 0, 0)
                dragging = false
                lastTapTime = 0L
                resetGesture()
                return true
            }
        }
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
                        if (GesturePolicy.isPinch(pinchDelta, dy, density)) {
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
                val isTap = GesturePolicy.isTap(event.eventTime - downTime, totalMotion, density)
                if (isTap) performClick()
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
                } else if (maxPointers >= 3 && GesturePolicy.isSwipe(gestureX, gestureY, density)) {
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
        // Send neutral reports even if the local gesture flag was already cleared:
        // a previous release report may have been rejected by the Bluetooth stack.
        (context as? MainActivity)?.releaseAllInput()
        dragging = false
        lastTapTime = 0L
        resetGesture()
    }
}
