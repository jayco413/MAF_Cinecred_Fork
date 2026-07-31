package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.service.Account
import com.loadingbyte.cinecred.projectio.service.WRITTEN_SERVICE_LINK_EXT
import com.loadingbyte.cinecred.projectio.service.abort
import com.loadingbyte.cinecred.projectio.service.writeServiceLink
import kotlinx.collections.immutable.persistentListOf
import java.nio.file.Path
import java.util.*
import kotlin.io.path.copyTo
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.math.max


/** @throws Exception */
fun tryCopyTemplate(destDir: Path, template: Template) {
    doTryCopyTemplate(destDir, template, null, null, null)
}

/** @throws Exception */
fun tryCopyTemplate(destDir: Path, template: Template, creditsFormat: SpreadsheetFormat) {
    doTryCopyTemplate(destDir, template, null, null, creditsFormat)
}

/** @throws Exception */
fun tryCopyTemplate(
    destDir: Path,
    template: Template,
    creditsAccount: Account,
    creditsFilename: String?,
    creditsFormat: SpreadsheetFormat?
) {
    doTryCopyTemplate(destDir, template, creditsAccount, creditsFilename, creditsFormat)
}

class Template(
    val locale: Locale,
    val resolution: Resolution,
    val fps: FPS,
    val timecodeFormat: TimecodeFormat,
    val sample: Boolean
)


private fun doTryCopyTemplate(
    destDir: Path,
    template: Template,
    creditsAccount: Account?,
    creditsFilename: String?,
    creditsFormat: SpreadsheetFormat?
) {
    // First try to write the credits file, so that if something goes wrong (which is likely with online services),
    // the project folder just isn't created at all, instead of being half-created.
    if (creditsFormat != null || creditsAccount != null)
        tryCopyCreditsTemplate(destDir, template, creditsAccount, creditsFilename, creditsFormat)
    tryCopyStylingTemplate(destDir, template)
    if (template.sample) {
        tryCopyLogoFile(destDir, "/branding/hLockup.svg", "Cinecred H.svg")
        tryCopyLogoFile(destDir, "/branding/vLockup.svg", "Cinecred V.svg")
    }
}


private fun tryCopyCreditsTemplate(
    destDir: Path,
    template: Template,
    creditsAccount: Account?,
    creditsFilename: String?,
    creditsFormat: SpreadsheetFormat?
) {
    var csv = useResourceStream("/template/credits.csv") { it.reader().readAllLines() }
    // If desired, cut off the sample credits and only keep the table header.
    if (!template.sample)
        csv = csv.subList(0, 1)
    val fileName = destDir.name
    val spreadsheetName = l10n("project.template.spreadsheetName", template.locale)
    val spreadsheetTemplate = CsvFormat.read(csv.joinToString("\n"), spreadsheetName)
    val spreadsheet = spreadsheetTemplate.map { fillIn(it, template) }
    val look = SpreadsheetLook(
        frozenRows = 1,
        rowLooks = mapOf(0 to SpreadsheetLook.RowLook(bold = true)),
        colWidths = listOf(45, 45, 15, 15, 25, 15, 40, 20, 25, 25),
        comments = spreadsheetTemplate[0].cells.mapIndexed { col, cell ->
            // Derive the comment keys from the CSV's first row.
            val text = fillIn("{projectIO.credits.table.${cell.substring(2, cell.length - 1)}Desc}", template)
            SpreadsheetLook.Comment(0, col, text)
        }
    )
    when {
        creditsAccount == null && creditsFormat != null -> {
            val destFile = destDir.resolve("$fileName.${creditsFormat.fileExt}")
            if (!destFile.notExists())
                return
            destDir.createDirectoriesSafely()
            creditsFormat.write(destFile, spreadsheet, look)
        }
        creditsAccount != null -> {
            val destFile = destDir.resolve("$fileName.$WRITTEN_SERVICE_LINK_EXT")
            if (!destFile.notExists())
                return
            var filename = if (creditsAccount.service.uploadNeedsFilename) requireNotNull(creditsFilename) else null
            val format = if (creditsAccount.service.uploadNeedsFormat) requireNotNull(creditsFormat) else null
            if (filename != null && format != null)
                filename = filename.removeAnySuffix(SPREADSHEET_FORMATS.map { ".${it.fileExt}" }, ignoreCase = true) +
                        ".${format.fileExt}"
            val link = creditsAccount.upload(filename, format, spreadsheet, look)
                .abort { error -> throw Exception(l10n("ui.projects.create.error.service", error.message)) }
            // Uploading the credits file can take some time. If the user cancels in the meantime, the uploader is
            // actually not interrupted. So instead, we detect interruption here and stop project initialization.
            if (Thread.interrupted())
                throw InterruptedException()
            destDir.createDirectoriesSafely()
            writeServiceLink(destFile, link)
        }
        else -> throw IllegalArgumentException()
    }
}


private fun tryCopyStylingTemplate(destDir: Path, template: Template) {
    val file = destDir.resolve(STYLING_FILE_NAME)
    if (file.notExists()) {
        val styling = (if (template.sample) sampleStyling(template) else emptyStyling(template))
            .scaleResolution(template.resolution.order.toDouble())
        destDir.createDirectoriesSafely()
        writeStyling(file, styling)
    }
}


private fun tryCopyLogoFile(destDir: Path, from: String, to: String) {
    val logoFile = destDir.resolve("Logos").resolve(to)
    if (logoFile.notExists()) {
        logoFile.parent.createDirectoriesSafely()
        useResourcePath(from) { it.copyTo(logoFile) }
    }
}


private fun fillIn(string: String, template: Template): String = string
    .replace(PLACEHOLDER_REGEX) { match ->
        when (val key = match.groups[1]!!.value) {
            "projectIO.credits.table.headDesc" -> {
                val styleKw = l10nKeyword("style", template.locale)
                val stylePlaceholder = "[${l10n("ui.styling.letter.name", template.locale)}]"
                val name = l10n("project.template.letterStyleName", template.locale)
                l10n(
                    key,
                    l10nQuoted("{{$styleKw $stylePlaceholder}}", template.locale),
                    stylePlaceholder,
                    l10nQuoted("{{$styleKw}}", template.locale),
                    l10nEnumQuoted("Copyright 2023 {{$styleKw $name}}Thomas Cash", locale = template.locale),
                    locale = template.locale
                )
            }
            "projectIO.credits.table.tailDesc", "projectIO.credits.table.bodyDesc" -> {
                val picKw = l10nKeyword("pic", template.locale)
                val videoKw = l10nKeyword("video", template.locale)
                val filenamePlaceholder = "[${l10n("filename", template.locale)}]"
                l10n(
                    key,
                    l10nQuoted("{{${l10nKeyword("style", template.locale)} …}}", template.locale),
                    "@" + l10nKeyword("head", template.locale),
                    l10nQuoted("{{${l10nKeyword("blank", template.locale)}}}", template.locale),
                    l10nQuoted("{{$picKw $filenamePlaceholder}}", template.locale),
                    l10nQuoted("{{$videoKw $filenamePlaceholder}}", template.locale),
                    l10nEnumQuoted(
                        "{{$picKw Cinecred Logo}}", "{{$videoKw Blooper 3.mov XXL}}",
                        locale = template.locale
                    ),
                    locale = template.locale
                )
            }
            "projectIO.credits.table.vGapDesc" ->
                l10n(key, l10nQuoted("px", template.locale), locale = template.locale)
            "projectIO.credits.table.breakHarmonizationDesc" ->
                l10n(
                    key,
                    l10nQuoted(l10nKeyword("head", template.locale), template.locale),
                    l10nQuoted(l10nKeyword("body", template.locale), template.locale),
                    l10nQuoted(l10nKeyword("tail", template.locale), template.locale),
                    locale = template.locale
                )
            "projectIO.credits.table.spinePosDesc" -> {
                val below = l10nKeyword("below", template.locale)
                val parallel = l10nKeyword("parallel", template.locale)
                val hook = l10nKeyword("hook", template.locale)
                val top = l10nKeyword("top", template.locale)
                val mid = l10nKeyword("middle", template.locale)
                val bot = l10nKeyword("bottom", template.locale)
                l10n(
                    key,
                    l10nQuoted(below, template.locale),
                    l10nQuoted(l10nKeyword("above", template.locale), template.locale),
                    l10nEnumQuoted("-400", "-400 200", "-400 200 $below", locale = template.locale),
                    l10nEnumQuoted("-400", locale = template.locale),
                    l10nQuoted(parallel, template.locale),
                    l10nQuoted(hook, template.locale),
                    l10nEnumQuoted(
                        "-400 $parallel", "$hook 1 $bot-$top", "$hook 1 $top-$top 800", "$hook 2 $bot-$mid 800 100",
                        locale = template.locale
                    ),
                    locale = template.locale
                )
            }
            "projectIO.credits.table.pageRuntimeDesc" ->
                l10n(
                    key,
                    l10nEnumQuoted("00:04:56:23", "XYZ 00:04:56:23", "XYZ", locale = template.locale),
                    locale = template.locale
                )
            "projectIO.credits.table.pageGapDesc" -> {
                val fuse = l10nKeyword("fuse", template.locale)
                val linear = l10n("linear", template.locale)
                l10n(
                    key,
                    l10nQuoted(fuse, template.locale),
                    l10nEnumQuoted(
                        "00:04:56:23", "-00:04:56:23", fuse, "$fuse 00:00:02:00 $linear",
                        locale = template.locale
                    ),
                    locale = template.locale
                )
            }
            else ->
                try {
                    l10nKeyword(key, template.locale)
                } catch (_: MissingResourceException) {
                    l10n(key, template.locale)
                }
        }
    }
    .replace(SCALING_REGEX) { match ->
        val num = match.groups[1]!!.value
        (num.toInt() * template.resolution.order).toString()
    }
    .replace(TIMECODE_REGEX) { match ->
        val num = match.groups[1]!!.value
        val frames = Timecode.Clock(num.toLong(), 1).toFramesCeil(template.fps).frames
        formatTimecode(template.fps, template.timecodeFormat, frames)
    }

private val PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z0-9.]+)}")
private val SCALING_REGEX = Regex("<([0-9]+)>")
private val TIMECODE_REGEX = Regex("\\[([0-9]+)s]")


private fun emptyStyling(template: Template) = Styling(
    global = PRESET_GLOBAL.copy(
        resolution = template.resolution,
        fps = template.fps,
        timecodeFormat = template.timecodeFormat,
        blankLastFrame = true,
        unitVGapPx = 32.0,
        locale = template.locale
    ),
    pageStyles = persistentListOf(),
    contentStyles = persistentListOf(),
    letterStyles = persistentListOf(),
    transitionStyles = persistentListOf(
        PRESET_TRANSITION_STYLE.copy(
            name = l10n("linear", template.locale)
        )
    ),
    pictureStyles = persistentListOf(),
    tapeStyles = persistentListOf()
)

private fun sampleStyling(template: Template) = emptyStyling(template).copy(
    pageStyles = persistentListOf(
        PRESET_PAGE_STYLE.copy(
            name = l10n("project.PageBehavior.CARD", template.locale),
            subsequentGapFrames = template.fps.run { roundingDiv(numerator, denominator) },
            behavior = PageBehavior.CARD,
            cardRuntimeFrames = template.fps.run { roundingDiv(5 * numerator, denominator) },
            cardFadeInFrames = template.fps.run { roundingDiv(numerator, 2 * denominator) },
            cardFadeInTransitionStyleName = l10n("linear", template.locale),
            cardFadeOutFrames = template.fps.run { roundingDiv(numerator, 2 * denominator) },
            cardFadeOutTransitionStyleName = l10n("linear", template.locale)
        ),
        PRESET_PAGE_STYLE.copy(
            name = l10n("project.PageBehavior.SCROLL", template.locale),
            subsequentGapFrames = template.fps.run { roundingDiv(numerator, denominator) },
            behavior = PageBehavior.SCROLL,
            scrollPxPerFrame = max(1, template.fps.run { roundingDiv(78 * denominator, numerator) }).toDouble()
        )
    ),
    contentStyles = persistentListOf(
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleHeading", template.locale),
            bodyLetterStyleName = l10n("project.template.contentStyleHeading", template.locale),
            bodyLayout = BodyLayout.GRID
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleSubheading", template.locale),
            bodyLetterStyleName = l10n("project.template.letterStyleSmall", template.locale),
            bodyLayout = BodyLayout.GRID
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleGutter", template.locale),
            blockOrientation = BlockOrientation.HORIZONTAL,
            spineAttachment = SpineAttachment.HEAD_GAP_CENTER,
            bodyLetterStyleName = l10n("project.template.letterStyleName", template.locale),
            bodyLayout = BodyLayout.GRID,
            gridHarmonizeColWidths = HarmonizeExtent.ACROSS_BLOCKS,
            gridCellHJustifyPerCol = persistentListOf(HJustify.LEFT),
            hasHead = true,
            headLetterStyleName = Override(l10n("project.template.letterStyleSmall", template.locale)),
            headHarmonizeWidth = HarmonizeExtent.ACROSS_BLOCKS,
            headHJustify = HJustify.RIGHT,
            headGapPx = 24.0
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleBullets", template.locale),
            bodyLetterStyleName = l10n("project.template.letterStyleName", template.locale),
            bodyLayout = BodyLayout.FLOW,
            flowRowWidthPx = 800.0,
            flowCellHGapPx = 32.0,
            hasHead = true,
            headLetterStyleName = Override(l10n("project.template.letterStyleSmall", template.locale)),
            headGapPx = 4.0
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleTabular", template.locale),
            bodyLetterStyleName = l10n("project.template.letterStyleName", template.locale),
            bodyLayout = BodyLayout.GRID,
            gridCols = 3,
            gridStructure = GridStructure.EQUAL_WIDTH_COLS,
            gridCellHJustifyPerCol = persistentListOf(HJustify.LEFT, HJustify.CENTER, HJustify.RIGHT),
            gridColGapPx = 32.0
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleSong", template.locale),
            bodyLetterStyleName = l10n("project.template.letterStyleSmall", template.locale),
            bodyLayout = BodyLayout.GRID,
            hasHead = true,
            headLetterStyleName = Override(l10n("project.template.letterStyleSongTitle", template.locale)),
            headGapPx = 0.0
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleLogos", template.locale),
            bodyLetterStyleName = l10n("project.template.letterStyleNormal", template.locale),
            bodyLayout = BodyLayout.FLOW,
            flowRowWidthPx = 1200.0,
            flowRowGapPx = 64.0,
            flowCellHGapPx = 96.0,
            flowSeparator = ""
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.template.contentStyleBlurb", template.locale),
            bodyLetterStyleName = l10n("project.template.letterStyleNormal", template.locale),
            bodyLayout = BodyLayout.PARAGRAPHS,
            paragraphsLineWidthPx = 500.0,
            paragraphsParaGapPx = 8.0
        ),
        PRESET_CONTENT_STYLE.copy(
            name = l10n("project.PageBehavior.CARD", template.locale),
            bodyLetterStyleName = l10n("project.template.letterStyleCardName", template.locale),
            bodyLayout = BodyLayout.GRID,
            hasHead = true,
            headLetterStyleName = Override(l10n("project.template.letterStyleCardSmall", template.locale)),
            headGapPx = 32.0,
            hasTail = true,
            tailLetterStyleName = Override(l10n("project.template.letterStyleCardSmall", template.locale)),
            tailGapPx = 32.0
        )
    ),
    letterStyles = persistentListOf(
        PRESET_LETTER_STYLE.copy(
            name = l10n("project.template.letterStyleNormal", template.locale),
            font = FontRef("Archivo Narrow Regular"),
            heightPx = 32.0
        ),
        PRESET_LETTER_STYLE.copy(
            name = l10n("project.template.letterStyleSmall", template.locale),
            font = FontRef("Archivo Narrow Regular"),
            heightPx = 24.0
        ),
        PRESET_LETTER_STYLE.copy(
            name = l10n("project.template.contentStyleHeading", template.locale),
            font = FontRef("Archivo Narrow Regular"),
            heightPx = 32.0,
            trackingEm = 0.05,
            layers = persistentListOf(
                PRESET_LAYER.copy(
                    name = l10n("project.LayerShape.TEXT", template.locale),
                    shape = LayerShape.TEXT
                ),
                PRESET_LAYER.copy(
                    name = l10n("project.StripePreset.UNDERLINE", template.locale),
                    shape = LayerShape.STRIPE,
                    stripePreset = StripePreset.UNDERLINE
                )
            )
        ),
        PRESET_LETTER_STYLE.copy(
            name = l10n("project.template.letterStyleName", template.locale),
            font = FontRef("Archivo Narrow Bold"),
            heightPx = 32.0
        ),
        PRESET_LETTER_STYLE.copy(
            name = l10n("project.template.letterStyleSongTitle", template.locale),
            font = FontRef("Archivo Narrow Bold"),
            heightPx = 32.0,
            smallCaps = SmallCaps.SMALL_CAPS
        ),
        PRESET_LETTER_STYLE.copy(
            name = l10n("project.template.letterStyleCardName", template.locale),
            font = FontRef("Archivo Narrow Bold"),
            heightPx = 100.0
        ),
        PRESET_LETTER_STYLE.copy(
            name = l10n("project.template.letterStyleCardSmall", template.locale),
            font = FontRef("Archivo Narrow Regular"),
            heightPx = 50.0
        )
    ),
    pictureStyles = persistentListOf(
        PRESET_PICTURE_STYLE.copy(
            name = "Cinecred H",
            picture = PictureRef("Cinecred H.svg"),
            heightPx = Override(90.0),
            cropBlankSpace = true
        ),
        PRESET_PICTURE_STYLE.copy(
            name = "Cinecred V",
            picture = PictureRef("Cinecred V.svg"),
            heightPx = Override(150.0),
            cropBlankSpace = true
        )
    )
)
