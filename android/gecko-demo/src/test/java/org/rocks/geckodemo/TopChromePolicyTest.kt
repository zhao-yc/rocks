package org.rocks.geckodemo

import org.junit.Assert.*
import org.junit.Test

class TopChromePolicyTest {
    private val white = 0xFFFFFFFF.toInt()
    private val dark = 0xFF071F1A.toInt()

    @Test fun whiteHeadersRemainWhiteDespiteLogosTextAndAntialiasing() {
        // 模拟百度/英纵白色页头中少量黑字和彩色 Logo，不应得到灰色或绿色顶带。
        val strip = IntArray(128) { index -> when {
            index < 10 -> 0xFF00A7AD.toInt()
            index < 24 -> 0xFF202020.toInt()
            index < 30 -> 0xFFFDFDFD.toInt()
            else -> white
        } }
        val color = TopChromePolicy.background(strip, dark)
        assertTrue((color and 255) >= 254)
        assertTrue((color ushr 8 and 255) >= 254)
        assertTrue(TopChromePolicy.darkIcons(color, false))
    }

    @Test fun photographsWithoutAStableBackgroundKeepThePreviousColor() {
        val strip = IntArray(128) { index -> when (index % 4) {
            0 -> 0xFFDA3520.toInt()
            1 -> 0xFF20AB44.toInt()
            2 -> 0xFF3355DB.toInt()
            else -> white
        } }
        assertEquals(dark, TopChromePolicy.background(strip, dark))
        assertEquals(white, TopChromePolicy.background(intArrayOf(), white))
    }

    @Test fun darkReaderBackgroundUsesLightIconsAndMidtonesDoNotFlicker() {
        assertEquals(dark, TopChromePolicy.background(IntArray(64) { dark }, white))
        assertFalse(TopChromePolicy.darkIcons(dark, true))
        val middle = 0xFF787878.toInt()
        assertTrue(TopChromePolicy.darkIcons(middle, true))
        assertFalse(TopChromePolicy.darkIcons(middle, false))
        assertTrue(TopChromePolicy.darkIcons(white, false))
    }

    @Test fun blurObscuresFineTextWithoutBlackEdgesOrMutatingTheSource() {
        val pixels = IntArray(32 * 12) { index -> if (index / 32 < 2 || index % 4 != 0) white else 0xFF000000.toInt() }
        val original = pixels.copyOf()
        val frame = TopChromePolicy.frame(pixels, 32, 12, dark)
        assertArrayEquals(original, pixels)
        assertEquals(white, frame.background)
        assertTrue(frame.pixels.all { (it ushr 24) == 255 && (it and 255) >= 180 })
        assertTrue(frame.pixels.toSet().size > 1)
        val solid = TopChromePolicy.frame(IntArray(7 * 3) { dark }, 7, 3, white)
        assertTrue(solid.pixels.all { it == dark })
        assertEquals(dark, TopChromePolicy.frame(intArrayOf(dark), 1, 1, white).pixels.single())
    }

    @Test fun effectTracksActualScrollAndDoesNotChangeViewportGeometry() {
        assertEquals(0f, TopChromePolicy.strength(-5f), 0f)
        assertEquals(0f, TopChromePolicy.strength(Float.NaN), 0f)
        assertEquals(0.5f, TopChromePolicy.strength(12f), 0f)
        assertEquals(1f, TopChromePolicy.strength(24f), 0f)
        assertEquals(1f, TopChromePolicy.strength(1000f), 0f)
        assertEquals(white, TopChromePolicy.blend(white, dark, 0f))
        assertEquals(dark, TopChromePolicy.blend(white, dark, 1f))
    }

    @Test fun lateFramesCannotCrossTabsDocumentsRotationOrBackgrounding() {
        val page = BrowserPageScope(1, 1, "https://example.org")
        val gate = TopChromeRequestGate()
        val first = gate.begin(page, 0)!!
        assertTrue(gate.accepts(first, page))
        assertFalse(gate.accepts(first, page.copy(tabId = 2)))
        assertFalse(gate.accepts(first, page.copy(revision = 2)))
        assertFalse(gate.accepts(first, null))
        // 同一页面旋转或进入后台也会显式失效，但旧采样仍占据唯一的在途名额。
        gate.invalidate()
        assertFalse(gate.accepts(first, page))
        assertNull(gate.begin(page, 100))
        gate.finish(first)
        val next = gate.begin(page, 100)!!
        assertTrue(gate.accepts(next, page))
        gate.finish(first)
        assertTrue(gate.busy)
        gate.finish(next)
        assertFalse(gate.busy)
    }

    @Test fun samplingIsLimitedToOneRequestPer67Milliseconds() {
        val page = BrowserPageScope(1, 1, "https://example.org")
        val gate = TopChromeRequestGate()
        val first = gate.begin(page, 500)!!
        assertNull(gate.begin(page, 1000))
        gate.finish(first)
        assertNull(gate.begin(page, 566))
        assertEquals(1L, gate.delay(566))
        assertNotNull(gate.begin(page, 567))
    }
}
