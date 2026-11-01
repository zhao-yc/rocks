package org.rocks.geckodemo

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.rocks.browser.core.PageRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class AddressHubPage { OVERVIEW, BOOKMARKS, HISTORY }
internal enum class HubInput { NONE, ADDRESS, BOOKMARKS, HISTORY }
internal val AddressInputError.label: Int get() = when (this) {
    AddressInputError.EMPTY -> R.string.address_empty
    AddressInputError.INVALID_INPUT -> R.string.address_invalid
    AddressInputError.INVALID_URL -> R.string.address_invalid_url
    AddressInputError.UNSUPPORTED_SCHEME -> R.string.address_unsupported_scheme
}
internal data class HubScrollPosition(val index: Int = 0, val offset: Int = 0)

/** 面板临时状态由 ViewModel 持有，旋转不会重置草稿，也不写入跨端浏览数据。 */
internal class AddressHubState {
    var visible by mutableStateOf(false)
        private set
    var ownerTabId = 0L
        private set
    var page by mutableStateOf(AddressHubPage.OVERVIEW)
        private set
    var input by mutableStateOf(HubInput.NONE)
        private set
    var focusVersion by mutableIntStateOf(0)
        private set
    var address by mutableStateOf(TextFieldValue())
        private set
    var changed by mutableStateOf(false)
        private set
    var error by mutableStateOf<AddressInputError?>(null)
    var bookmarkQuery by mutableStateOf("")
    var historyQuery by mutableStateOf("")
    var allClosed by mutableStateOf(false)
    val collapsedDates = mutableStateMapOf<LocalDate, Boolean>()
    val positions = mutableMapOf<AddressHubPage, HubScrollPosition>()
    val showingMatches get() = input == HubInput.ADDRESS && changed && address.text.isNotBlank()

    /** 第一次进入全选网址；分类入口则保留输入框但不请求软键盘。 */
    fun open(tabId: Long, url: String, initial: AddressHubPage = AddressHubPage.OVERVIEW, edit: Boolean = true) {
        reset()
        ownerTabId = tabId
        val value = url.takeUnless { it == DemoPolicy.HOME }.orEmpty()
        address = TextFieldValue(value, TextRange(0, value.length))
        page = initial
        input = if (edit) HubInput.ADDRESS else HubInput.NONE
        visible = true
        focusVersion++
    }

    /** 主动切分类先切换输入状态，使随后的键盘隐藏不被当成系统返回。 */
    fun browse(target: AddressHubPage) {
        address = address.copy(composition = null)
        page = target; input = HubInput.NONE; focusVersion++
    }
    /** 输入框实际获得焦点时同步互斥状态，不修改网址或查询。 */
    fun focus(target: HubInput) { if (input != target) { input = target; focusVersion++ } }
    /** 再次编辑保留原有光标范围，通过版本号重新请求键盘。 */
    fun editAddress() { input = HubInput.ADDRESS; focusVersion++ }
    /** 仅文本变化才启动检索，选择范围变化不会把原网址误判为新输入。 */
    fun changeAddress(value: TextFieldValue) {
        // 失焦时输入法可能回送折叠选区；分类浏览及其他搜索框不能覆盖已保留的地址草稿。
        if (input != HubInput.ADDRESS) return
        if (value.text != address.text) { changed = true; error = null }
        address = value
    }
    /** 清空后回到概览，但继续保持键盘输入能力。 */
    fun clearAddress() { address = TextFieldValue(); changed = true; error = null; page = AddressHubPage.OVERVIEW; editAddress() }

    /** 退出后不保留旧分类和查询，下次始终从概览开始。 */
    fun close() { visible = false; reset() }
    private fun reset() {
        input = HubInput.NONE; page = AddressHubPage.OVERVIEW
        address = TextFieldValue(); changed = false; error = null
        bookmarkQuery = ""; historyQuery = ""; allClosed = false
        collapsedDates.clear(); positions.clear()
    }
}

internal data class HistoryDateSection(val date: LocalDate, val records: List<PageRecord>)

/** 日期按本地时区划分，不把每个网址的最近访问快照伪装成完整访问轨迹。 */
internal object HistoryDateSections {
    fun group(records: List<PageRecord>, zone: ZoneId = ZoneId.systemDefault()): List<HistoryDateSection> =
        records.sortedByDescending { it.timestamp }.groupBy {
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
        }.map { HistoryDateSection(it.key, it.value) }
}
