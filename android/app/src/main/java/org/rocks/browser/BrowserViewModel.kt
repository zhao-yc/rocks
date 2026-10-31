package org.rocks.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rocks.browser.core.*
import java.io.File

/** 单线程消费写入队列，AtomicFile 保护进程中断；浏览记录不会进系统备份。 */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    var data by mutableStateOf(BrowserData()); private set
    var ready by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var hint by mutableStateOf<String?>(null); private set
    private val writes = Channel<BrowserData>(Channel.CONFLATED)
    private val repository = SessionRepository(AtomicSessionFile(File(application.filesDir, "browser-state.json")))

    init {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { runCatching { repository.restore() } }
            restored.onSuccess { it?.let { snapshot -> data = snapshot } }
                .onFailure { notice = getApplication<Application>().getString(R.string.restore_failed) }
            ready = true
            for (snapshot in writes) {
                val success = withContext(Dispatchers.IO) {
                    runCatching { repository.save(snapshot) }.isSuccess
                }
                if (!success) notice = getApplication<Application>().getString(R.string.save_failed)
            }
        }
    }

    /** UI 与 WebView 回调只在主线程更新快照，写入在 IO 线程完成。 */
    fun change(transform: (BrowserData) -> BrowserData) {
        if (!ready) return
        data = transform(data)
        writes.trySend(data)
    }

    fun navigate(input: String): Boolean {
        val target = AddressResolver.resolve(input, SearchEngine.fromId(data.settings.searchEngine))
        val url = target.url
        if (url != null && ready) {
            change { it.updateTab(it.activeTabId, url, url) }
            return true
        }
        notice = target.error
        return false
    }

    fun showNotice(message: String) { notice = message }
    fun clearNotice() { notice = null }
    fun showHint(message: String) { hint = message }
    fun clearHint(message: String) { if (hint == message) hint = null }
}
