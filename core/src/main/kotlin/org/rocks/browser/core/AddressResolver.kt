package org.rocks.browser.core

import java.net.IDN
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class NavigationTarget(val url: String? = null, val error: String? = null)

object AddressResolver {
    private val schemePattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** 仅将 HTTP(S) 交给网页引擎；搜索词编码后进入用户选择的引擎。 */
    fun resolve(input: String, engine: SearchEngine): NavigationTarget {
        val value = input.trim()
        if (value.isEmpty()) return NavigationTarget(error = "请输入网址或搜索词")
        if (value.length > 8192 || value.any { it.code < 32 }) return NavigationTarget(error = "网址无效")
        val explicitWeb = value.startsWith("https://", true) || value.startsWith("http://", true)
        val hostLike = !value.any(Char::isWhitespace) &&
            (value.substringBefore('/').contains('.') || value.startsWith("localhost") || value.startsWith("["))
        if (explicitWeb || hostLike) {
            val normalized = normalizeWebUrl(if (explicitWeb) value else "https://$value")
            return if (normalized != null) NavigationTarget(normalized) else NavigationTarget(error = "网站地址无效")
        }
        if (schemePattern.containsMatchIn(value)) return NavigationTarget(error = "仅支持 HTTP 和 HTTPS 网址")
        return NavigationTarget(engine.prefix + URLEncoder.encode(value, StandardCharsets.UTF_8.name()))
    }

    fun isWebUrl(value: String) = normalizeWebUrl(value) != null

    /** 拒绝 URL 内嵌账号、无效端口和控制字符，统一国际域名。 */
    fun normalizeWebUrl(value: String): String? = runCatching {
        if (value.length > 8192 || value.any { it.code <= 32 || it == '\\' }) return null
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val authority = uri.rawAuthority ?: return null
        if (authority.contains('@') || authority.contains('%')) return null
        val host = uri.host ?: authority.substringBefore(':')
        if (host.isBlank()) return null
        val asciiHost = if (host.startsWith("[")) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        val suffix = authority.removePrefix(host)
        if (suffix.isNotEmpty() && (!suffix.startsWith(':') || suffix.drop(1).toIntOrNull() !in 1..65535)) return null
        URI("$scheme://$asciiHost$suffix${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}${uri.rawFragment?.let { "#$it" }.orEmpty()}").toASCIIString()
    }.getOrNull()
}
