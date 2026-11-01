package org.rocks.kernelcompare;

import android.view.View;

/** 两个内核共用最小宿主契约；不接入正式浏览器的状态、扩展或工具栏。 */
interface PageEngine {
    interface Events {
        void location(String url);
        void failed();
    }
    View view();
    String description();
    void load(String url);
    void reload();
    void back();
    void foreground(boolean active);
    void close();
}
