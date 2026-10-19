package org.rocks.browser.core

import org.junit.Assert.*
import org.junit.Test

class BrowserCoreTest {
    private fun resolve(value: String) = AddressResolver.resolve(value, SearchEngine.DUCKDUCKGO)

    @Test fun domainsDefaultToHttpsAndKeepPaths() {
        assertEquals("https://example.com/path?q=one#two", resolve(" example.com/path?q=one#two ").url)
        assertEquals("http://localhost:8080/", resolve("http://localhost:8080/").url)
        assertEquals("https://[::1]:8443/", resolve("https://[::1]:8443/").url)
    }

    @Test fun internationalDomainsAndSearchTermsAreEncoded() {
        assertEquals("https://xn--fsqu00a.xn--0zwm56d/", resolve("例子.测试/").url)
        assertEquals("https://duckduckgo.com/?q=hello+world+%26+rocks", resolve("hello world & rocks").url)
        assertEquals("https://cn.bing.com/search?q=rocks+browser", AddressResolver.resolve("rocks browser", SearchEngine.BING).url)
    }

    @Test fun dangerousOrMalformedAddressesNeverReachWebView() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "content://provider/private", "data:text/html,hi",
            "intent://launch#Intent;end", "https://user:secret@example.com/", "https://example.com:99999/",
            "https://example.com:bad/", "https://", "http://foo\\bar/", "https://example.com/\nattack").forEach {
            assertNull("Must reject $it", resolve(it).url)
        }
    }

    @Test fun searchCannotInjectAnotherQueryParameter() {
        assertEquals("https://www.google.com/search?q=a+%26+safe%3Doff",
            AddressResolver.resolve("a & safe=off", SearchEngine.GOOGLE).url)
    }

    @Test fun closingActiveTabSelectsNeighborAndLastCloseCreatesHome() {
        var state = BrowserData().openTab("https://example.com").openTab("https://example.org")
        state = state.closeTab(state.activeTabId)
        assertEquals("https://example.com", state.activeTab.url)
        while (state.tabs.size > 1) state = state.closeTab(state.activeTabId)
        val oldId = state.activeTabId
        state = state.closeTab(oldId)
        assertEquals(1, state.tabs.size)
        assertEquals("", state.activeTab.url)
        assertNotEquals(oldId, state.activeTabId)
    }

    @Test fun closingBackgroundTabPreservesSelectionAndUnknownIdsDoNothing() {
        val first = BrowserData()
        val state = first.openTab("https://example.com")
        assertEquals(state.activeTabId, state.closeTab(first.activeTabId).activeTabId)
        assertEquals(state, state.selectTab("missing"))
        assertEquals(state, state.closeTab("missing"))
    }

    @Test fun tabLimitNeverSilentlyEvictsAnExistingTab() {
        var state = BrowserData()
        repeat(MAX_TABS - 1) { state = state.openTab() }
        assertEquals(MAX_TABS, state.tabs.size)
        assertEquals(state, state.openTab("https://extra.example"))
    }

    @Test fun lateCallbackFromClosedTabCannotCreateHistory() {
        val state = BrowserData()
        assertEquals(state, state.recordVisit("missing", "https://example.com", "Closed", 1))
    }

    @Test fun visitsDeduplicateAndHistoryIsBounded() {
        var state = BrowserData()
        repeat(MAX_HISTORY + 10) { index -> state = state.recordVisit(state.activeTabId, "https://example.com/$index", "Page $index", index.toLong()) }
        assertEquals(MAX_HISTORY, state.history.size)
        state = state.recordVisit(state.activeTabId, "https://example.com/509", "Updated", 999)
        assertEquals(MAX_HISTORY, state.history.size)
        assertEquals("Updated", state.history.first().title)
        assertEquals(999, state.history.first().timestamp)
    }

    @Test fun bookmarksToggleAndHomeCannotBeBookmarked() {
        var state = BrowserData()
        assertTrue(state.toggleBookmark(1).bookmarks.isEmpty())
        state = state.updateTab(state.activeTabId, "https://example.com", "Example").toggleBookmark(2)
        assertEquals("Example", state.bookmarks.single().title)
        assertTrue(state.toggleBookmark(3).bookmarks.isEmpty())
    }
}
