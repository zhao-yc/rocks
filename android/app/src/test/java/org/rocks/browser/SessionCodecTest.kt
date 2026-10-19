package org.rocks.browser

import org.junit.Assert.*
import org.junit.Test
import org.rocks.browser.core.*

class SessionCodecTest {
    @Test fun sessionRoundTripPreservesSelectedTabBookmarksHistoryAndSettings() {
        val input = BrowserData().openTab("https://example.com/").let {
            it.recordVisit(it.activeTabId, it.activeTab.url, "Example", 1234).toggleBookmark(1235)
                .copy(settings = BrowserSettings("bing"))
        }
        assertEquals(input, SessionCodec.decode(SessionCodec.encode(input)))
    }

    @Test fun sharedFixtureLoadsWithTheSameContract() {
        val text = java.io.File("../../shared/fixtures/session-v1.json").readText()
        val state = SessionCodec.decode(text)
        assertEquals("fixture-web", state.activeTabId)
        assertEquals("https://example.com/", state.activeTab.url)
        assertEquals("duckduckgo", state.settings.searchEngine)
        assertEquals(1, state.bookmarks.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureSchemaIsRejectedInsteadOfOverwritten() { SessionCodec.decode("""{"schemaVersion":2,"tabs":[]}""") }

    @Test fun emptySessionRepairsSelectionAndCreatesHome() {
        val state = SessionCodec.decode("""{"schemaVersion":1,"activeTabId":"missing","tabs":[]}""")
        assertEquals(1, state.tabs.size)
        assertEquals(state.tabs.single().id, state.activeTabId)
    }

    @Test fun corruptOrDuplicateTabsAndUnsafeUrlsAreDiscarded() {
        val state = SessionCodec.decode("""{"schemaVersion":1,"activeTabId":"bad","tabs":[
            {"id":"good","url":"https://example.com","title":"A"},
            {"id":"good","url":"https://example.org","title":"B"},
            {"id":"bad","url":"file:///private","title":"X"}],
            "history":[{"url":"javascript:alert(1)","title":"X","timestamp":1}]}""")
        assertEquals(1, state.tabs.size)
        assertEquals("good", state.activeTabId)
        assertTrue(state.history.isEmpty())
    }
}
