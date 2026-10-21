package org.rocks.geckodemo

import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs

internal object DemoPolicy {
    const val HOME = "about:blank"
    const val PAPER_ID = "paper@rocks.demo"
    const val DARK_READER_ID = "addon@darkreader.org"
    const val MAX_TABS = 8

    fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
            value.length <= 16384 && value.none { it.isISOControl() }
    }.getOrDefault(false)

    fun inputUrl(text: String): String? {
        val input = text.trim()
        if (input.isEmpty() || input.length > 16384) return null
        if (isWebUrl(input)) return input
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(input)) return null
        if (!input.any(Char::isWhitespace) && input.contains('.')) {
            val candidate = "https://$input"
            if (isWebUrl(candidate)) return candidate
        }
        return "https://www.baidu.com/s?wd=" + URLEncoder.encode(input, "UTF-8")
    }

    fun canLoad(value: String, extensionBase: String? = null): Boolean =
        value == HOME || isWebUrl(value) ||
            (extensionBase?.startsWith("moz-extension://") == true &&
                extensionBase.endsWith('/') && value.startsWith(extensionBase))

    fun swipeTarget(start: Int, offsetPages: Float, velocity: Float, count: Int): Int {
        val direction = when {
            abs(offsetPages) >= 0.18f -> if (offsetPages > 0) 1 else -1
            abs(velocity) >= 650f -> if (velocity < 0) 1 else -1
            else -> 0
        }
        return (start + direction).coerceIn(0, (count - 1).coerceAtLeast(0))
    }
}
