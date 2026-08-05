package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.BitmapConverter.ResamplingFilter.NEAREST_NEIGHBOR
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import org.apache.fontbox.ttf.OTFParser
import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.function.PDFunction
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType0
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask
import org.apache.pdfbox.util.Matrix
import org.bytedeco.ffmpeg.global.avutil.*
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.traversal.NodeFilter.SHOW_ELEMENT
import java.awt.BasicStroke
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.*
import java.io.OutputStream
import java.lang.Byte.toUnsignedInt
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.ByteBuffer
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.concurrent.CompletableFuture
import javax.xml.XMLConstants.XML_NS_URI
import kotlin.math.*


class DeferredImage(var width: Double = 0.0, var height: Y = 0.0.toY()) {

    private val instructions = HashMap<Layer, MutableList<Instruction>>()

    private fun addInstruction(layer: Layer, insn: Instruction) {
        instructions.computeIfAbsent(layer) { mutableListOf() }.add(insn)
    }

    fun copy(universeScaling: Double = 1.0, elasticScaling: Double = 1.0): DeferredImage {
        val copy = DeferredImage(
            width = width * universeScaling,
            height = (height * universeScaling).scaleElastic(elasticScaling)
        )
        copy.drawDeferredImage(this, universeScaling = universeScaling, elasticScaling = elasticScaling)
        return copy
    }

    fun drawDeferredImage(
        image: DeferredImage,
        x: Double = 0.0, y: Y = 0.0.toY(), universeScaling: Double = 1.0, elasticScaling: Double = 1.0
    ) {
        for (layer in image.instructions.keys) {
            val insn = Instruction.DrawDeferredImageLayer(x, y, universeScaling, elasticScaling, image, layer)
            addInstruction(layer, insn)
        }
    }

    fun drawShape(
        color: Color4f, shape: Shape, x: Double, y: Y, fill: Boolean, blurRadius: Double = 0.0, layer: Layer = STATIC
    ) {
        drawShape(Coat.Plain(color), shape, x, y, fill, blurRadius, layer)
    }

    fun drawShape(
        coat: Coat, shape: Shape, x: Double, y: Y, fill: Boolean, blurRadius: Double = 0.0, layer: Layer = STATIC
    ) {
        if (!coat.isVisible()) return
        addInstruction(layer, Instruction.DrawShape(x, y, shape, coat, fill, blurRadius))
    }

    fun drawLine(color: Color4f, x1: Double, y1: Y, x2: Double, y2: Y, dash: Boolean = false, layer: Layer = STATIC) {
        if (color.a == 0f) return
        addInstruction(layer, Instruction.DrawLine(x1, y1, x2, y2, color, dash))
    }

    fun drawRect(
        color: Color4f, x: Double, y: Y, width: Double, height: Y, fill: Boolean = false, layer: Layer = STATIC
    ) {
        if (color.a == 0f) return
        addInstruction(layer, Instruction.DrawRect(x, y, width, height, color, fill))
    }

    fun drawText(coat: Coat, text: Text, x: Double, yBaseline: Y, layer: Layer = STATIC) {
        if (!coat.isVisible()) return
        addInstruction(layer, Instruction.DrawText(x, yBaseline, text, coat))
    }

    fun drawEmbeddedPicture(embeddedPic: EmbeddedPicture, x: Double, y: Y, layer: Layer = STATIC) {
        addInstruction(layer, Instruction.DrawEmbeddedPicture(x, y, embeddedPic))
    }

    fun drawEmbeddedTape(embeddedTape: EmbeddedTape, x: Double, y: Y, layer: Layer = TAPES) {
        // When the deferred image is materialized later, we'll need the tape's thumbnail, so already start loading it.
        embeddedTape.tape.getPreviewFrame(embeddedTape.range.start)
        addInstruction(layer, Instruction.DrawEmbeddedTape(x, y, embeddedTape))
    }

    /**
     * Draws the content of this deferred image onto the given [Canvas]. The canvas must be backed by a bitmap. Raster
     * content is aligned with the canvas' pixel grid to prevent interpolation and retain as much quality as possible.
     */
    fun materialize(
        canvas: Canvas,
        cachePictures: Boolean,
        permitTapePreviews: PermitTapePreviews?,
        tolerateErroneousMedia: Boolean,
        layers: List<Layer>
    ) {
        require(canvas.bitmap != null) { "To materialize to an SVG or PDF, use the specialized methods." }
        val backend = CanvasBackend(
            canvas, cachePictures, permitTapePreviews as PermitTapePreviewsImpl?, tolerateErroneousMedia
        )
        // If only a portion of the deferred image is materialized, cull the rest to improve performance.
        // Notice that because the culling rect is aligned with the pixel grid, we correctly include all content
        // that at least partially lies inside one of the surface's pixels.
        val culling = Rectangle2D.Double(0.0, 0.0, canvas.width, canvas.height)
        materialize(backend, culling, layers)
    }

    /** Draws the content of this deferred image onto an SVG element. */
    fun materialize(svg: Element, layers: List<Layer>) {
        materialize(SVGBackend(svg), null, layers)
    }

    /** Draws the content of this deferred onto a PDF page. */
    fun materialize(tracker: PDFTracker, page: PDPage, cs: PDPageContentStream, layers: List<Layer>) {
        val backend = PDFBackend(tracker as PDFTrackerImpl, page, cs)
        materialize(backend, null, layers)
    }

    fun collectPlacedTapes(layers: List<Layer>): List<PlacedTape> {
        val backend = PlacedTapeCollectorBackend()
        materialize(backend, null, layers)
        return backend.collected
    }

    private fun materialize(backend: MaterializationBackend, culling: Rectangle2D?, layers: List<Layer>) {
        for (layer in layers)
            Instruction.DrawDeferredImageLayer(0.0, 0.0.toY(), 1.0, 1.0, this, layer)
                .materialize(backend, 0.0, 0.0, 1.0, 1.0, culling)
    }


    companion object {

        // These common layers are typically used. Additional layers may be defined by users of this class.
        val STATIC = object : Layer {}
        val TAPES = object : Layer {}
        val GUIDES = object : Layer {}

        private val F = DecimalFormat("#.####", DecimalFormatSymbols.getInstance(Locale.ROOT))

        private fun FloatArray.isFinite(end: Int): Boolean =
            allBetween(0, end, Float::isFinite)

        private fun Coat.isVisible(): Boolean = when (this) {
            is Coat.Plain -> color.a != 0f
            is Coat.Gradient -> stops.any { it.color.a != 0f }
        }

        private fun Coat.transform(tx: AffineTransform): Coat = when (this) {
            is Coat.Plain -> this
            is Coat.Gradient ->
                Coat.Gradient(tx.transform(point1, null), tx.transform(point2, null), stops, interpolation)
        }

        private fun Coat.toShader() = when (this) {
            is Coat.Plain -> Canvas.Shader.Solid(color)
            is Coat.Gradient -> Canvas.Shader.LinearGradient(
                point1, point2, stops.map { it.color }, stops.mapToDoubleArray { it.position }, interpolation
            )
        }

        private fun Coat.Gradient.clampAndSubdivide(colorSpace: ColorSpace, grid: Boolean): List<Coat.Gradient.Stop> {
            val minPos = stops.first().position
            val maxPos = stops.last().position

            val n = 19
            val spec = Bitmap.Spec(Resolution(n, 1), Canvas.compatibleRepresentation(colorSpace))
            check(spec.representation.pixelFormat.code == AV_PIX_FMT_RGBAF32)
            val subdivArr: FloatArray
            Bitmap.allocate(spec).use { bitmap ->
                Canvas.forBitmap(bitmap.zero()).use { canvas ->
                    val shader = Canvas.Shader.LinearGradient(
                        // We checked that placing the endpoints like this produces perfectly linearly spaced stops.
                        point1 = Point2D.Double(-0.5, 0.0),
                        point2 = Point2D.Double(n + 0.5, 0.0),
                        // Interpolate the alpha separately so that we don't have to deal with premultiplication.
                        colors = stops.map { it.color.copy(a = 1f) },
                        // Remap the positions s.t. the gradient uses the entire bitmap.
                        pos = stops.mapToDoubleArray { (it.position - minPos) / (maxPos - minPos) },
                        interpolation
                    )
                    canvas.fillShape(Rectangle(n, 1), shader)
                }
                subdivArr = bitmap.getF(n * 4)
            }

            val out = mutableListOf<Coat.Gradient.Stop>()
            for ((stopIdx, stop) in stops.withIndex()) {
                if (stopIdx != 0) {
                    val prevStop = stops[stopIdx - 1]
                    val pad = if (grid) 0.0 else 0.01
                    for (subdivIdx in max(0, ceil(subdivIdx(prevStop.position, minPos, maxPos, pad, n)).toInt())
                            ..min(n - 1, floor(subdivIdx(stop.position, minPos, maxPos, -pad, n)).toInt())) {
                        val position = (subdivIdx + 1) / (n + 1).toDouble() * (maxPos - minPos) + minPos
                        if (out.last().position == position)
                            continue
                        val t = ((position - prevStop.position) / (stop.position - prevStop.position)).toFloat()
                        val alpha = (1f - t) * prevStop.color.a + t * stop.color.a
                        val i = subdivIdx * 4
                        val color = Color4f(subdivArr[i], subdivArr[i + 1], subdivArr[i + 2], alpha, colorSpace)
                        out.add(Coat.Gradient.Stop(color, position))
                    }
                }
                if (!grid || stopIdx == 0 || stopIdx == stops.lastIndex)
                    out.add(stop.copy(color = stop.color.convert(colorSpace, clamp = true)))
            }
            return out
        }

        private fun subdivIdx(pos: Double, minPos: Double, maxPos: Double, pad: Double, n: Int): Double =
            ((pos - minPos) / (maxPos - minPos) + pad) * (n + 1) - 1.0

        private fun MaterializationBackend.materializeMissingMedia(width: Double, height: Double, tr: AffineTransform) {
            val shape = Rectangle2D.Double(0.0, 0.0, width, height).transformedBy(tr)
            val coat = Coat.Gradient(
                Point2D.Double(0.0, 0.0), Point2D.Double(0.0, height),
                listOf(
                    Coat.Gradient.Stop(Color4f.MISSING_MEDIA_TOP, 0.0),
                    Coat.Gradient.Stop(Color4f.MISSING_MEDIA_BOT, 1.0)
                )
            ).transform(tr)
            materializeShape(shape, coat, fill = true, dash = false, blurRadius = 0.0)
        }

        private fun gaussianStdDev(radius: Double) = radius / 2.0

    }


    interface Layer


    sealed interface Coat {

        class Plain(val color: Color4f) : Coat

        class Gradient(
            val point1: Point2D,
            val point2: Point2D,
            stops: List<Stop>,
            val interpolation: Canvas.GradientInterpolation = Canvas.GradientInterpolation.OKLAB
        ) : Coat {

            data class Stop(val color: Color4f, val position: Double)

            val stops: List<Stop> = stops.sortedBy { it.position }

            init {
                require(stops.size >= 2)
                for (idx in 1..<stops.size)
                    require(stops[idx - 1].position <= stops[idx].position)
            }

        }

    }


    interface Text {

        val bounds: Rectangle2D
        val outline: Shape
        val glyphCount: Int
        fun getGlyph(glyphIdx: Int): Int
        val string: String
        val fontCase: Font.Case

        // These accessors are for manually reproducing "outline" from the font's raw glyphs. You need to position each
        // glyph at the position given by the getManualGlyphPosition?() methods. Afterward, you apply "manualTransform"
        // to the entire structure.
        fun getManualGlyphPositionX(glyphIdx: Int): Double
        fun getManualGlyphPositionY(glyphIdx: Int): Double
        val manualTransform: AffineTransform

    }


    /** @throws Exception */
    class EmbeddedPicture(
        val picture: Picture,
        width: Double? = null,
        height: Double? = null,
        cropLeft: Double = 0.0,
        cropRight: Double = 0.0,
        cropTop: Double = 0.0,
        cropBottom: Double = 0.0,
        cropBlankSpace: Boolean = false,
        flipH: Boolean = false,
        flipV: Boolean = false,
        rotation: Double = 0.0,
        val resamplingFilter: BitmapConverter.ResamplingFilter = BitmapConverter.ResamplingFilter.DEFAULT
    ) {

        val width: Double
        val height: Double
        val widthBeforeRotation: Double
        val heightBeforeRotation: Double
        val transform: AffineTransform get() = AffineTransform(field)
        val crop: Rectangle2D get() = field.clone() as Rectangle2D
        val isCropped: Boolean

        init {
            require(cropLeft >= 0.0 && cropRight >= 0.0 && cropTop >= 0.0 && cropBottom >= 0.0)

            val userCrop = computeCrop(picture, cropLeft, cropRight, cropTop, cropBottom)
            require(userCrop.width > 0.0 && userCrop.height > 0.0)
            crop = userCrop
            isCropped = userCrop.width != picture.width || userCrop.height != picture.height

            val blankCrop = (if (cropBlankSpace) picture.nonBlankBounds(userCrop) else null)
                ?: Rectangle2D.Double(0.0, 0.0, userCrop.width, userCrop.height)

            // Note: Even though the Canvas would align raster pictures with the pixel grid anyway, it is a good idea to
            // already round the embedded size now so that the layout code sees the same size as is later drawn.
            // In addition, this aligns the vector backends with the canvas backend when it comes to embedded size.
            val width = width?.let(::roundIfRaster)
            val height = height?.let(::roundIfRaster)
            val w = width ?: roundIfRaster(blankCrop.width * if (height != null) height / blankCrop.height else 1.0)
            val h = height ?: roundIfRaster(blankCrop.height * if (width != null) width / blankCrop.width else 1.0)
            require(w > 0.0 && h > 0.0)
            widthBeforeRotation = w
            heightBeforeRotation = h

            val rotTransform = when {
                abs(rotation) % 360.0 !in 0.1..360.0 - 0.1 -> null
                abs(rotation) % 90.0 !in 0.1..90.0 - 0.1 ->
                    AffineTransform.getQuadrantRotateInstance(rotation.roundToInt() / 90, w / 2, h / 2)
                else ->
                    AffineTransform.getRotateInstance(Math.toRadians(rotation.mod(360.0)), w / 2, h / 2)
            }
            val transform = AffineTransform().apply {
                if (rotTransform != null)
                    concatenate(rotTransform)
                if (flipH) {
                    translate(w, 0.0)
                    scale(-1.0, 1.0)
                }
                if (flipV) {
                    translate(0.0, h)
                    scale(1.0, -1.0)
                }
                scale(w / blankCrop.width, h / blankCrop.height)
                translate(-blankCrop.x, -blankCrop.y)
            }
            var aabb: Rectangle2D? = null
            if (cropBlankSpace && rotTransform != null && rotTransform.type and AffineTransform.TYPE_GENERAL_ROTATION != 0)
                aabb = picture.nonBlankBounds(userCrop, transform)
            if (aabb == null)
                aabb = Rectangle2D.Double(0.0, 0.0, w, h).transformedBy(rotTransform).bounds2D
            transform.preConcatenate(AffineTransform.getTranslateInstance(-aabb.x, -aabb.y))
            this.width = aabb.width
            this.height = aabb.height
            this.transform = transform
        }

        private fun roundIfRaster(size: Double) = roundIfRaster(picture, size)

        companion object {

            fun computeCrop(
                picture: Picture, cropLeft: Double, cropRight: Double, cropTop: Double, cropBottom: Double
            ): Rectangle2D {
                val cropLeft = roundIfRaster(picture, cropLeft)
                val cropRight = roundIfRaster(picture, cropRight)
                val cropTop = roundIfRaster(picture, cropTop)
                val cropBottom = roundIfRaster(picture, cropBottom)
                return Rectangle2D.Double(
                    cropLeft, cropTop, picture.width - cropLeft - cropRight, picture.height - cropTop - cropBottom
                )
            }

            private fun roundIfRaster(picture: Picture, size: Double) =
                if (picture is Picture.Raster) round(size) else size

        }

    }


    /**
     * @throws IllegalArgumentException
     * @throws IllegalStateException
     */
    class EmbeddedTape(
        /** Note: Accessing the metadata of this tape is guaranteed to not throw exceptions. */
        val tape: Tape,
        width: Int? = null,
        height: Int? = null,
        val cropLeft: Int = 0,
        val cropRight: Int = 0,
        cropTop: Int = 0,
        val cropBottom: Int = 0,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        rotation: Int = 0,
        val resamplingFilter: BitmapConverter.ResamplingFilter = BitmapConverter.ResamplingFilter.DEFAULT,
        val leftMarginFrames: Int = 0,
        val rightMarginFrames: Int = 0,
        val fadeInFrames: Int = 0,
        val fadeInTransition: Transition = Transition.LINEAR,
        val fadeOutFrames: Int = 0,
        val fadeOutTransition: Transition = Transition.LINEAR,
        val range: OpenEndRange<Timecode> = tape.availableRange,
        val loop: Boolean = false,
        val align: Align = Align.START
    ) {

        enum class Align { START, MIDDLE, END }

        val resolution: Resolution
        val resolutionBeforeRotation: Resolution
        val crop: Rectangle get() = Rectangle(field)
        val cropTop: Int
        val rotation = roundingDiv(rotation.mod(360), 90) * 90

        init {
            tape.loadMetadata()

            require(cropLeft >= 0 && cropRight >= 0 && cropTop >= 0 && cropBottom >= 0)
            require(leftMarginFrames >= 0 && rightMarginFrames >= 0)
            require(fadeInFrames >= 0 && fadeOutFrames >= 0)
            if (tape.fileSeq) require(range.start is Timecode.Frames && range.endExclusive is Timecode.Frames) else
                require(range.start is Timecode.Clock && range.endExclusive is Timecode.Clock)
            val avail = tape.availableRange
            require(range.start.let { it >= avail.start && it < range.endExclusive })
            require(range.endExclusive.let { it > range.start && it <= avail.endExclusive })

            val crop = computeCrop(tape, cropLeft, cropRight, cropTop, cropBottom)
            require(crop.width > 0 && crop.height > 0)
            this.crop = crop
            this.cropTop = crop.y

            val w = width ?: if (height == null) crop.width else roundingDiv(crop.width * height, crop.height)
            // If the tape is interlaced, ensure that the embedded (= scaled) height is even. Once again, this avoids
            // edge cases in the DeferredVideo backend.
            val h = ec(tape, height ?: if (width == null) crop.height else roundingDiv(crop.height * width, crop.width))
            require(w > 0 && h > 0)
            resolution = if (this.rotation % 180 == 0) Resolution(w, h) else Resolution(h, w)
            resolutionBeforeRotation = Resolution(w, h)
        }

        fun withResolution(width: Int?, height: Int?) = EmbeddedTape(
            tape, width, height, cropLeft, cropRight, cropTop, cropBottom, flipH, flipV, rotation, resamplingFilter,
            leftMarginFrames, rightMarginFrames, fadeInFrames, fadeInTransition, fadeOutFrames, fadeOutTransition,
            range, loop, align
        )

        fun withAlign(align: Align) = EmbeddedTape(
            tape, resolutionBeforeRotation.widthPx, resolutionBeforeRotation.heightPx,
            cropLeft, cropRight, cropTop, cropBottom, flipH, flipV, rotation, resamplingFilter,
            leftMarginFrames, rightMarginFrames, fadeInFrames, fadeInTransition, fadeOutFrames, fadeOutTransition,
            range, loop, align
        )

        companion object {

            /** @throws IllegalStateException */
            fun computeCrop(tape: Tape, cropLeft: Int, cropRight: Int, cropTop: Int, cropBottom: Int): Rectangle {
                val (tapeWidth, tapeHeight) = tape.spec.resolution
                val cropWidth = tapeWidth - cropLeft - cropRight

                // If the tape is interlaced, preserve the Bitmap.Scan and even tape height. This avoids a lot of edge
                // cases that could otherwise arise in the DeferredVideo backend.
                val cropTop = ec(tape, cropTop)
                val cropHeight = ef(tape, tapeHeight - cropTop - cropBottom)

                return Rectangle(cropLeft, cropTop, cropWidth, cropHeight)
            }

            // Floors/ceils n to the next even number of the tape is interlaced.
            private fun ef(tape: Tape, n: Int) = if (tape.spec.scan == Bitmap.Scan.PROGRESSIVE) n else n / 2 * 2
            private fun ec(tape: Tape, n: Int) = if (tape.spec.scan == Bitmap.Scan.PROGRESSIVE) n else (n + 1) / 2 * 2

        }

    }


    sealed interface PermitTapePreviews {
        val usedPreviewThumbnails: Boolean
        fun loadFullThumbnailsAndThen(action: Runnable)

        companion object {
            operator fun invoke(): PermitTapePreviews = PermitTapePreviewsImpl()
        }
    }

    private class PermitTapePreviewsImpl : PermitTapePreviews {
        val fullThumbnailLoaders = mutableListOf<() -> CompletableFuture<*>>()
        override val usedPreviewThumbnails: Boolean get() = fullThumbnailLoaders.isNotEmpty()
        override fun loadFullThumbnailsAndThen(action: Runnable) {
            CompletableFuture.allOf(*fullThumbnailLoaders.map { it() }.toTypedArray()).thenRun(action)
        }
    }


    private sealed interface Instruction {

        fun materialize(
            backend: MaterializationBackend,
            x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
        )

        class DrawDeferredImageLayer(
            val x: Double, val y: Y, val universeScaling: Double, val elasticScaling: Double,
            val image: DeferredImage, val layer: Layer
        ) : Instruction {
            override fun materialize(
                backend: MaterializationBackend,
                x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
            ) {
                val x = x + universeScaling * this.x
                val y = y + universeScaling * this.y.resolve(elasticScaling)
                val universeScaling = universeScaling * this.universeScaling
                val elasticScaling = elasticScaling * this.elasticScaling
                for (insn in image.instructions.getOrDefault(layer, emptyList()))
                    insn.materialize(backend, x, y, universeScaling, elasticScaling, culling)
            }
        }

        class DrawShape(
            val x: Double, val y: Y, val shape: Shape, val coat: Coat, val fill: Boolean, val blurRadius: Double
        ) : Instruction {
            private val bounds = shape.bounds2D

            override fun materialize(
                backend: MaterializationBackend,
                x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
            ) {
                val x = x + universeScaling * this.x
                val y = y + universeScaling * this.y.resolve(elasticScaling)
                val blurRadius = universeScaling * this.blurRadius
                // It would be a bit complicated to exactly determine which pixels are affected after the blur, so
                // instead, we just add a safeguard buffer to better be sure that not a single blurred pixel is
                // accidentally culled.
                val safeBlurRadius = if (blurRadius == 0.0) 0.0 else blurRadius + 4.0
                if (culling == null ||
                    culling.intersects(
                        x + universeScaling * bounds.x - safeBlurRadius,
                        y + universeScaling * bounds.y - safeBlurRadius,
                        universeScaling * bounds.width + 2 * safeBlurRadius,
                        universeScaling * bounds.height + 2 * safeBlurRadius
                    )
                ) {
                    // We first transform the shape and then draw it without scaling the canvas. This simplifies code in
                    // the SVG and PDF backends, and is also required for snapping hairlines to pixels.
                    val tx = AffineTransform().apply { translate(x, y); scale(universeScaling) }
                    backend.materializeShape(
                        shape.transformedBy(tx), coat.transform(tx), fill, dash = false, blurRadius
                    )
                }
            }
        }

        class DrawLine(
            val x1: Double, val y1: Y, val x2: Double, val y2: Y, val color: Color4f, val dash: Boolean
        ) : Instruction {
            override fun materialize(
                backend: MaterializationBackend,
                x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
            ) {
                val x1 = x + universeScaling * this.x1
                val y1 = y + universeScaling * this.y1.resolve(elasticScaling)
                val x2 = x + universeScaling * this.x2
                val y2 = y + universeScaling * this.y2.resolve(elasticScaling)
                if (culling == null || culling.intersectsLine(x1, y1, x2, y2))
                    backend.materializeShape(
                        Line2D.Double(x1, y1, x2, y2), Coat.Plain(color), fill = false, dash, blurRadius = 0.0
                    )
            }
        }

        class DrawRect(
            val x: Double, val y: Y, val width: Double, val height: Y, val color: Color4f, val fill: Boolean
        ) : Instruction {
            override fun materialize(
                backend: MaterializationBackend,
                x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
            ) {
                val x = x + universeScaling * this.x
                val y = y + universeScaling * this.y.resolve(elasticScaling)
                val w = universeScaling * this.width
                val h = universeScaling * this.height.resolve(elasticScaling)
                if (culling == null ||
                    // Empty rectangles can occur as guides when cells have 0 width, and we want to draw them anyway!
                    // culling.intersect() would throw them out, so instead, we use our own conditions.
                    x + w > culling.minX && y + h > culling.minY && x < culling.maxX && y < culling.maxY
                )
                    backend.materializeShape(
                        Rectangle2D.Double(x, y, w, h), Coat.Plain(color), fill, dash = false, blurRadius = 0.0
                    )
            }
        }

        class DrawText(
            val x: Double, val yBaseline: Y, val text: Text, val coat: Coat
        ) : Instruction {
            override fun materialize(
                backend: MaterializationBackend,
                x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
            ) {
                val x = x + universeScaling * this.x
                val yBaseline = y + universeScaling * this.yBaseline.resolve(elasticScaling)
                if (culling == null ||
                    culling.intersects(
                        x + universeScaling * text.bounds.x,
                        yBaseline + universeScaling * text.bounds.y,
                        universeScaling * text.bounds.width,
                        universeScaling * text.bounds.height
                    )
                )
                    backend.materializeText(x, yBaseline, universeScaling, text, coat)
            }
        }

        class DrawEmbeddedPicture(
            val x: Double, val y: Y, val embeddedPic: EmbeddedPicture
        ) : Instruction {
            override fun materialize(
                backend: MaterializationBackend,
                x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
            ) {
                val x = x + universeScaling * this.x
                val y = y + universeScaling * this.y.resolve(elasticScaling)
                if (culling == null ||
                    culling.intersects(x, y, universeScaling * embeddedPic.width, universeScaling * embeddedPic.height)
                )
                    backend.materializeEmbeddedPicture(x, y, universeScaling, embeddedPic)
            }
        }

        class DrawEmbeddedTape(
            val x: Double, val y: Y, val embeddedTape: EmbeddedTape,
        ) : Instruction {
            override fun materialize(
                backend: MaterializationBackend,
                x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?
            ) {
                val x = x + universeScaling * this.x
                val y = y + universeScaling * this.y.resolve(elasticScaling)
                val (w, h) = embeddedTape.resolution
                if (culling == null || culling.intersects(x, y, universeScaling * w, universeScaling * h))
                    backend.materializeEmbeddedTape(x, y, universeScaling, embeddedTape)
            }
        }

    }


    private interface MaterializationBackend {

        // The default implementations skip materialization.
        fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {}
        fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {}
        fun materializeEmbeddedPicture(x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture) {}

        fun materializeEmbeddedTape(x: Double, y: Double, scaling: Double, embeddedTape: EmbeddedTape)

    }


    /** Materializes tapes by rendering the tape's thumbnail or a "missing media" placeholder. */
    private abstract class TapeThumbnailBackend(
        private val permitTapePreviews: PermitTapePreviewsImpl?,
        private val tolerateErroneousTapes: Boolean
    ) : MaterializationBackend {

        override fun materializeEmbeddedTape(x: Double, y: Double, scaling: Double, embeddedTape: EmbeddedTape) {
            var thumbnail: Picture.Raster? = null
            var preview = false
            try {
                val tape = embeddedTape.tape
                val timecode = embeddedTape.range.start
                if (permitTapePreviews == null)
                    thumbnail = tape.getCachedFrame(timecode).get()
                else {
                    thumbnail = tape.getFrameIfCached(timecode)
                    if (thumbnail == null) {
                        thumbnail = tape.getPreviewFrame(timecode).get()
                        preview = true
                        permitTapePreviews.fullThumbnailLoaders.add { tape.getCachedFrame(timecode) }
                    }
                }
            } catch (e: Exception) {
                if (!tolerateErroneousTapes)
                    throw e
            }

            val (w, h) = embeddedTape.resolution
            if (thumbnail != null) {
                val (picW, picH) = embeddedTape.resolutionBeforeRotation
                val tapeRes = embeddedTape.tape.spec.resolution
                val cropMulX = thumbnail.width / tapeRes.widthPx
                val cropMulY = thumbnail.height / tapeRes.heightPx
                val embeddedThumbnail = EmbeddedPicture(
                    thumbnail, picW.toDouble(), picH.toDouble(),
                    floor(cropMulX * embeddedTape.cropLeft), floor(cropMulX * embeddedTape.cropRight),
                    floor(cropMulY * embeddedTape.cropTop), floor(cropMulY * embeddedTape.cropBottom),
                    false, embeddedTape.flipH, embeddedTape.flipV, embeddedTape.rotation.toDouble(),
                    // Supplying NEAREST_NEIGHBOR achieves a "pixelated preview" effect and thereby communicates that
                    // the thumbnail is just a preview, in addition to the preview indicator text.
                    if (preview) NEAREST_NEIGHBOR else embeddedTape.resamplingFilter
                )
                materializeEmbeddedPicture(x, y, scaling, embeddedThumbnail)

                if (preview) {
                    val previewIndicator = Tape.previewIndicator(x, y, w * scaling, h * scaling)
                    val coat = Coat.Plain(Color4f.TAPE_PREVIEW)
                    materializeShape(previewIndicator, coat, fill = true, dash = false, blurRadius = 0.0)
                }
            } else
                materializeMissingMedia(w * scaling, h * scaling, AffineTransform.getTranslateInstance(x, y))
        }

    }


    private class CanvasBackend(
        private val canvas: Canvas,
        private val cachePictures: Boolean,
        permitTapePreviews: PermitTapePreviewsImpl?,
        private val tolerateErroneousMedia: Boolean
    ) : TapeThumbnailBackend(permitTapePreviews, tolerateErroneousMedia) {

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {
            if (fill)
                canvas.fillShape(shape, coat.toShader(), blurSigma = gaussianStdDev(blurRadius))
            else {
                val dashPattern = if (dash) floatArrayOf(4f, 8f) else null
                // Note: A stroke width of 0f makes Skia draw hairlines, which we desire for our layout guides.
                val stroke = BasicStroke(0f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f, dashPattern, 0f)
                // Snap the shape's coordinates to the nearest pixel center. This makes our hairlines very crisp.
                val pi = shape.getPathIterator(null)
                val s = Path2D.Double(pi.windingRule)
                val c = DoubleArray(6)
                while (!pi.isDone) {
                    when (pi.currentSegment(c)) {
                        PathIterator.SEG_MOVETO -> s.moveTo(snap(c[0]), snap(c[1]))
                        PathIterator.SEG_LINETO -> s.lineTo(snap(c[0]), snap(c[1]))
                        PathIterator.SEG_QUADTO -> s.quadTo(snap(c[0]), snap(c[1]), snap(c[2]), snap(c[3]))
                        PathIterator.SEG_CUBICTO ->
                            s.curveTo(snap(c[0]), snap(c[1]), snap(c[2]), snap(c[3]), snap(c[4]), snap(c[5]))
                        PathIterator.SEG_CLOSE -> s.closePath()
                    }
                    pi.next()
                }
                canvas.strokeShape(s, stroke, coat.toShader(), blurSigma = gaussianStdDev(blurRadius))
            }
        }

        private fun snap(coordinate: Double) = ceil(coordinate) - 0.5

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
            // We render the text by first converting the string to a path via FormattedString and then
            // filling that path. This has the following vital advantages:
            //   - We can render using Skia while still using AWT's excellent text layout capabilities.
            //   - Native text rendering usually applies hinting, which aligns each glyph at pixel
            //     boundaries. To achieve this, glyphs are slightly shifted to the left or right. This
            //     leads to inconsistent glyph spacing, which is acceptable for desktop purposes in
            //     exchange for higher readability, but not acceptable in a movie context. By converting
            //     the text layout to a path and then filling that path, we avoid calling the native text
            //     renderer and instead call the regular vector graphics renderer, which renders the glyphs
            //     at the exact positions where the text layouter has put them, without applying the
            //     counterproductive glyph shifting.
            //   - Vector-based means of imaging like SVG exactly match the raster-based means.
            // For these advantages, we put up with the following disadvantages:
            //   - Since the glyphs are no longer aligned at pixel boundaries, heavier antialiasing kicks
            //     in, leading to the rendered text sometimes appearing more blurry. However, this is an
            //     inherent disadvantage of rendering text with perfect glyph spacing and is typically
            //     acceptable in a movie context.
            val transform = AffineTransform().apply {
                translate(x, yBaseline)
                scale(scaling)
            }
            canvas.fillShape(text.outline, coat.toShader(), transform = transform)
        }

        override fun materializeEmbeddedPicture(x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture) {
            val pic = embeddedPic.picture
            val transform = AffineTransform().apply {
                // If we cache rendered pictures, we want to reuse them as often as possible. By aligning
                // them with the pixel grid, they will always be reusable unless the scaling changes.
                if (cachePictures)
                    translate(round(x), round(y))
                else
                    translate(x, y)
                scale(scaling)
                concatenate(embeddedPic.transform)
            }
            try {
                pic.drawTo(canvas, embeddedPic.crop, transform, embeddedPic.resamplingFilter, cache = cachePictures)
            } catch (e: Exception) {
                if (!tolerateErroneousMedia)
                    throw e
                materializeMissingMedia(embeddedPic.crop.width, embeddedPic.crop.height, transform)
                return
            }
        }

    }


    // Note: SVG blending is always in sRGB and there's no way to change that, so this backend doesn't accept a color
    // space parameter. Technically, one could use SVG filters to at least blend in linear light, but that's very
    // convoluted and still doesn't give us general color space support.
    private class SVGBackend(
        private val svg: Element
    ) : TapeThumbnailBackend(permitTapePreviews = null, tolerateErroneousTapes = false) {

        private val doc get() = svg.ownerDocument

        private val defs by lazy {
            doc.createElementNS(SVG_NS_URI, "defs").also { svg.insertBefore(it, svg.firstChild) }
        }
        private val glyphPathIds = HashMap<GlyphKey, String?>()
        private val picElementIds = HashMap<Picture, String>()
        private var clipPathCtr = 0
        private var gradientCtr = 0
        private val gradientIds = HashMap<Pair<List<Coat.Gradient.Stop>, Canvas.GradientInterpolation>, String>()
        private val blurFilterIds = HashMap<Double, String>()

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {
            check(!dash) { "The SVG backend does not support dashing." }
            val path = makePath(shape) ?: return
            applyCoat(path, coat, fill)

            if (blurRadius > 0.0) {
                val blurFilterId = blurFilterIds.computeIfAbsent(blurRadius) {
                    val filter = doc.createElementNS(SVG_NS_URI, "filter")
                    val id = "blur${blurFilterIds.size + 1}"
                    filter.setAttribute("id", id)
                    filter.appendChild(doc.createElementNS(SVG_NS_URI, "feGaussianBlur").apply {
                        setAttribute("stdDeviation", F.format(gaussianStdDev(blurRadius)))
                    })
                    defs.appendChild(filter)
                    id
                }
                path.setAttribute("filter", "url(#$blurFilterId)")
            }

            svg.appendChild(path)
        }

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
            val placementTx = AffineTransform().apply {
                translate(x, yBaseline)
                scale(scaling)
            }

            when (coat) {
                // For plain coats, assemble the text by <use>-ing individual glyphs. This is done by the code below.
                is Coat.Plain -> {}
                // For gradient coats, assembly is not possible because each <use> establishes its own coordinate
                // context, and there's no way of viewing a group of <use> as a singular object and apply the gradient
                // over the entire thing. The best way out is to represent the text as a single path.
                is Coat.Gradient -> {
                    materializeShape(
                        text.outline.transformedBy(placementTx), coat.transform(placementTx),
                        fill = true, dash = false, blurRadius = 0.0
                    )
                    return
                }
            }

            val defFontSize = 12.0
            val defToUseScaling = text.fontCase.size / defFontSize

            val textTx = AffineTransform().apply {
                concatenate(placementTx)
                concatenate(text.manualTransform)
                scale(defToUseScaling)
            }

            val g = doc.createElementNS(SVG_NS_URI, "g")
            g.setAttribute("transform", transformAttr(textTx))

            val font = text.fontCase.font
            val defFontCase = text.fontCase.withSize(defFontSize)
            for (glyphIdx in 0..<text.glyphCount) {
                val glyph = text.getGlyph(glyphIdx)
                val use = doc.createElementNS(SVG_NS_URI, "use")
                val glyphKey = GlyphKey(font, defFontCase.variations, glyph)
                val glyphPathId = glyphPathIds.computeIfAbsent(glyphKey) {
                    val id = "glyph${glyphPathIds.size + 1}"
                    val glyphOutline = defFontCase.getGlyphOutline(glyph)
                    defs.appendChild((makePath(glyphOutline) ?: return@computeIfAbsent null).apply {
                        setAttribute("id", id)
                    })
                    id
                } ?: continue
                use.setAttributeNS(XLINK_NS_URI, "xlink:href", "#$glyphPathId")
                use.setAttribute("x", F.format(text.getManualGlyphPositionX(glyphIdx) / defToUseScaling))
                use.setAttribute("y", F.format(text.getManualGlyphPositionY(glyphIdx) / defToUseScaling))
                g.appendChild(use)
            }

            if (g.hasChildNodes()) {
                applyCoat(g, coat, fill = true)
                svg.appendChild(g)
            }
        }

        private fun applyCoat(coatedElement: Element, coat: Coat, fill: Boolean) {
            when (coat) {
                is Coat.Plain -> {
                    val prefix = if (fill) "fill" else {
                        coatedElement.setAttribute("fill", "none")
                        "stroke"
                    }
                    coatedElement.setAttribute(prefix, coat.color.toSRGBHexString())
                    if (coat.color.a != 1f)
                        coatedElement.setAttribute("$prefix-opacity", F.format(coat.color.a.toDouble()))
                }
                is Coat.Gradient -> {
                    val gradientId = "gradient${++gradientCtr}"
                    coatedElement.setAttribute(if (fill) "fill" else "stroke", "url(#$gradientId)")
                    val key = Pair(coat.stops, coat.interpolation)
                    defs.appendChild(makeLinearGradient(coat, gradientIds[key]).apply {
                        setAttribute("id", gradientId)
                    })
                    gradientIds.putIfAbsent(key, gradientId)
                }
            }
        }

        override fun materializeEmbeddedPicture(x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture) {
            val use = doc.createElementNS(SVG_NS_URI, "use")

            val picElementId = picElementIds.computeIfAbsent(embeddedPic.picture) {
                val id = "picture${picElementIds.size + 1}"
                defs.appendChild(makePictureElement(embeddedPic.picture, id))
                id
            }
            use.setAttributeNS(XLINK_NS_URI, "xlink:href", "#$picElementId")

            val tx = AffineTransform.getTranslateInstance(x, y).apply {
                scale(scaling)
                concatenate(embeddedPic.transform)
                translate(-embeddedPic.crop.x, -embeddedPic.crop.y)
            }
            use.setAttribute("transform", transformAttr(tx))

            if (embeddedPic.isCropped) {
                val clipPathId = "clip${++clipPathCtr}"
                use.setAttribute("clip-path", "url(#$clipPathId)")
                defs.appendChild(doc.createElementNS(SVG_NS_URI, "clipPath").apply {
                    setAttribute("id", clipPathId)
                    appendChild(makePath(embeddedPic.crop))
                })
            }

            svg.appendChild(use)
        }

        private fun makePath(shape: Shape): Element? = when (shape) {
            is Rectangle2D -> if (shape.isEmpty) null else doc.createElementNS(SVG_NS_URI, "rect").apply {
                setAttribute("x", F.format(shape.x))
                setAttribute("y", F.format(shape.y))
                setAttribute("width", F.format(shape.width))
                setAttribute("height", F.format(shape.height))
            }
            else -> {
                val d = StringBuilder()
                val pi = shape.getPathIterator(null)
                val coords = DoubleArray(6)
                while (!pi.isDone) {
                    when (pi.currentSegment(coords)) {
                        PathIterator.SEG_MOVETO ->
                            d.append(" M ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                        PathIterator.SEG_LINETO ->
                            d.append(" L ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                        PathIterator.SEG_QUADTO ->
                            d.append(" Q ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                                .append(" ").append(F.format(coords[2])).append(" ").append(F.format(coords[3]))
                        PathIterator.SEG_CUBICTO ->
                            d.append(" C ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                                .append(" ").append(F.format(coords[2])).append(" ").append(F.format(coords[3]))
                                .append(" ").append(F.format(coords[4])).append(" ").append(F.format(coords[5]))
                        PathIterator.SEG_CLOSE ->
                            d.append(" Z")
                    }
                    pi.next()
                }
                if (d.isEmpty()) null else
                    doc.createElementNS(SVG_NS_URI, "path").apply {
                        setAttribute("d", d.substring(1))
                        if (pi.windingRule == PathIterator.WIND_EVEN_ODD)
                            setAttribute("fill-rule", "evenodd")
                    }
            }
        }

        private fun makeLinearGradient(coat: Coat.Gradient, refStopsFromId: String?): Element {
            val linearGradient = doc.createElementNS(SVG_NS_URI, "linearGradient")
            linearGradient.setAttribute("gradientUnits", "userSpaceOnUse")
            linearGradient.setAttribute("x1", F.format(coat.point1.x))
            linearGradient.setAttribute("y1", F.format(coat.point1.y))
            linearGradient.setAttribute("x2", F.format(coat.point2.x))
            linearGradient.setAttribute("y2", F.format(coat.point2.y))
            if (refStopsFromId != null)
                linearGradient.setAttributeNS(XLINK_NS_URI, "xlink:href", "#$refStopsFromId")
            val stops = when (coat.interpolation) {
                Canvas.GradientInterpolation.SRGB -> if (refStopsFromId != null) null else coat.stops
                Canvas.GradientInterpolation.OKLAB -> {
                    linearGradient.setAttribute("color-interpolation", "linearRGB")
                    if (refStopsFromId != null) null else coat.clampAndSubdivide(ColorSpace.SRGB, grid = false)
                }
            }
            stops?.forEach { stop ->
                linearGradient.appendChild(doc.createElementNS(SVG_NS_URI, "stop").apply {
                    setAttribute("offset", F.format(stop.position))
                    setAttribute("stop-color", stop.color.toSRGBHexString())
                    if (stop.color.a != 1f)
                        setAttribute("stop-opacity", F.format(stop.color.a.toDouble()))
                })
            }
            return linearGradient
        }

        private fun makePictureElement(pic: Picture, picElementId: String): Element {
            val picElement = when (pic) {
                is Picture.Raster -> {
                    // Use sRGB for raster images embedded into the SVG.
                    val transparent = pic.bitmap.spec.representation.alpha != Bitmap.Alpha.OPAQUE
                    val png = BitmapWriter.PNG(Bitmap.PixelFormat.Family.RGB, transparent, ColorSpace.SRGB)
                        .convertAndWrite(pic.bitmap)
                    val data = Base64.getEncoder().encodeToString(png)
                    val image = doc.createElementNS(SVG_NS_URI, "image")
                    image.setAttributeNS(XLINK_NS_URI, "xlink:href", "data:image/png;base64,$data")
                    image
                }
                is Picture.SVG -> {
                    val picSVG = pic.import(doc)
                    // If the nested SVG has a viewBox, it must also specify its width and height, or else it vanishes.
                    picSVG.setAttribute("width", F.format(pic.width))
                    picSVG.setAttribute("height", F.format(pic.height))
                    // This attribute messes up our formatting and is deprecated anyway.
                    picSVG.removeAttributeNS(XML_NS_URI, "space")
                    // Mangle IDs to ensure they are unique to the picture.
                    val mangling = HashMap<String, String>()
                    picSVG.forEachNodeInSubtree(SHOW_ELEMENT) { elem ->
                        elem as Element
                        val id = elem.getAttribute("id")
                        if (id.isNotEmpty()) {
                            val mangledId = "$picElementId-$id"
                            mangling["#$id"] = "#$mangledId"
                            elem.setAttribute("id", mangledId)
                        }
                    }
                    picSVG.forEachNodeInSubtree(SHOW_ELEMENT) { node ->
                        val attrs = node.attributes
                        for (idx in 0..<attrs.length) {
                            val attr = attrs.item(idx) as Attr
                            val value = attr.value
                            var mangledValue = value
                            for ((old, new) in mangling)
                                mangledValue = mangledValue.replace(old, new)
                            if (value != mangledValue)
                                attr.value = mangledValue
                        }
                    }
                    picSVG
                }
                is Picture.PDF -> {
                    setNativeNumericLocaleToC()
                    val canvas = Canvas.forSVG(pic.width, pic.height)
                    pic.drawTo(canvas)
                    return makePictureElement(Picture.SVG.load(canvas.closeAndGetOutput()), picElementId)
                }
            }
            picElement.setAttribute("id", picElementId)
            return picElement
        }

        private fun transformAttr(tx: AffineTransform): String {
            val m00 = F.format(tx.scaleX)
            val m10 = F.format(tx.shearY)
            val m01 = F.format(tx.shearX)
            val m11 = F.format(tx.scaleY)
            val m02 = F.format(tx.translateX)
            val m12 = F.format(tx.translateY)
            return "matrix($m00 $m10 $m01 $m11 $m02 $m12)"
        }

        private data class GlyphKey(val font: Font, val variations: Set<Font.Variation>, val glyph: Int)

    }


    sealed interface PDFTracker : AutoCloseable {
        companion object {
            operator fun invoke(
                doc: PDDocument,
                masterColorSpace: ColorSpace,
                shrinkRasters: Boolean,
                jpegRasters: Boolean,
                rasterizeSVGs: Boolean
            ): PDFTracker = PDFTrackerImpl(doc, masterColorSpace, shrinkRasters, jpegRasters, rasterizeSVGs)
        }
    }

    private class PDFBackend(
        private val tracker: PDFTrackerImpl,
        private val page: PDPage,
        private val cs: PDPageContentStream
    ) : TapeThumbnailBackend(permitTapePreviews = null, tolerateErroneousTapes = false) {

        private val csHeight = page.mediaBox.height
        private var fontKeyCtr = 1

        init {
            page.cosObject.setItem(COSName.GROUP, PDTransparencyGroupAttributes().apply {
                cosObject.setItem(COSName.TYPE, COSName.GROUP)
                cosObject.setItem(COSName.CS, tracker.obtainICCBasedCS(tracker.masterColorSpace))
            })
        }

        private fun materializeShapeWithoutTransforming(shape: Shape, stroke: Boolean, fill: Boolean) {
            if (shape is Rectangle2D) {
                cs.addRect(shape.x.toFloat(), shape.y.toFloat(), shape.width.toFloat(), shape.height.toFloat())
                if (stroke) cs.stroke() else if (fill) cs.fill()
                return
            }

            val pi = shape.getPathIterator(null)
            val coords = FloatArray(6)
            while (!pi.isDone) {
                when (pi.currentSegment(coords)) {
                    PathIterator.SEG_MOVETO ->
                        if (coords.isFinite(end = 2))
                            cs.moveTo(coords[0], coords[1])
                    PathIterator.SEG_LINETO ->
                        if (coords.isFinite(end = 2))
                            cs.lineTo(coords[0], coords[1])
                    PathIterator.SEG_QUADTO ->
                        if (coords.isFinite(end = 4))
                            cs.curveTo1(coords[0], coords[1], coords[2], coords[3])
                    PathIterator.SEG_CUBICTO ->
                        if (coords.isFinite(end = 6))
                            cs.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5])
                    PathIterator.SEG_CLOSE ->
                        cs.closePath()
                }
                pi.next()
            }

            if (stroke) cs.stroke() else if (fill)
                if (pi.windingRule == PathIterator.WIND_EVEN_ODD) cs.fillEvenOdd() else cs.fill()
        }

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {
            check(!dash) { "The PDF backend does not support dashing." }
            if (blurRadius > 0.0) {
                check(fill) { "The PDF backend does not support blurring stroke shapes." }
                // We add this padding on every side to have enough room for the blur to spill into.
                val pad = floor(blurRadius).toInt()
                // Determine the bounds of the shape, and the whole and fractional parts of the shape's coordinates.
                val bounds = shape.bounds2D
                val xWhole = floor(bounds.x)
                val yWhole = floor(bounds.y)
                val xFrac = bounds.x - xWhole
                val yFrac = bounds.y - yWhole
                // Draw the shape onto a bitmap in a blurred fashion.
                val w = ceil(xFrac + bounds.width).toInt() + 2 * pad
                val h = ceil(yFrac + bounds.height).toInt() + 2 * pad
                val rep = Canvas.compatibleRepresentation(tracker.masterColorSpace)
                Bitmap.allocate(Bitmap.Spec(Resolution(w, h), rep)).use { bitmap ->
                    Canvas.forBitmap(bitmap.zero()).use { canvas ->
                        canvas.fillShape(
                            shape, coat.toShader(), blurSigma = gaussianStdDev(blurRadius),
                            transform = AffineTransform.getTranslateInstance(pad - xWhole, pad - yWhole)
                        )
                    }
                    // Place the bitmap in the PDF.
                    val embeddedPic = EmbeddedPicture(Picture.Raster.convert(bitmap))
                    materializeEmbeddedPicture(xWhole - pad, yWhole - pad, 1.0, embeddedPic)
                }
                return
            }
            cs.saveGraphicsState()
            setCoat(coat, fill, shape.bounds2D)
            cs.transform(Matrix().apply { translate(0f, csHeight); scale(1f, -1f) })
            materializeShapeWithoutTransforming(shape, !fill, fill)
            cs.restoreGraphicsState()
        }

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
            val fontRecorder = tracker.obtainFontRecorder(text.fontCase)
            val resourceKey = COSName.getPDFName("Font${fontKeyCtr++}")
            fontRecorder.pagesAndResourceKeys.add(Pair(page, resourceKey))

            // Add all Unicode codepoints from the text to the recorder.
            val usedCodepoints = fontRecorder.usedCodepoints
            val string = text.string
            var i = 0
            while (i < string.length) {
                val codepoint = string.codePointAt(i)
                usedCodepoints.add(codepoint)
                i += Character.charCount(codepoint)
            }

            cs.saveGraphicsState()
            cs.beginText()

            val xShifts = FloatArray(text.glyphCount - 1) { glyphIdx ->
                val actualWidth = text.fontCase.getGlyphAdvance(text.getGlyph(glyphIdx))
                val wantedWidth = text.getManualGlyphPositionX(glyphIdx + 1) - text.getManualGlyphPositionX(glyphIdx)
                // Convert to the special PDF text coordinates.
                ((actualWidth - wantedWidth) * 1000.0 / text.fontCase.size).toFloat()
            }

            val textBBox = Rectangle2D.Double(
                x + text.bounds.x * scaling,
                yBaseline + text.bounds.y * scaling,
                text.bounds.width * scaling,
                text.bounds.height * scaling
            )
            val textTx = AffineTransform().apply {
                translate(x, csHeight - yBaseline)
                scale(scaling)
                val t = text.manualTransform
                concatenate(AffineTransform(t.scaleX, -t.shearY, -t.shearX, t.scaleY, t.translateX, -t.translateY))
            }
            val coatTx = AffineTransform().apply {
                translate(x, yBaseline)
                scale(scaling)
            }

            // Build an array of glyphs that we want to show, and also continue populating the glyphSet.
            val usedGlyphs = fontRecorder.usedGlyphs
            val glyphs = IntArray(text.glyphCount) { glyphIdx -> text.getGlyph(glyphIdx).also(usedGlyphs::add) }

            appendCOSName(cs, resourceKey)
            appendRawCommands(cs, " ${F.format(text.fontCase.size)} ${OperatorName.SET_FONT_AND_SIZE}\n")
            setCoat(coat.transform(coatTx), fill = true, textBBox)
            cs.setTextMatrix(Matrix(textTx))
            cs.showGlyphsWithPositioning(glyphs, xShifts, bytesPerGlyph = 2 /* always true for TTF/OTF fonts */)

            cs.endText()
            cs.restoreGraphicsState()
        }

        override fun materializeEmbeddedPicture(x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture) {
            val pic = embeddedPic.picture
            val q = pic is Picture.Vector || embeddedPic.isCropped
            if (q)
                cs.saveGraphicsState()
            val crop = embeddedPic.crop.let { c -> Rectangle2D.Double(c.minX, pic.height - c.maxY, c.width, c.height) }
            val transform = AffineTransform().apply {
                translate(x, csHeight - y)
                scale(scaling)
                scale(1.0, -1.0)
                concatenate(embeddedPic.transform)
                scale(1.0, -1.0)
                translate(-crop.x, -crop.y - crop.height)
            }
            if (embeddedPic.isCropped) {
                materializeShapeWithoutTransforming(crop.transformedBy(transform), false, false)
                cs.clip()
            }
            when {
                pic is Picture.Raster || pic is Picture.SVG && tracker.rasterizeSVGs -> {
                    val filter = embeddedPic.resamplingFilter
                    transform.scale(pic.width, pic.height)
                    cs.drawImage(tracker.pdImages.computeIfAbsent(Pair(pic, filter)) {
                        PDImageXObject(tracker.doc)
                            .apply { if (pic is Picture.Raster) interpolate = filter != NEAREST_NEIGHBOR }
                    }, Matrix(transform))
                    val tr = embeddedPic.transform
                    val w = ceil(scaling * tr.scalingFactorX * pic.width).toInt()
                    val h = ceil(scaling * tr.scalingFactorY * pic.height).toInt()
                    tracker.pdImageResolutions.computeIfAbsent(Pair(pic, filter)) { mutableListOf() }
                        .add(Resolution(w, h))
                }
                pic is Picture.Vector -> {
                    cs.transform(Matrix(transform))
                    cs.drawForm(tracker.pdForms.computeIfAbsent(pic) {
                        when (pic) {
                            is Picture.SVG -> {
                                val canvas = Canvas.forPDF(pic.width, pic.height, ColorSpace.SRGB)
                                pic.drawTo(canvas)
                                Picture.PDF.load(canvas.closeAndGetOutput()).import(tracker.layerUtil).apply {
                                    // Set the transparency group's blending color space to sRGB.
                                    group.cosObject.setItem(COSName.CS, tracker.obtainICCBasedCS(ColorSpace.SRGB))
                                }
                            }
                            is Picture.PDF ->
                                pic.import(tracker.layerUtil)
                        }
                    })
                }
            }
            if (q)
                cs.restoreGraphicsState()
        }

        /** This function expects both the Coat and the bound box to be in global coordinates (but Y is not flipped). */
        private fun setCoat(coat: Coat, fill: Boolean, bbox: Rectangle2D) {
            when (coat) {
                is Coat.Plain -> {
                    val color = coat.color.convert(tracker.masterColorSpace, clamp = true)
                    val pdColor = PDColor(color.rgb(), tracker.obtainICCBasedCS(tracker.masterColorSpace))
                    if (fill) cs.setNonStrokingColor(pdColor) else cs.setStrokingColor(pdColor)
                    if (color.a != 1f) cs.setGraphicsStateParameters(makeExtGState(fill, color.a))
                }
                is Coat.Gradient -> {
                    // Notice that we do not cache the COS objects we create for gradients. That is because pattern
                    // coordinates are always global to the page irrespective of any user matrix, so we'd need a new
                    // pattern for every place where we want to use it anyway.
                    val a0 = coat.stops[0].color.a
                    if (a0 != 1f && coat.stops.all { it.color.a == a0 })
                        cs.setGraphicsStateParameters(makeExtGState(fill, a0))
                    else if (coat.stops.any { it.color.a != 1f }) {
                        // First construct a form XObject.
                        val bboxW = bbox.width.toFloat()
                        val bboxH = bbox.height.toFloat()
                        val bboxX = bbox.x.toFloat()
                        val bboxY = csHeight - bbox.y.toFloat() - bboxH
                        val pdTrGroupResources = PDResources()
                        val pdTrGroupAttrs = PDTransparencyGroupAttributes().apply {
                            cosObject.setItem(COSName.TYPE, COSName.GROUP)
                            cosObject.setItem(COSName.CS, COSName.DEVICEGRAY)
                        }
                        val pdTrGroup = PDTransparencyGroup(tracker.doc).apply {
                            formType = 1
                            bBox = PDRectangle(bboxX, bboxY, bboxW, bboxH)
                            resources = pdTrGroupResources
                            cosObject.setItem(COSName.GROUP, pdTrGroupAttrs)
                        }
                        // Paint the alpha gradient into the XObject.
                        val trGroupPatternName =
                            pdTrGroupResources.add(makeShadingPattern(coat, forAlpha = true))
                        PDFormContentStream(pdTrGroup).use { csTr ->
                            csTr.saveGraphicsState()
                            csTr.setPattern(trGroupPatternName, stroking = false)
                            csTr.addRect(bboxX, bboxY, bboxW, bboxH)
                            csTr.fill()
                            csTr.restoreGraphicsState()
                        }
                        // Finally, construct an alpha mask ("soft mask") that uses the XObject we just created.
                        val pdSoftMask = PDSoftMask(COSDictionary()).apply {
                            cosObject.setItem(COSName.TYPE, COSName.MASK)
                            cosObject.setItem(COSName.S, COSName.LUMINOSITY)
                            cosObject.setItem(COSName.G, pdTrGroup)
                        }
                        // Now apply that alpha mask.
                        val extGState = PDExtendedGraphicsState().apply {
                            alphaSourceFlag = false
                            cosObject.setItem(COSName.SMASK, pdSoftMask)
                        }
                        cs.setGraphicsStateParameters(extGState)
                    }

                    val patternName = page.resources.add(makeShadingPattern(coat, forAlpha = false))
                    cs.setPattern(patternName, stroking = !fill)
                }
            }
        }

        private fun makeExtGState(fill: Boolean, alpha: Float) =
            tracker.extGStates.computeIfAbsent(PDFTrackerImpl.ExtGStateKey(fill, alpha)) {
                PDExtendedGraphicsState().apply {
                    if (fill) nonStrokingAlphaConstant = alpha else strokingAlphaConstant = alpha
                }
            }

        private fun makeShadingPattern(coat: Coat.Gradient, forAlpha: Boolean): PDShadingPattern {
            val interpolationTransfer = when (coat.interpolation) {
                Canvas.GradientInterpolation.SRGB -> ColorSpace.Transfer.SRGB
                Canvas.GradientInterpolation.OKLAB -> ColorSpace.Transfer.LINEAR
            }
            val interpolationColorSpace = ColorSpace.of(tracker.masterColorSpace.primaries, interpolationTransfer)

            val key = Pair(coat.stops, if (forAlpha) null else coat.interpolation)
            val pdFunc = tracker.gradientFuncs.computeIfAbsent(key) {
                val minPos = coat.stops.first().position
                val maxPos = coat.stops.last().position
                val domain = COSArray(listOf(COSFloat(minPos.toFloat()), COSFloat(maxPos.toFloat())))
                if ((forAlpha || coat.interpolation == Canvas.GradientInterpolation.SRGB) &&
                    coat.stops.size == 2 && minPos == 0.0 && maxPos == 1.0
                )
                    PDFunctionType2(COSDictionary().apply {
                        setInt(COSName.FUNCTION_TYPE, 2)
                        setItem(COSName.DOMAIN, domain)
                        setInt(COSName.N, 1)
                        for ((idx, stop) in coat.stops.withIndex()) {
                            val arr = COSArray()
                            if (forAlpha)
                                arr.add(COSFloat(stop.color.a))
                            else {
                                val c = stop.color.convert(interpolationColorSpace, clamp = true)
                                arr.add(COSFloat(c.r))
                                arr.add(COSFloat(c.g))
                                arr.add(COSFloat(c.b))
                            }
                            setItem(if (idx == 0) COSName.C0 else COSName.C1, arr)
                        }
                    })
                else {
                    val stops = coat.clampAndSubdivide(interpolationColorSpace, grid = true)
                    val buf = ByteBuffer.allocate(stops.size * 2 * if (forAlpha) 1 else 3)
                    for (stop in stops)
                        if (forAlpha)
                            buf.putShort(encodeAsShort(stop.color.a))
                        else {
                            val c = stop.color.convert(interpolationColorSpace, clamp = true)
                            buf.putShort(encodeAsShort(c.r))
                            buf.putShort(encodeAsShort(c.g))
                            buf.putShort(encodeAsShort(c.b))
                        }
                    PDFunctionType0(COSStream().apply {
                        setInt(COSName.FUNCTION_TYPE, 0)
                        setItem(COSName.DOMAIN, domain)
                        setItem(COSName.RANGE, COSArray(List(if (forAlpha) 2 else 6) { COSFloat((it % 2).toFloat()) }))
                        setItem(COSName.SIZE, COSArray(listOf(COSInteger.get(stops.size.toLong()))))
                        // Note: We can't use 32bps because PDFBox itself can't read that, and so may other PDF viewers.
                        setItem(COSName.BITS_PER_SAMPLE, COSInteger.get(16.toLong()))
                        createOutputStream().use { it.write(buf.array()) }
                    })
                }
            }

            val cosCoords = COSArray().apply {
                add(COSFloat(coat.point1.x.toFloat()))
                add(COSFloat(csHeight - coat.point1.y.toFloat()))
                add(COSFloat(coat.point2.x.toFloat()))
                add(COSFloat(csHeight - coat.point2.y.toFloat()))
            }
            val pdShading = PDShadingType2(COSDictionary()).apply {
                shadingType = PDShading.SHADING_TYPE2
                extend = COSArray().apply { add(COSBoolean.TRUE); add(COSBoolean.TRUE) }
                colorSpace = if (forAlpha) PDDeviceGray.INSTANCE else tracker.obtainICCBasedCS(interpolationColorSpace)
                coords = cosCoords
                function = pdFunc
            }
            return PDShadingPattern().apply {
                patternType = 2
                shading = pdShading
            }
        }

        private fun encodeAsShort(x: Float) = Math.round(x.coerceIn(0f, 1f) * 65535f).toShort()

        private fun Any /* PD(Page|Form)ContentStream */.setPattern(patternName: COSName, stroking: Boolean) {
            val opCS = if (stroking) OperatorName.STROKING_COLORSPACE else OperatorName.NON_STROKING_COLORSPACE
            val opSCN = if (stroking) OperatorName.STROKING_COLOR_N else OperatorName.NON_STROKING_COLOR_N
            appendRawCommands(this, "/Pattern $opCS\n")
            appendCOSName(this, patternName)
            appendRawCommands(this, " $opSCN\n")
        }

    }

    private class PDFTrackerImpl(
        val doc: PDDocument,
        val masterColorSpace: ColorSpace,
        private val shrinkRasters: Boolean,
        private val jpegRasters: Boolean,
        val rasterizeSVGs: Boolean
    ) : PDFTracker {

        data class ExtGStateKey(private val fill: Boolean, private val alpha: Float)

        class FontRecorder {
            val pagesAndResourceKeys = mutableListOf<Pair<PDPage, COSName>>()
            val usedCodepoints = HashSet<Int>()
            val usedGlyphs = hashSetOf(0)
        }

        val extGStates = HashMap<ExtGStateKey, PDExtendedGraphicsState>()
        val gradientFuncs = HashMap<Pair<List<Coat.Gradient.Stop>, Canvas.GradientInterpolation?>, PDFunction>()
        val pdImages = HashMap<Pair<Picture, BitmapConverter.ResamplingFilter>, PDImageXObject>()
        val pdImageResolutions = HashMap<Pair<Picture, BitmapConverter.ResamplingFilter>, MutableList<Resolution>>()
        val pdForms = HashMap<Picture.Vector, PDFormXObject>()
        val layerUtil by lazy { LayerUtility(doc) }
        private val pdColorSpaces = HashMap<ColorSpace, PDICCBased>()
        private val fontRecorders = HashMap<Pair<Font, Set<Font.Variation>>, FontRecorder>()

        fun obtainICCBasedCS(colorSpace: ColorSpace) = pdColorSpaces.computeIfAbsent(colorSpace) {
            makePDICCBased(doc, 3, ICCProfile.of(colorSpace).bytes)
        }

        fun obtainFontRecorder(case: Font.Case) = fontRecorders.computeIfAbsent(Pair(case.font, case.variations)) {
            FontRecorder()
        }

        override fun close() {
            for ((key, rec) in fontRecorders) {
                val (font, variations) = key
                endFont(font, variations, rec)
            }
            for ((key, pdImage) in pdImages) {
                val (pic, resamplingFilter) = key
                endImage(pic, pdImage, pdImageResolutions.getValue(key), resamplingFilter)
            }
        }

        private fun endFont(font: Font, variations: Set<Font.Variation>, rec: FontRecorder) {
            val subsettedFont = font.staticNonShapeableSubset(rec.usedCodepoints, rec.usedGlyphs, variations)
                ?: if (variations.all { variation ->
                        val axis = font.axes.find { axis -> axis.tag == variation.tag }
                        axis == null || abs(variation.value - axis.defaultValue) < 0.001
                    }
                ) {
                    LOGGER.warn("Cannot subset the font '{}' for PDF embedding; will embed it wholly.", font.name)
                    font
                } else
                    throw RuntimeException("Cannot instantiate the variable font '${font.name}' for PDF embedding.")
            val ttf = OTFParser().parse(RandomAccessReadBuffer(subsettedFont.toByteArray()))
            val pdFont = PDType0Font.load(doc, ttf, false)
            for ((page, resourceKey) in rec.pagesAndResourceKeys) {
                val res = page.resources.cosObject
                (res.getCOSDictionary(COSName.FONT) ?: COSDictionary().also { res.setItem(COSName.FONT, it) })
                    .setItem(resourceKey, pdFont)
            }
        }

        private fun endImage(
            pic: Picture,
            pdImage: PDImageXObject,
            resolutions: List<Resolution>,
            resamplingFilter: BitmapConverter.ResamplingFilter
        ) {
            // If the picture shall shrink or is an SVG, find the max res, which is 2x the largest embedded res (so that
            // there's still enough detail when zooming in). However, if the original picture is actually smaller than
            // that, don't blow it up. Notice that reducing the resolution asymmetrically is fine because PDF squeezes
            // all images into a 1x1 square anyway.
            val maxEmbRes = resolutions.reduce { (w1, h1), (w2, h2) -> Resolution(max(w1, w2), max(h1, h2)) }
            val maxRes = Resolution(maxEmbRes.widthPx * 2, maxEmbRes.heightPx * 2)
            // Obtain the storage color space and planar float bitmap.
            val colorSpace: ColorSpace
            var bitmap: Bitmap
            when (pic) {
                // If the picture is a raster, directly use the raster or shrink it if necessary.
                is Picture.Raster -> {
                    colorSpace = masterColorSpace
                    bitmap = pic.bitmap
                    if (shrinkRasters) {
                        val (picRes, rep) = pic.bitmap.spec
                        val res = Resolution(min(maxRes.widthPx, picRes.widthPx), min(maxRes.heightPx, picRes.heightPx))
                        // If the maximum resolution is actually lower than the original one, shrink the bitmap.
                        if (res != picRes) {
                            bitmap = Bitmap.allocate(Bitmap.Spec(res, rep))
                            BitmapConverter.convert(pic.bitmap, bitmap, resamplingFilter = resamplingFilter)
                        }
                    }
                }
                // If the picture is an SVG, rasterize it.
                is Picture.SVG -> {
                    colorSpace = ColorSpace.SRGB
                    val tr = AffineTransform.getScaleInstance(maxRes.widthPx / pic.width, maxRes.heightPx / pic.height)
                    Bitmap.allocate(Bitmap.Spec(maxRes, Canvas.compatibleRepresentation(colorSpace))).use { canvasBmp ->
                        Canvas.forBitmap(canvasBmp.zero()).use { canvas -> pic.drawTo(canvas, transform = tr) }
                        bitmap = Picture.Raster.convert(canvasBmp).bitmap
                    }
                }
                is Picture.PDF -> throw IllegalStateException()
            }
            // Now split the color and alpha components into a color image...
            populateImageXObject(pdImage, bitmap, colorSpace, obtainICCBasedCS(colorSpace))
            // ... and a grayscale alpha image. We can use alphaPlaneView() because the bitmap is planar.
            if (bitmap.spec.representation.alpha != Bitmap.Alpha.OPAQUE) {
                val pdAlphaImage = PDImageXObject(doc)
                bitmap.alphaPlaneView().use { alphaBitmap ->
                    val cs = alphaBitmap.spec.representation.colorSpace
                    populateImageXObject(pdAlphaImage, alphaBitmap, cs, PDDeviceGray.INSTANCE)
                }
                pdImage.cosObject.setItem(COSName.SMASK, pdAlphaImage)
            }
            // If we have allocated an intermediate bitmap, free it again.
            if (pic !is Picture.Raster || bitmap != pic.bitmap)
                bitmap.close()
        }

        private fun populateImageXObject(pdImage: PDImageXObject, bitmap: Bitmap, cs: ColorSpace, pdCS: PDColorSpace) {
            val (res, rep) = bitmap.spec
            val isGray = rep.pixelFormat.family == Bitmap.PixelFormat.Family.GRAY
            pdImage.apply {
                bitsPerComponent = 8
                width = res.widthPx
                height = res.heightPx
                colorSpace = pdCS
            }
            val stream = pdImage.cosObject
            if (jpegRasters) {
                val jpeg = BitmapWriter.JPEG(rep.pixelFormat.family, cs).convertAndWrite(bitmap)
                stream.setItem(COSName.FILTER, COSName.DCT_DECODE)
                stream.createRawOutputStream().use { it.write(jpeg) }
            } else {
                val pxFmt = Bitmap.PixelFormat.of(if (isGray) AV_PIX_FMT_GRAY8 else AV_PIX_FMT_RGB24)
                val byteBmp = Bitmap.allocate(Bitmap.Spec(res, Bitmap.Representation(pxFmt, cs)))
                BitmapConverter.convert(bitmap, byteBmp)
                stream.setItem(COSName.FILTER, COSName.FLATE_DECODE)
                if (!isGray) {
                    stream.setItem(COSName.DECODE_PARMS, COSDictionary().apply {
                        setItem(COSName.PREDICTOR, COSInteger.get(15L))
                        setItem(COSName.COLORS, COSInteger.get(3L))
                        setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(8L))
                        setItem(COSName.COLUMNS, COSInteger.get(res.widthPx.toLong()))
                    })
                    stream.createOutputStream().use { encodeRGB24Losslessly(byteBmp, it) }
                } else
                    stream.createOutputStream().use { it.write(byteBmp.getB(res.widthPx)) }
            }
        }

        // Adapted from PDFBox's LosslessFactory.PredictorEncoder.
        // Note that we've measured that encoder to be superior to the PDFBox's package-private PNGConverter.
        private fun encodeRGB24Losslessly(bitmap: Bitmap, os: OutputStream) {
            val (w, h) = bitmap.spec.resolution
            val seg = bitmap.memorySegment(0)
            val ls = bitmap.linesize(0).toLong()

            // c b
            // a x
            // x is the current pixel.
            val xRGB = ByteArray(3)
            val aRGB = ByteArray(3)
            val bRGB = ByteArray(3)
            val cRGB = ByteArray(3)

            val rawLen = 1 + w * 3
            val rawNone = ByteArray(rawLen).also { it[0] = 0 }
            val rawSub = ByteArray(rawLen).also { it[0] = 1 }
            val rawUp = ByteArray(rawLen).also { it[0] = 2 }
            val rawAvg = ByteArray(rawLen).also { it[0] = 3 }
            val rawPath = ByteArray(rawLen).also { it[0] = 4 }

            for (y in 0..<h) {
                aRGB.fill(0)
                cRGB.fill(0)
                var ib = (y - 1) * ls
                var ix = y * ls
                var r = 1
                repeat(w) {
                    if (y != 0)
                        MemorySegment.copy(seg, JAVA_BYTE, ib, bRGB, 0, 3); ib += 3L
                    MemorySegment.copy(seg, JAVA_BYTE, ix, xRGB, 0, 3); ix += 3L
                    for (channel in 0..2) {
                        val x = toUnsignedInt(xRGB[channel])
                        val a = toUnsignedInt(aRGB[channel])
                        val b = toUnsignedInt(bRGB[channel])
                        val c = toUnsignedInt(cRGB[channel])
                        rawNone[r] = x.toByte()
                        rawSub[r] = ((x and 0xFF) - (a and 0xFF)).toByte()
                        rawUp[r] = ((x and 0xFF) - (b and 0xFF)).toByte()
                        rawAvg[r] = (x - ((b + a) / 2)).toByte()
                        val p = a + b - c
                        rawPath[r] = (x - minBy(abs(p - a), a, abs(p - b), b, abs(p - c), c)).toByte()
                        r++
                    }
                    System.arraycopy(xRGB, 0, aRGB, 0, 3)
                    System.arraycopy(bRGB, 0, cRGB, 0, 3)
                }
                val raw = minBy(
                    est(rawNone), rawNone, est(rawSub), rawSub, est(rawUp), rawUp, est(rawAvg), rawAvg,
                    est(rawPath), rawPath
                )
                os.write(raw)
            }
        }

        private fun est(raw: ByteArray): Long = raw.sumOf { abs(it.toLong()) }

        private fun minBy(k1: Int, v1: Int, k2: Int, v2: Int, k3: Int, v3: Int): Int =
            if (k1 <= k2 && k1 <= k3) v1 else if (k2 <= k3) v2 else v3

        private fun <T> minBy(k1: Long, v1: T, k2: Long, v2: T, k3: Long, v3: T, k4: Long, v4: T, k5: Long, v5: T): T {
            var k = k1
            var v = v1
            // @formatter:off
            if (k2 < k) { k = k2; v = v2 }
            if (k3 < k) { k = k3; v = v3 }
            if (k4 < k) { k = k4; v = v4 }
            // @formatter:on
            if (k5 < k) v = v5
            return v
        }

    }


    class PlacedTape(val embeddedTape: EmbeddedTape, val x: Double, val y: Double)

    private class PlacedTapeCollectorBackend : MaterializationBackend {

        val collected = mutableListOf<PlacedTape>()

        override fun materializeEmbeddedTape(x: Double, y: Double, scaling: Double, embeddedTape: EmbeddedTape) {
            val res = embeddedTape.resolutionBeforeRotation
            val width = (res.widthPx * scaling).roundToInt()
            val height = (res.heightPx * scaling).roundToInt()
            if (width != 0 && height != 0)
                collected.add(PlacedTape(embeddedTape.withResolution(width, height), x, y))
        }

    }

}
