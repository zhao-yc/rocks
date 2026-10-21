package org.rocks.geckodemo

import android.app.Application
import android.net.Uri
import android.webkit.URLUtil
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

internal data class UploadRequest(
    val session: GeckoSession, val page: String,
    val prompt: GeckoSession.PromptDelegate.FilePrompt,
    val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    var launched: Boolean = false,
)
internal data class DownloadRequest(
    val session: GeckoSession, val page: String, val response: WebResponse,
    val filename: String, val mime: String, var launched: Boolean = false,
)
internal class DownloadTask(val filename: String, val total: Long) {
    var bytes by mutableLongStateOf(0)
    var status by mutableStateOf("running")
    var job: Job? = null
}

/** The original Gecko response stream retains authenticated downloads; never re-fetch without cookies. */
internal class BrowserFiles(
    private val app: Application,
    private val scope: CoroutineScope,
    private val selectedPage: (GeckoSession) -> String?,
    private val notify: (Int) -> Unit,
) {
    var upload by mutableStateOf<UploadRequest?>(null)
        private set
    var offer by mutableStateOf<DownloadRequest?>(null)
        private set
    var destination by mutableStateOf<DownloadRequest?>(null)
        private set
    val tasks = mutableStateListOf<DownloadTask>()

    fun requestUpload(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val page = selectedPage(session)
        if (page == null || upload != null || prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
            result.complete(prompt.dismiss()); return result
        }
        upload = UploadRequest(session, page, prompt, result)
        prompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                if (upload?.prompt === prompt) completeUpload(emptyList())
            }
        })
        return result
    }

    fun completeUpload(uris: List<Uri>) {
        val pending = upload ?: return
        upload = null
        if (pending.prompt.isComplete) {
            pending.result.completeExceptionally(CancellationException("Page dismissed the file request")); return
        }
        val selected = uris.filter { it.scheme == "content" }.take(20)
        val valid = selected.isNotEmpty() && selectedPage(pending.session) == pending.page
        val response = runCatching {
            if (!valid) pending.prompt.dismiss()
            else if (pending.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE)
                pending.prompt.confirm(app, selected.first())
            else pending.prompt.confirm(app, selected.toTypedArray())
        }
        response.fold(pending.result::complete) { error ->
            notify(R.string.file_failed)
            if (!pending.prompt.isComplete) pending.result.complete(pending.prompt.dismiss())
            else pending.result.completeExceptionally(error)
        }
    }

    fun requestDownload(session: GeckoSession, response: WebResponse) {
        val page = selectedPage(session)
        if (page == null || offer != null || destination != null || response.body == null ||
            response.statusCode !in 200..299 || !DemoPolicy.isWebUrl(response.uri)) {
            runCatching { response.body?.close() }; return
        }
        val mime = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
            ?.substringBefore(';')?.takeIf { it.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")) }
            ?: "application/octet-stream"
        val disposition = response.headers.entries.firstOrNull { it.key.equals("content-disposition", true) }?.value
        val name = DemoPolicy.safeFilename(URLUtil.guessFileName(response.uri, disposition, mime))
        offer = DownloadRequest(session, page, response, name, mime)
    }

    fun confirmDownload(allow: Boolean) {
        val pending = offer ?: return
        offer = null
        if (!allow || selectedPage(pending.session) != pending.page) runCatching { pending.response.body?.close() }
        else destination = pending
    }

    fun completeDownload(uri: Uri?) {
        val pending = destination ?: return
        destination = null
        if (uri?.scheme != "content" || selectedPage(pending.session) != pending.page) {
            runCatching { pending.response.body?.close() }; return
        }
        val encoded = pending.response.headers.entries.firstOrNull { it.key.equals("content-encoding", true) }?.value
        val length = if (encoded != null && !encoded.equals("identity", true)) -1 else
            pending.response.headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull() ?: -1
        val task = DownloadTask(pending.filename, length)
        tasks.add(0, task)
        if (tasks.size > 50) tasks.filter { it.status != "running" }.lastOrNull()?.let(tasks::remove)
        task.job = scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    pending.response.body!!.use { input ->
                        val output = app.contentResolver.openOutputStream(uri, "w") ?: error("No document stream")
                        output.use { target ->
                            copyDownload(input, target, length) { written ->
                                withContext(Dispatchers.Main) { task.bytes = written }
                            }
                        }
                    }
                }
            }
            task.status = if (outcome.isSuccess) "complete" else if (outcome.exceptionOrNull() is CancellationException) "cancelled" else "failed"
            outcome.getOrNull()?.let { task.bytes = it }
            if (outcome.isSuccess) notify(R.string.download_complete) else notify(R.string.download_incomplete)
        }
    }

    fun cancelPrompts() {
        completeUpload(emptyList())
        runCatching { offer?.response?.body?.close() }; offer = null
        runCatching { destination?.response?.body?.close() }; destination = null
    }

    fun backgrounded() {
        // Keep a user-invoked system picker alive; it intentionally pauses the activity.
        runCatching { offer?.response?.body?.close() }; offer = null
    }
}
