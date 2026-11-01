package org.rocks.kernelcompare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只从当前物理分辨率的有效刷新率生成选项，独立于 Android 便于验证。 */
final class RefreshRatePolicy {
    static final float FOLLOW_SYSTEM = 0f;
    static final float HIGHEST = -1f;
    private static final float EPSILON = 0.01f;

    /** 保存显示模式的最小信息；不申请或切换屏幕分辨率。 */
    static final class Mode {
        final int width;
        final int height;
        final float rate;
        Mode(int width, int height, float rate) { this.width = width; this.height = height; this.rate = rate; }
    }

    private RefreshRatePolicy() {}

    /** 合并同分辨率模式和无缝备选刷新率，过滤无效值并保留 59.94 等真实档位。 */
    static List<Float> supported(int width, int height, List<Mode> modes) {
        if (width <= 0 || height <= 0) return Collections.emptyList();
        final List<Float> sorted = new ArrayList<>();
        for (Mode mode : modes) {
            if (mode != null && mode.width == width && mode.height == height
                    && Float.isFinite(mode.rate) && mode.rate > 0f) sorted.add(mode.rate);
        }
        Collections.sort(sorted);
        final List<Float> result = new ArrayList<>();
        for (float rate : sorted) {
            if (result.isEmpty() || Math.abs(rate - result.get(result.size() - 1)) > EPSILON) result.add(rate);
        }
        return Collections.unmodifiableList(result);
    }

    /** 显示器变化或旧设置不可用时回到跟随系统，不提交已失效或任意的刷新率。 */
    static float normalize(float preference, List<Float> rates) {
        if (preference == FOLLOW_SYSTEM) return FOLLOW_SYSTEM;
        if (preference == HIGHEST) return rates.isEmpty() ? FOLLOW_SYSTEM : HIGHEST;
        if (Float.isFinite(preference) && preference > 0f) {
            for (float rate : rates) if (Math.abs(rate - preference) <= EPSILON) return rate;
        }
        return FOLLOW_SYSTEM;
    }

    /** 最高是动态意图；跟随系统返回零，表示撤回应用窗口的刷新率偏好。 */
    static float requested(float preference, List<Float> rates) {
        final float valid = normalize(preference, rates);
        return valid == HIGHEST ? rates.get(rates.size() - 1) : valid;
    }
}
