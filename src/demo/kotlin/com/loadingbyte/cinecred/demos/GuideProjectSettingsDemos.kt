package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.TimecodeFormat
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.Font
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.comms.DockableId.STYLING
import com.loadingbyte.cinecred.ui.helper.withG2
import com.loadingbyte.cinecred.ui.styling.OverrideWidgetSpec
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.Thread.sleep
import java.util.*


private const val DIR = "guide/project-settings"

val GUIDE_PROJECT_SETTINGS_DEMOS
    get() = listOf(
        GuideProjectSettingsResolutionAndFrameRateDemo,
        GuideProjectSettingsResolutionAndFrameRateRebuildDemo,
        GuideProjectSettingsTimecodeFormatDemo,
        GuideProjectSettingsRuntimeFineAdjustmentDemo,
        GuideProjectSettingsLeaveFramesBlankDemo,
        GuideProjectSettingsGroundingDemo,
        GuideProjectSettingsUnitVGapDemo,
        GuideProjectSettingsLocaleDemo,
        GuideProjectSettingsUppercaseExceptionsDemo
    )


object GuideProjectSettingsResolutionAndFrameRateDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/resolution-and-frame-rate", Format.STEP_GIF,
    listOf(Global::resolution.st(), Global::fps.st())
) {
    override fun styles() = buildList<Global> {
        this += presetGlobal()
        this += last().copy(resolution = Resolution(1080, 1080))
        this += last().copy(fps = FPS(25, 2))
    }
}


@Suppress("DEPRECATION")
object GuideProjectSettingsResolutionAndFrameRateRebuildDemo : ProjectDemo(
    "$DIR/resolution-and-frame-rate-rebuild", Format.PNG
) {
    override fun trees() = trees(tree(1000, 1000, STYLING))

    override fun generate() {
        val form = styDok.leakedGlobalForm
        edt {
            styDok.leakedStylingTree.selectionRows = intArrayOf(0)
            form.getFormRowFor(Global::fps.st())?.notice = null
        }
        sleep(500)
        val b = Rectangle(0, 0, -1, -1)
        for (widget in listOf(Global::resolution.st(), Global::fps.st()).map(form::getWidgetFor) +
                listOf(styDok.leakedRebuildForResWidget, styDok.leakedRebuildForFPSWidget)) {
            b.add(form.components.let { comps -> comps[comps.indexOf(widget.components[0]) - 1] }.bounds)
            for (comp in widget.components) if (comp.isVisible) b.add(comp.bounds)
        }
        val settImg = BufferedImage(b.width, b.height, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
            g2.translate(-b.x, -b.y)
            printWithPopups(form, g2)
        }
        write(BufferedImage(b.width + 40, b.height + 40, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
            g2.color = Color(settImg.getRGB(0, 0))
            g2.fillRect(0, 0, 1000, 1000)
            g2.drawImage(settImg, 20, 20, null)
        })
    }
}


object GuideProjectSettingsTimecodeFormatDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/timecode-format", Format.SLOW_STEP_GIF,
    listOf(Global::fps.st(), Global::timecodeFormat.st(), Global::runtimeFrames.st())
) {
    override fun styles() = buildList<Global> {
        this += presetGlobal().copy(fps = FPS(30, 1))
        this += last().copy(timecodeFormat = TimecodeFormat.FRAMES)
        this += last().copy(fps = FPS(30000, 1001), timecodeFormat = TimecodeFormat.SMPTE_DROP_FRAME)
        this += last().copy(timecodeFormat = TimecodeFormat.EXACT_FRAMES_IN_SECOND)
    }

    override val overrideCtx = OverrideWidgetSpec.Context(3284, emptyMap())
}


object GuideProjectSettingsRuntimeFineAdjustmentDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/runtime-fine-adjustment", Format.STEP_GIF,
    listOf(Global::runtimeFrames.st()), pageScaling = 0.45, pageHeight = 400
) {
    override fun styles() = buildList<Global> {
        this += TEMPLATE_PROJECT.styling.global
        this += last().copy(runtimeFrames = Override(1080))
    }

    override val overrideCtx = OverrideWidgetSpec.Context(1247, emptyMap())
    override fun credits(style: Global) = Pair(style, listOf(TEMPLATE_SCROLL_PAGE_FROM_DOP))
}


object GuideProjectSettingsLeaveFramesBlankDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/leave-frames-blank", Format.STEP_GIF,
    listOf(Global::blankFirstFrame.st(), Global::blankLastFrame.st())
) {
    override fun styles() = buildList<Global> {
        this += presetGlobal()
        this += last().copy(blankFirstFrame = true)
        this += last().copy(blankLastFrame = true)
    }
}


object GuideProjectSettingsGroundingDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/grounding", Format.STEP_GIF,
    listOf(Global::grounding.st()), pageScaling = 0.45, pageHeight = 115
) {
    override fun styles() = buildList<Global> {
        this += TEMPLATE_PROJECT.styling.global
        this += last().copy(grounding = Color4f.fromSRGBHexString("#006400"))
    }

    override fun credits(style: Global) = Pair(style, listOf(TEMPLATE_SCROLL_PAGE_FROM_DOP))
}


object GuideProjectSettingsUnitVGapDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/unit-vgap", Format.STEP_GIF,
    listOf(Global::unitVGapPx.st()), pageGuides = true
) {
    override fun styles() = buildList<Global> {
        this += presetGlobal()
        this += last().copy(unitVGapPx = 2.0 * last().unitVGapPx)
    }

    override fun credits(style: Global) =
        Pair(style, listOf(buildPage(style, listOf("Peter Panner", "Paul Puller", "Charly Clapper"), vGap = 1.0)))
}


object GuideProjectSettingsLocaleDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/locale", Format.STEP_GIF,
    listOf(Global::locale.st())
) {
    override fun styles() = buildList<Global> {
        this += presetGlobal().copy(locale = locale)
        this += last().copy(locale = Locale.of("tr"))
    }

    override fun credits(style: Global) = Pair(style, listOf(buildPage(style, listOf("Tina Times"), uppercase = true)))
}


object GuideProjectSettingsUppercaseExceptionsDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/uppercase-exceptions", Format.SLOW_STEP_GIF,
    listOf(Global::uppercaseExceptions.st())
) {
    override fun styles() = buildList<Global> {
        this += presetGlobal().copy(uppercaseExceptions = persistentListOf())
        this += last().copy(uppercaseExceptions = persistentListOf("von"))
        this += last().copy(uppercaseExceptions = persistentListOf("_von_"))
        this += last().copy(uppercaseExceptions = persistentListOf("_von_", "Mac"))
        this += last().copy(uppercaseExceptions = persistentListOf("_von_", "_Mac#"))
    }

    override fun credits(style: Global) = run {
        val texts = listOf("Ronny von Tommy", "Cleavon Tommy", "Johnny MacRonny", "Johnny Macbeth")
        Pair(style, listOf(buildPage(style, texts, uppercase = true)))
    }
}


private fun buildPage(global: Global, texts: List<String>, vGap: Double = 0.0, uppercase: Boolean = false): Page {
    val fontRef = FontRef(Font.bundled("Archivo Narrow Bold")!!)
    val letterStyle = presetLetterStyle().copy(font = fontRef, uppercase = uppercase)
    val blocks = texts.map { text ->
        val styledString = persistentListOf(BodyElement.Str(persistentListOf(listOf(Pair(text, letterStyle)))))
        Block(presetContentStyle(), null, styledString, null, vGap * global.unitVGapPx, Any(), Any(), Any())
    }.toPersistentList()
    val spine = Spine(null, VAnchor.TOP, VAnchor.TOP, 0.0, 0.0, blocks)
    val compound = Compound.Scroll(0.0, persistentListOf(spine), 0.0)
    return Page(persistentListOf(Stage(presetPageStyle(), 0, persistentListOf(compound), 0.0, 0, null)), 0)
}
