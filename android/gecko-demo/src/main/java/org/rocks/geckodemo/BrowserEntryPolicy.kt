package org.rocks.geckodemo

import java.net.URI
import java.util.Locale

internal data class BrowserPageScope(val tabId: Long, val revision: Long, val url: String)
internal enum class SiteCapability { CAMERA, MICROPHONE, LOCATION }

internal object BrowserEntryPolicy {
    fun externalUrl(action: String?, value: String?): String? = value?.takeIf {
        action == "android.intent.action.VIEW" && it.length <= 8192 && DemoPolicy.isWebUrl(it)
    }

    fun secureOrigin(value: String): String? = runCatching {
        if (!DemoPolicy.isWebUrl(value)) return null
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrEmpty()) return null
        URI("https", null, uri.host.lowercase(Locale.ROOT), if (uri.port == 443) -1 else uri.port,
            null, null, null).toASCIIString()
    }.getOrNull()

    fun canAuthorize(request: BrowserPageScope, current: BrowserPageScope?, requestingUri: String): Boolean =
        request == current && secureOrigin(request.url)?.let { it == secureOrigin(requestingUri) } == true
}

/** The Android and Gecko media prompts are two stages of one user decision, not a site allow-list. */
internal class PermissionHandshake {
    private var page: BrowserPageScope? = null
    private var capabilities = emptySet<SiteCapability>()
    private var expires = 0L
    fun issue(scope: BrowserPageScope, requested: Set<SiteCapability>, now: Long) {
        page = scope; capabilities = requested.toSet(); expires = now + 15_000L
    }
    fun consume(scope: BrowserPageScope, requested: Set<SiteCapability>, now: Long): Boolean {
        val allowed = page == scope && now < expires && requested.isNotEmpty() && capabilities.containsAll(requested)
        clear()
        return allowed
    }
    fun clear() { page = null; capabilities = emptySet(); expires = 0L }
}
