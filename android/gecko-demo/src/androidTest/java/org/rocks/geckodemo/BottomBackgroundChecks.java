package org.rocks.geckodemo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 在专用模拟器验证真实内置扩展、Gecko 坐标和 Compose 背景，不读取用户手机。 */
final class BottomBackgroundChecks {
    private final Instrumentation instrumentation;
    private final String base;
    private BrowserModel model;
    private Activity activity;

    static void verify(Instrumentation instrumentation, String base) throws ReflectiveOperationException {
        if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)) ||
                !"http://127.0.0.1:8878/".equals(base)) {
            throw new IllegalArgumentException("底栏回归仅支持专用模拟器与指定回环夹具");
        }
        new BottomBackgroundChecks(instrumentation, base).run();
    }

    private BottomBackgroundChecks(Instrumentation instrumentation, String base) {
        this.instrumentation = instrumentation; this.base = base;
    }

    /** 只修改模拟器测试标签，不导入或导出用户数据。 */
    private void run() throws ReflectiveOperationException {
        activity = instrumentation.startActivitySync(new Intent(Intent.ACTION_VIEW, Uri.parse(base + "bottom-white"))
                .setClass(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Method getter = MainActivity.class.getDeclaredMethod("getBrowser"); getter.setAccessible(true);
        model = (BrowserModel) getter.invoke(activity);
        try {
            await("会话恢复", () -> !model.getRestoring());
            navigate("bottom-white"); awaitColor(0xFFFFFFFF); verifyPixel(0xFFFFFFFF);
            navigate("bottom-dark"); awaitColor(0xFF141E28); verifyPixel(0xFF141E28);
            navigate("bottom-sticky"); awaitColor(0xFFFFFFFF); verifyPixel(0xFFFFFFFF);
            navigate("bottom-none"); SystemClock.sleep(500); awaitColor(null);
            navigate("bottom-obscured"); SystemClock.sleep(500); awaitColor(null);
            navigate("bottom-dynamic"); awaitColor(0xFFFFFFFF); awaitColor(0xFF141E28); awaitColor(null);
            navigate("bottom-white"); awaitColor(0xFFFFFFFF);
            final long[] first = new long[1];
            instrumentation.runOnMainSync(() -> {
                first[0] = model.getSelectedId(); model.newTab(base + "bottom-none", true, null, false, null);
            });
            await("新标签加载", () -> (base + "bottom-none").equals(model.getCurrent().getUrl()) && !model.getCurrent().getLoading());
            SystemClock.sleep(500); awaitColor(null);
            instrumentation.runOnMainSync(() -> model.selectTab(first[0])); awaitColor(0xFFFFFFFF);
            instrumentation.runOnMainSync(model::reload); awaitColor(0xFFFFFFFF);
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    private void navigate(String route) {
        instrumentation.runOnMainSync(() -> model.navigate(base + route));
        await("页面 " + route, () -> (base + route).equals(model.getCurrent().getUrl()) && !model.getCurrent().getLoading() && !model.getCurrent().getError());
    }

    private void awaitColor(Integer expected) {
        await("底色 " + expected, () -> java.util.Objects.equals(expected, model.getBottomBackground().getColor()));
    }

    /** 胶囊外侧像素必须等于衔接底色，防止只改变状态却没有绘制全宽背景。 */
    private void verifyPixel(int expected) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(150);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new AssertionError("无法取得模拟器测试画面");
        try {
            final int[] point = new int[2];
            instrumentation.runOnMainSync(() -> {
                float density = activity.getResources().getDisplayMetrics().density;
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
                int bottom = insets == null ? 0 : insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                point[0] = Math.max(1, (int) (2 * density));
                point[1] = activity.getWindow().getDecorView().getHeight() - bottom - (int) (24 * density);
            });
            if (screenshot.getPixel(point[0], point[1]) != expected) throw new AssertionError("胶囊外侧仍透出网页，预期 " + Integer.toHexString(expected));
        } finally { screenshot.recycle(); }
    }

    /** 有界等待真实引擎回调，超时保留明确失败，不以固定延迟冒充通过。 */
    private void await(String name, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        AtomicBoolean matched = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> matched.set(condition.getAsBoolean()));
            if (matched.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("等待超时：" + name + "；" + diagnostics());
    }

    /** 仅对回环夹具输出内存几何，方便识别真实 API 与 JVM 桩的差异。 */
    private String diagnostics() {
        StringBuilder result = new StringBuilder();
        instrumentation.runOnMainSync(() -> {
            try {
                result.append("committed=").append(model.getCurrent().getBottomDocumentCommitted())
                        .append(";scope=").append(model.pageScope(model.getCurrent().getSession())).append(';');
                Object bridge = model.getBottomBackground();
                for (String name : new String[]{"port", "boundScope", "report", "clipping", "paused", "view"}) {
                    java.lang.reflect.Field field = bridge.getClass().getDeclaredField(name); field.setAccessible(true);
                    Object value = field.get(bridge); result.append(name).append('=').append(value).append(';');
                    if (value instanceof org.mozilla.geckoview.GeckoView) {
                        org.mozilla.geckoview.GeckoView view = (org.mozilla.geckoview.GeckoView) value;
                        android.graphics.Matrix matrix = new android.graphics.Matrix(); view.getSession().getClientToScreenMatrix(matrix);
                        int[] origin = new int[2]; view.getLocationOnScreen(origin);
                        result.append("height=").append(view.getHeight()).append(";originY=").append(origin[1]).append(";matrix=").append(matrix.toShortString());
                    }
                }
            } catch (ReflectiveOperationException error) { result.append(error); }
        });
        return result.toString();
    }
}
