package uniear.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import dev.hyperears.integration.NoiseMode
import uniear.core.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val radio by lazy { AndroidRadio(this) }
    private val discovery = Executors.newSingleThreadExecutor()
    private lateinit var devices: Spinner
    private lateinit var scan: Button
    private lateinit var connect: Button
    private lateinit var disconnect: Button
    private lateinit var refresh: Button
    private lateinit var model: TextView
    private lateinit var battery: TextView
    private lateinit var status: TextView
    private lateinit var modes: Map<NoiseMode, Button>
    private var session: HeadsetSession? = null
    private var generation = 0
    private var scanning = false
    private var permissionAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = Color.rgb(237, 243, 249)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        layout.addView(text("UniEar", 32, bold = true))
        layout.addView(card().apply {
            addView(text("蓝牙耳机", 17, bold = true))
            devices = Spinner(this@MainActivity).also { addView(it) }
            scan = button("扫描") { withPermissions(::discover) }
            connect = button("连接") { withPermissions(::connect) }
            disconnect = button("断开", ::disconnect)
            addView(row(scan, connect, disconnect))
        })
        layout.addView(card().apply {
            model = text("选择一台耳机", 21, bold = true).also { addView(it) }
            battery = text("电量未知", 16).also { addView(it) }
            refresh = button("刷新状态") { session?.refresh() }
            modes = NoiseMode.entries.associateWith { mode ->
                button(mode.label()) { session?.setNoiseMode(mode) }
            }
            addView(row(modes.getValue(NoiseMode.ANC), modes.getValue(NoiseMode.TRANSPARENCY)))
            addView(row(modes.getValue(NoiseMode.OFF), modes.getValue(NoiseMode.WIND)))
            addView(refresh)
        })
        status = text("", 14).also {
            it.setPadding(dp(4), dp(10), dp(4), dp(10))
            it.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)
            layout.addView(it)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(background)
            addView(layout)
        })
        devices.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = buttons()
            override fun onNothingSelected(parent: AdapterView<*>?) = buttons()
        }
        render(HeadsetView("在系统中配对耳机，再点击扫描。"))
    }

    private fun discover() {
        if (scanning || session != null) return
        scanning = true
        buttons()
        status.text = "正在扫描蓝牙设备…"
        discovery.execute {
            val result = runCatching { radio.discover() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning = false
                result.onSuccess { peers ->
                    devices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, peers).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    status.text = if (peers.isEmpty()) "未发现设备。请确认耳机已配对或正在广播。"
                        else "找到 ${peers.size} 台设备。"
                }.onFailure { status.text = it.message ?: "蓝牙扫描失败" }
                buttons()
            }
        }
    }

    private fun connect() {
        val selected = devices.selectedItem as? Headset ?: return
        session?.close()
        val active = ++generation
        session = HeadsetSession(radio, selected) { view ->
            runOnUiThread { if (active == generation && !isDestroyed) render(view) }
        }
        render(HeadsetView("正在识别耳机…"))
    }

    private fun disconnect() {
        generation++
        session?.close()
        session = null
        render(HeadsetView("已断开控制连接"))
    }

    private fun render(view: HeadsetView) {
        if (view.ended) {
            generation++
            session?.close()
            session = null
        }
        model.text = view.model ?: "选择一台耳机"
        battery.text = view.battery.label()
        status.text = listOfNotNull(view.endpoint, view.status).joinToString("\n")
        for ((mode, button) in modes) {
            button.isEnabled = mode in view.modes
            button.text = if (mode == view.mode) "✓ ${mode.label()}" else mode.label()
        }
        refresh.isEnabled = view.ready
        buttons()
    }

    private fun buttons() {
        scan.isEnabled = session == null && !scanning
        connect.isEnabled = session == null && !scanning && devices.selectedItem != null
        disconnect.isEnabled = session != null
        devices.isEnabled = session == null && !scanning
    }

    private fun withPermissions(action: () -> Unit) {
        val wanted = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            permissionAction = action
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1) {
            val action = permissionAction ?: ::discover
            permissionAction = null
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) action()
            else status.text = "需要蓝牙设备访问权限才能发现和控制耳机。"
        }
    }

    override fun onDestroy() {
        generation++
        session?.close()
        discovery.shutdownNow()
        super.onDestroy()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun text(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(21, 40, 65))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        for (view in views) addView(view, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
