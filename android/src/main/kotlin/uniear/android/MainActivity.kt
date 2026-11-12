package uniear.android

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest
import uniear.core.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val discovery = Executors.newSingleThreadExecutor()
    private val radio by lazy { AndroidRadio(this) }
    private lateinit var devicePage: LinearLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var deviceTab: TextView
    private lateinit var settingsTab: TextView
    private lateinit var scroll: ScrollView
    private lateinit var devices: Spinner
    private lateinit var scan: TextView
    private lateinit var connect: TextView
    private lateinit var refresh: TextView
    private lateinit var model: TextView
    private lateinit var battery: TextView
    private lateinit var status: TextView
    private lateinit var modes: Map<NoiseMode, TextView>
    private lateinit var features: LinearLayout
    private lateinit var apps: LinearLayout
    private lateinit var settingsStatus: TextView
    private var lastControls: List<ControlGroup>? = null
    private var lastApps: List<dev.hyperears.integration.ControlAppSpec>? = null
    private var scanning = false
    private var permissionAction: (() -> Unit)? = null
    private var settingsSelected = false
    private val renderView: (HeadsetView) -> Unit = ::render

    private var dark = false
    private val backgroundColor get() = if (dark) Color.rgb(16, 26, 22) else Color.rgb(242, 245, 240)
    private val cardColor get() = if (dark) Color.rgb(26, 39, 33) else Color.WHITE
    private val ink get() = if (dark) Color.rgb(232, 244, 235) else Color.rgb(24, 42, 35)
    private val muted get() = if (dark) Color.rgb(176, 197, 184) else Color.rgb(74, 94, 84)
    private val accent get() = if (dark) Color.rgb(151, 218, 195) else Color.rgb(33, 90, 80)
    private val pale get() = if (dark) Color.rgb(41, 61, 52) else Color.rgb(225, 237, 228)
    private val onAccent get() = if (dark) Color.rgb(15, 52, 43) else Color.WHITE
    private var availableHeadsets: List<Headset> = emptyList()

    private data class DeviceSelection(val devices: List<Headset>, val address: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark_mode", false)
        setTheme(if (dark) R.style.UniEarDarkTheme else R.style.UniEarTheme)
        super.onCreate(savedInstanceState)
        val layout = column().apply {
            setBackgroundColor(backgroundColor)
            setPadding(dp(20), dp(14), dp(20), dp(24))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_headphones)
            setColorFilter(accent)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = shape(pale, 14)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        header.addView(text("UniEar", 28, bold = true))
        layout.addView(header)
        deviceTab = action("设备") { selectPage(false) }
        settingsTab = action("设置") { selectPage(true) }
        layout.addView(row(deviceTab, settingsTab))
        devicePage = column().apply { buildDevicePage(this) }
        settingsPage = column().apply { buildSettingsPage(this) }
        layout.addView(devicePage)
        layout.addView(settingsPage)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        setContentView(scroll)
        selectPage(savedInstanceState?.getBoolean("settings") == true)
        (lastNonConfigurationInstance as? DeviceSelection)?.let { showHeadsets(it.devices, it.address) }
    }

    override fun onRetainNonConfigurationInstance(): Any =
        DeviceSelection(availableHeadsets, (devices.selectedItem as? Headset)?.address)

    override fun onStart() {
        super.onStart()
        ControlState.observe(renderView)
    }

    override fun onResume() {
        super.onResume()
        ControlState.refreshSurfaces(this)
        updateSettingsStatus()
    }

    override fun onStop() {
        ControlState.remove(renderView)
        super.onStop()
    }

    override fun onDestroy() {
        discovery.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("settings", settingsSelected)
        super.onSaveInstanceState(state)
    }

    private fun selectPage(settings: Boolean) {
        settingsSelected = settings
        devicePage.visibility = if (settings) View.GONE else View.VISIBLE
        settingsPage.visibility = if (settings) View.VISIBLE else View.GONE
        style(deviceTab, !settings)
        style(settingsTab, settings)
        scroll.scrollTo(0, 0)
        if (settings) updateSettingsStatus()
    }

    private fun buildDevicePage(page: LinearLayout) {
        page.addView(card().apply {
            model = text("选择一台耳机", 23, bold = true).also { addView(it) }
            status = text("", 14, muted).also {
                it.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)
                addView(it)
            }
            battery = text("电量未知", 15, accent).also { addView(it) }
            devices = Spinner(this@MainActivity).also {
                it.minimumHeight = dp(48)
                it.contentDescription = "选择蓝牙耳机"
                addView(it, LinearLayout.LayoutParams(-1, -2))
            }
            devices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = buttons()
                override fun onNothingSelected(parent: AdapterView<*>?) = buttons()
            }
            scan = action("查找已连接耳机") { withBluetooth(false, ::discover) }
            connect = action("连接", primary = true) {
                if (ControlState.busy) ControlState.service?.disconnect() else withBluetooth(true, ::connect)
            }
            addView(row(scan, connect))
        })
        page.addView(card().apply {
            addView(text("噪声控制", 19, bold = true))
            modes = NoiseMode.entries.associateWith { mode ->
                action(mode.label()) { ControlState.command(StandardControlRequest.SetNoiseMode(mode)) }
            }
            addView(row(modes.getValue(NoiseMode.ANC), modes.getValue(NoiseMode.TRANSPARENCY)))
            addView(row(modes.getValue(NoiseMode.OFF), modes.getValue(NoiseMode.WIND)))
            refresh = action("刷新耳机状态") { ControlState.command(StandardControlRequest.Refresh) }
            addView(refresh)
        })
        features = column().also { page.addView(it) }
        apps = column().also { page.addView(it) }
    }

    private fun buildSettingsPage(page: LinearLayout) {
        page.addView(card().apply {
            addView(text("外观", 21, bold = true))
            addView(Switch(this@MainActivity).apply {
                text = "深色模式"
                textSize = 16f
                setTextColor(ink)
                minHeight = dp(48)
                isChecked = dark
                setOnCheckedChangeListener { _, enabled ->
                    getPreferences(MODE_PRIVATE).edit().putBoolean("dark_mode", enabled).apply()
                    recreate()
                }
            })
        })
        page.addView(card().apply {
            addView(text("快捷控制", 21, bold = true))
            addView(text("先在设备页连接耳机，再从各入口切换已支持的模式。", 14, muted))
            addSetting(this, Surface.NOTIFICATION, "通知中心菜单", "折叠时可切换模式，展开后查看耳机信息。")
            addSetting(this, Surface.TILE, "控制中心磁贴", "点按轮换模式，未连接时打开 UniEar。")
            addView(action("添加到控制中心") { addTile() })
            addSetting(this, Surface.WIDGET, "桌面小组件", "显示耳机状态和各模式按钮。")
            addView(action("添加到桌面") { pinWidget() })
            addSetting(this, Surface.CAPSULE, "UniEar 悬浮胶囊", "跨应用显示模式控制。需允许显示在其他应用上层。")
            addView(action("管理悬浮权限") { overlaySettings() })
            settingsStatus = text("", 13, muted).also { addView(it) }
        })
        page.addView(card().apply {
            addView(text("厂商原生入口", 18, bold = true))
            addView(text("华为实况窗、OPPO 流体云、vivo 原子岛和小米超级岛均需厂商接入。这一版本使用 UniEar 悬浮胶囊。", 14, muted))
        })
    }

    private fun addSetting(card: LinearLayout, surface: Surface, title: String, description: String) {
        card.addView(Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(ink)
            minHeight = dp(48)
            isChecked = surface.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                surface.set(this@MainActivity, enabled)
                if (enabled && surface == Surface.NOTIFICATION) askNotificationPermission()
                if (enabled && surface == Surface.CAPSULE && !Settings.canDrawOverlays(this@MainActivity)) {
                    overlaySettings()
                }
                updateSettingsStatus()
            }
        })
        card.addView(text(description, 13, muted))
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    private fun updateSettingsStatus() {
        val notes = mutableListOf<String>()
        if (Surface.CAPSULE.enabled(this) && !Settings.canDrawOverlays(this)) notes += "悬浮胶囊等待系统授权。"
        if (Surface.NOTIFICATION.enabled(this) &&
            getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == false) {
            notes += "请在系统设置中允许 UniEar 通知。"
        }
        settingsStatus.text = notes.joinToString("\n")
        settingsStatus.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun overlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: android.content.ActivityNotFoundException) {
            toast("请在系统设置中查找悬浮窗权限。")
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            toast("打开控制中心的编辑页面，添加“耳机模式”。")
            return
        }
        val manager = getSystemService(StatusBarManager::class.java) ?: return
        manager.requestAddTileService(ComponentName(this, EarbudTile::class.java), "耳机模式",
            Icon.createWithResource(this, R.drawable.ic_headphones), mainExecutor) { result ->
            if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                toast("也可以在控制中心编辑页面添加“耳机模式”。")
            }
        }
    }

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported ||
            !manager.requestPinAppWidget(ComponentName(this, EarbudWidget::class.java), null, null)) {
            toast("请长按桌面，在小组件列表中添加 UniEar。")
        }
    }

    private fun discover() = queryHeadsets { peers ->
        status.text = if (peers.isEmpty()) "未找到已连接的蓝牙耳机，请先在系统中连接耳机。"
            else "找到 ${peers.size} 副已连接的耳机"
    }

    private fun queryHeadsets(onResult: (List<Headset>) -> Unit) {
        if (scanning || ControlState.busy) return
        scanning = true
        buttons()
        status.text = "正在读取已连接的蓝牙耳机…"
        discovery.execute {
            val result = runCatching { radio.discover() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning = false
                result.onSuccess { peers ->
                    val selected = (devices.selectedItem as? Headset)?.address
                    showHeadsets(peers, selected ?: getPreferences(MODE_PRIVATE).getString("last_address", null))
                    onResult(peers)
                }.onFailure {
                    showHeadsets(emptyList(), null)
                    status.text = it.message ?: "无法读取蓝牙连接状态"
                }
                buttons()
            }
        }
    }

    private fun connect() {
        val selected = devices.selectedItem as? Headset ?: return
        queryHeadsets { peers ->
            val current = peers.firstOrNull { it.address == selected.address }
            if (current == null) status.text = "这副耳机已断开，请先在系统中重新连接。"
            else if (hasWindowFocus()) {
                getPreferences(MODE_PRIVATE).edit().putString("last_address", current.address).apply()
                ControlState.connect(this, current)
            } else status.text = "已找到耳机，回到设备页后点击连接。"
        }
    }

    private fun showHeadsets(peers: List<Headset>, address: String?) {
        availableHeadsets = peers
        devices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, peers).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val index = peers.indexOfFirst { it.address == address }
        if (index >= 0) devices.setSelection(index)
    }

    private fun render(view: HeadsetView) {
        model.text = view.model ?: "选择一台耳机"
        if (!scanning) status.text = view.status
        battery.text = view.battery.label()
        for ((mode, button) in modes) {
            button.isEnabled = mode in view.modes
            style(button, view.mode == mode)
        }
        refresh.isEnabled = view.ready
        style(refresh, false)
        if (view.controls != lastControls) {
            lastControls = view.controls
            features.removeAllViews()
            if (view.controls.isNotEmpty()) features.addView(card().apply {
                addView(text("型号专属功能", 19, bold = true))
                for (group in view.controls) {
                    addView(text(group.title, 15, bold = true).apply { setPadding(0, dp(12), 0, 0) })
                    group.hint?.let { addView(text(it, 13, muted)) }
                    group.choices.chunked(3).forEach { choices ->
                        addView(row(*choices.map { choice ->
                            action(choice.label, choice.selected) { ControlState.command(choice.request) }
                        }.toTypedArray()))
                    }
                }
            })
        }
        if (view.controlApps != lastApps) {
            lastApps = view.controlApps
            apps.removeAllViews()
            if (view.controlApps.isNotEmpty()) apps.addView(card().apply {
                addView(text("厂商应用", 18, bold = true))
                for (app in view.controlApps) addView(action("打开 ${app.displayName}") {
                    val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launch == null) toast("请先安装 ${app.displayName}") else {
                        ControlState.service?.disconnect()
                        startActivity(launch)
                    }
                })
            })
        }
        buttons()
    }

    private fun buttons() {
        val busy = ControlState.busy
        scan.isEnabled = !busy && !scanning
        devices.isEnabled = !busy && !scanning
        connect.text = if (busy) "断开" else "连接"
        connect.isEnabled = if (busy) ControlState.service?.active == true else !scanning && devices.selectedItem != null
        style(scan, false)
        style(connect, true)
    }

    private fun withBluetooth(forControl: Boolean, action: () -> Unit) {
        val wanted = if (Build.VERSION.SDK_INT >= 31) {
            if (forControl) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (forControl) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyArray()
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
            else status.text = "需要蓝牙权限才能发现和控制耳机。"
        }
        if (code == 2) {
            ControlState.refreshSurfaces(this)
            updateSettingsStatus()
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card() = column().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(cardColor, 22)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun row(vararg children: View) = LinearLayout(this).apply {
        for ((index, child) in children.withIndex()) addView(child, LinearLayout.LayoutParams(0, -2, 1f).apply {
            topMargin = dp(8)
            if (index > 0) marginStart = dp(8)
        })
    }

    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun action(label: String, primary: Boolean = false, run: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            minimumHeight = dp(46)
            minHeight = dp(46)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setOnClickListener { run() }
            style(this, primary)
        }

    private fun style(view: TextView, selected: Boolean) {
        view.background = shape(if (selected) accent else pale, 13)
        view.setTextColor(if (selected) onAccent else ink)
        view.isSelected = selected
        view.alpha = if (view.isEnabled) 1f else .45f
    }

    private fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(size: Int) = (size * resources.displayMetrics.density + .5f).toInt()
}

