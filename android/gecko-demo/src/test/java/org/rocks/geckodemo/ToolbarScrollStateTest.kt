package org.rocks.geckodemo

import org.junit.Assert.*
import org.junit.Test

class ToolbarScrollStateTest {
    @Test fun readingCollapsesAndReverseScrollRestoresWithoutChangingPagePosition() {
        val state = ToolbarScrollState()
        state.onScroll(0f, 1f, true, false)
        assertFalse(state.onScroll(30f, 1f, true, false))
        assertTrue(state.onScroll(65f, 1f, true, false))
        assertTrue(state.onScroll(60f, 1f, true, false))
        assertFalse(state.onScroll(48f, 1f, true, false))
    }

    @Test fun ScriptsZoomAndPinnedInteractionsCannotHideTheToolbar() {
        val state = ToolbarScrollState()
        state.onScroll(0f, 1f, false, false)
        assertFalse(state.onScroll(900f, 1f, false, false))
        assertFalse(state.onScroll(1500f, 2f, true, false))
        assertFalse(state.onScroll(1700f, 2f, true, true))
        assertFalse(state.onScroll(1730f, 2f, true, false))
        assertTrue(state.onScroll(1760f, 2f, true, false))
    }

    @Test fun DirectionChangesDiscardJitterAndTopAlwaysRestores() {
        val state = ToolbarScrollState()
        state.onScroll(100f, 1f, true, false)
        repeat(20) {
            assertFalse(state.onScroll(110f, 1f, true, false))
            assertFalse(state.onScroll(100f, 1f, true, false))
        }
        assertTrue(state.onScroll(180f, 1f, true, false))
        assertFalse(state.onScroll(-8f, 1f, true, false))
    }

    @Test fun ExplicitExpansionAndNewNavigationDiscardOldMomentum() {
        val state = ToolbarScrollState()
        state.onScroll(0f, 1f, true, false)
        state.onScroll(90f, 1f, true, false)
        state.expand()
        assertFalse(state.collapsed)
        assertFalse(state.onScroll(100f, 1f, true, false))
        state.reset()
        assertFalse(state.onScroll(2000f, 1f, true, false))
        assertFalse(state.onScroll(Float.NaN, 1f, true, false))
    }
}
