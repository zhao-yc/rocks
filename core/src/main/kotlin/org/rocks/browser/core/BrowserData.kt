package org.rocks.browser.core

import java.util.UUID

const val SCHEMA_VERSION = 1
const val MAX_TABS = 24
const val MAX_HISTORY = 500
const val MAX_BOOKMARKS = 200

enum class SearchEngine(val id: String, val label: String, val prefix: String) {
    BAIDU("baidu", "百度", "https://www.baidu.com/s?wd="),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
    GOOGLE("google", "Google", "https://www.google.com/search?q="),
    BING("bing", "必应", "https://cn.bing.com/search?q=");

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: BING
    }
}

data class BrowserTab(val id: String = UUID.randomUUID().toString(), val url: String = "", val title: String = "新标签页")
data class PageRecord(val url: String, val title: String, val timestamp: Long)
data class BrowserSettings(val searchEngine: String = SearchEngine.BING.id)

/** 平台使用相同 JSON 字段；空 URL 代表原生首页，不持久化引擎私有状态。 */
data class BrowserData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val tabs: List<BrowserTab> = listOf(BrowserTab()),
    val activeTabId: String = tabs.first().id,
    val bookmarks: List<PageRecord> = emptyList(),
    val history: List<PageRecord> = emptyList(),
    val settings: BrowserSettings = BrowserSettings(),
) {
    val activeTab: BrowserTab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    /** 所有变更返回新快照，关闭最后一个标签时保持一个可用首页。 */
    fun openTab(url: String = "", inBackground: Boolean = false): BrowserData {
        if (tabs.size >= MAX_TABS) return this
        val target = if (url.isEmpty()) "" else AddressResolver.normalizeWebUrl(url) ?: return this
        val tab = BrowserTab(url = target)
        return copy(tabs = tabs + tab, activeTabId = if (inBackground) activeTabId else tab.id)
    }

    fun closeTab(id: String): BrowserData {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }.ifEmpty { listOf(BrowserTab()) }
        val selected = if (activeTabId == id) remaining[index.coerceAtMost(remaining.lastIndex)].id else activeTabId
        return copy(tabs = remaining, activeTabId = selected)
    }

    fun selectTab(id: String) = if (tabs.any { it.id == id }) copy(activeTabId = id) else this

    /** 地址栏横划只切换相邻标签，到达两端时停留，不循环或隐式新建。 */
    fun selectAdjacentTab(next: Boolean): BrowserData {
        val index = tabs.indexOfFirst { it.id == activeTabId }
        if (index < 0) return this
        val neighbor = tabs.getOrNull(index + if (next) 1 else -1) ?: return this
        return selectTab(neighbor.id)
    }

    fun closeOtherTabs(): BrowserData = copy(tabs = listOf(activeTab), activeTabId = activeTab.id)

    fun updateTab(id: String, url: String, title: String? = null): BrowserData = copy(
        tabs = tabs.map { if (it.id == id) it.copy(url = url, title = title ?: it.title) else it },
    )

    /** 历史按 URL 去重并限量，避免会话文件无限增长。 */
    fun recordVisit(id: String, url: String, title: String, timestamp: Long): BrowserData {
        if (tabs.none { it.id == id } || !AddressResolver.isWebUrl(url)) return this
        return updateTab(id, url, title).copy(history =
            (listOf(PageRecord(url, title, timestamp)) + history.filterNot { it.url == url }).take(MAX_HISTORY))
    }

    fun toggleBookmark(timestamp: Long): BrowserData {
        val tab = activeTab
        if (!AddressResolver.isWebUrl(tab.url)) return this
        val next = if (bookmarks.any { it.url == tab.url }) bookmarks.filterNot { it.url == tab.url }
        else (listOf(PageRecord(tab.url, tab.title, timestamp)) + bookmarks).take(MAX_BOOKMARKS)
        return copy(bookmarks = next)
    }
}
