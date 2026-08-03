package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.ColorSpace.Primaries.Companion.BT709
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.BLENDING
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.project.Scan
import com.loadingbyte.cinecred.ui.helper.PALETTE_YELLOW
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.BasicStroke
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.text.AttributedString
import java.text.BreakIterator
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.*
import kotlin.math.ceil
import kotlin.math.min


class VideoDeliverer(
    private val video: DeferredVideo,
    private val timecodeFormat: TimecodeFormat,
    grounding: Color4f?,
    private val locale: Locale,
    private val slate: RenderFormat.Slate?,
    userRepresentation: Bitmap.Representation,
    ceiling: Float?,
    scan: Scan,
    private val matte: Boolean
) : AutoCloseable {

    val numFrames: Int =
        video.numFrames + if (slate == null) 0 else 1

    val userSpec = Bitmap.Spec(
        resolution = video.resolution,
        representation = userRepresentation,
        scan = when (scan) {
            Scan.PROGRESSIVE -> Bitmap.Scan.PROGRESSIVE
            Scan.INTERLACED_TOP_FIELD_FIRST -> Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
            Scan.INTERLACED_BOT_FIELD_FIRST -> Bitmap.Scan.INTERLACED_BOT_FIELD_FIRST
        },
        content = when (scan) {
            Scan.PROGRESSIVE -> Bitmap.Content.PROGRESSIVE_FRAME
            Scan.INTERLACED_TOP_FIELD_FIRST, Scan.INTERLACED_BOT_FIELD_FIRST -> Bitmap.Content.INTERLEAVED_FIELDS
        }
    )

    private val backend: DeferredVideo.BitmapBackend
    private var backendSpec: Bitmap.Spec
    private var blackUserBitmap: Bitmap? = null
    private var frameIdx = if (slate == null) 0 else -1

    init {
        backendSpec = userSpec
        if (matte) {
            val pixFmtCode = when (userRepresentation.pixelFormat.family) {
                Bitmap.PixelFormat.Family.GRAY -> when (userRepresentation.pixelFormat.code) {
                    AV_PIX_FMT_GRAY8 -> AV_PIX_FMT_GBRAP
                    AV_PIX_FMT_GRAY10BE -> AV_PIX_FMT_GBRAP10BE
                    AV_PIX_FMT_GRAY10LE -> AV_PIX_FMT_GBRAP10LE
                    AV_PIX_FMT_GRAY12BE -> AV_PIX_FMT_GBRAP12BE
                    AV_PIX_FMT_GRAY12LE -> AV_PIX_FMT_GBRAP12LE
                    AV_PIX_FMT_GRAY16BE -> AV_PIX_FMT_GBRAP16BE
                    AV_PIX_FMT_GRAY16LE -> AV_PIX_FMT_GBRAP16LE
                    AV_PIX_FMT_GRAYF32BE -> AV_PIX_FMT_GBRAPF32BE
                    AV_PIX_FMT_GRAYF32LE -> AV_PIX_FMT_GBRAPF32LE
                    else -> throw IllegalArgumentException("No color format for ${userRepresentation.pixelFormat}.")
                }
                else -> when (val depth = userRepresentation.pixelFormat.depth) {
                    8 -> AV_PIX_FMT_GBRAP
                    10 -> AV_PIX_FMT_GBRAP10
                    12 -> AV_PIX_FMT_GBRAP12
                    16 -> AV_PIX_FMT_GBRAP16
                    else -> throw IllegalArgumentException("No color format for depth $depth.")
                }
            }
            val backendRep = Bitmap.Representation(
                Bitmap.PixelFormat.of(pixFmtCode), ColorSpace.of(BT709, BLENDING), Bitmap.Alpha.PREMULTIPLIED
            )
            backendSpec = userSpec.copy(representation = backendRep)
            if (userRepresentation.pixelFormat.family != Bitmap.PixelFormat.Family.GRAY) {
                val rgbRep = Bitmap.Representation(
                    Bitmap.PixelFormat.of(AV_PIX_FMT_GBRPF32), userRepresentation.colorSpace
                )
                blackUserBitmap = Bitmap.allocate(userSpec)
                Bitmap.allocate(userSpec.copy(representation = rgbRep)).zero()
                    .use { BitmapConverter.convert(it, blackUserBitmap!!) }
            }
        }

        backend = DeferredVideo.BitmapBackend(
            video, listOf(STATIC), listOf(TAPES), grounding, backendSpec, ceiling
        )
    }

    override fun close() {
        backend.close()
        blackUserBitmap?.close()
    }

    /** Delivers the next frame, or returns null if the video has come to an end. */
    fun deliverFrame(): Bitmap? {
        val frameIdx = this.frameIdx++
        if (frameIdx == -1) {
            val isGray = userSpec.representation.pixelFormat.family == Bitmap.PixelFormat.Family.GRAY
            val convBitmap = Bitmap.allocate(if (isGray) backendSpec else userSpec)
            val canvasRep = Canvas.compatibleRepresentation(ColorSpace.of(BT709, BLENDING))
            Bitmap.allocate(userSpec.copy(representation = canvasRep)).use { canvasBitmap ->
                Canvas.forBitmap(canvasBitmap.zero()).use { canvas ->
                    if (!isGray)
                        canvas.fill(Canvas.Shader.Solid(Color4f.BLACK))
                    drawSlate(canvas)
                }
                BitmapConverter.convert(canvasBitmap, convBitmap)
            }
            return if (isGray) convBitmap.use(Bitmap::alphaPlaneView) else convBitmap
        }
        val colorBitmap = backend.materializeFrame(frameIdx) ?: return null
        return when {
            !matte -> colorBitmap
            userSpec.representation.pixelFormat.family == Bitmap.PixelFormat.Family.GRAY ->
                colorBitmap.use(Bitmap::alphaPlaneView)
            else -> {
                val matteBitmap = Bitmap.allocate(userSpec).zero()
                matteBitmap.blit(blackUserBitmap!!)
                matteBitmap.blitComponent(colorBitmap, 3, 0)
                if (userSpec.representation.pixelFormat.family == Bitmap.PixelFormat.Family.RGB) {
                    matteBitmap.blitComponent(colorBitmap, 3, 1)
                    matteBitmap.blitComponent(colorBitmap, 3, 2)
                }
                colorBitmap.close()
                matteBitmap
            }
        }
    }

    private fun drawSlate(canvas: Canvas) {
        val slate = this.slate!!
        val rep = userSpec.representation
        val pixFmt = rep.pixelFormat

        val details = mutableListOf<Pair<String, String>>()
        details += l10n("date", locale) to
                LocalDate.now().toString()
        details += l10n("ui.styling.page.cardRuntimeFrames", locale) to
                formatTimecode(video.fps, timecodeFormat, video.numFrames) +
                if (timecodeFormat == TimecodeFormat.FRAMES) "" else " (${video.numFrames})"
        details += l10n("resolution", locale) to
                video.resolution.toString()
        details += l10n("ui.styling.global.fps", locale) to
                DecimalFormat("#.##", DecimalFormatSymbols.getInstance(locale)).format(video.fps.frac) +
                if (userSpec.scan == Bitmap.Scan.PROGRESSIVE) "p" else "i"
        details += l10n("bitDepth", locale) to
                pixFmt.depth.toString()
        if (!pixFmt.isFloat)
            details += l10n("ui.styling.tape.range", locale) to
                    when (rep.range) {
                        Bitmap.Range.FULL -> "0-${(1 shl pixFmt.depth) - 1}"
                        Bitmap.Range.LIMITED -> (1 shl (pixFmt.depth - 8)).let { "${16 * it}-${235 * it}/${240 * it}" }
                    }
        if (!matte) {
            val cs = rep.colorSpace!!
            details += l10n("gamut", locale) to cs.primaries.toString()
            details += "EOTF" to cs.transfer.toString()
        }
        if (rep.alpha != Bitmap.Alpha.OPAQUE)
            details += l10n("ui.styling.tape.alpha", locale) to l10n("yes", locale)
        else if (matte)
            details += "Matte" to l10n("yes", locale)

        val drawer = SlateDrawer(canvas, locale)

        drawer.drawSVG(LOCKUP_SVG, 0.0, 1.0, 0.15, anchorBot = true)
        drawer.drawSVG(MUTE_SVG, 1.0, 1.0, 0.05, anchorRight = true, anchorBot = true)

        val warnWidth = 0.08
        drawer.drawSVG(WARN_SVG, 0.775 - 0.5 * warnWidth, 0.0, warnWidth)

        val titleBotY = drawer.drawString(slate.title, Color4f.WHITE, 0.0, 0.0, breakWidth = 0.45)
        drawer.drawLine(0.0, 0.45, titleBotY, 0.002, Color4f.WHITE)

        var cy = titleBotY + 0.5 * drawer.fontHeight
        for ((k, v) in details) {
            drawer.drawString(k, Color4f.GRAY, 0.215, cy, anchorRight = true)
            cy = drawer.drawString(v, Color4f.WHITE, 0.235, cy) + 0.1 * drawer.fontHeight
        }

        cy = 0.17
        for ((i, paragraph) in l10n("delivery.resizeRetimeWarning", locale).split(Regex("\n+")).withIndex()) {
            val color = if (i == 0) Color4f.fromSRGBHexString(PALETTE_YELLOW) else Color4f.WHITE
            cy = drawer.drawString(paragraph, color, 0.55, cy, breakWidth = 0.45) + 0.5 * drawer.fontHeight
        }
    }


    companion object {
        private val LOCKUP_SVG = useResourcePath("/branding/hLockup.svg", Picture.SVG::load)
        private val MUTE_SVG = useResourcePath("/icons/mute.svg", Picture.SVG::load)
        private val WARN_SVG = useResourcePath("/icons/warn.svg", Picture.SVG::load)
    }


    // Note: In this class, coordinates prefixed with "p" are in physical pixels, while those without a prefix are
    // logical coordinates between 0 and 1.
    private class SlateDrawer(private val canvas: Canvas, private val locale: Locale) {

        val fontHeight get() = 0.05

        // The size of the slate in pixels.
        private val pw: Double = 0.8 * min(canvas.width, canvas.height / 9.0 * 16.0)
        private val ph: Double = pw / 16.0 * 9.0
        // The top left position of the slate within the video frame in pixels.
        private val px0: Double = 0.5 * (canvas.width - pw)
        private val py0: Double = 0.5 * (canvas.height - ph)

        private val font = compositeBundledFont("/fonts/SourceSansPro-Regular.ttf").let {
            val pFH12 = it.deriveFont(12f).getLineMetrics("Sample", FontRenderContext(null, true, true)).height
            it.deriveFont(12f * (fontHeight * ph / pFH12).toFloat())
        }

        fun drawLine(x1: Double, x2: Double, y: Double, lw: Double, color: Color4f) {
            val py = py0 + y * ph
            val shape = Line2D.Double(px0 + x1 * pw, py, px0 + x2 * pw, py)
            canvas.strokeShape(shape, BasicStroke(ceil(lw * ph).toFloat()), Canvas.Shader.Solid(color))
        }

        fun drawSVG(
            pic: Picture.SVG, x: Double, y: Double, w: Double, anchorRight: Boolean = false, anchorBot: Boolean = false
        ) {
            val scaling = w * pw / pic.width
            val px = px0 + x * pw - if (anchorRight) scaling * pic.width else 0.0
            val py = py0 + y * ph - if (anchorBot) scaling * pic.height else 0.0
            pic.drawTo(canvas, transform = AffineTransform().apply { translate(px, py); scale(scaling) })
        }

        fun drawString(
            str: String, color: Color4f, x: Double, y: Double, breakWidth: Double? = null, anchorRight: Boolean = false
        ): Double {
            val px1 = px0 + x * pw
            val py1 = py0 + y * ph
            val pBreakWidth = breakWidth?.times(pw)

            val attrs = mapOf(TextAttribute.FONT to font, TextAttribute.LANGUAGE to locale)
            val iter = AttributedString(str, attrs).iterator
            val frc = FontRenderContext(null, true, true)
            val lineLayouts = if (pBreakWidth == null) listOf(TextLayout(iter, frc)) else buildList {
                val lbm = LineBreakMeasurer(iter, BreakIterator.getLineInstance(locale), frc)
                while (lbm.position != str.length)
                    add(lbm.nextLayout(pBreakWidth.toFloat()))
            }
            val outline = Path2D.Float()
            var py = py1
            for (lineLayout in lineLayouts) {
                val px = px1 - if (anchorRight) lineLayout.advance.toDouble() else 0.0
                py += lineLayout.ascent
                outline.append(lineLayout.getOutline(AffineTransform.getTranslateInstance(px, py)), false)
                py += lineLayout.descent + lineLayout.leading
            }

            canvas.fillShape(outline, Canvas.Shader.Solid(color))
            return (py - py0) / ph
        }

    }

}
