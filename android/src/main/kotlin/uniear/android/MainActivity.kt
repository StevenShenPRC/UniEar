package uniear.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var networkRuleList: LinearLayout
    private lateinit var networkRuleStatus: TextView
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
        val root = column().apply {
            setBackgroundColor(backgroundColor)
            setPadding(dp(20), dp(14), dp(20), 0)
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
        root.addView(header)
        deviceTab = action(tr("devices")) { selectPage(false) }
        settingsTab = action(tr("settings")) { selectPage(true) }
        root.addView(row(deviceTab, settingsTab))
        val layout = column().apply { setPadding(0, 0, 0, dp(24)) }
        devicePage = column().apply { buildDevicePage(this) }
        settingsPage = column().apply { buildSettingsPage(this) }
        layout.addView(devicePage)
        layout.addView(settingsPage)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
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
        ControlState.service?.updateAutomation()
        updateSettingsStatus()
        renderNetworkRules()
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
            model = text(tr("select_headphones"), 23, bold = true).also { addView(it) }
            status = text("", 14, muted).also {
                it.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)
                addView(it)
            }
            battery = text(tr("battery_level_unknown"), 15, accent).also { addView(it) }
            addView(text(tr("connected_bluetooth_headphones"), 14, muted).apply { setPadding(0, dp(12), 0, dp(4)) })
            devices = Spinner(this@MainActivity).also {
                it.minimumHeight = dp(48)
                it.contentDescription = tr("select_bluetooth_headphones")
                addView(it, LinearLayout.LayoutParams(-1, -2))
            }
            devices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = buttons()
                override fun onNothingSelected(parent: AdapterView<*>?) = buttons()
            }
            scan = action(tr("find_connected_headphones")) { withBluetooth(false, ::discover) }
            connect = action(tr("connect"), primary = true) {
                if (ControlState.busy) ControlState.service?.disconnect() else withBluetooth(true, ::connect)
            }
            addView(scan)
            addView(connect)
        })
        page.addView(card().apply {
            addView(text(tr("noise_control"), 19, bold = true))
            addView(text(tr("choose_a_mode_supported_by_your_headphones"), 14, muted))
            modes = NoiseMode.entries.associateWith { mode ->
                action(mode.label()) { ControlState.command(StandardControlRequest.SetNoiseMode(mode)) }
            }
            addView(row(modes.getValue(NoiseMode.ANC), modes.getValue(NoiseMode.TRANSPARENCY)))
            addView(row(modes.getValue(NoiseMode.OFF), modes.getValue(NoiseMode.WIND)))
            refresh = action(tr("refresh_headphone_status")) { ControlState.command(StandardControlRequest.Refresh) }
            addView(refresh)
        })
        features = column().also { page.addView(it) }
        apps = column().also { page.addView(it) }
    }

    private fun buildSettingsPage(page: LinearLayout) {
        page.addView(card().apply {
            addView(text(tr("appearance"), 21, bold = true))
            addView(Switch(this@MainActivity).apply {
                text = tr("dark_mode")
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
            addView(text(tr("network_automation"), 21, bold = true))
            addView(text(tr("switch_listening_modes_by_network_while_headphones"), 14, muted))
            val rules = NetworkRules.read(this@MainActivity)
            addView(Switch(this@MainActivity).apply {
                text = tr("enable_network_automation")
                textSize = 16f
                setTextColor(ink)
                minHeight = dp(48)
                isChecked = rules.enabled
                setOnCheckedChangeListener { _, enabled ->
                    updateNetworkRules(NetworkRules.read(this@MainActivity).copy(enabled = enabled))
                }
            })
            networkRuleList = column().also { addView(it) }
            addView(action(tr("add_a_wi_fi_rule")) { addWifiRule() })
            addView(action(tr("allow_access_to_wi_fi_names")) { requestSsidPermission() })
            networkRuleStatus = text("", 13, muted).also { addView(it) }
            renderNetworkRules()
        })
        page.addView(card().apply {
            addView(text(tr("headphone_connection"), 21, bold = true))
            addSetting(this, Surface.AUTO_CONNECT, tr("automatically_connect_controls"), tr("try_up_to_three_times_after_supported"))
            addSetting(this, Surface.LIVE_UPDATE, tr("headphone_connection_updates"), tr("with_auto_connect_off_the_notification_offers"))
            addView(action(tr("allow_bluetooth_connection_detection")) {
                withBluetooth(true) {
                    askNotificationPermission()
                    updateSettingsStatus()
                }
            })
            if (CompanionHeadsets.available(this@MainActivity)) {
                addView(action(tr("associate_headphones_for_background_connection")) { selectCompanionHeadset() })
            }
            if (LiveUpdates.supported) addView(action(tr("manage_live_update_permissions")) {
                if (!LiveUpdates.openSettings(this@MainActivity)) toast(tr("check_uniear_notification_permissions_in_system_settings"))
            })
        })
        page.addView(card().apply {
            addView(text(tr("quick_controls"), 21, bold = true))
            addView(text(tr("connect_your_headphones_on_the_devices_tab"), 14, muted))
            addSetting(this, Surface.NOTIFICATION, tr("notification_controls"), tr("switch_modes_in_the_collapsed_notification_expand"))
            addSetting(this, Surface.TILE, tr("quick_settings_tile"), tr("tap_to_cycle_through_modes_long_press"))
            addView(action(tr("add_to_quick_settings")) { addTile() })
            addSetting(this, Surface.WIDGET, tr("home_screen_widget"), tr("show_headphone_status_and_mode_buttons"))
            addView(action(tr("add_to_home_screen")) { pinWidget() })
            addSetting(this, Surface.CAPSULE, tr("uniear_floating_controls"), tr("show_controls_over_other_apps_requires_overlay"))
            addView(action(tr("manage_overlay_permission")) { overlaySettings() })
            settingsStatus = text("", 13, muted).also { addView(it) }
        })
        page.addView(card().apply {
            addView(text(tr("vendor_integrations"), 18, bold = true))
            addView(text(tr("huawei_live_view_oppo_fluid_cloud_vivo"), 14, muted))
        })
    }

    private fun updateNetworkRules(rules: NetworkRules) {
        rules.save(this)
        ControlState.service?.updateAutomation()
        renderNetworkRules()
    }

    private fun renderNetworkRules() {
        val rules = NetworkRules.read(this)
        networkRuleList.removeAllViews()
        networkRuleList.addView(action(tr("mobile_data_0", rules.cellular?.label() ?: tr("do_nothing"))) {
            chooseNetworkMode(tr("mobile_data"), rules.cellular) { mode ->
                updateNetworkRules(NetworkRules.read(this).copy(cellular = mode))
            }
        })
        for ((ssid, mode) in rules.wifi.toSortedMap()) {
            networkRuleList.addView(action("$ssid · ${mode.label()}") { editWifiRule(ssid) })
        }
        networkRuleStatus.text = when {
            rules.wifi.isEmpty() -> tr("add_rules_for_specific_wi_fi_names")
            !hasSsidPermission() -> tr("wi_fi_rules_need_precise_location_access")
            !canReadWifiName() -> tr("turn_on_system_location_so_android_can")
            else -> tr("wi_fi_names_and_rules_are_stored")
        }
    }

    private fun editWifiRule(ssid: String) {
        chooseNetworkMode(ssid, NetworkRules.read(this).wifi[ssid]) { mode ->
            val current = NetworkRules.read(this)
            updateNetworkRules(current.copy(wifi = if (mode == null) current.wifi - ssid else current.wifi + (ssid to mode)))
        }
    }

    private fun chooseNetworkMode(title: String, current: NoiseMode?, save: (NoiseMode?) -> Unit) {
        val modes = listOf(null) + NoiseMode.entries
        val labels = modes.map { it?.label() ?: tr("do_nothing") }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, item ->
                save(modes[item])
                dialog.dismiss()
            }.setNegativeButton(tr("cancel"), null).show()
    }

    private fun addWifiRule() {
        val input = EditText(this).apply {
            hint = tr("wi_fi_name_ssid")
            setSingleLine(true)
        }
        val container = column().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(action(tr("use_current_wi_fi")) {
                val ssid = connectedWifiName()
                if (ssid != null) input.setText(ssid)
                else if (!hasSsidPermission()) requestSsidPermission()
                else toast(tr("android_did_not_provide_a_wi_fi"))
            })
        }
        val dialog = AlertDialog.Builder(this).setTitle(tr("add_a_wi_fi_rule"))
            .setView(container).setNegativeButton(tr("cancel"), null).setPositiveButton(tr("choose_a_mode"), null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val ssid = input.text.toString()
            if (ssid.isEmpty()) input.error = tr("enter_a_wi_fi_name")
            else {
                dialog.dismiss()
                editWifiRule(ssid)
            }
        }
    }

    private fun requestSsidPermission() {
        if (!hasSsidPermission()) {
            val permissions = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(permissions, 3)
        } else if (!canReadWifiName()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else toast(tr("wi_fi_name_access_granted"))
    }

    private fun addSetting(card: LinearLayout, surface: Surface, title: String, description: String) {
        card.addView(Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(ink)
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            isChecked = surface.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                surface.set(this@MainActivity, enabled)
                if (enabled && surface == Surface.NOTIFICATION) askNotificationPermission()
                if (enabled && (surface == Surface.LIVE_UPDATE || surface == Surface.AUTO_CONNECT)) {
                    withBluetooth(true) { askNotificationPermission() }
                }
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
        if (Surface.CAPSULE.enabled(this) && !Settings.canDrawOverlays(this)) notes += tr("allow_the_floating_controls_to_appear_over")
        if (LiveUpdates.supported && HeadsetConnection.enabled(this) && !LiveUpdates.canPostPromotedNotifications(this)) {
            notes += tr("allow_uniear_live_updates_in_system_settings")
        }
        if (HeadsetConnection.enabled(this) && !HeadsetConnection.hasBluetoothPermission(this)) {
            notes += tr("connection_updates_require_nearby_devices_access")
        }
        if ((Surface.NOTIFICATION.enabled(this) || HeadsetConnection.enabled(this)) &&
            getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == false) {
            notes += tr("allow_uniear_notifications_in_system_settings")
        }
        settingsStatus.text = notes.joinToString("\n")
        settingsStatus.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun selectCompanionHeadset() = withBluetooth(true) {
        if (ControlState.busy) {
            toast(tr("disconnect_the_uniear_control_session_and_keep"))
            return@withBluetooth
        }
        queryHeadsets { connected ->
            val peers = connected.filter(HeadsetConnection::canControl)
            if (peers.isEmpty()) toast(tr("connect_supported_headphones_in_system_bluetooth_settings"))
            else AlertDialog.Builder(this).setTitle(tr("select_headphones_to_associate"))
                .setItems(peers.map { it.identity.deviceName ?: it.address }.toTypedArray()) { _, index ->
                    runCatching { CompanionHeadsets.associate(this, peers[index], ::toast) }
                        .onFailure { toast(tr("could_not_associate_these_headphones")) }
                }.setNegativeButton(tr("cancel"), null).show()
        }
    }

    private fun overlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: android.content.ActivityNotFoundException) {
            toast(tr("find_overlay_permissions_in_system_settings"))
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            toast(tr("open_quick_settings_edit_mode_and_add"))
            return
        }
        val manager = getSystemService(StatusBarManager::class.java) ?: return
        manager.requestAddTileService(ComponentName(this, EarbudTile::class.java), tr("headphone_modes"),
            Icon.createWithResource(this, R.drawable.ic_headphones), mainExecutor) { result ->
            if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                toast(tr("you_can_also_add_the_headphone_modes"))
            }
        }
    }

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported ||
            !manager.requestPinAppWidget(ComponentName(this, EarbudWidget::class.java), null, null)) {
            toast(tr("long_press_the_home_screen_and_add"))
        }
    }

    private fun discover() = queryHeadsets { peers ->
        status.text = if (peers.isEmpty()) tr("no_connected_headphones_found_connect_them_in")
            else tr("connected_headphones_found_0", peers.size)
    }

    private fun queryHeadsets(onResult: (List<Headset>) -> Unit) {
        if (scanning || ControlState.busy) return
        scanning = true
        buttons()
        status.text = tr("finding_connected_bluetooth_headphones")
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
                    status.text = it.message ?: tr("could_not_read_bluetooth_connection_status")
                }
                buttons()
            }
        }
    }

    private fun connect() {
        val selected = devices.selectedItem as? Headset ?: return
        queryHeadsets { peers ->
            val current = peers.firstOrNull { it.address == selected.address }
            if (current == null) status.text = tr("these_headphones_disconnected_reconnect_them_in_system")
            else if (hasWindowFocus()) {
                getPreferences(MODE_PRIVATE).edit().putString("last_address", current.address).apply()
                ControlState.connect(this, current)
            } else status.text = tr("headphones_found_return_to_devices_and_tap")
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
        model.text = view.model ?: tr("select_headphones")
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
                addView(text(tr("model_specific_controls"), 19, bold = true))
                for (group in view.controls) {
                    addView(text(group.title, 15, bold = true).apply { setPadding(0, dp(12), 0, 0) })
                    group.hint?.let { addView(text(it, 13, muted)) }
                    if (group.choices.size > 6) {
                        val selected = group.choices.indexOfFirst { it.selected }
                        val summary = group.choices.getOrNull(selected)?.label ?: tr("choose_a_setting")
                        addView(action(summary) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(group.title)
                                .setSingleChoiceItems(group.choices.map { it.label }.toTypedArray(), selected) { dialog, which ->
                                    ControlState.command(group.choices[which].request)
                                    dialog.dismiss()
                                }
                                .setNegativeButton(tr("cancel"), null)
                                .show()
                        })
                    } else {
                        group.choices.chunked(2).forEach { choices ->
                            addView(row(*choices.map { choice ->
                                action(choice.label, choice.selected) { ControlState.command(choice.request) }
                            }.toTypedArray()))
                        }
                    }
                }
            })
        }
        if (view.controlApps != lastApps) {
            lastApps = view.controlApps
            apps.removeAllViews()
            if (view.controlApps.isNotEmpty()) apps.addView(card().apply {
                addView(text(tr("vendor_apps"), 18, bold = true))
                for (app in view.controlApps) addView(action(tr("open_0", app.displayName)) {
                    val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launch == null) toast(tr("install_0_first", app.displayName)) else {
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
        connect.text = if (busy) tr("disconnect") else tr("connect")
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
            else status.text = tr("bluetooth_access_is_needed_to_find_and")
        }
        if (code == 2) {
            ControlState.refreshSurfaces(this)
            updateSettingsStatus()
        }
        if (code == 3) {
            ControlState.service?.updateAutomation()
            renderNetworkRules()
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card() = column().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
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
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            text = label
            isAllCaps = false
            minimumHeight = dp(48)
            minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
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

