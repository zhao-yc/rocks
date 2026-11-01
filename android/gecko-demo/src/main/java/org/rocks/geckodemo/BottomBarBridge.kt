package org.rocks.geckodemo

import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** 单用途内置扩展通道：只有布局/颜色上报，无网页命令、存储或网络能力。 */
internal class BottomBarBridge(
    private val controller: WebExtensionController,
    private val page: (GeckoSession) -> BrowserPageScope?,
) {
    companion object { const val ID = "bottom-background@rocks.internal" }
    private var extension: WebExtension? = null
    private val sessions = mutableSetOf<GeckoSession>()
    private var port: WebExtension.Port? = null
    private var boundScope: BrowserPageScope? = null
    private val gate = BottomBarDocumentGate()
    private var report: BottomBarReport? = null
    private var view: GeckoView? = null
    private var clipping = 0
    private var paused = false
    private var closed = false
    var color by mutableStateOf<Int?>(null)
        private set

    /** 安装内置组件失败时保持透明，不阻断浏览器启动。 */
    fun start() {
        controller.ensureBuiltIn("resource://android/assets/bottom-bar/", ID).accept({ installed ->
            if (!closed && installed != null) { extension = installed; sessions.forEach(::attach) }
        }, { clear() })
    }

    /** 每个网页会话单独注册；扩展面板不接入此通道。 */
    fun attach(session: GeckoSession) {
        sessions.add(session)
        val internal = extension ?: return
        session.webExtensionController.setMessageDelegate(internal, object : WebExtension.MessageDelegate {
            override fun onConnect(incoming: WebExtension.Port) {
                val sender = incoming.sender
                val scope = page(session)
                if (closed || scope == null || sender.session !== session || sender.webExtension.id != ID ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    !sender.isTopLevel || sender.url != scope.url) {
                    incoming.disconnect(); return
                }
                clear()
                port = incoming; boundScope = scope; gate.reset(scope)
                incoming.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, source: WebExtension.Port) {
                        if (port !== source) return
                        val parsed = BottomBarReport.parse(message)
                        if (parsed == null || !gate.accepts(page(session), scope, parsed.documentId)) {
                            clear(); return
                        }
                        report = parsed
                        updateColor()
                    }
                    override fun onDisconnect(source: WebExtension.Port) { if (port === source) clear() }
                })
            }
        }, "rocksBottomBackground")
    }

    /** 标签、前后台或文档身份变化后旧端口永远不能恢复旧颜色。 */
    fun sync() {
        val session = port?.sender?.session
        if (session != null && page(session) != boundScope) clear()
        else updateColor()
    }

    /** 键盘期间清除报告；恢复时等待网页 resize/focus 事件重新上报。 */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value; report = null; color = null
    }

    /** 记录当前 Gecko 几何；矩阵转换同时处理缩放、横屏和顶部安全区。 */
    fun geometry(view: GeckoView, clipping: Int) {
        if (page(view.session ?: return) == null) return
        this.view = view; this.clipping = clipping
        updateColor()
    }

    private fun updateColor() {
        val currentView = view
        val session = port?.sender?.session
        val currentReport = report
        if (paused || currentView == null || session == null || currentView.session !== session ||
            page(session) != boundScope || currentReport == null) { color = null; return }
        val matrix = Matrix(); val inverse = Matrix()
        session.getClientToScreenMatrix(matrix)
        if (!matrix.invert(inverse)) { color = null; return }
        val origin = IntArray(2); currentView.getLocationOnScreen(origin)
        val bottom = floatArrayOf(origin[0].toFloat(), (origin[1] + currentView.height - clipping).toFloat())
        inverse.mapPoints(bottom)
        color = currentReport.colorAt(bottom[1].toDouble())
    }

    /** 清理时先取消当前身份，再断开端口，防止断开回调重入。 */
    private fun clear() {
        val old = port; port = null; boundScope = null; gate.reset(null); report = null; color = null
        old?.disconnect()
    }

    fun detach(session: GeckoSession) {
        sessions.remove(session)
        if (port?.sender?.session === session) clear()
        if (view?.session === session) view = null
        extension?.let { session.webExtensionController.setMessageDelegate(it, null, "rocksBottomBackground") }
    }

    fun close() {
        closed = true; clear(); sessions.toList().forEach(::detach); extension = null; view = null
    }
}
