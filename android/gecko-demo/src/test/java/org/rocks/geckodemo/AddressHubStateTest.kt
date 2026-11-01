package org.rocks.geckodemo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.rocks.browser.core.PageRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AddressHubStateTest {
    @Test fun addressEntrySelectsAllAndRequestsTheKeyboard() {
        val state = AddressHubState()
        state.open(7, "https://example.org/path")
        assertTrue(state.visible)
        assertEquals(HubInput.ADDRESS, state.input)
        assertEquals(TextRange(0, state.address.text.length), state.address.selection)
        assertFalse(state.showingMatches)
        assertEquals(7L, state.ownerTabId)
    }

    @Test fun changingCategoryPreservesDraftSelectionAndIndependentSearches() {
        val state = AddressHubState()
        state.open(7, "https://example.org")
        val draft = TextFieldValue("中文草稿", TextRange(1, 3))
        state.changeAddress(draft)
        assertTrue(state.showingMatches)
        state.bookmarkQuery = "收藏"
        state.historyQuery = "历史"
        state.positions[AddressHubPage.OVERVIEW] = HubScrollPosition(3, 11)
        state.browse(AddressHubPage.BOOKMARKS)
        state.changeAddress(TextFieldValue("迟到的输入法更新", TextRange.Zero))
        assertEquals(HubInput.NONE, state.input)
        assertFalse(state.showingMatches)
        state.editAddress()
        assertEquals(draft, state.address)
        assertEquals("收藏", state.bookmarkQuery)
        assertEquals("历史", state.historyQuery)
        assertEquals(HubScrollPosition(3, 11), state.positions[AddressHubPage.OVERVIEW])
    }

    @Test fun clearingAndClosingReturnToOverviewAndDiscardTemporaryState() {
        val state = AddressHubState()
        state.open(7, "https://example.org", AddressHubPage.HISTORY, edit = false)
        assertEquals(HubInput.NONE, state.input)
        state.error = AddressInputError.INVALID_URL
        state.clearAddress()
        assertEquals("", state.address.text)
        assertFalse(state.showingMatches)
        assertEquals(AddressHubPage.OVERVIEW, state.page)
        assertNull(state.error)
        state.bookmarkQuery = "旧查询"
        state.collapsedDates[LocalDate.of(2026, 11, 1)] = true
        state.close()
        state.open(8, DemoPolicy.HOME)
        assertTrue(state.visible)
        assertEquals("", state.bookmarkQuery)
        assertTrue(state.collapsedDates.isEmpty())
        assertEquals("", state.address.text)
    }

    @Test fun datesUseLocalMidnightAndOrderTheMostRecentVisitFirst() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun record(name: String, time: String) = PageRecord("https://example.org/$name", name, Instant.parse(time).toEpochMilli())
        val records = listOf(record("昨天", "2026-10-31T15:59:59Z"), record("凌晨", "2026-10-31T16:00:00Z"),
            record("晚间", "2026-11-01T14:00:00Z"))
        val groups = HistoryDateSections.group(records, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31)), groups.map { it.date })
        assertEquals(listOf("晚间", "凌晨"), groups.first().records.map { it.title })
        assertEquals(listOf("凌晨"), HistoryDateSections.group(LocalBrowserSearch.filter(records, "凌晨"), zone).single().records.map { it.title })
        assertTrue(HistoryDateSections.group(emptyList(), zone).isEmpty())
    }

    @Test fun daylightSavingAndYearBoundariesDoNotUseFixedTwentyFourHourBuckets() {
        val records = listOf(PageRecord("https://example.org/a", "", Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()),
            PageRecord("https://example.org/b", "", Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()))
        assertEquals(1, HistoryDateSections.group(records, ZoneId.of("America/New_York")).size)
        val newYear = PageRecord("https://example.org", "", Instant.parse("2026-12-31T16:00:00Z").toEpochMilli())
        assertEquals(LocalDate.of(2027, 1, 1), HistoryDateSections.group(listOf(newYear), ZoneId.of("Asia/Shanghai")).single().date)
    }

    @Test fun specifiedRecoveryAndClearingNeverFallBackToAnotherClosure() {
        val closed = RecentlyClosedTabs()
        val old = closed.record("https://example.org/same", "旧", 0, true, TabCloseCause.USER)!!
        val latest = closed.record("https://example.org/same", "新", 1, false, TabCloseCause.USER)!!
        val snapshot = closed.snapshot
        assertNull(closed.forRestore(24, false, old.closureId))
        assertEquals(old, closed.forRestore(2, false, old.closureId))
        closed.consume(old)
        assertEquals(listOf(latest), closed.snapshot)
        assertEquals(2, snapshot.size)
        assertNull(closed.forRestore(2, false, old.closureId))
        closed.clear()
        assertTrue(closed.snapshot.isEmpty())
        assertNull(closed.forRestore(1, false, latest.closureId))
    }
}
