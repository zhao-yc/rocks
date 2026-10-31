package org.rocks.geckodemo

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.roundToInt

/** 原生顶部安全区的取色与模糊控制器；不采集应用窗口，避免把效果层反复采回自身。 */
internal class ImmersiveStatusBar(private val window: Window) : DefaultLifecycleObserver {
    private data class Page(val session: GeckoSession, val scope: BrowserPageScope)
    private val handler = Handler(Looper.getMainLooper())
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val views = mutableMapOf<GeckoSession, GeckoView>()
    private val colors = mutableMapOf<GeckoSession, Pair<BrowserPageScope, Int>>()
    private val gate = TopChromeRequestGate()
    private var page: Page? = null
    private var currentScope: () -> BrowserPageScope? = { null }
    private var ready = false
    private var topHeight = 0
    private var active = false
    private var destroyed = false
    private var dirty = false
    private var scheduled = false
    private var boundView: GeckoView? = null
    private var boundSession: GeckoSession? = null
    private var darkIcons = true
    private var frameAverage = TopChromePolicy.WHITE
    var background by mutableIntStateOf(TopChromePolicy.WHITE)
        private set
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 在重组提交前也按请求页面取颜色，避免切标签时短暂显示上一页的顶部。 */
    fun backgroundFor(session: GeckoSession?, scope: BrowserPageScope?, fallback: Int): Int =
        if (page?.session === session && page?.scope == scope) background
        else colors[session]?.takeIf { it.first == scope }?.second ?: fallback

    /** 页身份和几何尺寸同时匹配才显示纹理，旋转和切页时不拉伸旧帧。 */
    fun imageFor(scope: BrowserPageScope?, height: Int): ImageBitmap? =
        image.takeIf { page?.scope == scope && topHeight == height }

    private val drawCallback = Runnable { requestSample() }
    private val sample = Runnable { scheduled = false; capture() }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) requestSample() }
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            invalidateFrame()
            requestSample()
        }
    }

    /** 页面工厂和释放回调都带实际 View 身份，旧 View 的释放不能解绑替代 View。 */
    fun attach(session: GeckoSession, view: GeckoView) {
        if (destroyed) return
        views[session] = view
        bind()
    }

    /** 关闭页或销毁旧 View 时清理其缓存，不保留浏览画面。 */
    fun release(session: GeckoSession, view: GeckoView) {
        if (views[session] !== view) return
        views.remove(session); colors.remove(session)
        if (boundView === view) { unbind(); invalidateFrame() }
    }

    /** Compose 提供布局状态，实时作用域直接读取模型，避免重组前的一帧接收旧页面结果。 */
    fun configure(session: GeckoSession?, scope: BrowserPageScope?, ready: Boolean, topHeight: Int,
        fallback: Int, currentScope: () -> BrowserPageScope?) {
        if (destroyed) return
        this.currentScope = currentScope
        val next = if (session != null && scope != null) Page(session, scope) else null
        val changed = next != page
        val geometryChanged = this.topHeight != topHeight
        val readinessChanged = this.ready != ready
        if (changed) {
            unbind()
            page = next
            background = colors[session]?.takeIf { it.first == scope }?.second ?: fallback
        }
        this.ready = ready
        this.topHeight = topHeight
        if (changed || geometryChanged || readinessChanged) invalidateFrame()
        if (next == null) background = fallback
        bind()
        if (changed || geometryChanged || readinessChanged) requestSample()
    }

    /** 图标依据合成后的背景切换深浅；强遮蔽让文字和照片不会直接出现在图标后面。 */
    fun updateAppearance(paintedBase: Int, strength: Float, hasImage: Boolean) {
        val painted = TopChromePolicy.blend(paintedBase, frameAverage, if (hasImage) strength else 0f)
        darkIcons = TopChromePolicy.darkIcons(painted, darkIcons)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = darkIcons
    }

    /** 回到前台后重新绑定真实网页；窗口尚未取得焦点时等待焦点事件。 */
    override fun onResume(owner: LifecycleOwner) {
        active = true
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        bind()
        requestSample()
    }

    /** 后台停止所有新采样，已发出的异步请求仅负责回收自己的资源。 */
    override fun onPause(owner: LifecycleOwner) {
        active = false
        if (window.decorView.viewTreeObserver.isAlive)
            window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        unbind()
        invalidateFrame()
    }

    /** 不让 Activity 的窗口、原生 View 或处理任务跨销毁继续存活。 */
    override fun onDestroy(owner: LifecycleOwner) {
        onPause(owner)
        destroyed = true
        views.clear(); colors.clear(); page = null; currentScope = { null }
        jobs.cancel()
    }

    /** 发起和接收时都核对当前页面，不能仅依赖 Compose 上一次提交的状态。 */
    private fun eligible(): Boolean = active && !destroyed && ready && topHeight > 0 &&
        window.decorView.hasWindowFocus() && page?.scope == currentScope() &&
        boundView?.let { it.isAttachedToWindow && it.width > 0 && it.height > 0 } == true

    /** 只监听当前网页自身的合成更新，顶部 Canvas 更新不会反过来触发采样。 */
    private fun bind() {
        val session = page?.session
        val view = views[session]
        if (!active || !ready || topHeight <= 0 || view == null) { unbind(); return }
        if (boundView === view && boundSession === session) return
        unbind()
        boundView = view; boundSession = session
        session?.compositorController?.addDrawCallback(drawCallback)
        view.addOnLayoutChangeListener(layoutListener)
        requestSample()
    }

    /** 移除当前订阅，避免相邻标签页的合成事件驱动本页采集。 */
    private fun unbind() {
        boundSession?.compositorController?.removeDrawCallback(drawCallback)
        boundView?.removeOnLayoutChangeListener(layoutListener)
        boundSession = null; boundView = null
    }

    /** 清除显示帧并废弃请求代次，同时保留最后一次有效底色。 */
    private fun invalidateFrame() {
        gate.invalidate()
        handler.removeCallbacks(sample)
        scheduled = false; dirty = false; image = null
        frameAverage = background
    }

    /** 多次画面变化合并为一次待处理标记，最后一帧不会因在途请求被漏掉。 */
    private fun requestSample() {
        if (!eligible()) return
        dirty = true
        schedule()
    }

    /** 仅有脏画面时调度，不对静止网页轮询。 */
    private fun schedule() {
        if (!dirty || scheduled || gate.busy || !eligible()) return
        scheduled = true
        handler.postDelayed(sample, gate.delay(SystemClock.uptimeMillis()))
    }

    /** 只裁剪 TextureView 独立 Surface 顶部窄条，不包含系统图标、浏览器控件或模糊层。 */
    private fun capture() {
        if (!dirty || !eligible()) return
        val view = boundView ?: return
        val texture = findTexture(view) ?: return
        val surfaceTexture = texture.surfaceTexture ?: return
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0) return
        val sourceWidth = texture.width
        val sourceHeight = texture.height
        val sourceTop = page ?: return
        val ticket = gate.begin(sourceTop.scope, SystemClock.uptimeMillis()) ?: return
        dirty = false
        val cropHeight = topHeight.coerceIn(1, sourceHeight)
        val width = minOf(128, sourceWidth)
        val height = (cropHeight.toFloat() * width / sourceWidth).roundToInt().coerceIn(1, 64)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrElse { gate.finish(ticket); return }
        val surface = runCatching { Surface(surfaceTexture) }
            .getOrElse { bitmap.recycle(); gate.finish(ticket); return }
        fun valid(): Boolean = eligible() && gate.accepts(ticket, currentScope()) && boundView === view &&
            texture.surfaceTexture === surfaceTexture && texture.width == sourceWidth && texture.height == sourceHeight
        fun complete() { gate.finish(ticket); schedule() }
        runCatching {
            PixelCopy.request(surface, Rect(0, 0, sourceWidth, cropHeight), bitmap, { result ->
                var processing = false
                try {
                    if (result != PixelCopy.SUCCESS || !valid()) return@request
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    val previous = background
                    jobs.launch {
                        try {
                            val processed = withContext(Dispatchers.Default) { TopChromePolicy.frame(pixels, width, height, previous) }
                            if (valid()) {
                                background = processed.background
                                frameAverage = processed.average
                                colors[sourceTop.session] = sourceTop.scope to background
                                // 显示帧由 Compose 持有，替换后交给图形运行时释放，不能提前 recycle。
                                image = Bitmap.createBitmap(processed.pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
                            }
                        } finally { complete() }
                    }
                    processing = true
                } finally {
                    bitmap.recycle()
                    // 只释放临时 Surface 包装，不释放 Gecko 拥有的 SurfaceTexture。
                    surface.release()
                    if (!processing) complete()
                }
            }, handler)
        }.onFailure { bitmap.recycle(); surface.release(); complete() }
    }

    /** 使用公开 View 树寻找实际渲染目标，不反射 Gecko 的私有字段。 */
    private fun findTexture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findTexture(view.getChildAt(index))?.let { return it }
        return null
    }
}
