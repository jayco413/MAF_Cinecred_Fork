package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.RenderProfiling
import com.loadingbyte.cinecred.common.cleanDirectory
import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.throwableAwareTask
import com.loadingbyte.cinecred.common.userNotification
import com.loadingbyte.cinecred.delivery.RenderFormat.Config
import com.loadingbyte.cinecred.delivery.RenderFormat.Config.Assortment.Companion.choice
import com.loadingbyte.cinecred.delivery.RenderFormat.Config.Assortment.Companion.fixed
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DPX_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.EXR_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.HDR
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRIMARIES
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SPATIAL_SCALING_LOG2
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TIFF_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSFER
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSPARENCY
import com.loadingbyte.cinecred.delivery.RenderFormat.Sliders
import com.loadingbyte.cinecred.delivery.RenderFormat.Transparency.*
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.GRAY
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.RGB
import com.loadingbyte.cinecred.imaging.BitmapWriter
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.LINEAR
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.imaging.RenderDiskCache
import com.loadingbyte.cinecred.project.Styling
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.math.pow


class ImageSequenceRenderJob private constructor(
    private val format: Format,
    private val config: Config,
    private val sliders: Sliders,
    private val styling: Styling,
    private val video: DeferredVideo,
    private val projectDir: Path,
    private val dir: Path,
    private val filenamePattern: String
) : RenderJob {

    override val prefix: Path
        get() = dir

    override fun render(progressCallback: (Int) -> Unit) {
        if (dir.exists())
            dir.cleanDirectory()
        dir.createDirectoriesSafely()

        val embedAlpha = config[TRANSPARENCY] == TRANSPARENT
        val matte = config[TRANSPARENCY] == MATTE
        val family = if (matte) GRAY else RGB
        val colorSpace = if (matte) ColorSpace.of(LINEAR) else ColorSpace.of(config[PRIMARIES], config[TRANSFER])
        val ceiling = if (config.getOrDefault(HDR) || colorSpace.transfer.isHDR) null else 1f
        val scan = config[SCAN]
        val grounding = if (config[TRANSPARENCY] == GROUNDED) styling.global.grounding else null
        var scaledVideo = video.copy(2.0.pow(config[SPATIAL_SCALING_LOG2]), fpsScaling = config[FPS_SCALING])
        if (sliders.resolution != null)
            scaledVideo = scaledVideo.copy(
                resolutionPaddingH = (sliders.resolution.widthPx - scaledVideo.resolution.widthPx) / 2.0,
                resolutionPaddingV = (sliders.resolution.heightPx - scaledVideo.resolution.heightPx) / 2.0,
            )

        val bitmapWriter = when (format) {
            PNG -> BitmapWriter.PNG(family, embedAlpha, colorSpace, config[DEPTH])
            TIFF -> BitmapWriter.TIFF(family, embedAlpha, colorSpace, config[DEPTH], config[TIFF_COMPRESSION])
            DPX -> BitmapWriter.DPX(family, embedAlpha, colorSpace, config[DEPTH], config[DPX_COMPRESSION])
            EXR -> BitmapWriter.EXR(
                family, embedAlpha, colorSpace.primaries, config[DEPTH], config[EXR_COMPRESSION], scaledVideo.fps
            )
            else -> throw IllegalArgumentException()
        }

        val diskCache = RenderDiskCache.open(
            projectDir, dir,
            renderKey(
                job = "image-sequence",
                format = format.label,
                configIdx = format.configs.indexOf(config),
                sliders = sliders,
                video = scaledVideo,
                spec = "grounding=${styling.global.grounding} ceiling=$ceiling matte=$matte scan=$scan"
            )
        )
        val profiling = RenderProfiling("image sequence render profile for '${dir.name}'")

        VideoDeliverer(
            scaledVideo, styling.global.timecodeFormat, grounding, styling.global.locale, sliders.slate,
            bitmapWriter.representation, ceiling, scan, matte, diskCache, profiling
        ).use { deliverer ->
            val numFrames = deliverer.numFrames
            val numWorkers = Runtime.getRuntime().availableProcessors() - 1
            val executor = Executors.newFixedThreadPool(numWorkers) { Thread(it, "ImageSequenceWriter") }
            try {
                val done = CountDownLatch(numFrames)
                val backlog = Semaphore(numWorkers * 5)
                val writerExc = AtomicReference<Exception?>()
                for (frameIdx in 0..<numFrames) {
                    val bitmap = deliverer.deliverFrame()!!
                    val file = dir.resolve(filenamePattern.format(frameIdx + 1))
                    backlog.acquire()
                    executor.submit(throwableAwareTask {
                        try {
                            bitmap.use { bitmapWriter.write(bitmap, file) }
                            if (!Thread.interrupted())
                                progressCallback(MAX_RENDER_PROGRESS * (numFrames - done.count.toInt()) / numFrames)
                        } catch (_: InterruptedException) {
                            // Return.
                        } catch (e: Exception) {
                            writerExc.set(e)
                        } finally {
                            backlog.release()
                            done.countDown()
                        }
                    })
                    writerExc.get()?.let { e -> throw RuntimeException(e.userNotification, e) }
                    if (Thread.interrupted())
                        throw InterruptedException()
                }
                done.await()
                writerExc.get()?.let { e -> throw RuntimeException(e.userNotification, e) }
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(1, TimeUnit.SECONDS)
            }
        }
    }


    companion object {

        private val PNG = Format(
            "png",
            transparencyTimesColorSpace() * choice(DEPTH, 8, 16)
        )
        private val TIFF = Format(
            "tiff",
            transparencyTimesColorSpace() * choice(DEPTH, 8, 16) * choice(TIFF_COMPRESSION)
        )
        private val DPX = Format(
            "dpx",
            transparencyTimesColorSpace() * choice(DEPTH, 8, 10, 12, 16) * choice(DPX_COMPRESSION) -
                    fixed(DEPTH, 10) * fixed(TRANSPARENCY, TRANSPARENT)
        )
        private val EXR = Format(
            "exr",
            choice(DEPTH, 16, 32, default = 32) * choice(EXR_COMPRESSION) * (
                    choice(TRANSPARENCY, GROUNDED, TRANSPARENT) * choice(PRIMARIES) * fixed(TRANSFER, LINEAR)
                            * choice(HDR)
                            + fixed(TRANSPARENCY, MATTE)
                    )
        )

        val FORMATS = listOf<RenderFormat>(PNG, TIFF, DPX, EXR)

        private fun transparencyTimesColorSpace() =
            choice(TRANSPARENCY, GROUNDED, TRANSPARENT) * choice(PRIMARIES) * choice(TRANSFER) +
                    fixed(TRANSPARENCY, MATTE)

    }


    private class Format(fileExt: String, configAssortment: Config.Assortment) : RenderFormat(
        fileExt.uppercase(), auxLabel = null, fileSeq = true, setOf(fileExt), fileExt,
        configAssortment * choice(SPATIAL_SCALING_LOG2) * choice(FPS_SCALING) * choice(SCAN),
        isRaster = true
    ) {
        override fun createRenderJob(
            projectDir: Path,
            config: Config,
            sliders: Sliders,
            styling: Styling,
            pageDefImages: List<DeferredImage>?,
            video: DeferredVideo?,
            fileOrDir: Path,
            filenamePattern: String?
        ) = ImageSequenceRenderJob(this, config, sliders, styling, video!!, projectDir, fileOrDir, filenamePattern!!)
    }

}
