package org.rocks.browser.core

import java.net.URI
import java.util.Locale

data class NavigationContext(
    val isMainFrame: Boolean,
    val hasGesture: Boolean,
    val isActiveTab: Boolean,
    val isResumed: Boolean,
    val isCurrentView: Boolean,
) {
    /** 只有用户正在操作的顶层页面才可以提示或请求打开其他应用。 */
    val mayPromptUser: Boolean get() = isMainFrame && hasGesture && isActiveTab && isResumed && isCurrentView
}

enum class NavigationDecision { LOAD_WEB, CONFIRM_EXTERNAL, HINT_UNSUPPORTED, BLOCK_SILENTLY }

object NavigationPolicy {
    private val externalSchemes = setOf("mailto", "tel", "sms", "geo")

    fun decide(url: String, context: NavigationContext): NavigationDecision {
        if (!context.isCurrentView) return NavigationDecision.BLOCK_SILENTLY
        if (AddressResolver.isWebUrl(url)) return NavigationDecision.LOAD_WEB
        if (!context.mayPromptUser) return NavigationDecision.BLOCK_SILENTLY
        val scheme = if (url.length <= 8192 && url.none { it.code < 32 || it.code == 127 }) {
            runCatching { URI(url).takeIf { !it.rawSchemeSpecificPart.isNullOrBlank() }?.scheme?.lowercase(Locale.ROOT) }.getOrNull()
        } else null
        return if (scheme in externalSchemes) NavigationDecision.CONFIRM_EXTERNAL
        else NavigationDecision.HINT_UNSUPPORTED
    }
}
