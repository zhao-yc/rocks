package org.rocks.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.rocks.browser.core.BrowserData

/** 组件级无网络回归，不读取或修改应用的持久化会话。 */
class BrowserInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tappingAddressSelectsFullUrlAndCancelDiscardsTheDraft() {
        val url = "https://example.com/path?q=rocks"
        var navigated: String? = null
        rule.setContent {
            var editing by remember { mutableStateOf(false) }
            MaterialTheme {
                BrowserAddressBar("tab", url, 100, editing, { editing = it },
                    onNavigate = { navigated = it; true }, onSwitchTab = {}, onReloadOrStop = {})
            }
        }
        rule.onNodeWithTag("address-bar").performTouchInput { click() }
        rule.onNodeWithTag("address-input").assertTextContains(url)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, url.length)))
            .performTextReplacement("unfinished search")
        rule.onNodeWithTag("cancel-address").performClick()
        rule.onNodeWithTag("address-input").assertDoesNotExist()
        rule.onNodeWithTag("address-bar").performTouchInput { click() }
        rule.onNodeWithTag("address-input").assertTextContains(url)
        rule.runOnIdle { assertNull(navigated) }
    }

    @Test fun invalidSubmissionKeepsDraftAndSuccessfulSubmissionClosesEditor() {
        var accepted: String? = null
        rule.setContent {
            var editing by remember { mutableStateOf(false) }
            MaterialTheme {
                BrowserAddressBar("tab", "", 100, editing, { editing = it },
                    onNavigate = { if (it == "example.com") { accepted = it; true } else false },
                    onSwitchTab = {}, onReloadOrStop = {})
            }
        }
        rule.onNodeWithTag("address-bar").performTouchInput { click() }
        rule.onNodeWithTag("address-input").performTextReplacement("file:///blocked").performImeAction()
        rule.onNodeWithTag("address-input").assertTextContains("file:///blocked")
        rule.onNodeWithTag("address-input").performTextReplacement("example.com").performImeAction()
        rule.onNodeWithTag("address-input").assertDoesNotExist()
        rule.runOnIdle { assertEquals("example.com", accepted) }
    }

    @Test fun horizontalAddressSwipesSwitchTabsButShortDragsDoNot() {
        val directions = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                BrowserAddressBar("tab", "", 100, false, {}, { true },
                    onSwitchTab = { directions += it }, onReloadOrStop = {})
            }
        }
        rule.onNodeWithTag("address-bar").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX + 10f, centerY))
        }
        rule.runOnIdle { assertTrue(directions.isEmpty()) }
        rule.onNodeWithTag("address-bar").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("address-bar").performTouchInput { swipeRight() }
        rule.runOnIdle { assertEquals(listOf(true, false), directions) }
    }

    @Test fun overviewHighlightsCurrentTabAndCanSelectOrCreateATab() {
        val data = BrowserData().openTab()
        var selected: String? = null
        var created = false
        var done = false
        rule.setContent {
            MaterialTheme {
                TabOverview(data, onSelect = { selected = it }, onClose = {}, onNew = { created = true }, onDone = { done = true })
            }
        }
        rule.onNodeWithTag("tab-card-${data.activeTabId}").assertIsSelected()
        rule.onNodeWithTag("tab-card-${data.tabs.first().id}").performClick()
        rule.onNodeWithTag("add-tab").performClick()
        rule.onNodeWithTag("done-tabs").performClick()
        rule.runOnIdle { assertEquals(data.tabs.first().id, selected); assertTrue(created); assertTrue(done) }
    }

    @Test fun swipingAwayTheLastTabLeavesANewHomeCard() {
        var data by mutableStateOf(BrowserData())
        val originalId = data.activeTabId
        rule.setContent {
            MaterialTheme {
                TabOverview(data, onSelect = {}, onClose = { data = data.closeTab(it) }, onNew = {}, onDone = {})
            }
        }
        rule.onNodeWithTag("tab-card-$originalId").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("tab-card-$originalId").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, data.tabs.size); assertEquals("", data.activeTab.url) }
        rule.onNodeWithTag("tab-card-${data.activeTabId}").assertIsSelected()
    }
}
