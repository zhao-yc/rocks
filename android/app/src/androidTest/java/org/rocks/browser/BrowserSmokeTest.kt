package org.rocks.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** 无网络的基本 UI 回归；真实网页、证书和设备接口另按验收清单验证。 */
class BrowserSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeCanOpenAndCloseTabsThroughBottomControls() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("tabs-button").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tabs-button").assertTextContains("1", substring = true).performClick()
        rule.onNodeWithTag("add-tab").performClick()
        rule.onNodeWithTag("tabs-button").assertTextContains("2", substring = true).performClick()
        rule.onAllNodesWithTag("close-tab").onFirst().performClick()
        rule.onAllNodesWithTag("close-tab").onFirst().performClick()
        rule.onNodeWithTag("add-tab").performClick()
        rule.onNodeWithTag("tabs-button").assertTextContains("2", substring = true)
    }
}
