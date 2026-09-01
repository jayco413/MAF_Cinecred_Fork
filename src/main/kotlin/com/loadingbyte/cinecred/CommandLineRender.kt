@file:JvmName("CommandLineRender")

package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.walkSafely
import com.loadingbyte.cinecred.delivery.RenderFormat
import com.loadingbyte.cinecred.delivery.RenderFormat.Config
import com.loadingbyte.cinecred.delivery.RenderFormat.GenericProfile
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.GENERIC_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRIMARIES
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SPATIAL_SCALING_LOG2
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSFER
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSPARENCY
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.YUV
import com.loadingbyte.cinecred.delivery.RenderFormat.Sliders
import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnCreditsBook
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.drawer.drawVideo
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.Font
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.ContentStyle
import com.loadingbyte.cinecred.project.Credits
import com.loadingbyte.cinecred.project.CreditsBook
import com.loadingbyte.cinecred.project.FontRef
import com.loadingbyte.cinecred.project.LetterStyle
import com.loadingbyte.cinecred.project.ListedStyle
import com.loadingbyte.cinecred.project.PageStyle
import com.loadingbyte.cinecred.project.PictureRef
import com.loadingbyte.cinecred.project.PictureStyle
import com.loadingbyte.cinecred.project.PopupStyle
import com.loadingbyte.cinecred.project.presetPageStyle
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.project.Style
import com.loadingbyte.cinecred.project.Styling
import com.loadingbyte.cinecred.project.TapeRef
import com.loadingbyte.cinecred.project.TapeStyle
import com.loadingbyte.cinecred.project.TransitionStyle
import com.loadingbyte.cinecred.project.copy
import com.loadingbyte.cinecred.project.findUsedStyles
import com.loadingbyte.cinecred.project.st
import com.loadingbyte.cinecred.project.verifyConstraints
import com.loadingbyte.cinecred.projectio.MigrationDataSource
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.projectio.ProjectIntake
import com.loadingbyte.cinecred.projectio.SPREADSHEET_FORMATS
import com.loadingbyte.cinecred.projectio.STYLING_FILE_NAME
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.readCredits
import com.loadingbyte.cinecred.projectio.readStyling
import com.loadingbyte.cinecred.projectio.service.SERVICE_LINK_EXTS
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.nio.file.Files
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.SortedMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString


private data class CliRenderOptions(
    val projectDir: Path,
    val spreadsheetName: String?,
    val firstPage: Int?,
    val lastPage: Int?,
    val format: String,
    val profile: String,
    val output: Path?,
    val overwrite: Boolean,
    val expectedSha256: String?
)

private data class LoadedProject(
    val creditsFileName: String,
    val creditsFileUri: URI,
    val fonts: SortedMap<String, Font>,
    val styling: Styling,
    val spreadsheets: List<Spreadsheet>,
    val pictureLoaders: SortedMap<String, Picture.Loader>,
    val tapes: SortedMap<String, Tape>
) : AutoCloseable {
    override fun close() {
        pictureLoaders.values.forEach(Picture.Loader::close)
        tapes.values.forEach(Tape::close)
    }
}

fun runCommandLineRender(args: Array<String>): Int {
    val options = try {
        parseCliRenderOptions(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        printCliUsage()
        return 2
    }

    return try {
        loadProjectForRender(options.projectDir).use { loaded ->
            val selectedSpreadsheet = selectSpreadsheet(loaded.spreadsheets, options.spreadsheetName)
            val drawnProject = drawProjectForRender(loaded, listOf(selectedSpreadsheet))
            val drawnCredits = drawnProject.drawnCreditsBooks.single().drawnCredits.single()
            val pageIndices = selectPageIndices(drawnCredits, options.firstPage, options.lastPage)
            val format = selectVideoFormat(options.format)
            val config = buildVideoConfig(format, options.profile)
            val output = options.output ?: defaultOutputPath(options.projectDir, selectedSpreadsheet, format)
            if (options.overwrite)
                output.deleteIfExists()
            val pageDefImages = drawnCredits.drawnPages.filterIndexed { idx, _ -> idx in pageIndices }
                .map { it.defImage }
            val video = drawnCredits.video.sub(
                if (0 in pageIndices) null else pageDefImages.first(),
                if (drawnCredits.drawnPages.lastIndex in pageIndices) null else pageDefImages.last()
            )
            val renderJob = format.createRenderJob(
                options.projectDir,
                config,
                Sliders(null, null),
                drawnProject.project.styling,
                null,
                video,
                output,
                null
            )

            var lastProgress = -1
            LOGGER.info("CLI render started: {}", output.pathString)
            renderJob.render { progress ->
                val percent = (progress * 100) / 10_000
                if (percent >= lastProgress + 5 || percent == 100) {
                    lastProgress = percent
                    LOGGER.info("CLI render progress: {}%", percent)
                }
            }
            val actualSha256 = sha256Hex(output)
            LOGGER.info("CLI render SHA-256: {}", actualSha256.uppercase(Locale.ROOT))
            options.expectedSha256?.let { expectedSha256 ->
                require(actualSha256 == expectedSha256) {
                    "Rendered output SHA-256 mismatch for '$output'. Expected " +
                            expectedSha256.uppercase(Locale.ROOT) +
                            " but got " + actualSha256.uppercase(Locale.ROOT) + "."
                }
            }
            LOGGER.info("CLI render finished: {}", output.pathString)
        }
        0
    } catch (e: Exception) {
        LOGGER.error("CLI render failed.", e)
        1
    }
}

private fun parseCliRenderOptions(args: Array<String>): CliRenderOptions {
    var projectDir: Path? = System.getenv("CINECRED_RENDER_PROJECT")?.let(Path::of)
    var spreadsheetName: String? = System.getenv("CINECRED_RENDER_SPREADSHEET")
    var firstPage: Int? = System.getenv("CINECRED_RENDER_FIRST_PAGE")?.toIntOrNull()
    var lastPage: Int? = System.getenv("CINECRED_RENDER_LAST_PAGE")?.toIntOrNull()
    var format = System.getenv("CINECRED_RENDER_FORMAT") ?: "h264"
    var profile = System.getenv("CINECRED_RENDER_PROFILE") ?: "high"
    var output: Path? = System.getenv("CINECRED_RENDER_OUTPUT")?.let(Path::of)
    var overwrite = System.getenv("CINECRED_RENDER_OVERWRITE")?.toBooleanStrictOrNull() ?: true
    var expectedSha256 = System.getenv("CINECRED_RENDER_EXPECT_SHA256")?.normalizeSha256()

    var idx = 0
    while (idx < args.size) {
        when (val arg = args[idx++]) {
            "--help", "-h" -> throw IllegalArgumentException("Usage requested.")
            "--project" -> projectDir = requirePathArg(arg, args, idx++)
            "--spreadsheet" -> spreadsheetName = requireStringArg(arg, args, idx++)
            "--first-page" -> firstPage = requireStringArg(arg, args, idx++).toInt()
            "--last-page" -> lastPage = requireStringArg(arg, args, idx++).toInt()
            "--format" -> format = requireStringArg(arg, args, idx++)
            "--profile" -> profile = requireStringArg(arg, args, idx++)
            "--output" -> output = requirePathArg(arg, args, idx++)
            "--expect-sha256" -> expectedSha256 = requireStringArg(arg, args, idx++).normalizeSha256()
            "--no-overwrite" -> overwrite = false
            else -> throw IllegalArgumentException("Unknown argument: $arg")
        }
    }

    return CliRenderOptions(
        projectDir = projectDir ?: throw IllegalArgumentException("Missing required argument: --project"),
        spreadsheetName = spreadsheetName,
        firstPage = firstPage,
        lastPage = lastPage,
        format = format,
        profile = profile,
        output = output,
        overwrite = overwrite,
        expectedSha256 = expectedSha256
    )
}

private fun requireStringArg(flag: String, args: Array<String>, idx: Int): String =
    args.getOrNull(idx) ?: throw IllegalArgumentException("Missing value for $flag")

private fun requirePathArg(flag: String, args: Array<String>, idx: Int): Path =
    Path.of(requireStringArg(flag, args, idx))

private fun String.normalizeSha256(): String {
    val normalized = trim().lowercase(Locale.ROOT)
    require(normalized.matches(Regex("[0-9a-f]{64}"))) { "Expected a 64-character SHA-256 hex string." }
    return normalized
}

private fun printCliUsage() {
    println(
        """
        Usage:
          render --project <project-dir> [options]

        Options:
          --spreadsheet <name>   Spreadsheet name inside Credits file. Default: first spreadsheet.
          --first-page <n>       1-based first page index. Default: 1.
          --last-page <n>        1-based last page index. Default: last page.
          --format <h264|h265>   Video format. Default: h264.
          --profile <medium|high|best>
          --output <file>        Output file path. Default: <project>/<project> <sheet>.mp4
          --expect-sha256 <hex>  Fail if the output SHA-256 differs from this hash.
          --no-overwrite         Refuse to overwrite an existing output file.

        Environment fallback:
          CINECRED_RENDER_PROJECT
          CINECRED_RENDER_SPREADSHEET
          CINECRED_RENDER_FIRST_PAGE
          CINECRED_RENDER_LAST_PAGE
          CINECRED_RENDER_FORMAT
          CINECRED_RENDER_PROFILE
          CINECRED_RENDER_OUTPUT
          CINECRED_RENDER_OVERWRITE
          CINECRED_RENDER_EXPECT_SHA256
        """.trimIndent()
    )
}

private fun loadProjectForRender(projectDir: Path): LoadedProject {
    require(projectDir.resolve(STYLING_FILE_NAME).exists()) { "Not a Cinecred project dir: $projectDir" }

    val fonts = sortedMapOf<String, Font>(String.CASE_INSENSITIVE_ORDER)
    val picturesByPath = linkedMapOf<Path, Picture.Loader>()
    val tapesByPath = linkedMapOf<Path, Tape>()
    for (fileOrDir in projectDir.walkSafely()) {
        readFonts(fileOrDir).forEach { font -> fonts.putIfAbsent(font.name, font) }
        Picture.Loader.recognize(fileOrDir)?.let { loader -> picturesByPath.putIfAbsent(fileOrDir, loader) }
        Tape.recognize(fileOrDir)?.let { tape -> tapesByPath.putIfAbsent(fileOrDir, tape) }
    }
    val pictureLoaders = sortedMapOf<String, Picture.Loader>(String.CASE_INSENSITIVE_ORDER)
    for (loader in picturesByPath.values)
        pictureLoaders.putIfAbsent(loader.file.name, loader)
    val tapes = sortedMapOf<String, Tape>(String.CASE_INSENSITIVE_ORDER)
    for (tape in tapesByPath.values)
        tapes.putIfAbsent(tape.fileOrDir.name, tape)

    val creditsFile = projectDir.walkSafely()
        .filter { it.isRegularFile() && ProjectIntake.hasCreditsFilename(it) }
        .minByOrNull { it.nameCount }
        ?: throw IllegalArgumentException("No credits file found in project dir: $projectDir")
    require(creditsFile.extension !in SERVICE_LINK_EXTS) { "CLI render does not support service-link credits files." }
    val format = SPREADSHEET_FORMATS.firstOrNull { it.fileExt.equals(creditsFile.extension, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unsupported credits file type: ${creditsFile.extension}")
    val spreadsheets = format.read(creditsFile, l10n("project.template.spreadsheetName"))

    val styling = readStyling(projectDir.resolve(STYLING_FILE_NAME), fonts, pictureLoaders, tapes)
    return LoadedProject(
        creditsFile.name, creditsFile.toUri(), fonts, styling, spreadsheets, pictureLoaders, tapes
    )
}

private fun selectSpreadsheet(spreadsheets: List<Spreadsheet>, requestedName: String?): Spreadsheet {
    require(spreadsheets.isNotEmpty()) { "Credits file does not contain any spreadsheets." }
    if (requestedName == null)
        return spreadsheets.first()
    return spreadsheets.find { it.name.equals(requestedName, ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "Spreadsheet '$requestedName' not found. Available: ${spreadsheets.joinToString { it.name }}"
        )
}

private fun drawProjectForRender(loaded: LoadedProject, spreadsheets: List<Spreadsheet>): DrawnProject {
    var styling = loaded.styling
    val pictureLoaders = loaded.pictureLoaders
    val tapes = loaded.tapes

    updateAuxiliaryReferences(
        styling.letterStyles,
        LetterStyle::font.st(),
        FontRef::name,
        FontRef::font,
        ::FontRef,
        ::FontRef
    ) { name -> loaded.fonts[name] ?: Font.bundled(name) ?: Font.system(name) }
        ?.let { styling = styling.copy(letterStyles = it) }
    updateAuxiliaryReferences(
        styling.pictureStyles,
        PictureStyle::picture.st(),
        PictureRef::name,
        PictureRef::loader,
        ::PictureRef,
        ::PictureRef,
        pictureLoaders::get
    )?.let { styling = styling.copy(pictureStyles = it) }
    updateAuxiliaryReferences(
        styling.tapeStyles,
        TapeStyle::tape.st(),
        TapeRef::name,
        TapeRef::tape,
        ::TapeRef,
        ::TapeRef,
        tapes::get
    )?.let { styling = styling.copy(tapeStyles = it) }

    for (style in styling.tapeStyles)
        style.tape.tape?.loadMetadataInBackground()

    require(verifyConstraints(styling).none { it.severity == ERROR }) { l10n("ui.edit.stylingError") }

    val credits = mutableListOf<Credits>()
    val log = mutableListOf<ParserMsg>()
    for (spreadsheet in spreadsheets) {
        val (curCredits, curLog, _) = readCredits(loaded.creditsFileName, spreadsheet, styling, pictureLoaders, tapes)
        credits += curCredits
        log += curLog
    }
    require(log.none { it.severity == ERROR }) { log.joinToString("\n") { it.msg } }

    val usedStyles = findUsedStyles(credits)
    addRemovePopupStyles(styling.pictureStyles, usedStyles)?.let { styling = styling.copy(pictureStyles = it) }
    addRemovePopupStyles(styling.tapeStyles, usedStyles)?.let { styling = styling.copy(tapeStyles = it) }
    clearLegacyPageStyleSettings(styling.pageStyles, log)?.let { styling = styling.copy(pageStyles = it) }

    for (style in usedStyles)
        when (style) {
            is PictureStyle -> style.picture.loader?.loadInBackground()
            is TapeStyle -> style.tape.tape?.loadMetadataInBackground()
            is PageStyle, is ContentStyle, is LetterStyle, is TransitionStyle -> {}
        }

    val creditsBook = CreditsBook(loaded.creditsFileName, loaded.creditsFileUri, credits.toPersistentList())
    val project = Project(styling, persistentListOf(creditsBook))
    val drawnCredits = credits.map { curCredits ->
        val (drawnPages, _) = drawPages(styling, curCredits)
        require(drawnPages.none { it.defImage.height.resolve() > 1_000_000.0 }) {
            "Excessive page size while drawing spreadsheet '${curCredits.spreadsheetName}'."
        }
        DrawnCredits(curCredits, drawnPages.toPersistentList(), drawVideo(styling, drawnPages))
    }
    return DrawnProject(
        project,
        persistentListOf(DrawnCreditsBook(creditsBook, drawnCredits.toPersistentList()))
    )
}

private fun selectPageIndices(drawnCredits: DrawnCredits, firstPage: Int?, lastPage: Int?): Set<Int> {
    val firstIdx = (firstPage ?: 1) - 1
    val lastIdx = (lastPage ?: drawnCredits.drawnPages.size) - 1
    require(firstIdx in drawnCredits.drawnPages.indices) { "First page out of range: ${firstPage ?: 1}" }
    require(lastIdx in drawnCredits.drawnPages.indices) { "Last page out of range: ${lastPage ?: drawnCredits.drawnPages.size}" }
    require(firstIdx <= lastIdx) { "First page must be <= last page." }
    return (firstIdx..lastIdx).toSet()
}

private fun selectVideoFormat(name: String): RenderFormat = when (name.lowercase(Locale.ROOT)) {
    "h264", "video-h264", "mp4" -> com.loadingbyte.cinecred.delivery.VideoContainerRenderJob.H264
    "h265", "video-h265", "hevc" -> com.loadingbyte.cinecred.delivery.VideoContainerRenderJob.H265
    else -> throw IllegalArgumentException("Unsupported CLI video format: $name")
}

private fun buildVideoConfig(format: RenderFormat, profileName: String): Config {
    val profile = when (profileName.lowercase(Locale.ROOT)) {
        "medium" -> GenericProfile.MEDIUM
        "high", "high-quality", "high_quality" -> GenericProfile.HIGH
        "best" -> GenericProfile.BEST
        else -> throw IllegalArgumentException("Unsupported CLI profile: $profileName")
    }
    return Config.Lookup().apply {
        this[GENERIC_PROFILE] = profile
        this[TRANSPARENCY] = RenderFormat.Transparency.GROUNDED
        this[SPATIAL_SCALING_LOG2] = 0
        this[FPS_SCALING] = 1
        this[SCAN] = com.loadingbyte.cinecred.project.Scan.PROGRESSIVE
        this[DEPTH] = 8
        this[PRIMARIES] = com.loadingbyte.cinecred.imaging.ColorSpace.Primaries.BT709
        this[TRANSFER] = com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.BT1886
        this[YUV] = Bitmap.YUVCoefficients.BT709_NCL
    }.findConfig(format) ?: throw IllegalArgumentException("Could not build render config for ${format.label}.")
}

private fun defaultOutputPath(projectDir: Path, spreadsheet: Spreadsheet, format: RenderFormat): Path =
    projectDir.resolve("${projectDir.name} ${spreadsheet.name}.${format.defaultFileExt}")

private fun readFonts(file: Path): List<Font> {
    val ext = file.extension.lowercase(Locale.ROOT)
    if (!file.isRegularFile() || ext !in setOf("ttf", "ttc", "otf", "otc"))
        return emptyList()
    return try {
        Font.read(file, mmap = false)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun sha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0)
                break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

private inline fun <S : Style, R : Any, A : Any> updateAuxiliaryReferences(
    styles: List<S>,
    refSetting: com.loadingbyte.cinecred.project.DirectStyleSetting<S, R>,
    ref2name: (R) -> String,
    ref2aux: (R) -> A?,
    name2ref: (String) -> R,
    aux2ref: (A) -> R,
    name2aux: (String) -> A?,
): PersistentList<S>? {
    var updatedStyles: MutableList<S>? = null
    for ((idx, style) in styles.withIndex()) {
        val ref = refSetting.get(style)
        val newAux = name2aux(ref2name(ref))
        if (newAux !== ref2aux(ref)) {
            val updatedRef = if (newAux != null) aux2ref(newAux) else name2ref(ref2name(ref))
            if (updatedStyles == null)
                updatedStyles = styles.toMutableList()
            updatedStyles[idx] = style.copy(refSetting.notarize(updatedRef))
        }
    }
    return updatedStyles?.toPersistentList()
}

private inline fun <reified S : PopupStyle> addRemovePopupStyles(
    styles: List<S>,
    usedStyles: Set<ListedStyle>
): PersistentList<S>? {
    var updatedStyles: MutableList<S>? = null
    for (idx in styles.lastIndex downTo 0) {
        val style = styles[idx]
        if (style.volatile && style !in usedStyles) {
            if (updatedStyles == null)
                updatedStyles = styles.toMutableList()
            updatedStyles.removeAt(idx)
        }
    }
    for (usedStyle in usedStyles)
        if (usedStyle is S && styles.none { it === usedStyle }) {
            if (updatedStyles == null)
                updatedStyles = styles.toMutableList()
            updatedStyles.add(usedStyle)
        }
    return updatedStyles?.toPersistentList()
}

private fun clearLegacyPageStyleSettings(
    styles: List<PageStyle>,
    log: List<ParserMsg>
): PersistentList<PageStyle>? {
    fun <SUBJ : Any> clearSetting(
        style: PageStyle,
        setting: com.loadingbyte.cinecred.project.DirectStyleSetting<PageStyle, SUBJ>
    ): PageStyle = style.copy(setting.notarize(setting.get(presetPageStyle())))

    val legacySettings = arrayOf(PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st())
    val usedLegacySettings = log.mapNotNullTo(HashSet(), ParserMsg::migrationDataSource)

    var clearedStyles: MutableList<PageStyle>? = null
    for ((idx, style) in styles.withIndex()) {
        var clearedStyle = style
        for (setting in legacySettings)
            if (setting.get(style) != setting.get(presetPageStyle()) &&
                MigrationDataSource(style, setting) !in usedLegacySettings
            ) {
                clearedStyle = clearSetting(clearedStyle, setting)
            }
        if (clearedStyle !== style) {
            if (clearedStyles == null)
                clearedStyles = styles.toMutableList()
            clearedStyles[idx] = clearedStyle
        }
    }
    return clearedStyles?.toPersistentList()
}
