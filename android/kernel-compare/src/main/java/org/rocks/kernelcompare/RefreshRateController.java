package org.rocks.kernelcompare;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 两份内核共用的窗口级刷新率选择器；不修改系统全局设置或内核帧率偏好。 */
final class RefreshRateController implements DisplayManager.DisplayListener {
    private static final String PREFERENCE = "refresh_rate_hz";
    private final Activity activity;
    private final View anchor;
    private final Runnable changed;
    private final DisplayManager displays;
    private final SharedPreferences preferences;
    private final LinearLayout row;
    private final Spinner selector;
    private List<Float> rates = Collections.emptyList();
    private float preference;
    private boolean active;
    private boolean updatingSelector;
    private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { if (active) refresh(); }
        @Override public void onViewDetachedFromWindow(View view) { applyWindow(0f); }
    };

    /** 为每份测试应用保存自己的选择；初次安装默认跟随系统。 */
    RefreshRateController(Activity activity, View anchor, Runnable changed) {
        this.activity = activity;
        this.anchor = anchor;
        this.changed = changed;
        displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        preferences = activity.getSharedPreferences("kernel_comparison", Context.MODE_PRIVATE);
        try { preference = preferences.getFloat(PREFERENCE, RefreshRatePolicy.FOLLOW_SYSTEM); }
        catch (ClassCastException error) { preference = RefreshRatePolicy.FOLLOW_SYSTEM; }
        row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView label = new TextView(activity);
        label.setText(R.string.refresh_rate);
        final int padding = Math.round(8 * activity.getResources().getDisplayMetrics().density);
        label.setPadding(padding, 0, padding, 0);
        row.addView(label);
        selector = new Spinner(activity, Spinner.MODE_DROPDOWN);
        selector.setId(R.id.refresh_selector);
        selector.setContentDescription(activity.getString(R.string.refresh_rate));
        selector.setEnabled(false);
        row.addView(selector, new LinearLayout.LayoutParams(0, -1, 1));
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingSelector || !active) return;
                // 读取当前选中项而非迟到回调的位置，防止模式列表重建时覆盖已保存选择。
                final Choice choice = (Choice) selector.getSelectedItem();
                if (choice == null || Float.compare(choice.preference, preference) == 0) return;
                preference = choice.preference;
                savePreference();
                applySelection();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        anchor.addOnAttachStateChangeListener(attachListener);
    }

    /** 返回固定高度的共用原生选择行，不让切换档位改变网页视口。 */
    View view() { return row; }

    /** 显示已提交给窗口的申请值，不把它冒充为系统实际采用的刷新率。 */
    String requestedLabel() {
        final float submitted = activity.getWindow().getAttributes().preferredRefreshRate;
        return submitted > 0 ? format(submitted) : activity.getString(R.string.refresh_follow);
    }

    /** 返回前台后重读显示器能力，再恢复当前选择的窗口申请。 */
    void resume() {
        if (active) return;
        active = true;
        displays.registerDisplayListener(this, null);
        refresh();
        anchor.post(() -> { if (active) refresh(); });
    }

    /** 后台撤回窗口偏好，保留用户选择供恢复时使用。 */
    void pause() {
        active = false;
        displays.unregisterDisplayListener(this);
        applyWindow(0f);
    }

    /** 销毁时解除外部显示监听和 View 订阅，不保留 Activity。 */
    void close() {
        pause();
        anchor.removeOnAttachStateChangeListener(attachListener);
    }

    /** 汇总当前分辨率的模式与无缝备选频率；不把其他分辨率的高刷放进选项。 */
    private void refresh() {
        final Display display = anchor.getDisplay();
        if (display == null || !display.isValid()) {
            selector.setEnabled(false);
            applyWindow(0f);
            changed.run();
            return;
        }
        final Display.Mode current = display.getMode();
        final List<RefreshRatePolicy.Mode> modes = new ArrayList<>();
        addMode(modes, current);
        for (Display.Mode mode : display.getSupportedModes()) addMode(modes, mode);
        final List<Float> available = RefreshRatePolicy.supported(current.getPhysicalWidth(), current.getPhysicalHeight(), modes);
        final float normalized = RefreshRatePolicy.normalize(preference, available);
        final boolean fallback = Float.compare(preference, normalized) != 0;
        preference = normalized;
        if (fallback) {
            savePreference();
            if (normalized == RefreshRatePolicy.FOLLOW_SYSTEM) Toast.makeText(activity, R.string.refresh_unavailable, Toast.LENGTH_SHORT).show();
        }
        if (!rates.equals(available) || selector.getAdapter() == null || fallback) {
            rates = available;
            rebuildChoices();
        }
        selector.setEnabled(!rates.isEmpty());
        applySelection();
    }

    /** 只通过公开显示模式 API 获取能力，Android 12 起同时读取无缝备选列表。 */
    private void addMode(List<RefreshRatePolicy.Mode> modes, Display.Mode mode) {
        modes.add(new RefreshRatePolicy.Mode(mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate()));
        if (Build.VERSION.SDK_INT >= 31) {
            for (float rate : mode.getAlternativeRefreshRates()) {
                modes.add(new RefreshRatePolicy.Mode(mode.getPhysicalWidth(), mode.getPhysicalHeight(), rate));
            }
        }
    }

    /** 按系统、实际档位、最高排列；数值保留小数，且不增加手机未报告的 60/120Hz。 */
    private void rebuildChoices() {
        final List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(RefreshRatePolicy.FOLLOW_SYSTEM, activity.getString(R.string.refresh_follow)));
        for (float rate : rates) choices.add(new Choice(rate, format(rate)));
        if (!rates.isEmpty()) choices.add(new Choice(RefreshRatePolicy.HIGHEST,
                activity.getString(R.string.refresh_highest, format(rates.get(rates.size() - 1)))));
        int selected = 0;
        for (int index = 0; index < choices.size(); index++) {
            if (Float.compare(choices.get(index).preference, preference) == 0) selected = index;
        }
        updatingSelector = true;
        try {
            final ArrayAdapter<Choice> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, choices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            selector.setAdapter(adapter);
            selector.setSelection(selected, false);
        } finally { updatingSelector = false; }
    }

    /** 提交整个窗口的刷新率偏好，两个内核均走此路径，不钉住显示模式或分辨率。 */
    private void applySelection() {
        applyWindow(active ? RefreshRatePolicy.requested(preference, rates) : 0f);
        changed.run();
    }

    /** 零值撤回申请；相同请求不重复 setAttributes，避免显示事件反馈循环。 */
    private void applyWindow(float rate) {
        final WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        if (attributes.preferredDisplayModeId == 0 && Float.compare(attributes.preferredRefreshRate, rate) == 0) return;
        attributes.preferredDisplayModeId = 0;
        attributes.preferredRefreshRate = rate;
        activity.getWindow().setAttributes(attributes);
    }

    /** 异步保存纯测试设置，不写入浏览器共享数据或访问记录。 */
    private void savePreference() { preferences.edit().putFloat(PREFERENCE, preference).apply(); }

    /** 只格式化标签，不把取整值用于实际刷新率申请。 */
    private String format(float rate) {
        final NumberFormat number = NumberFormat.getNumberInstance(activity.getResources().getConfiguration().getLocales().get(0));
        number.setMaximumFractionDigits(2);
        number.setGroupingUsed(false);
        return number.format(rate) + " Hz";
    }

    /** 显示器变化时重新判断选项与申请，状态栏仍独立显示系统报告值。 */
    @Override public void onDisplayChanged(int id) {
        final Display current = anchor.getDisplay();
        if (active && (current == null || current.getDisplayId() == id)) refresh();
    }
    /** 新增显示器可能承载当前窗口，按当前 View 所在显示器重算。 */
    @Override public void onDisplayAdded(int id) { if (active) refresh(); }
    /** 当前显示器移除后撤回过期请求，后续附着时重新读取能力。 */
    @Override public void onDisplayRemoved(int id) { if (active) refresh(); }

    /** 将显示文案与真正保存的意图区分，最高不会被保存成固定数字。 */
    private static final class Choice {
        final float preference;
        private final String label;
        Choice(float preference, String label) { this.preference = preference; this.label = label; }
        @Override public String toString() { return label; }
    }
}
