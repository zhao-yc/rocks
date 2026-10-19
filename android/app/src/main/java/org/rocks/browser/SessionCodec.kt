package org.rocks.browser

import org.json.JSONArray
import org.json.JSONObject
import org.rocks.browser.core.*

/** 有界的跨平台 JSON 编解码器；不接受未知版本，避免覆盖未来版本的数据。 */
object SessionCodec {
    fun encode(data: BrowserData): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("activeTabId", data.activeTabId)
        put("tabs", JSONArray().apply { data.tabs.forEach { tab -> put(JSONObject().apply {
            put("id", tab.id); put("url", tab.url); put("title", tab.title.take(512))
        }) } })
        put("bookmarks", records(data.bookmarks))
        put("history", records(data.history))
        put("settings", JSONObject().put("searchEngine", data.settings.searchEngine))
    }.toString()

    fun decode(text: String): BrowserData {
        require(text.length <= 4 * 1024 * 1024) { "Session file is too large" }
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported session version" }
        val array = json.getJSONArray("tabs")
        val tabs = (0 until minOf(array.length(), MAX_TABS)).mapNotNull { index ->
            val tab = array.optJSONObject(index) ?: return@mapNotNull null
            val id = tab.optString("id").take(128)
            val url = tab.optString("url")
            if (id.isBlank() || (url.isNotEmpty() && !AddressResolver.isWebUrl(url))) null
            else BrowserTab(id, url, tab.optString("title", "New tab").take(512))
        }.distinctBy { it.id }.ifEmpty { listOf(BrowserTab()) }
        val active = json.optString("activeTabId").takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
        return BrowserData(tabs = tabs, activeTabId = active,
            bookmarks = readRecords(json.optJSONArray("bookmarks"), MAX_BOOKMARKS),
            history = readRecords(json.optJSONArray("history"), MAX_HISTORY),
            settings = BrowserSettings(SearchEngine.fromId(json.optJSONObject("settings")?.optString("searchEngine").orEmpty()).id))
    }

    private fun records(items: List<PageRecord>) = JSONArray().apply { items.forEach { item -> put(JSONObject().apply {
        put("url", item.url); put("title", item.title.take(512)); put("timestamp", item.timestamp)
    }) } }

    private fun readRecords(array: JSONArray?, limit: Int): List<PageRecord> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), limit)).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val url = row.optString("url")
            if (!AddressResolver.isWebUrl(url)) null
            else PageRecord(url, row.optString("title").take(512), row.optLong("timestamp").coerceAtLeast(0))
        }.distinctBy { it.url }
    }
}
