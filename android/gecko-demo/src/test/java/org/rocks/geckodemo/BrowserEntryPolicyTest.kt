package org.rocks.geckodemo

import org.junit.Assert.*
import org.junit.Test

class BrowserEntryPolicyTest {
    @Test fun externalIntentsAcceptOnlyBoundedWebLinksWithoutSearchOrPrivilegedSchemes() {
        assertEquals("https://example.org/a?q=1#x", BrowserEntryPolicy.externalUrl("android.intent.action.VIEW", "https://example.org/a?q=1#x"))
        assertNotNull(BrowserEntryPolicy.externalUrl("android.intent.action.VIEW", "http://example.org"))
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "content://private/file", "intent://test", "moz-extension://x/", "about:config", "https://user:pass@example.org", "example.org", "https://example.org/"+"a".repeat(8192)))
            assertNull(BrowserEntryPolicy.externalUrl("android.intent.action.VIEW", url))
        assertNull(BrowserEntryPolicy.externalUrl("android.intent.action.SEND", "https://example.org"))
    }
    @Test fun originsNormalizeHostAndDefaultPortButKeepSubdomainsAndNonDefaultPortsSeparate() {
        assertEquals("https://example.org", BrowserEntryPolicy.secureOrigin("https://EXAMPLE.org:443/a"))
        assertNotEquals(BrowserEntryPolicy.secureOrigin("https://example.org"), BrowserEntryPolicy.secureOrigin("https://example.org:8443"))
        assertNull(BrowserEntryPolicy.secureOrigin("http://example.org"))
        assertNull(BrowserEntryPolicy.secureOrigin("https://user@example.org"))
    }
    @Test fun permissionsCannotFollowTabSwitchReloadSameUrlOrCrossOriginFrames() {
        val scope = BrowserPageScope(1, 2, "https://example.org/a")
        assertTrue(BrowserEntryPolicy.canAuthorize(scope, scope, "https://example.org/media"))
        assertFalse(BrowserEntryPolicy.canAuthorize(scope, scope.copy(tabId=2), scope.url))
        assertFalse(BrowserEntryPolicy.canAuthorize(scope, scope.copy(revision=3), scope.url))
        assertFalse(BrowserEntryPolicy.canAuthorize(scope, scope, "https://example.org.evil/a"))
        assertFalse(BrowserEntryPolicy.canAuthorize(scope, null, scope.url))
    }
    @Test fun twoStageApprovalIsScopedExpiresAndCanBeConsumedOnlyOnce() {
        val gate = PermissionHandshake()
        val page = BrowserPageScope(1, 3, "https://example.org")
        val media = setOf(SiteCapability.CAMERA, SiteCapability.MICROPHONE)
        gate.issue(page, media, 100)
        assertTrue(gate.consume(page, media, 200))
        assertFalse(gate.consume(page, media, 201))
        gate.issue(page, media, 100)
        assertFalse(gate.consume(page.copy(revision=4), media, 101))
        gate.issue(page, media, 100)
        assertFalse(gate.consume(page, media, 15_100))
        gate.issue(page, setOf(SiteCapability.CAMERA), 100)
        assertFalse(gate.consume(page, media, 101))
    }
}
