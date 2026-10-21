package org.rocks.geckodemo

import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs
import org.rocks.browser.core.AddressResolver
import org.rocks.browser.core.SearchEngine

internal object DemoPolicy {
    const val HOME = "about:blank"
    const val PAPER_ID = "paper@rocks.demo"
    const val DARK_READER_ID = "addon@darkreader.org"
    const val MAX_TABS = 24

    fun isWebUrl(value: String): Boolean = AddressResolver.isWebUrl(value)

    fun inputUrl(text: String, search: SearchEngine = SearchEngine.BING): String? =
        AddressResolver.resolve(text, search).url

    fun safeFilename(name: String?): String = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.').take(120).ifBlank { "download" }

    fun canUpload(schemes: List<String?>, multiple: Boolean): Boolean =
        schemes.size in 1..(if (multiple) 20 else 1) && schemes.all { it == "content" }

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
