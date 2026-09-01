package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS
import com.formdev.flatlaf.util.SystemInfo
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.getLog
import com.loadingbyte.cinecred.ui.helper.EMAIL_ICON
import com.loadingbyte.cinecred.ui.helper.tryMail
import com.loadingbyte.cinecred.ui.helper.usableBounds
import de.siegmar.fastcsv.reader.CsvReader
import de.siegmar.fastcsv.reader.StringArrayHandler
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.URLEncoder
import java.util.*
import javax.swing.*


class Report {

    private val header: String

    init {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage

        var rss: Long? = null
        val pid = ProcessHandle.current().pid()
        if (SystemInfo.isWindows) {
            val process = ProcessBuilder(listOf("tasklist", "/fo", "csv", "/nh", "/fi", "pid eq $pid")).start()
            if (process.waitFor() == 0)
                rss = CsvReader.builder().build(StringArrayHandler.of(), process.inputReader())
                    .use { it.firstOrNull()?.getOrNull(4) }
                    ?.removeSuffix(" K")?.replace(".", "")?.replace(",", "")?.toLongOrNull()?.times(1024)
        } else {
            val process = ProcessBuilder(listOf("ps", "-o", "rss=", "-p", pid.toString())).start()
            if (process.waitFor() == 0)
                rss = process.inputReader().use { it.readAllAsString() }.trim().toLongOrNull()?.times(1024)
        }

        header = """---- SYSTEM INFO ----
Cinecred: $VERSION
JVM: ${System.getProperty("java.vm.vendor")} ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}
OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")} ${System.getProperty("os.version")}
Cores: ${ManagementFactory.getOperatingSystemMXBean().availableProcessors}
RSS: ${if (rss == null) "?" else mb(rss)} MB
Heap: Used ${mb(heap.used)} MB, Committed ${mb(heap.committed)} MB, Max ${mb(heap.max)} MB
Disposable: Used ${mb(disposableBytes())} MB, Max ${mb(maxDisposableBytes())} MB
Locale: ${Locale.getDefault().toLanguageTag()}

---- LOG ----
"""
    }

    private val log = getLog()

    fun send(type: Type) {
        val (address, subject) = when (type) {
            Type.BUG -> Pair("bugs@cinecred.com", "Cinecred Bug Report")
            Type.CRASH -> Pair("crashes@cinecred.com", "Cinecred Crash Report")
        }
        // We replace tabs by four dots because some email programs trim leading tabs and spaces.
        val body = "[${l10n("ui.report.email")}]\n\n\n$header${log.replace("\t", "....")}"
        tryMail(URI("mailto:${encodeForMail(address)}?Subject=${encodeForMail(subject)}&Body=${encodeForMail(body)}"))
    }

    private fun encodeForMail(str: String): String =
        URLEncoder.encode(str, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    /** Should be called from within the AWT thread! */
    fun showCrashDialog() {
        val win = FocusManager.getCurrentKeyboardFocusManager().activeWindow
        // We can't use our own version of getSystemScaleFactor(), as that is defined in Common.kt, which loads a lot of
        // other classes if it wasn't yet initialized. That loading messes with our startup sequence and crashes the
        // program again before we can even open this error window.
        val s = UIScale.getSystemScaleFactor(
            win?.graphicsConfiguration
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        )
        val logComp = JTextArea(header + log).apply {
            isEditable = false
            putClientProperty(STYLE_CLASS, "monospaced")
        }
        val msgComp = JPanel(MigLayout("insets 0, wrap", "[::${50.0 * s}sp]", "[][]unrel[][]")).apply {
            add(JLabel(l10n("ui.crash.msg.error")))
            add(JScrollPane(logComp), "hmax ${40.0 * s}sp")
            add(JLabel(l10n("ui.crash.msg.exit")))
            add(JLabel(l10n("ui.crash.msg.report")))
        }
        val iconKey = "OptionPane.yesIcon"
        val prevIcon = UIManager.getIcon(iconKey)
        UIManager.put(iconKey, EMAIL_ICON)
        val send = JOptionPane.showConfirmDialog(
            win, msgComp, l10n("ui.crash.title"), JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE
        ) == JOptionPane.YES_OPTION
        UIManager.put(iconKey, prevIcon)
        if (send)
            send(Type.CRASH)
    }


    companion object {

        fun showLogDialog() {
            val win = FocusManager.getCurrentKeyboardFocusManager().activeWindow
            val gCfg = win?.graphicsConfiguration
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val screenBounds = gCfg.usableBounds
            val log = getLog()
            val msgComp = JScrollPane(JTextArea(log).apply {
                isEditable = false
                putClientProperty(STYLE_CLASS, "monospaced")
            })
            msgComp.preferredSize = Dimension(screenBounds.width / 2, screenBounds.height / 2)
            JOptionPane.showMessageDialog(win, msgComp, "Log", JOptionPane.INFORMATION_MESSAGE)
        }

        private fun mb(bytes: Long) = roundingDiv(bytes, 1024 * 1024)

    }


    enum class Type { BUG, CRASH }

}
