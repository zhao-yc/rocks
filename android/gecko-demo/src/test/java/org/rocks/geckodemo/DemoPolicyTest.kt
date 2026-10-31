package org.rocks.geckodemo

import org.junit.Assert.*
import org.junit.Test

class DemoPolicyTest {
    @Test fun pickerCannotInjectFileUrisOrSilentlyTruncateAMultipleSelection() {
        assertTrue(DemoPolicy.canUpload(listOf("content"), false))
        assertTrue(DemoPolicy.canUpload(List(20) { "content" }, true))
        assertFalse(DemoPolicy.canUpload(listOf("content", "file"), true))
        assertFalse(DemoPolicy.canUpload(List(21) { "content" }, true))
        assertFalse(DemoPolicy.canUpload(listOf("content", "content"), false))
    }
    @Test fun addressInputCannotLoadPrivilegedOrExternalSchemes() {
        listOf("javascript:alert(1)", "file:///data/data/secret", "intent://app", "moz-extension://id/popup.html", "about:config").forEach {
            assertNull(DemoPolicy.inputUrl(it))
        }
        assertEquals("https://example.org", DemoPolicy.inputUrl("example.org"))
        assertTrue(DemoPolicy.inputUrl("两只猫")!!.contains("%E4%B8%A4"))
    }
    @Test fun extensionPageCannotEscapeItsOwnOrigin() {
        val base = "moz-extension://allowed/"
        assertTrue(DemoPolicy.canLoad(base + "options.html", base))
        assertFalse(DemoPolicy.canLoad("moz-extension://allowed.evil/options.html", base))
        assertFalse(DemoPolicy.canLoad("moz-extension://other/options.html", base))
        assertFalse(DemoPolicy.isWebUrl("https://user:pass@example.org"))
    }
    @Test fun shortDragReturnsToTheSameTabAndFlickAdvancesOne() {
        assertEquals(2, DemoPolicy.swipeTarget(2, 0.1f, 30f, 5))
        assertEquals(3, DemoPolicy.swipeTarget(2, 0.1f, -800f, 5))
        assertEquals(1, DemoPolicy.swipeTarget(2, -0.3f, 0f, 5))
        assertEquals(3, DemoPolicy.swipeTarget(2, 2f, 0f, 5))
    }
    @Test fun endTabsDoNotWrapAround() {
        assertEquals(0, DemoPolicy.swipeTarget(0, -0.6f, 1000f, 3))
        assertEquals(2, DemoPolicy.swipeTarget(2, 0.6f, -1000f, 3))
    }
}
