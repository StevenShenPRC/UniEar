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
    private val scan = JButton(tr("scan"))
    private val connect = JButton(tr("connect"))
    private val disconnect = JButton(tr("disconnect"))
    private val refresh = JButton(tr("refresh"))
    private val model = JLabel(tr("select_headphones"))
    private val battery = JLabel(tr("battery_level_unknown"))
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
            render(HeadsetView(tr("disconnected")))
        }
        refresh.addActionListener { session?.refresh() }
        render(HeadsetView(tr("scan_for_paired_headphones_and_nearby_bluetooth")))
        frame.minimumSize = Dimension(650, 420)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun scan() {
        scanning = true
        updateButtons()
        status.text = tr("scanning_for_bluetooth_devices")
        object : SwingWorker<List<Headset>, Void>() {
            override fun doInBackground() = radio.discover()
            override fun done() {
                try {
                    val peers = get()
                    devices.model = DefaultComboBoxModel(peers.toTypedArray())
                    status.text = if (peers.isEmpty()) tr("no_devices_found_turn_on_bluetooth_and")
                    else tr("devices_found_0", peers.size)
                } catch (error: Exception) {
                    status.text = error.cause?.message ?: error.message ?: tr("scan_failed")
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
        render(HeadsetView(tr("identifying_headphones")))
    }

    private fun render(view: HeadsetView) {
        if (view.ended) {
            generation++
            session?.close()
            session = null
        }
        model.text = view.model ?: tr("select_headphones")
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
                if (group.choices.size > 6) {
                    val selection = JComboBox(group.choices.map { it.label }.toTypedArray())
                    selection.selectedIndex = group.choices.indexOfFirst { it.selected }
                    choices.add(selection)
                    choices.add(JButton(tr("apply")).apply {
                        isEnabled = selection.selectedIndex >= 0
                        selection.addActionListener { isEnabled = selection.selectedIndex >= 0 }
                        addActionListener {
                            group.choices.getOrNull(selection.selectedIndex)?.let { session?.control(it.request) }
                        }
                    })
                } else {
                    for (choice in group.choices) {
                        choices.add(JButton(if (choice.selected) "✓ ${choice.label}" else choice.label).apply {
                            addActionListener { session?.control(choice.request) }
                        })
                    }
                }
                group.hint?.let { choices.add(JLabel(it)) }
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
