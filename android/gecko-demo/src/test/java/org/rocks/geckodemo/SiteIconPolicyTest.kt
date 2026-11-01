package org.rocks.geckodemo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class SiteIconPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun metadataSupportsRelativePathsBaseAndAppleIconsWithoutExecutingScripts() {
        val html = """<html><head><base href="https://cdn.example.org/assets/"><link rel="ICON" href="one.png">
            <link rel="apple-touch-icon" href="/touch.png"><script>throw new Error('不执行')</script></head></html>"""
        assertEquals(listOf("https://cdn.example.org/assets/one.png", "https://cdn.example.org/touch.png", "https://example.org/favicon.ico"),
            SiteIconPolicy.candidates("https://example.org/path/page", html))
    }

    @Test fun missingIconsAndDuplicatesUseABoundedStandardFallback() {
        assertEquals(listOf("https://example.org/favicon.ico"), SiteIconPolicy.candidates("https://example.org/path", ""))
        val html = (1..10).joinToString("") { "<link rel='shortcut icon' href='/icon$it.png'>" }
        assertEquals(3, SiteIconPolicy.candidates("https://example.org", "<head>$html</head>").size)
        assertEquals(listOf("https://example.org/favicon.ico"), SiteIconPolicy.candidates("https://example.org", "<head><link rel='icon' href='/favicon.ico'></head>"))
    }

    @Test fun credentialsUnsafeSchemesAndHttpsDowngradesAreRejected() {
        listOf("file:///secret", "data:image/png,abc", "javascript:alert(1)", "https://user:pass@example.org/icon", "https://example.org:99999/icon").forEach {
            assertNull(SiteIconPolicy.safeUrl(it))
        }
        assertNull(SiteIconPolicy.safeUrl("http://example.org/icon", "https://example.org/page"))
        assertEquals("https://cdn.example.org/icon", SiteIconPolicy.safeUrl("https://cdn.example.org/icon", "https://example.org/page"))
        assertEquals(listOf("https://example.org/favicon.ico"), SiteIconPolicy.candidates("https://example.org", "<head><link rel='icon' href='http://example.org/icon'></head>"))
    }

    @Test fun encodedPathsAndChineseDomainsArePreserved() {
        assertEquals("https://example.org/a%2Fb?q=x%26y", SiteIconPolicy.safeUrl("https://EXAMPLE.org/a%2Fb?q=x%26y#section"))
        assertNotNull(SiteIconPolicy.safeUrl("https://中文.example/图标.png"))
        assertEquals(64, SiteIconPolicy.key("https://example.org/?private=query").length)
    }

    @Test fun streamedSizeIsCheckedEvenWhenHeadersLie() {
        assertEquals(SiteIconPolicy.BYTE_LIMIT, SiteIconPolicy.readBounded(ByteArrayInputStream(ByteArray(SiteIconPolicy.BYTE_LIMIT))).size)
        try { SiteIconPolicy.readBounded(ByteArrayInputStream(ByteArray(SiteIconPolicy.BYTE_LIMIT + 1))); fail("超限流必须拒绝") }
        catch (_: IOException) { }
    }

    @Test fun diskCacheRetainsSuccessFailureExpiryAndRejectsCorruptData() {
        val cache = SiteIconDiskCache(temporary.root)
        val key = SiteIconPolicy.key("https://example.org/")
        cache.write(key, DiskIconEntry(1000, byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.read(key, 999)!!.png)
        assertNull(cache.read(key, 1000))
        cache.write(key, DiskIconEntry(2000, null))
        assertNull(cache.read(key, 1001)!!.png)
        File(temporary.root, "$key.icon").writeBytes(byteArrayOf(1, 2))
        assertNull(cache.read(key, 1002))
        assertFalse(File(temporary.root, "$key.icon").exists())
    }

    @Test fun diskBudgetEvictsOnlyOwnedCacheFiles() {
        val cache = SiteIconDiskCache(temporary.root, 35)
        val first = SiteIconPolicy.key("https://first.example/")
        val second = SiteIconPolicy.key("https://second.example/")
        File(temporary.root, "unrelated.txt").writeText("不能删除")
        cache.write(first, DiskIconEntry(Long.MAX_VALUE, ByteArray(8)))
        File(temporary.root, "$first.icon").setLastModified(1)
        cache.write(second, DiskIconEntry(Long.MAX_VALUE, ByteArray(8)))
        assertNull(cache.read(first, 0))
        assertNotNull(cache.read(second, 0))
        assertTrue(File(temporary.root, "unrelated.txt").exists())
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun memoryBudgetUsesRecencyAndDistinguishesFailureFromMiss() {
        val cache = SiteIconMemoryCache<ByteArray>(5) { it.size }
        cache.put("a", ByteArray(2), 100)
        cache.put("b", ByteArray(2), 100)
        assertTrue(cache.get("a", 0).first)
        cache.put("c", ByteArray(2), 100)
        assertFalse(cache.get("b", 0).first)
        cache.put("failure", null, 100)
        assertEquals(true to null, cache.get("failure", 99))
        assertEquals(false to null, cache.get("failure", 100))
        cache.clear()
        assertFalse(cache.get("a", 0).first)
    }
}
