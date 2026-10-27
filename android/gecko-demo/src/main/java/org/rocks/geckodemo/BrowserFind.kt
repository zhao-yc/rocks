package org.rocks.geckodemo

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoSession

internal data class FindMatch(val current: Int, val total: Int)
internal interface FindPage {
    val scope: BrowserPageScope
    val identity: Any
    fun highlightAll()
    fun find(query: String?, backwards: Boolean, result: (FindMatch) -> Unit, failed: () -> Unit)
    fun clear()
}
internal class GeckoFindPage(override val scope: BrowserPageScope, private val session: GeckoSession) : FindPage {
    override val identity: Any get() = session
    override fun highlightAll() { session.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL }
    override fun find(query: String?, backwards: Boolean, result: (FindMatch) -> Unit, failed: () -> Unit) {
        session.finder.find(query,
            if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD)
            .accept({ value -> result(FindMatch(value?.current ?: 0, value?.total ?: 0)) }, { failed() })
    }
    override fun clear() { if (session.isOpen) session.finder.clear() }
}

internal class BrowserFind(private val scope: CoroutineScope,
    private val page: () -> FindPage?, private val debounceMillis: Long = 160) {
    var visible by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var current by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    private var attached: FindPage? = null
    private var job: Job? = null
    private var generation = 0L
    private var deferred = false

    fun open() {
        val target = page() ?: return
        if (!DemoPolicy.isWebUrl(target.scope.url)) return
        close(); attached = target; target.highlightAll(); visible = true
    }
    fun change(value: String) {
        query = value.take(512)
        generation++; job?.cancel(); current = 0; total = 0; deferred = false
        if (query.isEmpty()) { busy = false; attached?.clear(); return }
        busy = true
        job = scope.launch { delay(debounceMillis); search(false, false) }
    }
    fun next(backwards: Boolean) {
        if (!visible || query.isEmpty() || busy) return
        generation++; search(backwards, true)
    }
    fun resumed() {
        if (visible && deferred && query.isNotEmpty()) { generation++; search(false, false) }
    }
    private fun search(backwards: Boolean, again: Boolean) {
        val target = page()
        if (target == null) { deferred = true; busy = false; return }
        if (!visible) return
        if (target.identity !== attached?.identity || target.scope != attached?.scope) { close(); return }
        val expected = generation
        deferred = false; busy = true
        target.find(if (again) null else query, backwards, { result ->
            if (visible && expected == generation && attached?.identity === target.identity) {
                // A null foreground scope means Activity pause, not replacement of the document.
                // Keep that session's result; navigation and tab changes invalidate generation.
                val currentPage = page()?.scope
                if (currentPage != null && currentPage != target.scope) close()
                else { current = result.current; total = result.total; busy = false }
            }
        }, {
            if (visible && expected == generation) { current = 0; total = 0; busy = false }
        })
    }
    fun close() {
        generation++; job?.cancel(); job = null; deferred = false
        attached?.clear(); attached = null
        visible = false; query = ""; current = 0; total = 0; busy = false
    }
}
