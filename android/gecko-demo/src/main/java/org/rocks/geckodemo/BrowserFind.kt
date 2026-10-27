package org.rocks.geckodemo

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoSession

internal class BrowserFind(private val scope: CoroutineScope,
    private val page: () -> Pair<BrowserPageScope, GeckoSession>?) {
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
    private var session: GeckoSession? = null
    private var job: Job? = null
    private var generation = 0L

    fun open() {
        val target = page() ?: return
        if (!DemoPolicy.isWebUrl(target.first.url)) return
        close()
        session = target.second
        target.second.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        visible = true
    }

    fun change(value: String) {
        query = value.take(512)
        generation++; job?.cancel(); current = 0; total = 0
        if (query.isEmpty()) { busy = false; session?.finder?.clear(); return }
        busy = true
        job = scope.launch { delay(160); search(false, false) }
    }

    fun next(backwards: Boolean) {
        if (!visible || query.isEmpty() || busy) return
        generation++; search(backwards, true)
    }

    private fun search(backwards: Boolean, again: Boolean) {
        val target = page() ?: return
        if (!visible || target.second !== session) return
        val expected = generation
        busy = true
        target.second.finder.find(if (again) null else query,
            if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD).accept({ result ->
            if (visible && expected == generation && page()?.first == target.first) {
                current = result?.current ?: 0; total = result?.total ?: 0; busy = false
            }
        }, {
            if (visible && expected == generation) { current = 0; total = 0; busy = false }
        })
    }

    fun close() {
        generation++; job?.cancel(); job = null
        session?.let { if (it.isOpen) it.finder.clear() }
        session = null; visible = false; query = ""; current = 0; total = 0; busy = false
    }
}
