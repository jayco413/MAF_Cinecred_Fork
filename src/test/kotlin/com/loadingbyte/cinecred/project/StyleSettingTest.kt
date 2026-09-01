package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.setupNatives
import com.loadingbyte.cinecred.imaging.Color4f
import java.util.UUID
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test


internal class StyleSettingTest {

    companion object {
        init {
            setupNatives()
        }
    }

    private val red = Color4f.fromSRGBHexString("#FF0000")
    private val green = Color4f.fromSRGBHexString("#00FF00")
    private val blue = Color4f.fromSRGBHexString("#0000FF")

    @Test
    fun `newStyleUnsafe uses primary constructor for layer with default parameters`() {
        val values = getStyleSettings(Layer::class.java).map { setting ->
            when (setting.name) {
                "name" -> "Flash"
                "collapsed" -> false
                "plainColor" -> red
                "flashColors" -> persistentListOf(green, blue)
                "flashIntervalFrames" -> 4
                else -> setting.get(presetLayer())
            }
        }

        val layer = newStyleUnsafe(Layer::class.java, UUID.randomUUID(), values)

        assertEquals("Flash", layer.name)
        assertEquals(false, layer.collapsed)
        assertEquals(red, layer.plainColor)
        assertEquals(persistentListOf(green, blue), layer.flashColors)
        assertEquals(4, layer.flashIntervalFrames)
    }

    @Test
    fun `style copy preserves flashing layer settings`() {
        val layer = presetLayer().copy(
            Layer::flashColors.st().notarize(persistentListOf(red, green)),
            Layer::flashIntervalFrames.st().notarize(3)
        )

        assertEquals(persistentListOf(red, green), layer.flashColors)
        assertEquals(3, layer.flashIntervalFrames)
    }
}
