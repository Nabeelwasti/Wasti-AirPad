package com.example.airpad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import kotlin.math.*

val DESC: ByteArray = ("05010902A10185010901A100050919012903150025019503750181029501750581030501" +
    "0930093109381581257F750895038106C0C0" +
    "05010906A1018502050719E029E7150025017501950881029501750881039506750815002565" +
    "0507190029658100C0").replace("81030501", "81030501").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class MainActivity : Activity() {
    var hid: BluetoothHidDevice? = null
    var host: BluetoothDevice? = null
    lateinit var status: TextView
    lateinit var pad: PadView
    val ad: BluetoothAdapter get() = getSystemService(BluetoothManager::class.java).adapter

    fun send(id: Int, b: ByteArray) { host?.let { hid?.sendReport(it, id, b) } }
    fun mouse(btn: Int, dx: Int, dy: Int, w: Int) = send(1, byteArrayOf(btn.toByte(),
        dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), w.coerceIn(-127, 127).toByte()))
    fun key(mod: Int, k: Int) { send(2, byteArrayOf(mod.toByte(), 0, k.toByte(), 0, 0, 0, 0, 0)); send(2, ByteArray(8)) }
    fun ctrlWheel(w: Int) { send(2, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0)); mouse(0, 0, 0, w); send(2, ByteArray(8)) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF05060F.toInt()) }
        status = TextView(this).apply { text = "Not connected"; setTextColor(Color.WHITE); setPadding(24, 24, 24, 8) }
        val row = LinearLayout(this)
        row.addView(Button(this).apply { text = "Pair mode"; setOnClickListener {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) } })
        row.addView(Button(this).apply { text = "Connect"; setOnClickListener { pickHost() } })
        pad = PadView(this)
        val sens = SeekBar(this).apply { max = 100; progress = 45
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) { pad.sens = 0.5f + p / 40f }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {} }) }
        val nat = CheckBox(this).apply { text = "Natural scroll"; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> pad.natural = c } }
        root.addView(status); root.addView(row)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(sens); root.addView(nat)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        else initHid()
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { initHid() }

    fun initHid() {
        ad.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice; hid = h
                h.registerApp(BluetoothHidDeviceAppSdpSettings("AirPad", "Phone touchpad", "AirPad",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESC), null, null, mainExecutor,
                    object : BluetoothHidDevice.Callback() {
                        override fun onConnectionStateChanged(d: BluetoothDevice, st: Int) {
                            host = if (st == BluetoothProfile.STATE_CONNECTED) d else null
                            runOnUiThread { status.text = if (host != null) "Connected: ${d.name}" else "Not connected" }
                        } })
            }
            override fun onServiceDisconnected(p: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun pickHost() {
        val l = ad.bondedDevices.toList()
        AlertDialog.Builder(this).setTitle("Choose your laptop")
            .setItems(l.map { it.name }.toTypedArray()) { _, i -> hid?.connect(l[i]) }.show()
    }
}

class PadView(val a: MainActivity) : View(a) {
    var sens = 1.6f; var natural = false
    class B(var x: Float, var y: Float, var r: Float, var al: Float, val h: Float)
    val bl = ArrayList<B>()
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    var hue = 0f; var lx = Float.NaN; var ly = 0f; var maxN = 0; var total = 0f; var t0 = 0L
    var lastTap = 0L; var drag = false; var edge = false
    var mx = 0f; var my = 0f; var sAcc = 0f; var zAcc = 0f; var lastD = 0f; var gx = 0f; var gy = 0f

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF05060F.toInt())
        val it = bl.iterator()
        while (it.hasNext()) {
            val b = it.next(); b.r += 1.2f; b.al -= 0.025f; b.y -= 0.3f
            if (b.al <= 0) { it.remove(); continue }
            p.shader = RadialGradient(b.x, b.y, b.r, Color.HSVToColor((b.al * 255).toInt(), floatArrayOf(b.h, 0.8f, 1f)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(b.x, b.y, b.r, p)
        }
        if (bl.isNotEmpty()) postInvalidateOnAnimation()
    }

    fun tick() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun click(b: Int) { a.mouse(b, 0, 0, 0); a.mouse(0, 0, 0, 0); tick() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        for (i in 0 until e.pointerCount) if (e.getTouchMajor(i) > 260f) return true // palm rejection
        val n = e.pointerCount; var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += e.getX(i); cy += e.getY(i) }
        cx /= n; cy /= n
        val now = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                t0 = now; maxN = 1; total = 0f; gx = 0f; gy = 0f; lx = cx; ly = cy; edge = cx > width * 0.9f
                if (now - lastTap < 250) { drag = true; a.mouse(1, 0, 0, 0) } // tap-and-drag
            }
            MotionEvent.ACTION_POINTER_DOWN -> { maxN = max(maxN, n); lx = Float.NaN
                if (n == 2) lastD = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) }
            MotionEvent.ACTION_POINTER_UP -> lx = Float.NaN
            MotionEvent.ACTION_MOVE -> {
                if (lx.isNaN()) { lx = cx; ly = cy; return true }
                val dx = cx - lx; val dy = cy - ly; lx = cx; ly = cy; total += hypot(dx, dy)
                bl.add(B(cx, cy, 40f, 0.6f, hue)); hue = (hue + 4) % 360; if (bl.size > 150) bl.removeAt(0)
                postInvalidateOnAnimation()
                when {
                    n == 1 && edge -> scroll(dy)
                    n == 1 -> { mx += dx * sens; my += dy * sens
                        val ix = mx.toInt(); val iy = my.toInt(); mx -= ix; my -= iy
                        a.mouse(if (drag) 1 else 0, ix, iy, 0) }
                    n == 2 -> { val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); zAcc += d - lastD; lastD = d
                        if (abs(zAcc) > 30) { a.ctrlWheel(if (zAcc > 0) 1 else -1); zAcc = 0f } else if (abs(dy) > 0) scroll(dy) }
                    else -> { gx += dx; gy += dy }
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = now - t0 < 220 && total < 20
                if (drag) { a.mouse(0, 0, 0, 0); drag = false; lastTap = 0 }
                else if (tap) when (maxN) {
                    1 -> { click(1); lastTap = now }
                    2 -> click(2)
                    3 -> { a.key(8, 0x16); tick() }          // Win+S search
                    4 -> { a.key(8, 0x04); tick() }          // Win+A action center
                } else if (maxN >= 3 && max(abs(gx), abs(gy)) > 120) {
                    val horiz = abs(gx) > abs(gy)
                    if (maxN == 3) when { !horiz && gy < 0 -> a.key(8, 0x2B) // Win+Tab overview
                        !horiz -> a.key(8, 0x07)                              // Win+D desktop
                        gx < 0 -> a.key(4, 0x2B) else -> a.key(6, 0x2B) }     // Alt(+Shift)+Tab
                    else when { horiz && gx < 0 -> a.key(9, 0x50)             // Ctrl+Win+Left
                        horiz -> a.key(9, 0x4F)                               // Ctrl+Win+Right
                        gy < 0 -> a.key(8, 0x2B) else -> a.key(8, 0x07) }
                    tick()
                }
                lx = Float.NaN; maxN = 0
            }
        }
        return true
    }

    fun scroll(dy: Float) {
        sAcc += if (natural) dy else -dy
        while (abs(sAcc) >= 10) { val s = if (sAcc > 0) 1 else -1; a.mouse(0, 0, 0, s); sAcc -= 10 * s }
    }
}
