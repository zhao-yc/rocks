package org.rocks.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.rocks.browser.core.*

data class PageStatus(val back: Boolean = false, val forward: Boolean = false, val progress: Int = 100)

/** 每个普通标签拥有自己的 WebView；网页存储属于同一普通浏览配置。 */
class BrowserEngine(
    private val activity: MainActivity,
    private val model: BrowserViewModel,
    private val external: (Uri) -> Unit,
    private val requestDownload: (DownloadRequest) -> Unit,
) {
    private val views = mutableMapOf<String, WebView>()
    private val requested = mutableMapOf<String, String>()
    val statuses = mutableStateMapOf<String, PageStatus>()
    var fullScreen by mutableStateOf<View?>(null); private set
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var resumed = true

    /** URL 的状态回调也更新 requested，避免重组把重定向页面重新加载。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun view(tab: BrowserTab): WebView {
        val web = views.getOrPut(tab.id) {
            WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.safeBrowsingEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(true)
                settings.mediaPlaybackRequiresUserGesture = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = client(tab.id)
                webChromeClient = chrome(tab.id)
                setDownloadListener { url, userAgent, disposition, mimeType, _ ->
                    if (AddressResolver.isWebUrl(url)) requestDownload(DownloadRequest(url, userAgent, disposition, mimeType))
                    else model.showNotice(activity.getString(R.string.unsupported_download))
                }
            }
        }
        if (requested[tab.id] != tab.url && AddressResolver.isWebUrl(tab.url)) {
            requested[tab.id] = tab.url
            web.loadUrl(tab.url)
        }
        views.forEach { (id, view) -> if (id == tab.id && resumed) view.onResume() else view.onPause() }
        return web
    }

    private fun updateStatus(id: String, web: WebView, progress: Int = web.progress) {
        statuses[id] = PageStatus(web.canGoBack(), web.canGoForward(), progress)
    }

    private fun client(id: String) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (AddressResolver.isWebUrl(uri.toString())) return false
            if (request.isForMainFrame && request.hasGesture() && model.data.activeTabId == id &&
                uri.scheme?.lowercase() in setOf("mailto", "tel", "sms", "geo")) external(uri)
            else model.showNotice(activity.getString(R.string.blocked_link))
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null && AddressResolver.isWebUrl(url)) {
                requested[id] = url
                model.change { it.updateTab(id, url) }
            }
            updateStatus(id, view, 0)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null && AddressResolver.isWebUrl(url)) {
                requested[id] = url
                model.change { it.recordVisit(id, url, view.title?.take(512) ?: url, System.currentTimeMillis()) }
            }
            updateStatus(id, view, 100)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null && AddressResolver.isWebUrl(url)) {
                requested[id] = url
                model.change { it.updateTab(id, url) }
            }
            updateStatus(id, view)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            model.showNotice(activity.getString(R.string.certificate_error))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) model.showNotice(activity.getString(R.string.page_failed))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            dispose(id)
            model.change { it.updateTab(id, "", activity.getString(R.string.new_tab)) }
            model.showNotice(activity.getString(R.string.renderer_stopped))
            return true
        }
    }

    private fun chrome(id: String) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { updateStatus(id, view, newProgress) }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val url = view.url ?: return
            if (AddressResolver.isWebUrl(url)) model.change { it.updateTab(id, url, title?.take(512) ?: url) }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
            model.showNotice(activity.getString(R.string.device_permission_denied))
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
            model.showNotice(activity.getString(R.string.location_denied))
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            activity.chooseFiles(filePathCallback, fileChooserParams)

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            model.showNotice(activity.getString(R.string.popup_blocked))
            return false
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullScreen != null || model.data.activeTabId != id) { callback.onCustomViewHidden(); return }
            fullScreen = view; fullScreenCallback = callback
        }

        override fun onHideCustomView() { exitFullScreen() }
    }

    fun back(id: String) { views[id]?.takeIf { it.canGoBack() }?.goBack() }
    fun forward(id: String) { views[id]?.takeIf { it.canGoForward() }?.goForward() }
    fun reload(id: String) { views[id]?.reload() }
    fun stop(id: String) { views[id]?.stopLoading() }

    fun exitFullScreen() {
        fullScreen = null
        val callback = fullScreenCallback; fullScreenCallback = null
        callback?.onCustomViewHidden()
    }

    /** Activity 暂停时暂停全部标签，关闭标签时立即释放原生资源。 */
    fun setResumed(value: Boolean) {
        resumed = value
        views.forEach { (id, view) -> if (value && id == model.data.activeTabId) view.onResume() else view.onPause() }
        if (!value) CookieManager.getInstance().flush()
    }

    fun prune(tabs: List<BrowserTab>) {
        views.keys.toList().filter { id -> tabs.none { it.id == id && it.url.isNotEmpty() } }.forEach(::dispose)
    }

    private fun dispose(id: String) {
        views.remove(id)?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading(); view.onPause(); view.destroy()
        }
        requested.remove(id); statuses.remove(id)
    }

    fun destroy() { exitFullScreen(); views.keys.toList().forEach(::dispose) }
}
