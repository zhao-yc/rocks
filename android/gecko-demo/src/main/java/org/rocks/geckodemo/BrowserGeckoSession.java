package org.rocks.geckodemo;

import org.mozilla.geckoview.GeckoDisplay;
import org.mozilla.geckoview.GeckoSession;

/** 保留 GeckoView 取得的唯一显示连接，不另行取得显示所有权。 */
final class BrowserGeckoSession extends GeckoSession {
    private GeckoDisplay attachedDisplay;

    /** 仅向宿主提供当前仍有效的连接，允许尚未附着或已经解绑。 */
    public GeckoDisplay getAttachedDisplay() {
        return attachedDisplay;
    }

    /** 复用 GeckoView 发起的连接。 */
    @Override
    public GeckoDisplay acquireDisplay() {
        final GeckoDisplay display = super.acquireDisplay();
        attachedDisplay = display;
        return display;
    }

    /**
     * GeckoView 在未附着或先 detach 后 releaseSession 时也会传 null。
     * 上游虽标为 NonNull，实际实现允许两侧均为空；用 Java 保持该运行时契约，
     * 避免 Kotlin 自动插入非空检查。先让上游校验所有权，成功后再清除引用。
     */
    @Override
    public void releaseDisplay(final GeckoDisplay display) {
        super.releaseDisplay(display);
        if (attachedDisplay == display) attachedDisplay = null;
    }
}
