package org.rocks.kernelcompare;

import android.content.Context;
import android.view.View;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Firefox 版仅创建一个 Gecko 会话，保留默认渲染后端，不注入刷新率偏好。 */
final class EngineFactory {
    private static GeckoRuntime runtime;
    private EngineFactory() {}

    static PageEngine create(Context context, String testUrl, PageEngine.Events events) {
        // 同一进程复用运行时，旋转和重建 Activity 不创建第二个 GeckoRuntime。
        if (runtime == null) runtime = GeckoRuntime.create(context.getApplicationContext(),
                new GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(false).consoleOutput(false)
                        .loginAutofillEnabled(false).build());
        final GeckoSession session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(
                    GeckoSession current, LoadRequest request) {
                return NavigationPolicy.allowed(request.uri, testUrl) ? GeckoResult.allow() : GeckoResult.deny();
            }
            @Override public void onLocationChange(GeckoSession current, String url,
                    java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                if (url != null) events.location(url);
            }
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession current, ContentPermission permission) {
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStop(GeckoSession current, boolean success) { if (!success) events.failed(); }
        });
        session.open(runtime);
        final GeckoView view = new GeckoView(context);
        view.setSession(session);
        return new PageEngine() {
            @Override public View view() { return view; }
            @Override public String description() { return "Firefox · GeckoView 158.0.20261021001945"; }
            @Override public void load(String url) { session.loadUri(url); }
            @Override public void reload() { session.reload(); }
            @Override public void back() { session.goBack(); }
            @Override public void foreground(boolean active) { session.setActive(active); session.setFocused(active); }
            @Override public void close() { view.releaseSession(); session.close(); }
        };
    }
}
