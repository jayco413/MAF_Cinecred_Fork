package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.Picture
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.round
import kotlin.math.roundToInt


val SETTINGS_SCALING_WITH_RESOLUTION: Set<StyleSetting<*, *>> = setOf(
    Global::resolution.st(),
    Global::unitVGapPx.st(),
    PageStyle::scrollPxPerFrame.st(),
    ContentStyle::vMarginTopPx.st(),
    ContentStyle::vMarginBottomPx.st(),
    ContentStyle::gridForceColWidthPx.st(),
    ContentStyle::gridForceRowHeightPx.st(),
    ContentStyle::gridRowGapPx.st(),
    ContentStyle::gridColGapPx.st(),
    ContentStyle::flowForceCellWidthPx.st(),
    ContentStyle::flowForceCellHeightPx.st(),
    ContentStyle::flowRowWidthPx.st(),
    ContentStyle::flowRowGapPx.st(),
    ContentStyle::flowCellHGapPx.st(),
    ContentStyle::paragraphsLineWidthPx.st(),
    ContentStyle::paragraphsParaGapPx.st(),
    ContentStyle::paragraphsLineGapPx.st(),
    ContentStyle::headForceWidthPx.st(),
    ContentStyle::headGapPx.st(),
    ContentStyle::headLeaderMarginLeftPx.st(),
    ContentStyle::headLeaderMarginRightPx.st(),
    ContentStyle::headLeaderSpacingPx.st(),
    ContentStyle::tailForceWidthPx.st(),
    ContentStyle::tailGapPx.st(),
    ContentStyle::tailLeaderMarginLeftPx.st(),
    ContentStyle::tailLeaderMarginRightPx.st(),
    ContentStyle::tailLeaderSpacingPx.st(),
    LetterStyle::heightPx.st(),
    PictureStyle::widthPx.st(),
    PictureStyle::heightPx.st(),
    TapeStyle::widthPx.st(),
    TapeStyle::heightPx.st()
)


val SETTINGS_SCALING_WITH_FPS: Set<StyleSetting<*, *>> = setOf(
    Global::fps.st(),
    Global::runtimeFrames.st(),
    PageStyle::subsequentGapFrames.st(),
    PageStyle::cardRuntimeFrames.st(),
    PageStyle::cardFadeInFrames.st(),
    PageStyle::cardFadeOutFrames.st(),
    PageStyle::scrollRuntimeFrames.st(),
    TapeStyle::leftTemporalMarginFrames.st(),
    TapeStyle::rightTemporalMarginFrames.st(),
    TapeStyle::fadeInFrames.st(),
    TapeStyle::fadeOutFrames.st()
)


val SETTINGS_SCALING_INVERSELY_WITH_FPS: Set<StyleSetting<*, *>> = setOf(
    PageStyle::scrollPxPerFrame.st()
)


fun Styling.scaleResolution(scaling: Double): Styling =
    Styling(
        global.scaleResolution(scaling),
        pageStyles.map { it.scaleResolution(scaling) }.toPersistentList(),
        contentStyles.map { it.scaleResolution(scaling) }.toPersistentList(),
        letterStyles.map { it.scaleResolution(scaling) }.toPersistentList(),
        transitionStyles.map { it.scaleResolution(scaling) }.toPersistentList(),
        pictureStyles.map { it.scaleResolution(scaling) }.toPersistentList(),
        tapeStyles.map { it.scaleResolution(scaling) }.toPersistentList()
    )

fun Styling.scaleFPS(scaling: Double): Styling =
    Styling(
        global.scaleFPS(scaling),
        pageStyles.map { it.scaleFPS(scaling) }.toPersistentList(),
        contentStyles.map { it.scaleFPS(scaling) }.toPersistentList(),
        letterStyles.map { it.scaleFPS(scaling) }.toPersistentList(),
        transitionStyles.map { it.scaleFPS(scaling) }.toPersistentList(),
        pictureStyles.map { it.scaleFPS(scaling) }.toPersistentList(),
        tapeStyles.map { it.scaleFPS(scaling) }.toPersistentList()
    )


fun <S : Style> S.scaleResolution(scaling: Double): S {
    var style = scaleStyle(this, SETTINGS_SCALING_WITH_RESOLUTION, scaling)

    // Add width overrides to picture and tape styles which don't have width/height overrides yet.
    if (style is PictureStyle && style.widthPx.value == null && style.heightPx.value == null)
        style.computeResolutionBeforeRotation()?.let { (w, _) ->
            val isRaster = style.picture.loader!!.picture is Picture.Raster
            @Suppress("UNCHECKED_CAST")
            style = style.copy(widthPx = Override((w * scaling).let { if (isRaster) round(it) else it })) as S
        }
    if (style is TapeStyle && style.widthPx.value == null && style.heightPx.value == null)
        style.computeResolutionBeforeRotation()?.let { (w, _) ->
            @Suppress("UNCHECKED_CAST")
            style = style.copy(widthPx = Override((w * scaling).roundToInt())) as S
        }

    return style
}

fun <S : Style> S.scaleFPS(scaling: Double): S =
    scaleStyle(scaleStyle(this, SETTINGS_SCALING_WITH_FPS, scaling), SETTINGS_SCALING_INVERSELY_WITH_FPS, 1 / scaling)


private fun <S : Style> scaleStyle(style: S, settings: Iterable<StyleSetting<*, *>>, scaling: Double): S =
    style.copy(settings.mapNotNull { setting ->
        if (!setting.declaringClass.isInstance(style)) null else
            @Suppress("UNCHECKED_CAST")
            scaleSetting(style, setting as StyleSetting<S, *>, scaling)
    })

private fun <S : Style, SUBJ : Any> scaleSetting(style: S, setting: StyleSetting<S, SUBJ>, scaling: Double) =
    when (setting) {
        is DirectStyleSetting ->
            setting.notarize(scaleSubject(setting.get(style), scaling))
        is OptStyleSetting ->
            setting.get(style).let { opt -> setting.notarize(Opt(opt.isActive, scaleSubject(opt.value, scaling))) }
        is OverrideStyleSetting ->
            setting.notarize(Override(setting.get(style).value?.let { scaleSubject(it, scaling) }))
        is ListStyleSetting ->
            setting.notarize(setting.get(style).map { scaleSubject(it, scaling) }.toPersistentList())
    }

@Suppress("UNCHECKED_CAST")
private fun <SUBJ : Any> scaleSubject(subject: SUBJ, scaling: Double): SUBJ =
    when (subject) {
        is Int -> (scaling * subject).roundToInt()
        is Double -> scaling * subject
        is Resolution -> Resolution((scaling * subject.widthPx).roundToInt(), (scaling * subject.heightPx).roundToInt())
        is FPS -> FPS((scaling * subject.numerator).roundToInt(), subject.denominator)
        else -> throw IllegalStateException()
    } as SUBJ


data class DoubleResolution(val widthPx: Double, val heightPx: Double)

fun PictureStyle.computeResolutionBeforeRotation(
    respectStyleWidth: Boolean = true, respectStyleHeight: Boolean = true
): DoubleResolution? {
    try {
        val pic = (picture.loader ?: return null).picture
        val embPic = DeferredImage.EmbeddedPicture(
            pic,
            if (respectStyleWidth) widthPx.value else null,
            if (respectStyleHeight) heightPx.value else null,
            cropLeftPx, cropRightPx, cropTopPx, cropBottomPx, cropBlankSpace
        )
        return DoubleResolution(embPic.widthBeforeRotation, embPic.heightBeforeRotation)
    } catch (_: Exception) {
        return null
    }
}

fun TapeStyle.computeResolutionBeforeRotation(
    respectStyleWidth: Boolean = true, respectStyleHeight: Boolean = true
): Resolution? {
    try {
        val tape = tape.tape ?: return null
        val embTape = DeferredImage.EmbeddedTape(
            tape,
            if (respectStyleWidth) widthPx.value else null,
            if (respectStyleHeight) heightPx.value else null,
            cropLeftPx, cropRightPx, cropTopPx, cropBottomPx
        )
        return embTape.resolutionBeforeRotation
    } catch (_: Exception) {
        return null
    }
}
