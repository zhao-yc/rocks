package org.rocks.kernelcompare;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 两份 APK 使用同一原生界面和同一离线页面，只替换内核适配器。 */
public final class MainActivity extends Activity implements DisplayManager.DisplayListener {
    private PageEngine engine;
    private EditText address;
    private TextView info;
    private LinearLayout root;
    private String testUrl;
    private DisplayManager displays;

    /** 原生线性布局不带重叠浮层、滚动动画、纹理读取或帧率请求。 */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                final android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            }
            final android.view.DisplayCutout cutout = insets.getDisplayCutout();
            root.setPadding(Math.max(insets.getSystemWindowInsetLeft(), cutout == null ? 0 : cutout.getSafeInsetLeft()),
                    Math.max(insets.getSystemWindowInsetTop(), cutout == null ? 0 : cutout.getSafeInsetTop()),
                    Math.max(insets.getSystemWindowInsetRight(), cutout == null ? 0 : cutout.getSafeInsetRight()),
                    insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        info = new TextView(this);
        info.setTextSize(11);
        info.setTextColor(Color.DKGRAY);
        info.setMaxLines(2);
        info.setPadding(dp(8), 0, dp(8), 0);
        root.addView(info, new LinearLayout.LayoutParams(-1, dp(42)));
        final LinearLayout entry = new LinearLayout(this);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint(R.string.address_hint);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        entry.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1));
        entry.addView(button(R.string.open, this::openAddress));
        root.addView(entry);
        final LinearLayout actions = new LinearLayout(this);
        actions.addView(button(R.string.test_page, () -> showTest()));
        actions.addView(button(R.string.back, () -> engine.back()));
        actions.addView(button(R.string.reload, () -> engine.reload()));
        root.addView(actions);
        try { testUrl = testDataUrl(); }
        catch (IOException error) { throw new IllegalStateException("内置测试页缺失", error); }
        engine = EngineFactory.create(this, testUrl, new PageEngine.Events() {
            @Override public void location(String url) {
                if (!address.hasFocus()) address.setText(url.equals(testUrl) ? "" : url);
            }
            @Override public void failed() { Toast.makeText(MainActivity.this, R.string.load_error, Toast.LENGTH_SHORT).show(); }
        });
        engine.view().setContentDescription("kernel-page");
        root.addView(engine.view(), new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
        address.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            openAddress();
            return true;
        });
        displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        // 两种内核都等原生视口完成布局再加载，避免零尺寸或 Surface 初始位置干扰对照。
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (engine.view().getWidth() <= 0 || engine.view().getHeight() <= 0) return;
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                root.post(() -> { if (!isDestroyed()) showTest(); });
            }
        });
        root.post(this::updateInfo);
    }

    /** 生成相同 data URL：页面与计数逻辑均来自共同 assets，无服务器或原生桥。 */
    private String testDataUrl() throws IOException {
        final String html = asset("test-page.html").replace("/* FRAME_METER_SOURCE */", asset("frame-meter.js"));
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private String asset(String name) throws IOException {
        try (InputStream stream = getAssets().open(name)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Button button(int label, Runnable action) {
        final Button result = new Button(this);
        result.setText(label);
        result.setOnClickListener(view -> action.run());
        return result;
    }

    /** 输入地址只走 HTTP(S)，不会启动其他应用或解释 javascript/intent 协议。 */
    private void openAddress() {
        final String url = NavigationPolicy.address(address.getText().toString());
        if (url == null) { Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show(); return; }
        address.clearFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
        engine.view().requestFocus();
        engine.load(url);
    }

    private void showTest() {
        address.clearFocus();
        address.setText("");
        root.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
        engine.load(testUrl);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** 系统刷新率只随显示事件更新，不创建额外的逐帧回调影响网页对比。 */
    private void updateInfo() {
        final Display display = root.getDisplay();
        info.setText(getString(R.string.engine_info, engine.description(), display == null ? 0f : display.getRefreshRate()));
    }

    @Override protected void onResume() {
        super.onResume();
        if (engine != null) {
            engine.foreground(true);
            displays.registerDisplayListener(this, null);
            updateInfo();
        }
    }
    @Override protected void onPause() {
        if (engine != null) { displays.unregisterDisplayListener(this); engine.foreground(false); }
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (engine != null) {
            root.removeView(engine.view());
            engine.close();
        }
        super.onDestroy();
    }
    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}
    @Override public void onDisplayChanged(int id) { updateInfo(); }
}
