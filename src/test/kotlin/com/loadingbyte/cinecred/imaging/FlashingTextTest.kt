package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.setupNatives
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test


internal class FlashingTextTest {

    companion object {
        init {
            setupNatives()
        }
    }

    private val red = Color4f.fromSRGBHexString("#FF0000")
    private val green = Color4f.fromSRGBHexString("#00FF00")
    private val blue = Color4f.fromSRGBHexString("#0000FF")

    @Test
    fun `plain text layer with flash colors becomes flashing coloring`() {
        val layer = presetLayer().copy(
            shape = LayerShape.TEXT,
            plainColor = red,
            flashColors = persistentListOf(green, blue),
            flashIntervalFrames = 3
        )

        val coloring = layer.toFormattedStringColoring(100.0)
        val flashing = assertInstanceOf(FormattedString.Layer.Coloring.Flashing::class.java, coloring)

        assertEquals(listOf(red, green, blue), flashing.colors)
        assertEquals(3, flashing.intervalFrames)
    }

    @Test
    fun `preview keeps primary color for flashing coat`() {
        val coat = DeferredImage.Coat.Flashing(listOf(red, green, blue), intervalFrames = 2)

        val resolved = DeferredImage.Flashing(frameIdx = 5, animate = false).resolve(coat)
        val plain = assertInstanceOf(DeferredImage.Coat.Plain::class.java, resolved)

        assertEquals(red, plain.color)
    }

    @Test
    fun `render cycles flashing coat by frame interval`() {
        val coat = DeferredImage.Coat.Flashing(listOf(red, green, blue), intervalFrames = 2)

        val frame0 = assertInstanceOf(
            DeferredImage.Coat.Plain::class.java,
            DeferredImage.Flashing(frameIdx = 0, animate = true).resolve(coat)
        )
        val frame2 = assertInstanceOf(
            DeferredImage.Coat.Plain::class.java,
            DeferredImage.Flashing(frameIdx = 2, animate = true).resolve(coat)
        )
        val frame4 = assertInstanceOf(
            DeferredImage.Coat.Plain::class.java,
            DeferredImage.Flashing(frameIdx = 4, animate = true).resolve(coat)
        )

        assertEquals(red, frame0.color)
        assertEquals(green, frame2.color)
        assertEquals(blue, frame4.color)
    }
}
