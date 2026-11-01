package org.rocks.geckodemo

import org.rocks.browser.core.AddressResolver
import org.rocks.browser.core.PageRecord
import org.rocks.browser.core.SearchEngine

internal data class SearchableTab(val id: Long, val url: String, val title: String)
internal enum class SuggestionSource { TAB, BOOKMARK, HISTORY }
internal data class AddressSuggestion(
    val url: String, val title: String, val source: SuggestionSource, val tabId: Long? = null,
)
internal enum class AddressInputError { EMPTY, INVALID_INPUT, INVALID_URL, UNSUPPORTED_SCHEME }
internal data class AddressInputResult(val url: String?, val error: AddressInputError?)

/** 本地检索只读取已有快照；不联网上传输入，也不缓存已删除的历史或书签。 */
internal object LocalBrowserSearch {
    private const val LIMIT = 6

    /** 将核心解析器的诊断映射为可翻译的界面状态，网址规则仍只由核心解析器维护。 */
    fun resolve(input: String, engine: SearchEngine): AddressInputResult {
        val result = AddressResolver.resolve(input, engine)
        val resolvedUrl = result.url
        // 中文搜索词编码后也可能超过核心网址上限；此时直接给出输入诊断。
        if (resolvedUrl != null && !AddressResolver.isWebUrl(resolvedUrl))
            return AddressInputResult(null, AddressInputError.INVALID_INPUT)
        val error = when (result.error) {
            null -> null
            "请输入网址或搜索词" -> AddressInputError.EMPTY
            "网站地址无效" -> AddressInputError.INVALID_URL
            "仅支持 HTTP 和 HTTPS 网址" -> AddressInputError.UNSUPPORTED_SCHEME
            else -> AddressInputError.INVALID_INPUT
        }
        return AddressInputResult(resolvedUrl, error)
    }

    /** 空输入显示最近访问；输入后按标签、书签、历史的优先级匹配并去重。 */
    fun suggestions(query: String, tabs: List<SearchableTab>, bookmarks: List<PageRecord>,
        history: List<PageRecord>): List<AddressSuggestion> {
        val text = query.trim()
        val open = linkedMapOf<String, SearchableTab>()
        tabs.forEach { tab -> AddressResolver.normalizeWebUrl(tab.url)?.let { open.putIfAbsent(it, tab) } }
        val candidates = if (text.isEmpty()) {
            history.sortedByDescending { it.timestamp }.map { record ->
                val tab = open[AddressResolver.normalizeWebUrl(record.url)]
                AddressSuggestion(record.url, record.title, SuggestionSource.HISTORY, tab?.id)
            }
        } else {
            tabs.map { AddressSuggestion(it.url, it.title, SuggestionSource.TAB, it.id) } +
                bookmarks.map { AddressSuggestion(it.url, it.title, SuggestionSource.BOOKMARK,
                    open[AddressResolver.normalizeWebUrl(it.url)]?.id) } +
                history.map { AddressSuggestion(it.url, it.title, SuggestionSource.HISTORY,
                    open[AddressResolver.normalizeWebUrl(it.url)]?.id) }
        }
        val seen = mutableSetOf<String>()
        return candidates.asSequence().mapNotNull { item ->
            AddressResolver.normalizeWebUrl(item.url)?.let { item.copy(url = it) }
        }.filter { matches(it.title, it.url, text) && seen.add(it.url) }.take(LIMIT).toList()
    }

    /** 列表检索保持原有顺序；空白搜索等同于查看全部记录。 */
    fun filter(records: List<PageRecord>, query: String): List<PageRecord> =
        records.filter { matches(it.title, it.url, query.trim()) }

    private fun matches(title: String, url: String, query: String): Boolean =
        query.isEmpty() || title.contains(query, ignoreCase = true) || url.contains(query, ignoreCase = true)
}

internal enum class TabCloseCause { USER, PROGRAMMATIC, BULK }
internal data class ClosedTabSnapshot(
    val closureId: Long, val url: String, val title: String, val index: Int, val desktop: Boolean,
)
internal data class AutomaticHomeMarker(val tabId: Long, val revision: Long) {
    /** 只有关闭最后一页自动生成、且尚未导航的唯一首页允许被恢复操作替换。 */
    fun matches(tabCount: Int, id: Long, documentRevision: Long, url: String): Boolean =
        tabCount == 1 && id == tabId && documentRevision == revision && url == DemoPolicy.HOME
}

/** 最近关闭仅保留网址快照，不持有引擎会话、网页内容或设备授权。 */
internal class RecentlyClosedTabs(private val capacity: Int = 10) {
    private val entries = ArrayDeque<ClosedTabSnapshot>()
    private var nextId = 1L
    val latest: ClosedTabSnapshot? get() = entries.firstOrNull()
    val size: Int get() = entries.size

    /** 自动关闭、批量关闭、原生首页和扩展页均不进入用户的误关恢复记录。 */
    fun record(url: String, title: String, index: Int, desktop: Boolean,
        cause: TabCloseCause, extension: Boolean = false): ClosedTabSnapshot? {
        if (cause != TabCloseCause.USER || extension) return null
        val normalized = AddressResolver.normalizeWebUrl(url) ?: return null
        val entry = ClosedTabSnapshot(nextId++, normalized, title, index, desktop)
        entries.addFirst(entry)
        while (entries.size > capacity) entries.removeLast()
        return entry
    }

    /** 达到标签上限时保留记录，过期撤销提示不能误恢复另一条记录。 */
    fun forRestore(tabCount: Int, replaceHome: Boolean, expectedId: Long? = null): ClosedTabSnapshot? {
        val entry = latest ?: return null
        if (expectedId != null && entry.closureId != expectedId) return null
        if (tabCount >= DemoPolicy.MAX_TABS && !replaceHome) return null
        return entry
    }

    /** 新标签成功创建之后才消费记录，失败或上限拦截不会丢失恢复机会。 */
    fun consume(entry: ClosedTabSnapshot) {
        if (latest?.closureId == entry.closureId) entries.removeFirst()
    }
}

/** 区分用户停止与真实失败；新文档开始时清除前一次停止标记。 */
internal class PageLoadState {
    var loading = false
        private set
    var error = false
        private set
    private var stopped = false

    fun start() { loading = true; error = false; stopped = false }
    fun stop() { loading = false; error = false; stopped = true }
    /** 只有真实完成的网页可以写入历史；停止后的迟到回调不制造访问记录。 */
    fun finish(success: Boolean): Boolean {
        loading = false
        if (!stopped) error = error || !success
        return success && !stopped && !error
    }
    // Gecko 的主动停止通过 onPageStop(false) 完成；onLoadError 仍表示真实错误。
    fun fail() { loading = false; error = true }
}
