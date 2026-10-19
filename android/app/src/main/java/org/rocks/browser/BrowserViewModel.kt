package org.rocks.browser

import android.app.Application
import android.util.AtomicFile
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
    private var writable = true
    private val writes = Channel<BrowserData>(Channel.CONFLATED)
    private val file = AtomicFile(File(application.filesDir, "browser-state.json"))

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                    runCatching { file.openRead().bufferedReader().use { SessionCodec.decode(it.readText()) } }
                        .onSuccess { restored -> withContext(Dispatchers.Main) { data = restored } }
                        .onFailure { writable = false }
                }
            }
            if (!writable) notice = getApplication<Application>().getString(R.string.restore_failed)
            ready = true
            for (snapshot in writes) {
                if (!writable) continue
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = file.startWrite()
                        try {
                            stream.write(SessionCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
                            file.finishWrite(stream)
                        } catch (error: Exception) { file.failWrite(stream); throw error }
                    }.isSuccess
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

    fun navigate(input: String) {
        val target = AddressResolver.resolve(input, SearchEngine.fromId(data.settings.searchEngine))
        if (target.url != null) change { it.updateTab(it.activeTabId, target.url, target.url) }
        else notice = target.error
    }

    fun showNotice(message: String) { notice = message }
    fun clearNotice() { notice = null }
}
