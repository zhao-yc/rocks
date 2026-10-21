package org.rocks.browser.core

import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    private val click = NavigationContext(isMainFrame = true, hasGesture = true,
        isActiveTab = true, isResumed = true, isCurrentView = true)
    private val appLinks = listOf("tel:+8612345", "mailto:test@example.com", "sms:12345", "geo:0,0?q=Beijing")
    private val unsupported = listOf("baiduboxapp://open", "intent://open#Intent;end", "weixin://open",
        "javascript:alert(1)", "file:///private", "data:text/html,hello", "about:blank")

    @Test fun automaticAppLaunchesAreBlockedWithoutPromptsOrHints() {
        (appLinks + unsupported).forEach { url ->
            assertEquals(url, NavigationDecision.BLOCK_SILENTLY,
                NavigationPolicy.decide(url, click.copy(hasGesture = false)))
        }
    }

    @Test fun repeatedAutomaticLaunchesNeverTurnIntoAPrompt() {
        repeat(100) {
            assertEquals(NavigationDecision.BLOCK_SILENTLY,
                NavigationPolicy.decide("baiduboxapp://open", click.copy(hasGesture = false)))
        }
    }

    @Test fun directClicksOnSupportedSchemesRequireConfirmation() {
        (appLinks + "TEL:+8612345").forEach { url ->
            assertEquals(url, NavigationDecision.CONFIRM_EXTERNAL, NavigationPolicy.decide(url, click))
        }
    }

    @Test fun directUnsupportedClicksOnlyProduceANonBlockingHint() {
        unsupported.forEach { url ->
            assertEquals(url, NavigationDecision.HINT_UNSUPPORTED, NavigationPolicy.decide(url, click))
        }
    }

    @Test fun subframesBackgroundTabsPausedActivitiesAndStaleViewsCannotPrompt() {
        listOf(click.copy(isMainFrame = false), click.copy(isActiveTab = false),
            click.copy(isResumed = false), click.copy(isCurrentView = false)).forEach { context ->
            (appLinks + unsupported).forEach { url ->
                assertEquals(url, NavigationDecision.BLOCK_SILENTLY, NavigationPolicy.decide(url, context))
            }
        }
    }

    @Test fun ordinaryWebNavigationAndFramesStillLoadWithoutLeavingTheBrowser() {
        listOf("https://www.baidu.com/", "http://example.com/").forEach { url ->
            listOf(click, click.copy(hasGesture = false), click.copy(isMainFrame = false),
                click.copy(isActiveTab = false)).forEach { context ->
                assertEquals(NavigationDecision.LOAD_WEB, NavigationPolicy.decide(url, context))
            }
            assertEquals(NavigationDecision.BLOCK_SILENTLY,
                NavigationPolicy.decide(url, click.copy(isCurrentView = false)))
        }
    }

    @Test fun malformedExternalUrlsAreNeverConfirmed() {
        listOf("tel:", "mailto:", "tel:123\n456", "tel:" + "1".repeat(8192), "geo:bad coordinate").forEach { url ->
            assertEquals(url.take(40), NavigationDecision.HINT_UNSUPPORTED, NavigationPolicy.decide(url, click))
        }
    }

    @Test fun onlyForegroundUserActionsMayShowBlockedPopupFeedback() {
        assertTrue(click.mayPromptUser)
        assertFalse(click.copy(hasGesture = false).mayPromptUser)
        assertFalse(click.copy(isMainFrame = false).mayPromptUser)
        assertFalse(click.copy(isActiveTab = false).mayPromptUser)
        assertFalse(click.copy(isResumed = false).mayPromptUser)
        assertFalse(click.copy(isCurrentView = false).mayPromptUser)
    }
}
