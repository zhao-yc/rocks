package org.rocks.geckodemo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import org.mozilla.geckoview.GeckoDisplay;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.PanZoomController;
import org.mozilla.geckoview.ScreenLength;

/** 在真实 Android/Gecko 上验证释放契约，不用 JVM 桩替代原生 View 生命周期。 */
public final class DisplayLifecycleInstrumentation extends Instrumentation {
    private String safeAreaUrl;
    private String dailyActionsUrl;
    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        safeAreaUrl = arguments == null ? null : arguments.getString("safe_area_url");
        dailyActionsUrl = arguments == null ? null : arguments.getString("daily_actions_url");
        start();
    }

    /** 三条路径均在 UI 线程调用实际 Gecko API，任何断言失败都会返回非成功状态。 */
    @Override
    public void onStart() {
        final Bundle result = new Bundle();
        try {
            if (dailyActionsUrl != null) {
                DailyBrowserActionsChecks.verify(this, dailyActionsUrl);
                result.putString("stream", "日常浏览回归：末页恢复、桌面模式、程序关闭、标签上限、停止、真实失败、输入反馈、新建聚焦及标签匹配均通过\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (safeAreaUrl != null) {
                verifySafeArea();
                result.putString("stream", "固定安全区回归：首屏、滚动、回顶的位置与视口尺寸均通过\n");
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
            result.putString("stream", (dailyActionsUrl != null ? "日常浏览回归失败：" : "显示连接生命周期回归失败：") + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** 在真实 View 树上检查固定安全区，不依赖已移除的截图或模糊控制器。 */
    private void verifySafeArea() {
        final Activity activity = startActivitySync(new Intent(Intent.ACTION_VIEW, Uri.parse(safeAreaUrl))
                .setClass(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final GeckoView[] source = new GeckoView[1];
        try {
            for (int attempt = 0; attempt < 120 && source[0] == null; attempt++) {
                runOnMainSync(() -> source[0] = findPage(activity.getWindow().getDecorView()));
                SystemClock.sleep(100);
            }
            if (source[0] == null) throw new AssertionError("网页未附着到真实窗口");
            final int[] geometry = new int[2];
            runOnMainSync(() -> {
                final android.view.View decor = activity.getWindow().getDecorView();
                final androidx.core.view.WindowInsetsCompat insets =
                        androidx.core.view.ViewCompat.getRootWindowInsets(decor);
                if (insets == null) throw new AssertionError("无法读取真实系统安全区");
                final int safeTop = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                        | androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top;
                final int[] location = new int[2];
                final int[] origin = new int[2];
                source[0].getLocationOnScreen(location);
                decor.getLocationOnScreen(origin);
                geometry[0] = location[1]; geometry[1] = source[0].getHeight();
                if (safeTop <= 0 || location[1] != origin[1] + safeTop
                        || geometry[1] != decor.getHeight() - safeTop) {
                    throw new AssertionError("顶部安全区未恰好避让一次或视口尺寸错误");
                }
            });
            scrollAndAwait(source[0], false);
            assertStableGeometry(source[0], geometry[0], geometry[1]);
            scrollAndAwait(source[0], true);
            assertStableGeometry(source[0], geometry[0], geometry[1]);
        } finally {
            runOnMainSync(activity::finish);
        }
    }

    /** 只选当前可见且完成布局的网页，避免误取分页预加载的相邻标签。 */
    private GeckoView findPage(final android.view.View view) {
        if (!view.isShown()) return null;
        if (view instanceof GeckoView && view.getWidth() > 0 && view.getHeight() > 0
                && ((GeckoView) view).getSession() != null) return (GeckoView) view;
        if (view instanceof android.view.ViewGroup) {
            final android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                final GeckoView found = findPage(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 等到真实滚动回调，防止长页尚未加载或未实际移动时误报几何回归通过。 */
    private void scrollAndAwait(final GeckoView view, final boolean toTop) {
        final java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();
        final GeckoSession.CompositorScrollDelegate[] previous = new GeckoSession.CompositorScrollDelegate[1];
        runOnMainSync(() -> {
            previous[0] = view.getSession().getCompositorScrollDelegate();
            view.getSession().setCompositorScrollDelegate(new GeckoSession.CompositorScrollDelegate() {
                @Override
                public void onScrollChanged(final GeckoSession session, final GeckoSession.ScrollPositionUpdate update) {
                    if (previous[0] != null) previous[0].onScrollChanged(session, update);
                    if (toTop ? Math.abs(update.scrollY) <= 1f : update.scrollY > 20f) reached.set(true);
                }
            });
        });
        try {
            for (int attempt = 0; attempt < 120 && !reached.get(); attempt++) {
                if (attempt % 5 == 0) runOnMainSync(() -> {
                    if (toTop) view.getSession().getPanZoomController().scrollToTop();
                    else view.getSession().getPanZoomController().scrollTo(ScreenLength.zero(),
                            ScreenLength.fromPixels(300), PanZoomController.SCROLL_BEHAVIOR_AUTO);
                });
                SystemClock.sleep(100);
            }
            if (!reached.get()) throw new AssertionError("未收到真实网页滚动回调");
        } finally {
            runOnMainSync(() -> view.getSession().setCompositorScrollDelegate(previous[0]));
        }
    }

    /** 连续观察异步滚动提交，旧下穿实现会在此暴露位置变化。 */
    private void assertStableGeometry(final GeckoView view, final int expectedTop, final int expectedHeight) {
        for (int attempt = 0; attempt < 20; attempt++) {
            SystemClock.sleep(100);
            runOnMainSync(() -> {
                final int[] location = new int[2];
                view.getLocationOnScreen(location);
                if (location[1] != expectedTop || view.getHeight() != expectedHeight
                        || !view.getGlobalVisibleRect(new android.graphics.Rect())) {
                    throw new AssertionError("滚动改变了顶部安全区或网页视口尺寸");
                }
            });
        }
    }
}
