package uniear.desktop

import dev.hyperears.integration.NoiseMode
import uniear.core.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*

fun main() = SwingUtilities.invokeLater {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    DesktopApp().show()
}

private class DesktopApp {
    private val radio = DesktopRadio()
    private val frame = JFrame("UniEar")
    private val devices = JComboBox<Headset>()
    private val scan = JButton("扫描")
    private val connect = JButton("连接")
    private val disconnect = JButton("断开")
    private val refresh = JButton("刷新")
    private val model = JLabel("选择一台耳机")
    private val battery = JLabel("电量未知")
    private val modeButtons = NoiseMode.entries.associateWith { JButton(it.label()) }
    private val features = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private var shownControls: List<ControlGroup> = emptyList()
    private val status = JTextArea(3, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = Color(245, 247, 249)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }
    private var session: HeadsetSession? = null
    private var generation = 0
    private var scanning = false

    fun show() {
        val content = JPanel(BorderLayout(12, 14)).apply {
            border = BorderFactory.createEmptyBorder(20, 22, 20, 22)
        }
        val header = JPanel(BorderLayout(8, 10))
        header.add(JLabel("UniEar").apply { font = font.deriveFont(Font.BOLD, 27f) }, BorderLayout.NORTH)
        header.add(JPanel(BorderLayout(8, 0)).apply {
            add(devices, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply {
                add(scan)
                add(connect)
                add(disconnect)
            }, BorderLayout.EAST)
        }, BorderLayout.CENTER)
        content.add(header, BorderLayout.NORTH)
        val controls = JPanel(GridLayout(0, 1, 0, 10)).apply {
            add(model.apply { font = font.deriveFont(Font.BOLD, 18f) })
            add(battery)
            add(JPanel(FlowLayout(FlowLayout.LEADING, 7, 0)).apply {
                NoiseMode.entries.forEach { mode ->
                    add(modeButtons.getValue(mode).apply { addActionListener { session?.setNoiseMode(mode) } })
                }
                add(refresh)
            })
        }
        content.add(JPanel(BorderLayout(0, 12)).apply {
            add(controls, BorderLayout.NORTH)
            add(JScrollPane(features).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        content.add(JScrollPane(status), BorderLayout.SOUTH)
        frame.contentPane = content
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) { session?.close() }
        })
        devices.addActionListener { updateButtons() }
        scan.addActionListener { scan() }
        connect.addActionListener { connect() }
        disconnect.addActionListener {
            generation++
            session?.close()
            session = null
            render(HeadsetView("已断开"))
        }
        refresh.addActionListener { session?.refresh() }
        render(HeadsetView("扫描已配对耳机和附近的蓝牙设备。"))
        frame.minimumSize = Dimension(650, 420)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun scan() {
        scanning = true
        updateButtons()
        status.text = "正在扫描蓝牙设备…"
        object : SwingWorker<List<Headset>, Void>() {
            override fun doInBackground() = radio.discover()
            override fun done() {
                try {
                    val peers = get()
                    devices.model = DefaultComboBoxModel(peers.toTypedArray())
                    status.text = if (peers.isEmpty()) "未发现设备。请开启蓝牙并确认耳机可连接。"
                    else "找到 ${peers.size} 台设备。"
                } catch (error: Exception) {
                    status.text = error.cause?.message ?: error.message ?: "扫描失败"
                } finally {
                    scanning = false
                    updateButtons()
                }
            }
        }.execute()
    }

    private fun connect() {
        val selected = devices.selectedItem as? Headset ?: return
        session?.close()
        val active = ++generation
        session = HeadsetSession(radio, selected) { view ->
            SwingUtilities.invokeLater { if (active == generation && frame.isDisplayable) render(view) }
        }
        render(HeadsetView("正在识别耳机…"))
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
        for ((mode, button) in modeButtons) {
            button.isEnabled = mode in view.modes
            button.text = if (mode == view.mode) "✓ ${mode.label()}" else mode.label()
        }
        refresh.isEnabled = view.ready
        if (shownControls != view.controls) {
            shownControls = view.controls
            features.removeAll()
            for (group in shownControls) {
                val choices = JPanel(FlowLayout(FlowLayout.LEADING))
                choices.border = BorderFactory.createTitledBorder(group.title)
                group.hint?.let { choices.toolTipText = it }
                for (choice in group.choices) {
                    choices.add(JButton(if (choice.selected) "✓ ${choice.label}" else choice.label).apply {
                        addActionListener { session?.control(choice.request) }
                    })
                }
                choices.maximumSize = Dimension(Int.MAX_VALUE, choices.preferredSize.height)
                features.add(choices)
            }
            features.revalidate()
            features.repaint()
        }
        updateButtons()
    }

    private fun updateButtons() {
        disconnect.isEnabled = session != null
        connect.isEnabled = session == null && !scanning && devices.selectedItem != null
        devices.isEnabled = session == null && !scanning
        scan.isEnabled = session == null && !scanning
    }
}
