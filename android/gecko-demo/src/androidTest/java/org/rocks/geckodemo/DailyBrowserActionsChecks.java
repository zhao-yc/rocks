package org.rocks.geckodemo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.mozilla.geckoview.GeckoSessionSettings;

/** 使用现有原生 runner 验证真实引擎与 Compose 输入，不添加产品调试桥或测试框架依赖。 */
final class DailyBrowserActionsChecks {
    private final Instrumentation instrumentation;
    private final String base;
    private BrowserModel model;

    /** 只允许显式提供回环夹具的模拟器；本测试会整理测试标签，不应在用户手机上运行。 */
    static void verify(Instrumentation instrumentation, String base) throws ReflectiveOperationException {
        if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)) ||
                !"http://127.0.0.1:8878/".equals(base)) {
            throw new IllegalArgumentException("日常操作回归仅支持专用模拟器与指定的本机夹具");
        }
        new DailyBrowserActionsChecks(instrumentation, base).run();
    }

    /** 夹具地址在调用入口校验，测试不会接触外部账号、文件选择器或网站设备授权。 */
    private DailyBrowserActionsChecks(Instrumentation instrumentation, String base) {
        this.instrumentation = instrumentation;
        this.base = base;
    }

    /** 按真实事件顺序覆盖初始化空白、关闭恢复、停止完成及错误空白回退。 */
    private void run() throws ReflectiveOperationException {
        Activity activity = instrumentation.startActivitySync(new Intent(Intent.ACTION_VIEW, Uri.parse(base + "one"))
                .setClass(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Method getter = MainActivity.class.getDeclaredMethod("getBrowser");
        getter.setAccessible(true);
        model = (BrowserModel) getter.invoke(activity);
        awaitState("会话恢复", () -> !model.getRestoring());
        onMain(() -> { model.closeOtherTabs(); model.navigate(base + "one"); });
        awaitPage("one");

        onMain(model::toggleDesktop);
        awaitPage("one");
        onMain(() -> model.closeTab(model.getSelectedId(), TabCloseCause.USER, false));
        // 必须让真实 Gecko 的空白初始化回调到达，不能只验证同一 UI 帧内的理想顺序。
        SystemClock.sleep(600);
        onMain(() -> {
            check(model.getTabs().size() == 1 && DemoPolicy.HOME.equals(model.getCurrent().getUrl()), "关闭末页后应只有自动首页");
            check(model.reopenClosedTab(null), "末页恢复失败");
            check(model.getTabs().size() == 1, "恢复末页后多留了自动首页");
            check(model.getCurrent().getDesktop(), "恢复丢失桌面模式");
            check(model.getCurrent().getSession().getSettings().getUserAgentMode() == GeckoSessionSettings.USER_AGENT_MODE_DESKTOP,
                    "首次恢复加载前未应用桌面 UA");
        });
        awaitPage("one");

        onMain(() -> {
            BrowserTab programmatic = model.newTab(base + "two", true, null, false, null);
            check(programmatic != null, "程序关闭夹具创建失败");
            model.closeTab(programmatic.getId(), TabCloseCause.PROGRAMMATIC, false);
            check(model.getLatestClosedTab() == null, "程序关闭污染误关记录");
            model.closeTab(model.getSelectedId(), TabCloseCause.USER, false);
            long closure = model.getLatestClosedTab().getClosureId();
            while (model.getTabs().size() < DemoPolicy.MAX_TABS) model.newTab(DemoPolicy.HOME, true, null, false, null);
            check(!model.reopenClosedTab(null), "达到标签上限后仍恢复了新标签");
            check(model.getLatestClosedTab().getClosureId() == closure, "达到上限消耗了恢复记录");
            model.closeOtherTabs();
            check(model.reopenClosedTab(null), "腾出空间后无法恢复");
            check(model.getTabs().size() == 2, "恢复不应替换用户主动新建的首页");
            model.navigate(base + "slow");
        });
        awaitState("慢速请求开始", () -> model.getCurrent().getLoading());
        onMain(model::stopLoading);
        SystemClock.sleep(500);
        onMain(() -> check(!model.getCurrent().getLoading() && !model.getCurrent().getError(), "主动停止被显示为加载失败"));
        onMain(() -> model.navigate(base + "one"));
        awaitPage("one");
        onMain(() -> model.navigate(base + "fail"));
        awaitState("真实网络失败", () -> model.getCurrent().getError() && !model.getCurrent().getLoading());
        SystemClock.sleep(500);
        onMain(() -> check(model.getCurrent().getError() && (base + "fail").equals(model.getCurrent().getUrl()),
                "引擎空白回退抹掉了真实错误或失败网址"));
        onMain(() -> model.navigate(base + "one"));
        awaitPage("one");
        verifyAddressUi();
        onMain(activity::finish);
    }

    /** 用原生无障碍文本动作避免输入法把测试协议改成全角标点，仍走真实 Compose 提交与提示。 */
    private void verifyAddressUi() {
        clickLabel("127.0.0.1");
        setAddress("javascript:alert(1)");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitLabel(instrumentation.getTargetContext().getString(R.string.address_unsupported_scheme));
        check("javascript:alert(1)".contentEquals(editable().getText()), "非法提交丢失了地址草稿");
        clickLabel(instrumentation.getTargetContext().getString(R.string.cancel));
        clickLabel(instrumentation.getTargetContext().getString(R.string.more));
        final int[] before = {0};
        onMain(() -> before[0] = model.getTabs().size());
        clickLabel(instrumentation.getTargetContext().getString(R.string.new_tab));
        awaitEditable();
        onMain(() -> check(model.getTabs().size() == before[0] + 1 && DemoPolicy.HOME.equals(model.getCurrent().getUrl()),
                "新建未选中新标签"));
        setAddress(base + "one");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitPage("one");
        awaitNoEditor();
        onMain(() -> model.newTab(base + "two", true, null, false, null));
        awaitPage("two");
        onMain(() -> before[0] = model.getTabs().size());
        clickLabel("127.0.0.1");
        setAddress("One");
        clickLabel(instrumentation.getTargetContext().getString(R.string.switch_to_tab));
        awaitState("匹配已有标签", () -> (base + "one").equals(model.getCurrent().getUrl()));
        onMain(() -> check(model.getTabs().size() == before[0], "切换已有标签额外新建了标签"));
        awaitNoEditor();
    }

    /** 成功状态同时核对网址、加载结束和错误标记，不能只依据进度值。 */
    private void awaitPage(String suffix) {
        awaitState("夹具页面 " + suffix, () -> (base + suffix).equals(model.getCurrent().getUrl()) &&
                !model.getCurrent().getLoading() && !model.getCurrent().getError());
    }

    /** 只在主线程读取模型，并将异步异常带回 runner，而非造成应用主线程崩溃。 */
    private void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable caught) { failure.set(caught); } });
        if (failure.get() != null) throw new AssertionError(failure.get().getMessage(), failure.get());
    }

    /** 有界等待实际引擎回调，超时必须失败，不用固定等待替代验收。 */
    private void awaitState(String label, BooleanSupplier condition) {
        for (int i = 0; i < 80; i++) {
            AtomicBoolean ready = new AtomicBoolean();
            onMain(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError(label + "超时");
    }

    /** 原生节点递归检索仅用于测试包，不向产品提供网站脚本或外部控制接口。 */
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> predicate) {
        if (root == null) return null;
        if (predicate.test(root)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(root.getChild(i), predicate);
            if (found != null) return found;
        }
        return null;
    }

    /** 等待 Compose 语义树完成后操作节点，避免坐标与键盘动画竞态。 */
    private AccessibilityNodeInfo awaitNode(Predicate<AccessibilityNodeInfo> predicate, String label) {
        for (int i = 0; i < 60; i++) {
            AccessibilityNodeInfo node = findNode(instrumentation.getUiAutomation().getRootInActiveWindow(), predicate);
            if (node != null) return node;
            SystemClock.sleep(150);
        }
        throw new AssertionError("没有找到原生节点：" + label);
    }

    /** 标签可能位于可点击容器的子节点，沿父节点找到真正的操作目标。 */
    private void clickLabel(String text) {
        AccessibilityNodeInfo node = awaitLabel(text);
        while (node != null && !node.isClickable()) node = node.getParent();
        check(node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "无法点击原生控件：" + text);
    }

    /** 同时支持图标说明和文本按钮，遵循当前界面的中英文资源。 */
    private AccessibilityNodeInfo awaitLabel(String text) {
        return awaitNode(node -> text.contentEquals(node.getText() == null ? "" : node.getText()) ||
                text.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()), text);
    }

    /** 地址遮罩展开时，仅原生编辑框拥有输入焦点。 */
    private AccessibilityNodeInfo editable() {
        return findNode(instrumentation.getUiAutomation().getRootInActiveWindow(), node -> node.isEditable() && node.isFocused());
    }

    /** 新建标签必须自动将输入焦点交给地址框。 */
    private AccessibilityNodeInfo awaitEditable() {
        return awaitNode(node -> node.isEditable() && node.isFocused(), "地址输入框");
    }

    /** 通过标准 Android 文本动作写入精确测试值，随后仍由真实键盘提交路径处理。 */
    private void setAddress(String text) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        check(awaitEditable().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments), "地址输入失败");
        SystemClock.sleep(200);
    }

    /** 成功提交和切换标签之后，地址编辑层必须退出。 */
    private void awaitNoEditor() {
        for (int i = 0; i < 60; i++) {
            if (editable() == null) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError("地址编辑未退出");
    }

    /** 失败条件使用明确中文原因，不记录网页内容或用户浏览数据。 */
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
