package org.rocks.geckodemo

import org.jsoup.Jsoup
import org.rocks.browser.core.AddressResolver
import java.io.*
import java.net.URI
import java.security.MessageDigest

/** 图标网络与缓存的固定边界，纯 JVM 测试不依赖 Android 或真实网络。 */
internal object SiteIconPolicy {
    const val BYTE_LIMIT = 256 * 1024
    const val MEMORY_LIMIT = 4 * 1024 * 1024
    const val DISK_LIMIT = 10L * 1024 * 1024
    const val SUCCESS_TTL = 7L * 24 * 60 * 60 * 1000
    const val FAILURE_TTL = 30L * 60 * 1000
    const val TIMEOUT = 5000L

    /** 图标地址只接受无凭据的网页协议；所有重定向也必须重新经过此检查。 */
    fun safeUrl(value: String, previous: String? = null): String? = runCatching {
        val uri = URI(AddressResolver.normalizeWebUrl(value) ?: return null)
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if (previous != null && URI(previous).scheme.equals("https", true) && uri.scheme.equals("http", true)) return null
        // 保留原始百分号编码，不能把路径里的 %2F 解码成真实分隔符。
        val host = uri.host.lowercase().let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        val authority = host + if (uri.port >= 0) ":${uri.port}" else ""
        URI("${uri.scheme.lowercase()}://$authority${uri.rawPath.orEmpty().ifEmpty { "/" }}" +
            (uri.rawQuery?.let { "?$it" } ?: "")).toASCIIString()
    }.getOrNull()

    /** 只解析 HTML 元数据，不执行脚本；候选最多三项，最后保留标准站点图标回退。 */
    fun candidates(page: String, html: String): List<String> {
        val safe = safeUrl(page) ?: return emptyList()
        val document = Jsoup.parse(html, safe)
        val declared = document.select("head link[rel][href]").filter { link ->
            link.attr("rel").lowercase().split(Regex("\\s+")).any {
                it == "icon" || it == "apple-touch-icon" || it == "apple-touch-icon-precomposed"
            }
        }.mapNotNull { safeUrl(it.absUrl("href"), safe) }.distinct()
        val fallback = URI(safe).resolve("/favicon.ico").toASCIIString()
        return (declared.filterNot { it == fallback }.take(2) + fallback).distinct()
    }

    /** 缓存文件名不包含网址、账号或查询参数。 */
    fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** 读取流时校验实际体积，不能仅相信服务器的 Content-Length。 */
    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > BYTE_LIMIT) throw IOException("图标资源超过限制")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

internal data class DiskIconEntry(val expires: Long, val png: ByteArray?)

/** 图标属于可丢弃缓存，不参与浏览数据契约；损坏文件会失效而不影响书签历史。 */
internal class SiteIconDiskCache(private val directory: File, private val limit: Long = SiteIconPolicy.DISK_LIMIT) {
    /** 每次读取核对格式、有效期及长度，失败记录也受同一个过期策略约束。 */
    @Synchronized fun read(key: String, now: Long): DiskIconEntry? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val result = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == 0x52494331)
                val expires = input.readLong()
                require(expires > now)
                val count = input.readInt()
                require(count in 0..SiteIconPolicy.BYTE_LIMIT && file.length() == 16L + count)
                DiskIconEntry(expires, if (count == 0) null else ByteArray(count).also(input::readFully))
            }
        }.getOrNull()
        if (result == null) file.delete() else file.setLastModified(now)
        return result
    }

    /** 原子替换单个缓存，容量按访问时间淘汰；只操作本目录中自有的摘要文件。 */
    @Synchronized fun write(key: String, entry: DiskIconEntry) {
        require((entry.png?.size ?: 0) <= SiteIconPolicy.BYTE_LIMIT)
        directory.mkdirs()
        val destination = fileFor(key)
        val temporary = File.createTempFile("icon-", ".tmp", directory)
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { out ->
                out.writeInt(0x52494331); out.writeLong(entry.expires)
                out.writeInt(entry.png?.size ?: 0); entry.png?.let(out::write)
            }
            try {
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            var total = 0L
            directory.listFiles { file -> file.name.matches(Regex("[a-f0-9]{64}\\.icon")) }
                .orEmpty().sortedByDescending { it.lastModified() }.forEach { file ->
                    total += file.length()
                    if (total > limit) file.delete()
                }
        } finally { temporary.delete() }
    }

    private fun fileFor(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.icon")
    }
}

/** 按实际字节数淘汰，不回收仍可能被界面使用的 Bitmap。 */
internal class SiteIconMemoryCache<T>(private val limit: Int, private val weight: (T) -> Int) {
    private data class Entry<T>(val value: T?, val expires: Long)
    private val entries = LinkedHashMap<String, Entry<T>>(16, .75f, true)
    @Synchronized fun get(key: String, now: Long): Pair<Boolean, T?> {
        val entry = entries[key] ?: return false to null
        if (entry.expires <= now) { entries.remove(key); return false to null }
        return true to entry.value
    }
    @Synchronized fun put(key: String, value: T?, expires: Long) {
        entries[key] = Entry(value, expires)
        var total = entries.values.sumOf { it.value?.let(weight) ?: 1 }
        val iterator = entries.iterator()
        while (total > limit && iterator.hasNext()) {
            total -= iterator.next().value.value?.let(weight) ?: 1
            iterator.remove()
        }
    }
    @Synchronized fun clear() { entries.clear() }
}
