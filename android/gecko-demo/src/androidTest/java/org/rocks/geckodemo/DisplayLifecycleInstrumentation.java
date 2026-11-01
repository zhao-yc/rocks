package org.rocks.geckodemo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.mozilla.geckoview.GeckoDisplay;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.PanZoomController;
import org.mozilla.geckoview.ScreenLength;

/** 在真实 Android/Gecko 上验证释放契约，不用 JVM 桩替代原生 View 生命周期。 */
public final class DisplayLifecycleInstrumentation extends Instrumentation {
    private String renderUrl;
    private boolean checkUnderlay;
    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        renderUrl = arguments == null ? null : arguments.getString("render_url");
        if (arguments != null && arguments.getString("underlay_url") != null) {
            renderUrl = arguments.getString("underlay_url");
            checkUnderlay = true;
        }
        start();
    }

    /** 三条路径均在 UI 线程调用实际 Gecko API，任何断言失败都会返回非成功状态。 */
    @Override
    public void onStart() {
        final Bundle result = new Bundle();
        try {
            if (renderUrl != null) {
                verifyRenderedFrames();
                result.putString("stream", checkUnderlay
                        ? "网页下穿回归：首屏避让、真实视图进入状态栏、固定视口高度、回顶恢复均通过\n"
                        : "顶部真实绘制回归：收到至少 3 帧独立模糊纹理\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            runOnMainSync(() -> {
                final BrowserGeckoSession session = new BrowserGeckoSession();
                session.open(((DemoApplication) getTargetContext().getApplicationContext()).getRuntime());
                try {
                    final SafeGeckoView view = new SafeGeckoView(
                            new ContextThemeWrapper(getTargetContext(), R.style.Theme_RocksDemo));
                    view.setSession(session);
                    // 从未附着的 View 没有 display；这是分页预加载/丢弃时的真实释放入口。
                    if (view.releaseSession() != session || session.getAttachedDisplay() != null) {
                        throw new AssertionError("未附着网页释放后仍保留显示连接");
                    }
                    final GeckoDisplay display = session.acquireDisplay();
                    if (session.getAttachedDisplay() != display) {
                        throw new AssertionError("取得的显示连接没有同步");
                    }
                    session.releaseDisplay(display);
                    // 模拟 onDetachedFromWindow 已释放后，Compose onRelease 再释放会话。
                    view.setSession(session);
                    view.releaseSession();
                    if (session.getAttachedDisplay() != null) {
                        throw new AssertionError("重复释放后仍保留旧连接");
                    }
                    try {
                        session.releaseDisplay(display);
                        throw new AssertionError("过期连接不应绕过 Gecko 的所有权校验");
                    } catch (IllegalArgumentException expected) {
                        // 错误连接仍由 Gecko 拒绝，不能为了防闪退吞掉生命周期错误。
                    }
                } finally {
                    session.close();
                }
            });
            result.putString("stream", "显示连接生命周期回归：3 项通过\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "显示连接生命周期回归失败：" + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** 仅测试包读取本应用控制器；动画夹具由显式 URL 提供，不给产品增加脚本或调试桥。 */
    private void verifyRenderedFrames() throws ReflectiveOperationException {
        final Activity activity = startActivitySync(new Intent(Intent.ACTION_VIEW, Uri.parse(renderUrl))
                .setClass(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final Field chromeField = MainActivity.class.getDeclaredField("topChrome");
        chromeField.setAccessible(true);
        final Set<Object> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int attempt = 0; attempt < 120 && frames.size() < 3; attempt++) {
            runOnMainSync(() -> {
                try {
                    final ImmersiveStatusBar chrome = (ImmersiveStatusBar) chromeField.get(activity);
                    final Object image = chrome.getImage();
                    if (image != null) frames.add(image);
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            SystemClock.sleep(250);
        }
        if (frames.size() < 3) throw new AssertionError("未收到连续的顶部画面，实际帧数：" + frames.size());
        if (checkUnderlay) verifyUnderlay(activity, chromeField);
        runOnMainSync(activity::finish);
    }

    /** 验证真实网页 View 的屏幕位置和尺寸，防止只用截图延展伪造状态栏下方内容。 */
    private void verifyUnderlay(final Activity activity, final Field chromeField) throws ReflectiveOperationException {
        final Field viewField = ImmersiveStatusBar.class.getDeclaredField("boundView");
        viewField.setAccessible(true);
        final GeckoView[] source = new GeckoView[1];
        final int[] initial = new int[2];
        runOnMainSync(() -> {
            try {
                source[0] = (GeckoView) viewField.get(chromeField.get(activity));
                final int[] location = new int[2];
                source[0].getLocationOnScreen(location);
                initial[0] = location[1]; initial[1] = source[0].getHeight();
                if (initial[0] <= 0 || initial[1] != activity.getWindow().getDecorView().getHeight()) {
                    throw new AssertionError("首屏未避让或网页没有完整窗口高度");
                }
                source[0].getSession().getPanZoomController().scrollTo(ScreenLength.zero(),
                        ScreenLength.fromPixels(300), PanZoomController.SCROLL_BEHAVIOR_AUTO);
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        });
        waitForPosition(source[0], 0, initial[1]);
        runOnMainSync(() -> source[0].getSession().getPanZoomController().scrollToTop());
        waitForPosition(source[0], initial[0], initial[1]);
    }

    /** 等引擎滚动和 Compose 提交完成后再断言，不把单帧尚未同步当成最终位置。 */
    private void waitForPosition(final GeckoView view, final int expectedTop, final int expectedHeight) {
        final boolean[] matched = new boolean[1];
        for (int attempt = 0; attempt < 80 && !matched[0]; attempt++) {
            runOnMainSync(() -> {
                final int[] location = new int[2];
                view.getLocationOnScreen(location);
                if (view.getHeight() != expectedHeight) throw new AssertionError("滚动改变了真实网页视口高度");
                matched[0] = location[1] == expectedTop && view.getGlobalVisibleRect(new android.graphics.Rect());
            });
            SystemClock.sleep(100);
        }
        if (!matched[0]) throw new AssertionError("真实网页未到达预期屏幕位置：" + expectedTop);
    }
}
