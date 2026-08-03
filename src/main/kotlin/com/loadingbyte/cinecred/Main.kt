@file:JvmName("Main")

package com.loadingbyte.cinecred

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatIconColors
import com.formdev.flatlaf.FlatSystemProperties
import com.formdev.flatlaf.util.HSLColor
import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.DeckLink
import com.loadingbyte.cinecred.ui.Report
import com.loadingbyte.cinecred.ui.UIFactory
import com.loadingbyte.cinecred.ui.UI_LOCALE_PREFERENCE
import com.loadingbyte.cinecred.ui.comms.MasterCtrlComms
import com.loadingbyte.cinecred.ui.comms.WelcomeTab
import com.loadingbyte.cinecred.ui.helper.*
import com.oracle.si.Singleton
import net.miginfocom.layout.PlatformDefaults
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.slf4j.LoggerFactory
import sun.misc.Signal
import java.awt.*
import java.awt.event.MouseEvent
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.*
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.*
import java.util.logging.Formatter
import javax.swing.*
import kotlin.concurrent.schedule
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString


private const val SINGLETON_APP_ID = "com.loadingbyte.cinecred"

var demoCallback: (() -> Unit)? = null

private lateinit var masterCtrl: MasterCtrlComms
private val didSetupNatives = AtomicBoolean()
private val hasCrashed = AtomicBoolean()


fun main(args: Array<String>) {
    // Cinecred is a singleton application. When the application is launched a second time, we just simulate
    // a second application instance in the same VM.
    if (Singleton.invoke(SINGLETON_APP_ID, args))
        return
    Singleton.start({ otherArgs -> SwingUtilities.invokeLater { openUI(otherArgs) } }, SINGLETON_APP_ID)

    // If an unexpected exception reaches the top of a thread's stack, we want to terminate the program in a
    // controlled fashion and inform the user. We also ask whether to send a crash report.
    Thread.setDefaultUncaughtExceptionHandler(UncaughtHandler)

    // Remove all existing handlers from the root logger.
    val rootLogger = Logger.getLogger("")
    for (handler in rootLogger.handlers)
        rootLogger.removeHandler(handler)
    // Add new logging handlers.
    rootLogger.addHandler(ConsoleHandler().apply { formatter = JULFormatter })
    rootLogger.addHandler(JULBuilderHandler)

    // Set up the native libraries.
    setupNatives()

    // Make PDFBox store its font cache in our config directory.
    System.setProperty("pdfbox.fontcache", CONFIG_DIR.absolutePathString())

    // Already load the currently connected DeckLink devices so that they can be later passed to clients all in one go.
    // This is important because one client preselects the last selected device from the first device list it gets.
    DeckLink.preload()

    // Regularly suggest to run the GC. Without this, the GC usually only runs when there's memory pressure, but as our
    // configured maximum heap size is pretty large, there is rarely pressure. Thus, a lot of garbage lingers around on
    // the heap and fills up the user's precious RAM.
    Timer("GCCaller", true).schedule(0, 60_000) { System.gc() }

    SwingUtilities.invokeLater { mainSwing(args) }
}


fun setupNatives() {
    if (didSetupNatives.getAndSet(true))
        return

    // Make FlatLaf load its native library from java.library.path.
    // We must st this property before the first usage of SystemInfo, as that sneakily loads the library on Windows 10.
    System.setProperty(FlatSystemProperties.NATIVE_LIBRARY_PATH, "system")

    // On Linux, set glibc's M_MMAP_THRESHOLD to a fixed value. This is the threshold above which malloc() calls are
    // directly forwarded to mmap(), meaning that when the memory is freed again, it's immediately returned to the OS.
    // We desire this behavior for all our bigger allocations (e.g., bitmaps), since if they don't go via mmap(), they
    // often linger around forever in malloc()'s memory pool after deallocation and are never returned to the OS.
    // By default, glibc dynamically adjusts the M_MMAP_THRESHOLD, but for our usage profile, that adjustment leads to
    // few allocations going via mmap(). As a fix, we set the threshold to a fixed value.
    // Notice that on Windows and macOS, we never observed the above problematic behavior, so no fix is needed for them.
    if (SystemInfo.isLinux)
        Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("mallopt").get(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)
        )(-3 /* M_MMAP_THRESHOLD */, 64 * 1024)

    // Load our native libraries.
    System.loadLibrary("clib")
    System.loadLibrary("skia")
    System.loadLibrary("skiacapi")
    System.loadLibrary("harfbuzz")
    System.loadLibrary("clipper")
    System.loadLibrary("zimg")
    if (SystemInfo.isLinux)
        System.loadLibrary("nfd")
    System.loadLibrary("decklinkcapi")

    // Make JavaCPP load its native libraries from java.library.path.
    System.setProperty("org.bytedeco.javacpp.cacheLibraries", "false")
    System.setProperty("org.bytedeco.javacpp.findLibraries", "false")
    // Redirect JavaCPP's logging output to slf4j.
    System.setProperty("org.bytedeco.javacpp.logger", "slf4j")
    // Load the FFmpeg libs that we require.
    Loader.load(avutil::class.java)
    Loader.load(avcodec::class.java)
    Loader.load(avformat::class.java)
    Loader.load(swscale::class.java)
    avcodec.av_jni_set_java_vm(Loader.getJavaVM(), null)
    // Redirect FFmpeg's logging output to slf4j.
    avutil.setLogCallback(FFmpegLogCallback)
}


private fun mainSwing(args: Array<String>) {
    // On Linux, the WM_CLASS property is set to the main class name by default. This leads to the main class name being
    // displayed as the application name on, e.g., the Gnome Desktop. We fix this by setting WM_CLASS to the app name.
    // Notice that we could also set it to "cinecred" (in lower case) as Gnome would then find the matching
    // cinecred.desktop file and extract the app name from there, but directly setting the app name seems more portable.
    trySetAWTAppClassNameLinux("Cinecred")

    // Tooltips should not disappear on their own after some time.
    // To achieve this, we set the dismiss delay to one hour.
    ToolTipManager.sharedInstance().dismissDelay = 60 * 60 * 1000

    // MigLayout's platform-specific gaps etc. mess up our rather intricate layouts.
    // To alleviate this, we force one invariant set of platform defaults.
    // We chose the Gnome defaults because they waste the least space on gaps.
    // At the same time, we retain the platform-specific button order.
    val nativeButtonOrder = PlatformDefaults.getButtonOrder()
    PlatformDefaults.setPlatform(PlatformDefaults.GNOME)
    PlatformDefaults.setButtonOrder(nativeButtonOrder)

    // Set the Swing Look & Feel.
    FlatDarkLaf.setup()
    // Activate custom window decorations also on Linux. On Windows, they're the default anyway.
    if (SystemInfo.isLinux) {
        JFrame.setDefaultLookAndFeelDecorated(true)
        JDialog.setDefaultLookAndFeelDecorated(true)
    }
    // If text antialiasing is not enabled by the OS (which may choose a specific variant like subpixel rendering)
    // or we cannot detect it, enable its most generic variant now.
    val h = UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING)
    if (h == null || h == RenderingHints.VALUE_TEXT_ANTIALIAS_OFF || h == RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT)
        UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    // If FlatLaf cannot find a font on KDE, that's because no one has been explicitly configured. First try to find
    // the default font via Gnome mechanisms since some distros expose the font that way as well. If even that fails,
    // try to use the hardcoded default KDE font, which is Noto Sans, or just Dialog if even that doesn't exist.
    if (SystemInfo.isKDE) {
        val defaultFont = UIManager.getFont("defaultFont")
        if (defaultFont.getFamily(Locale.ROOT).equals(Font.SANS_SERIF, ignoreCase = true)) {
            var replFont = resolveGnomeFont()
            if (replFont.getFamily(Locale.ROOT).equals(Font.SANS_SERIF, ignoreCase = true))
                replFont = Font("Noto Sans", Font.PLAIN, 1)  // Falls back to Dialog if Noto Sans is not found.
            UIManager.put("defaultFont", replFont.deriveFont(defaultFont.size2D))
        }
    }
    // On Linux, FlatLaf often fractionally scales the fonts a bit too large compared to what the OS does. Additionally,
    // this often results in weird looking fonts. As a quick fix, just remove the fractional part from the font size.
    if (SystemInfo.isLinux) {
        val defaultFont = UIManager.getFont("defaultFont")
        UIManager.put("defaultFont", defaultFont.deriveFont(defaultFont.size.toFloat()))
    }
    // Apart from yellow and green, we use IntelliJ's new icon colors, and we need to tell FlatLaf about them.
    UIManager.put(FlatIconColors.ACTIONS_RED.key, PALETTE_RED_COLOR)
    UIManager.put(FlatIconColors.ACTIONS_YELLOW.key, PALETTE_YELLOW_COLOR)
    UIManager.put(FlatIconColors.ACTIONS_GREEN.key, PALETTE_GREEN_COLOR)
    UIManager.put(FlatIconColors.ACTIONS_BLUE.key, PALETTE_BLUE_COLOR)
    UIManager.put(FlatIconColors.ACTIONS_GREY.key, PALETTE_GRAY_COLOR)
    // Enable alternated coloring of table rows.
    UIManager.put("Table.alternateRowColor", HSLColor(UIManager.getColor("Table.background")).adjustTone(10f))
    // Add maximization buttons to plain dialog windows.
    fixTitlePane()
    // Fix the slightly offset vertical centering of text in text fields.
    fixTextFieldVerticalCentering()
    // Fix the inability to get a dock progress bar to appear on macOS.
    fixTaskbarProgressBarOnMacOS()

    // Run the demo code if configured, and then abort the regular startup.
    demoCallback?.let { it(); return }

    masterCtrl = UIFactory().master()

    // Globally listen to all key and mouse events.
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(masterCtrl::preGlobalKeyEvent)
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor(masterCtrl::postGlobalKeyEvent)
    Toolkit.getDefaultToolkit()
        .addAWTEventListener({ e -> masterCtrl.globalMouseEvent(e as MouseEvent) }, AWTEvent.MOUSE_EVENT_MASK)

    // On macOS, allow the user to open the about and preferences tabs via the OS.
    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT))
        Desktop.getDesktop().setAboutHandler { masterCtrl.showWelcomeFrame(tab = WelcomeTab.ABOUT) }
    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_PREFERENCES))
        Desktop.getDesktop().setPreferencesHandler { masterCtrl.showWelcomeFrame(tab = WelcomeTab.PREFERENCES) }

    // On Windows and macOS, don't suddenly terminate the application when the user logs off (or quits the application
    // using the system-provided menu on macOS), but instead try to close all windows, which in turn triggers all
    // "unsaved changes" dialogs.
    if (SystemInfo.isWindows)
        Signal.handle(Signal("TERM")) { masterCtrl.tryCloseProjectsAndDisposeAllFrames() }
    else if (Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
        Desktop.getDesktop().setQuitHandler { _, response ->
            if (masterCtrl.tryCloseProjectsAndDisposeAllFrames())
                response.performQuit()
            else
                response.cancelQuit()
        }

    // Apply the locale configured by the user. If it changes in the future, re-apply it.
    // Note that we need to call the macOS menu localizer after having set the preference handler, otherwise the
    // "Preferences" menu item is not available yet and hence not localized.
    comprehensivelyApplyLocale(UI_LOCALE_PREFERENCE.get().locale)
    MacOSMenuLocalizer.localize()
    UI_LOCALE_PREFERENCE.addListener { wish ->
        comprehensivelyApplyLocale(wish.locale)
        MacOSMenuLocalizer.localize()
    }

    // Finally open the UI.
    openUI(args)
}


private fun openUI(args: Array<String>) {
    // If the program is launched for a second time while the firstly-launched process is still initializing itself,
    // ignore the second launch, as the program would otherwise crash.
    if (!::masterCtrl.isInitialized)
        return
    // If the program has crashed and the crash dialog is still open, launching another instance (which results in a
    // call to this method) should no longer open the welcome window.
    if (hasCrashed.get())
        return
    // If the user dragged a folder onto the program, try opening that, otherwise show the regular welcome window.
    val openProjectDir = if (args.isEmpty()) null else args[0].toPathSafely()?.absolute()
    masterCtrl.showWelcomeFrame(openProjectDir)
}


fun getLog(): String =
    JULBuilderHandler.log.toString()


private object UncaughtHandler : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        LOGGER.error("Uncaught exception. Will terminate the program.", e)
        if (hasCrashed.getAndSet(true))
            return
        val report = Report()
        SwingUtilities.invokeLater {
            report.showCrashDialog()
            // Once all frames have been disposed, no more non-daemon threads are running and hence Java will terminate.
            if (::masterCtrl.isInitialized)
                masterCtrl.tryCloseProjectsAndDisposeAllFrames(force = true)
        }
    }
}


private object JULFormatter : Formatter() {
    private val startMillis = System.currentTimeMillis()
    override fun format(record: LogRecord): String {
        val millis = record.millis - startMillis
        val threadName = getThreadByID(record.longThreadID)?.name ?: "???"
        val exc = record.thrown?.let(::formatThrowable) ?: ""
        val msg = formatMessage(record)
        return "$millis [$threadName] ${record.level} ${record.loggerName} - $msg\n$exc"
    }

    private fun getThreadByID(threadID: Long): Thread? {
        // Find the root thread group.
        var rootGroup = Thread.currentThread().threadGroup ?: return null
        while (true)
            rootGroup = rootGroup.parent ?: break
        // Enumerate all threads.
        var allThreads = arrayOfNulls<Thread>(rootGroup.activeCount() + 1)
        while (rootGroup.enumerate(allThreads) == allThreads.size)
            allThreads = arrayOfNulls(allThreads.size * 2)
        // Find the thread we are looking for.
        return allThreads.find { it != null && it.threadId() == threadID }
    }

    private fun formatThrowable(t: Throwable): String {
        val stacktrace = t.stackTraceToString()
        var t = t
        while (true) t = t.cause ?: break
        val inner = when (t) {
            is ch.rabanti.nanoxlsx4j.exceptions.FormatException -> t.innerException
            is ch.rabanti.nanoxlsx4j.exceptions.IOException -> t.innerException
            else -> return stacktrace
        }
        return "${stacktrace}Inner exception: ${formatThrowable(inner)}"
    }
}


private object JULBuilderHandler : Handler() {
    init {
        level = Level.INFO
        formatter = JULFormatter
    }

    // Use StringBuffer for thread-safety.
    val log = StringBuffer()

    override fun publish(record: LogRecord) {
        if (isLoggable(record))
            try {
                log.append(formatter.format(record))
            } catch (e: Exception) {
                reportError(null, e, ErrorManager.FORMAT_FAILURE)
            }
    }

    override fun flush() {}
    override fun close() {}
}


private object FFmpegLogCallback : LogCallback() {
    private val logger = LoggerFactory.getLogger("FFmpeg")
    override fun call(level: Int, msg: BytePointer) {
        // FFmpeg's log messages end with a newline character, which we have to remove.
        val message = msg.string.trim()
        when (level) {
            avutil.AV_LOG_PANIC, avutil.AV_LOG_FATAL, avutil.AV_LOG_ERROR -> logger.error(message)
            avutil.AV_LOG_WARNING -> logger.warn(message)
            avutil.AV_LOG_INFO -> logger.info(message)
            avutil.AV_LOG_VERBOSE, avutil.AV_LOG_DEBUG -> logger.debug(message)
            avutil.AV_LOG_TRACE -> logger.trace(message)
        }
    }
}
