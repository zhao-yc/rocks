package org.rocks.geckodemo

import android.content.Context
import android.graphics.Region
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** 保留 GeckoView 正常取得的 Display 引用，只用于公开的安全区 API，不另行取得显示所有权。 */
internal class BrowserGeckoSession : GeckoSession() {
    var attachedDisplay: GeckoDisplay? = null
        private set

    /** 复用 GeckoView 发起的唯一显示连接。 */
    override fun acquireDisplay(): GeckoDisplay = super.acquireDisplay().also { attachedDisplay = it }

    /** 与 GeckoView 的释放保持一致，不能留下失效的显示引用。 */
    override fun releaseDisplay(display: GeckoDisplay) {
        if (attachedDisplay === display) attachedDisplay = null
        super.releaseDisplay(display)
    }
}

/** 宿主已避让顶部和两侧后，不再让 viewport-fit=cover 网页通过 CSS env 重复避让。 */
internal class SafeGeckoView(context: Context) : GeckoView(context) {
    var protectTopAndSides = true
        set(value) {
            if (field == value) return
            field = value
            updateSafeArea()
        }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updateSafeArea() }

    /** 等宿主完成布局后再次校正安全边距。 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    /** 随 View 解绑窗口监听，避免旋转后旧 View 继续工作。 */
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        super.onDetachedFromWindow()
    }

    /** 系统栏变化时同步剩余边距。 */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets =
        super.onApplyWindowInsets(insets).also { updateSafeArea() }

    /** Gecko 在此更新整个窗口的边距，必须在其更新后应用宿主的消费结果。 */
    override fun gatherTransparentRegion(region: Region?): Boolean =
        super.gatherTransparentRegion(region).also { updateSafeArea() }

    /** Gecko 默认传递整个窗口的安全区；此处在其布局更新后仅保留尚未被宿主消费的部分。 */
    private fun updateSafeArea() {
        val display = (session as? BrowserGeckoSession)?.attachedDisplay ?: return
        val insets = ViewCompat.getRootWindowInsets(this)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: return
        display.safeAreaInsetsChanged(if (protectTopAndSides) 0 else insets.top,
            if (protectTopAndSides) 0 else insets.right, insets.bottom,
            if (protectTopAndSides) 0 else insets.left)
    }
}
