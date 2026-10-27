package org.rocks.geckodemo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test

class BrowserFindTest {
    private class Page(override val scope: BrowserPageScope = BrowserPageScope(1, 1, "https://example.org")) : FindPage {
        override val identity = Any()
        val replies = mutableListOf<(FindMatch) -> Unit>()
        var clears = 0
        override fun highlightAll() {}
        override fun find(query: String?, backwards: Boolean, result: (FindMatch) -> Unit, failed: () -> Unit) { replies += result }
        override fun clear() { clears++ }
    }
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun resultArrivingWhilePausedDoesNotLeaveTheStripBusy() {
        val target = Page(); var foreground: FindPage? = target
        val finder = BrowserFind(jobs, { foreground }, 0)
        finder.open(); finder.change("word"); assertTrue(finder.busy)
        foreground = null
        target.replies.single()(FindMatch(1, 7))
        assertFalse(finder.busy); assertEquals(7, finder.total)
        foreground = target; finder.resumed()
        assertEquals(1, target.replies.size)
    }
    @Test fun searchDeferredBeforeDispatchIsRetriedOnResume() {
        val target = Page(); var foreground: FindPage? = target
        val finder = BrowserFind(jobs, { foreground }, 0)
        finder.open(); foreground = null; finder.change("word")
        assertTrue(target.replies.isEmpty()); assertFalse(finder.busy)
        foreground = target; finder.resumed()
        assertTrue(finder.busy)
        target.replies.single()(FindMatch(1, 2))
        assertFalse(finder.busy); assertEquals(2, finder.total)
    }
    @Test fun lateResultCannotOverwriteAReplacementTabSearch() {
        val first = Page(); val second = Page(BrowserPageScope(2, 1, "https://example.org"))
        var selected: FindPage? = first
        val finder = BrowserFind(jobs, { selected }, 0)
        finder.open(); finder.change("old")
        finder.close(); selected = second; finder.open(); finder.change("new")
        first.replies.single()(FindMatch(9, 99))
        assertTrue(finder.busy); assertEquals("new", finder.query); assertEquals(0, finder.total)
        second.replies.single()(FindMatch(1, 3))
        assertEquals(3, finder.total); assertFalse(finder.busy)
    }
    @Test fun clearingQueryInvalidatesAnOutstandingNativeResult() {
        val target = Page()
        val finder = BrowserFind(jobs, { target }, 0)
        finder.open(); finder.change("word"); finder.change("")
        target.replies.single()(FindMatch(1, 10))
        assertEquals("", finder.query); assertEquals(0, finder.total); assertFalse(finder.busy)
        assertTrue(target.clears > 0)
    }
}
