package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.ColorSpace.Primaries.Companion.BT709
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.BLENDING
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.project.Scan
import org.bytedeco.ffmpeg.global.avutil.*


class VideoDeliverer(
    video: DeferredVideo,
    grounding: Color4f?,
    userRepresentation: Bitmap.Representation,
    ceiling: Float?,
    scan: Scan,
    private val matte: Boolean
) : AutoCloseable {

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
    private var blackUserBitmap: Bitmap? = null
    private var frameIdx = 0

    init {
        var backendSpec = userSpec
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
                    Bitmap.PixelFormat.of(AV_PIX_FMT_GBRPF32), userRepresentation.colorSpace, Bitmap.Alpha.OPAQUE
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

}
