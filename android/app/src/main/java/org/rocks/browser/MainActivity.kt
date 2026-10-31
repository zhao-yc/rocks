package org.rocks.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.rocks.browser.core.AddressResolver

data class DownloadRequest(val url: String, val userAgent: String, val disposition: String?, val mimeType: String?)

class MainActivity : ComponentActivity() {
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }.toTypedArray()
            else data?.data?.let { arrayOf(it) }
        } else null
        fileCallback?.onReceiveValue(uris?.filter { it.scheme == "content" }?.toTypedArray())
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RocksTheme {
                BrowserScreen(this)
            }
        }
    }

    /** 文件上传只通过系统文档选择器获取用户选中的 content URI。 */
    fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val types = params.acceptTypes.filter { it.contains('/') && !it.contains(';') }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (types.size == 1) types.first() else "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }
        return try { filePicker.launch(intent); true }
        catch (_: ActivityNotFoundException) { fileCallback?.onReceiveValue(null); fileCallback = null; true }
    }

    /** 只处理白名单协议；调用者已经展示用户确认，不执行网页给出的 Intent extras。 */
    fun openExternal(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() !in setOf("mailto", "tel", "sms", "geo")) return false
        return try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); true }
        catch (_: ActivityNotFoundException) { false }
    }

    fun share(url: String) {
        if (!AddressResolver.isWebUrl(url)) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        }, getString(R.string.share_page)))
    }

    fun copyLink(url: String) {
        if (!AddressResolver.isWebUrl(url)) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.link), url))
    }

    /** 系统下载仅在用户确认后启动，文件名清理且加时间前缀避免覆盖。 */
    fun download(item: DownloadRequest): Boolean = runCatching {
        require(AddressResolver.isWebUrl(item.url))
        val name = URLUtil.guessFileName(item.url, item.disposition, item.mimeType)
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120).ifBlank { "download" }
        val request = DownloadManager.Request(Uri.parse(item.url)).apply {
            setTitle(name)
            setMimeType(item.mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "rocks-${System.currentTimeMillis()}-$name")
            addRequestHeader("User-Agent", item.userAgent)
            CookieManager.getInstance().getCookie(item.url)?.let { addRequestHeader("Cookie", it) }
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
    }.isSuccess

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); fileCallback = null
        super.onDestroy()
    }
}
