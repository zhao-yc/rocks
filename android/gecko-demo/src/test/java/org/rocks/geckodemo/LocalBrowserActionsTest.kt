package org.rocks.geckodemo

import org.junit.Assert.*
import org.junit.Test
import org.rocks.browser.core.PageRecord
import org.rocks.browser.core.SearchEngine

class LocalBrowserActionsTest {
    private fun record(url: String, title: String = "网页", timestamp: Long = 0) = PageRecord(url, title, timestamp)

    @Test fun typedSuggestionsPrioritizeTabsThenBookmarksThenHistoryAndDeduplicateNormalizedUrls() {
        val suggestions = LocalBrowserSearch.suggestions("网页",
            listOf(SearchableTab(7, "https://EXAMPLE.org/open", "网页标签")),
            listOf(record("https://example.org/open", "网页书签"), record("https://example.org/bookmark")),
            listOf(record("https://example.org/bookmark"), record("https://example.org/history")))
        assertEquals(listOf(SuggestionSource.TAB, SuggestionSource.BOOKMARK, SuggestionSource.HISTORY), suggestions.map { it.source })
        assertEquals(listOf("https://example.org/open", "https://example.org/bookmark", "https://example.org/history"), suggestions.map { it.url })
        assertEquals(7L, suggestions.first().tabId)
    }

    @Test fun recentVisitsAreNewestFirstBoundedAndOfferSwitchingWithoutDuplicatingTabs() {
        val history = (0..9).map { record("https://example.org/$it", timestamp = it.toLong()) }
        val items = LocalBrowserSearch.suggestions(" ", listOf(
            SearchableTab(5, "https://example.org/9", "当前"), SearchableTab(6, "https://example.org/9", "重复页")), emptyList(), history)
        assertEquals(6, items.size)
        assertEquals("https://example.org/9", items.first().url)
        assertEquals(5L, items.first().tabId)
        assertEquals("https://example.org/4", items.last().url)
    }

    @Test fun titleAndUrlSearchSupportChineseAndIgnoreAsciiCaseWhileExcludingUnsafeUrls() {
        val records = listOf(record("https://example.org/help", "使用说明"), record("javascript:alert(1)", "使用说明"))
        assertEquals(1, LocalBrowserSearch.suggestions("说明", emptyList(), records, emptyList()).size)
        assertEquals(1, LocalBrowserSearch.suggestions("EXAMPLE.ORG/HELP", emptyList(), records, emptyList()).size)
        assertTrue(LocalBrowserSearch.suggestions("about:", listOf(SearchableTab(1, DemoPolicy.HOME, "首页")), emptyList(), emptyList()).isEmpty())
    }

    @Test fun clearingHistoryAndRemovingBookmarksCannotLeaveCachedSuggestions() {
        val history = mutableListOf(record("https://example.org/history"))
        val bookmarks = mutableListOf(record("https://example.org/bookmark"))
        assertEquals(2, LocalBrowserSearch.suggestions("example", emptyList(), bookmarks, history).size)
        history.clear(); bookmarks.clear()
        assertTrue(LocalBrowserSearch.suggestions("example", emptyList(), bookmarks, history).isEmpty())
        assertTrue(LocalBrowserSearch.suggestions("", emptyList(), bookmarks, history).isEmpty())
    }

    @Test fun libraryFilteringPreservesOrderAndNeverChangesTheSourceList() {
        val records = listOf(record("https://example.org/2", "中文"), record("https://other.org", "其他"), record("https://example.org/1", "中文"))
        assertEquals(listOf(records[0], records[2]), LocalBrowserSearch.filter(records, " 中文 "))
        assertEquals(listOf(records[0], records[2]), LocalBrowserSearch.filter(records, "EXAMPLE"))
        assertEquals(records, LocalBrowserSearch.filter(records, " "))
        assertTrue(LocalBrowserSearch.filter(records, "不存在").isEmpty())
        assertEquals(3, records.size)
    }

    @Test fun invalidInputProducesSpecificUiErrorsAndValidSearchUsesTheSelectedEngine() {
        assertEquals(AddressInputError.EMPTY, LocalBrowserSearch.resolve(" ", SearchEngine.BING).error)
        assertEquals(AddressInputError.INVALID_URL, LocalBrowserSearch.resolve("https://example.org:99999", SearchEngine.BING).error)
        assertEquals(AddressInputError.UNSUPPORTED_SCHEME, LocalBrowserSearch.resolve("javascript:alert(1)", SearchEngine.BING).error)
        assertEquals(AddressInputError.INVALID_INPUT, LocalBrowserSearch.resolve("a".repeat(8193), SearchEngine.BING).error)
        assertEquals(AddressInputError.INVALID_INPUT, LocalBrowserSearch.resolve("a\u0000b", SearchEngine.BING).error)
        assertEquals("https://example.org", LocalBrowserSearch.resolve("example.org", SearchEngine.BING).url)
        assertTrue(LocalBrowserSearch.resolve("浏览器", SearchEngine.BAIDU).url!!.startsWith(SearchEngine.BAIDU.prefix))
    }

    @Test fun closedTabsRetainOnlyTenMostRecentSnapshotsInLifoOrder() {
        val closed = RecentlyClosedTabs()
        for (index in 0..11) closed.record("https://example.org/$index", "标题$index", index, index % 2 == 0, TabCloseCause.USER)
        assertEquals(10, closed.size)
        for (index in 11 downTo 2) {
            val entry = closed.forRestore(1, false)!!
            assertEquals("https://example.org/$index", entry.url)
            assertEquals("标题$index", entry.title)
            assertEquals(index, entry.index)
            assertEquals(index % 2 == 0, entry.desktop)
            closed.consume(entry)
        }
        assertNull(closed.latest)
    }

    @Test fun programmaticBulkHomeAndExtensionClosuresDoNotEnterRecovery() {
        val closed = RecentlyClosedTabs()
        assertNull(closed.record("https://example.org", "", 0, false, TabCloseCause.PROGRAMMATIC))
        assertNull(closed.record("https://example.org", "", 0, false, TabCloseCause.BULK))
        assertNull(closed.record("https://example.org", "", 0, false, TabCloseCause.USER, extension = true))
        assertNull(closed.record(DemoPolicy.HOME, "", 0, false, TabCloseCause.USER))
        assertNull(closed.record("moz-extension://id/options", "", 0, false, TabCloseCause.USER))
        assertEquals(0, closed.size)
    }

    @Test fun tabLimitAndStaleUndoCannotConsumeAnotherClosure() {
        val closed = RecentlyClosedTabs()
        val old = closed.record("https://example.org/old", "", 0, false, TabCloseCause.USER)!!
        val current = closed.record("https://example.org/new", "", 0, false, TabCloseCause.USER)!!
        assertNull(closed.forRestore(DemoPolicy.MAX_TABS, false))
        assertEquals(current, closed.latest)
        assertNull(closed.forRestore(1, false, old.closureId))
        closed.consume(old)
        assertEquals(2, closed.size)
        assertEquals(current, closed.forRestore(1, false, current.closureId))
        closed.consume(current)
        assertEquals(old, closed.forRestore(1, false))
    }

    @Test fun onlyTheExactUntouchedAutomaticHomeCanBeReplaced() {
        val home = AutomaticHomeMarker(9, 3)
        assertTrue(home.matches(1, 9, 3, DemoPolicy.HOME))
        assertFalse(home.matches(2, 9, 3, DemoPolicy.HOME))
        assertFalse(home.matches(1, 10, 3, DemoPolicy.HOME))
        assertFalse(home.matches(1, 9, 4, DemoPolicy.HOME))
        assertFalse(home.matches(1, 9, 3, "https://example.org"))
        assertNull(RecentlyClosedTabs().latest)
    }

    @Test fun stoppingThenReceivingAnUnsuccessfulCompletionIsNotAVisitOrError() {
        val load = PageLoadState()
        load.start(); assertTrue(load.loading)
        load.stop(); assertFalse(load.loading)
        assertFalse(load.finish(false)); assertFalse(load.error)
        assertFalse(load.finish(true)); assertFalse(load.error)
    }

    @Test fun navigationAfterStoppingResetsCancellationAndCanSucceedOrFailNormally() {
        val load = PageLoadState()
        load.start(); load.stop(); load.finish(false)
        load.start(); assertTrue(load.loading)
        assertTrue(load.finish(true)); assertFalse(load.error)
        load.start(); assertFalse(load.finish(false)); assertTrue(load.error)
    }

    @Test fun explicitNetworkOrCertificateFailureIsNeverHiddenByAStopMarker() {
        val load = PageLoadState()
        load.start(); load.stop(); load.fail()
        assertTrue(load.error)
        assertFalse(load.finish(false)); assertTrue(load.error)
        load.start(); load.fail()
        assertFalse(load.finish(true)); assertTrue(load.error)
        load.start(); assertFalse(load.error)
    }
}
