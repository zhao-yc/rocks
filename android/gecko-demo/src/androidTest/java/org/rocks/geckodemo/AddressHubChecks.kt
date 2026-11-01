package org.rocks.geckodemo

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.rocks.browser.core.PageRecord
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/** 显式、隔离的原生面板验收，只可在本机夹具与专用模拟器上运行。 */
internal object AddressHubChecks {
    @JvmStatic fun verify(instrumentation: Instrumentation, base: String, iconsOnly: Boolean) {
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && base == "http://127.0.0.1:8878/") {
            "资料面板回归仅允许专用模拟器与指定回环夹具"
        }
        Checks(instrumentation, base).run(iconsOnly)
    }

    private class Checks(val inst: Instrumentation, val base: String) {
        @Volatile lateinit var activity: Activity
        lateinit var model: BrowserModel
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // 持续跟踪真正恢复的窗口；ActivityMonitor 的单次通知可能早于配置重建完成。
        val windows = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(value: Activity) { if (value is MainActivity) activity = value }
            override fun onActivityCreated(value: Activity, state: Bundle?) { }
            override fun onActivityStarted(value: Activity) { }
            override fun onActivityPaused(value: Activity) { }
            override fun onActivityStopped(value: Activity) { }
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) { }
            override fun onActivityDestroyed(value: Activity) { }
        }

        /** 同时验证真正的输入焦点、视图几何、配置重建与网站图标。 */
        fun run(iconsOnly: Boolean) {
            val application = inst.targetContext.applicationContext as Application
            application.registerActivityLifecycleCallbacks(windows)
            activity = inst.startActivitySync(Intent(Intent.ACTION_VIEW, Uri.parse(base + "one"))
                .setClass(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val getter = MainActivity::class.java.getDeclaredMethod("getBrowser").apply { isAccessible = true }
            model = main { getter.invoke(activity) as BrowserModel }
            try {
                await("夹具网页加载") { !model.restoring && model.current.url == base + "one" && !model.current.loading }
                if (iconsOnly) { iconChecks(); return }
                main {
                    model.closeOtherTabs(); model.clearClosedTabs()
                    check(model.newTab(base + "one") != null) { "无法准备非首个标签的触摸检查" }
                    model.bookmarks.clear()
                    model.bookmarks.add(PageRecord(base + "one", "面板收藏", System.currentTimeMillis()))
                }
                await("非首个网页实际可见") {
                    val view = gecko(activity.window.decorView, model.current.session)
                    val rect = android.graphics.Rect()
                    !model.current.loading && view != null && view.getGlobalVisibleRect(rect) &&
                        rect.width() >= activity.window.decorView.width * .8f
                }
                val selectedBefore = main { model.selectedId }
                val original = main { gecko(activity.window.decorView, model.current.session) ?: error("缺少当前网页") }
                val dimensions = main { original.width to original.height }
                touch("127.0.0.1")
                await("首次键盘与面板") { model.addressHub.visible && ime() }
                main { check(model.selectedId == selectedBefore) { "触摸地址栏意外切换了标签" } }
                awaitSelectedAddress()
                main { check(gecko(activity.window.decorView, model.current.session) === original &&
                    (original.width to original.height) == dimensions) { "面板或键盘改变了原网页视图" } }
                main { WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
                await("主动隐藏输入法") { !ime() }
                SystemClock.sleep(750)
                main { check(model.addressHub.visible) { "输入法隐藏被误当成系统返回" }; model.addressHub.editAddress() }
                await("重新显示地址输入法") { ime() }
                setText("面板草稿")
                await("地址草稿实际同步") { model.addressHub.address.text == "面板草稿" }
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
                }
                check(editable().performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)) { "无法设置原生光标范围" }
                await("原生光标同步") { model.addressHub.address.selection.start == 1 && model.addressHub.address.selection.end == 3 }
                click(inst.targetContext.getString(R.string.bookmarks))
                await("主动分类收键盘") { model.addressHub.visible && model.addressHub.page == AddressHubPage.BOOKMARKS && !ime() }
                main {
                    val draft = model.addressHub.address
                    check(draft.text == "面板草稿" && draft.selection.start == 1 && draft.selection.end == 3) {
                        "切分类后草稿一致=${draft.text == "面板草稿"}，长度=${draft.text.length}，选区=${draft.selection}，输入=${model.addressHub.input}"
                    }
                }
                click(inst.targetContext.getString(R.string.search_bookmarks))
                setText("面板")
                await("书签焦点独立") { model.addressHub.bookmarkQuery == "面板" && model.addressHub.input == HubInput.BOOKMARKS && ime() }
                check(findAll(inst.uiAutomation.rootInActiveWindow) { it.isEditable && it.isFocused }.size == 1) { "两个编辑框同时获得焦点" }

                rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                await("旋转后保留面板") {
                    model.addressHub.visible && model.addressHub.page == AddressHubPage.BOOKMARKS &&
                        model.addressHub.bookmarkQuery == "面板" && activity.window.decorView.hasWindowFocus() && ime()
                }
                main { check(getter.invoke(activity) === model) { "旋转创建了另一份浏览模型" } }
                SystemClock.sleep(250)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("一次返回退出") { !model.addressHub.visible && !ime() }
                main { check(model.current.url == base + "one") { "取消输入改变了原网页" } }

                // 恢复竖屏后从更多菜单进入历史，不能再次强制唤起地址键盘。
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                click(inst.targetContext.getString(R.string.more))
                click(inst.targetContext.getString(R.string.history))
                await("菜单历史入口") { model.addressHub.visible && model.addressHub.page == AddressHubPage.HISTORY && !ime() }
                click(inst.targetContext.getString(R.string.cancel))
                await("关闭历史面板") { !model.addressHub.visible }

                // 两条同运行内的记录均实际关闭引擎会话，随后点击较旧的那一条恢复。
                val closures = main {
                    model.clearClosedTabs()
                    val first = model.newTab(base + "one")!!
                    first.title = "指定恢复一"; model.closeTab(first.id, TabCloseCause.USER, false)
                    val second = model.newTab(base + "two")!!
                    second.title = "指定恢复二"; model.closeTab(second.id, TabCloseCause.USER, false)
                    model.closedTabs.toList()
                }
                click("127.0.0.1")
                // Android 会移除已选中 Tab 的标准点击动作，先切另一分类再返回概览。
                click(inst.targetContext.getString(R.string.bookmarks))
                click(inst.targetContext.getString(R.string.overview))
                await("概览恢复入口") { model.addressHub.visible && !ime() }
                click("指定恢复一")
                await("恢复指定旧记录") { !model.addressHub.visible && model.closedTabs.none { it.closureId == closures.last().closureId } }
                main { check(model.closedTabs.single().closureId == closures.first().closureId) { "误消费了最新恢复记录" } }
                iconChecks()
            } finally {
                main { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                application.unregisterActivityLifecycleCallbacks(windows)
                scope.cancel()
            }
        }

        /** 使用实际方向和当前恢复窗口确认旋转，不能把旧窗口的恢复事件当成新布局。 */
        private fun rotate(request: Int, expected: Int) {
            main { activity.requestedOrientation = request }
            await("实际屏幕方向 $expected") {
                activity.resources.configuration.orientation == expected && activity.window.decorView.hasWindowFocus()
            }
        }

        /** 使用真实 Gecko 请求和解码，覆盖回退、超时、体积限制及正负缓存。 */
        private fun iconChecks() {
            // 每轮独立计数和缓存键，不让前一轮失败留下的统计或磁盘缓存掩盖真实请求。
            resetStats()
            val run = SystemClock.elapsedRealtime()
            val pngUrl = base + "icon-case/png?run=$run"
            val png = loadIcon(pngUrl) ?: error("真实 PNG 解码失败")
            check(png.width in 1..128 && png.height in 1..128) { "图标未限制像素尺寸" }
            val before = stats().getInt("requests")
            check(loadIcon(pngUrl) === png) { "内存缓存未复用位图" }
            check(stats().getInt("requests") == before) { "缓存命中仍发起网络请求" }
            listOf("ico", "relative/deep", "missing", "redirect", "loop", "timeout", "oversized", "broken").forEach {
                check(loadIcon(base + "icon-case/" + it + "?run=$run") != null) { "图标候选或标准回退失败：$it" }
            }
            val negative = "http://127.0.0.1:8879/icon-case/failure?run=$run"
            check(loadIcon(negative) == null) { "真实失败没有使用首字回退" }
            val failureCount = stats().getInt("requests")
            check(loadIcon(negative) == null && stats().getInt("requests") == failureCount) { "负缓存仍重复发起请求" }
            val parallel = AtomicReference<List<Bitmap?>?>()
            scope.launch { parallel.set((1..3).map { index -> async { model.icons.load(base + "icon-case/parallel?entry=$index&run=$run") } }.awaitAll()) }
            await("并发图标订阅") { parallel.get() != null }
            check(parallel.get()!!.all { it != null }) { "并发图标加载失败" }
            val result = stats()
            check(!result.getBoolean("sensitive")) { "图标请求携带了 Cookie、认证或来源地址" }
            check(result.getInt("maximumActive") == 2) { "图标实际并发异常：${result.getInt("maximumActive")}" }
            check(result.getJSONObject("paths").getInt("/icons/loop") == 4) { "没有执行重定向上限" }
            lateRowCheck(run)
        }

        /** 删除正在请求慢图标的行，再核对新行的蓝色像素，迟到的绿色图标不得覆盖它。 */
        private fun lateRowCheck(run: Long) {
            val previous = stats().getJSONObject("paths").optInt("/icons/slow")
            main {
                model.bookmarks.clear()
                model.bookmarks.add(PageRecord(base + "icon-case/timeout?late=$run", "迟到图标", 0))
                model.addressHub.open(model.selectedId, model.current.url, AddressHubPage.BOOKMARKS, edit = false)
            }
            var started = false
            for (attempt in 0 until 80) {
                if (stats().getJSONObject("paths").optInt("/icons/slow") > previous) { started = true; break }
                SystemClock.sleep(100)
            }
            check(started) { "慢速图标没有实际开始请求" }
            main {
                model.bookmarks.clear()
                model.bookmarks.add(PageRecord(base + "icon-case/blue?late=$run", "替换后的图标", 0))
            }
            awaitBlueIcon()
            var ended = false
            for (attempt in 0 until 100) {
                if (stats().getInt("active") == 0) { ended = true; break }
                SystemClock.sleep(100)
            }
            check(ended) { "迟到图标连接没有结束" }
            awaitBlueIcon()
            check(stats().getInt("maximumActive") <= 2) { "取消可见订阅后超出了网络并发上限" }
            click(inst.targetContext.getString(R.string.cancel))
            await("图标验收退出面板") { !model.addressHub.visible }
        }

        /** 只检查合成夹具图块的像素，不保存屏幕或读取真实网页内容。 */
        private fun awaitBlueIcon() {
            repeat(100) {
                var row: AccessibilityNodeInfo? = node { it.text?.toString() == "替换后的图标" }
                while (row != null && !row.isClickable) row = row.parent
                val bounds = android.graphics.Rect()
                row?.getBoundsInScreen(bounds)
                val density = main { activity.resources.displayMetrics.density }
                val capture = inst.uiAutomation.takeScreenshot()
                try {
                    if (capture != null && !bounds.isEmpty) {
                        val x = bounds.left + (36 * density).toInt()
                        val y = bounds.centerY()
                        if (x in 0 until capture.width && y in 0 until capture.height) {
                            val pixel = capture.getPixel(x, y)
                            // 容许系统色彩转换的微小差异，绿色迟到图标仍必然无法通过。
                            if (kotlin.math.abs(android.graphics.Color.red(pixel) - 45) <= 3 &&
                                kotlin.math.abs(android.graphics.Color.green(pixel) - 105) <= 3 &&
                                kotlin.math.abs(android.graphics.Color.blue(pixel) - 200) <= 3) return
                        }
                    }
                } finally { capture?.recycle() }
                SystemClock.sleep(100)
            }
            error("替换后的行没有显示自己的真实蓝色图标")
        }

        /** 在主线程订阅仓库，在 instrumentation 工作线程等待，不能阻塞 Gecko 的主线程回调。 */
        private fun loadIcon(url: String): Bitmap? {
            data class Outcome(val value: Bitmap?, val error: Throwable?)
            val outcome = AtomicReference<Outcome?>()
            scope.launch {
                try { outcome.set(Outcome(model.icons.load(url), null)) }
                catch (failure: Throwable) { outcome.set(Outcome(null, failure)) }
            }
            await("图标请求结束") { outcome.get() != null }
            val result = outcome.get()!!
            result.error?.let { throw AssertionError("图标请求异常", it) }
            return result.value
        }

        /** 只读取已确认的回环夹具计数，不接触账号或外部网络。 */
        private fun stats(): JSONObject {
            val connection = URL(base + "icon-stats").openConnection() as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            return try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
        }

        /** 等上一次合成连接结束再开始独立计数，不重启浏览器也不清空应用数据。 */
        private fun resetStats() {
            repeat(100) {
                val connection = URL(base + "icon-reset").openConnection() as HttpURLConnection
                connection.connectTimeout = 2000; connection.readTimeout = 2000
                try { if (connection.responseCode == 200) { connection.inputStream.close(); return } }
                finally { connection.disconnect() }
                SystemClock.sleep(100)
            }
            error("旧图标连接未结束，拒绝重置验收计数")
        }

        private fun ime() = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

        /** 几何断言绑定实际会话，不以模型选中标记代替真实显示。 */
        private fun gecko(view: View, session: GeckoSession): GeckoView? {
            if (view is GeckoView && view.session === session) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) gecko(view.getChildAt(index), session)?.let { return it }
            return null
        }

        /** 所有模型和 View 读取都在主线程进行，失败带回 runner。 */
        private fun <T> main(action: () -> T): T {
            val value = AtomicReference<T>(); val failure = AtomicReference<Throwable?>()
            inst.runOnMainSync { try { value.set(action()) } catch (error: Throwable) { failure.set(error) } }
            failure.get()?.let { throw AssertionError(it.message, it) }
            return value.get()
        }

        private fun await(label: String, predicate: () -> Boolean) {
            repeat(250) { if (main(predicate)) return; SystemClock.sleep(100) }
            error("原生资料面板等待超时：$label")
        }

        /** 原生语义树动作替代坐标，兼容键盘动画和屏幕旋转。 */
        private fun findAll(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            val result = mutableListOf<AccessibilityNodeInfo>()
            if (predicate(root)) result += root
            for (index in 0 until root.childCount) result += findAll(root.getChild(index), predicate)
            return result
        }

        private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            repeat(80) {
                findAll(inst.uiAutomation.rootInActiveWindow, predicate).firstOrNull()?.let { return it }
                SystemClock.sleep(100)
            }
            error("未找到原生资料面板控件")
        }

        private fun editable() = node { it.packageName?.toString() == inst.targetContext.packageName && it.isEditable && it.isFocused }

        /** 用真实触摸覆盖地址点击与横向拖动取消的竞争，不能只依靠语义动作。 */
        private fun touch(label: String) {
            val target = node { it.text?.toString() == label || it.contentDescription?.toString() == label }
            val bounds = android.graphics.Rect()
            target.getBoundsInScreen(bounds)
            check(!bounds.isEmpty) { "触摸目标没有实际尺寸" }
            val downAt = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            val up = MotionEvent.obtain(downAt, downAt + 80, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN; up.source = InputDevice.SOURCE_TOUCHSCREEN
            try { inst.sendPointerSync(down); SystemClock.sleep(80); inst.sendPointerSync(up) }
            finally { down.recycle(); up.recycle() }
        }

        /** 输入法可见不等于无障碍选区事件已经送达，等待实际原生选区稳定而不是固定延迟。 */
        private fun awaitSelectedAddress() {
            var observed = "无选区"
            repeat(50) {
                val field = editable()
                observed = "${field.textSelectionStart}:${field.textSelectionEnd}/${field.text?.length}"
                if (field.textSelectionStart == 0 && field.textSelectionEnd == field.text?.length) return
                SystemClock.sleep(100)
            }
            error("首次网址未全选：原生选区 $observed；模型选区 ${main { model.addressHub.address.selection }}")
        }
        private fun click(label: String) {
            repeat(80) {
                var target: AccessibilityNodeInfo? = findAll(inst.uiAutomation.rootInActiveWindow) {
                    it.text?.toString() == label || it.contentDescription?.toString() == label
                }.firstOrNull()
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                SystemClock.sleep(100)
            }
            error("无法点击原生资料控件：$label；面板=${main { model.addressHub.visible }}，输入=${main { model.addressHub.input }}")
        }

        private fun setText(value: String) {
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            check(editable().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "无法设置原生输入" }
            // 模型先更新、原生语义随后更新；旧节点的选区动作会把旧网址一并写回。
            node { it.packageName?.toString() == inst.targetContext.packageName && it.isEditable && it.isFocused && it.text?.toString() == value }
        }
    }
}
