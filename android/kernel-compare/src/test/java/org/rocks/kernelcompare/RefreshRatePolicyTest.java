package org.rocks.kernelcompare;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** 验证档位来源、分辨率隔离、保存值失效和真实小数刷新率，不依赖模拟器高刷能力。 */
public final class RefreshRatePolicyTest {
    private static RefreshRatePolicy.Mode mode(int width, int height, float hz) {
        return new RefreshRatePolicy.Mode(width, height, hz);
    }

    @Test public void includesOnlyCurrentResolutionAndSortsDeduplicatedRates() {
        final List<Float> rates = RefreshRatePolicy.supported(1080, 2400, Arrays.asList(
                mode(1080, 2400, 120), mode(1080, 2400, 60), mode(1080, 2400, 90),
                mode(1080, 2400, 60.000004f), mode(1440, 3200, 144)));
        assertEquals(Arrays.asList(60f, 90f, 120f), rates);
        assertEquals(120f, RefreshRatePolicy.requested(RefreshRatePolicy.HIGHEST, rates), 0f);
    }

    @Test public void preservesRealFractionalRefreshRates() {
        final List<Float> rates = RefreshRatePolicy.supported(1080, 2400, Arrays.asList(
                mode(1080, 2400, 59.94f), mode(1080, 2400, 60), mode(1080, 2400, 119.88f)));
        assertEquals(Arrays.asList(59.94f, 60f, 119.88f), rates);
        assertEquals(59.94f, RefreshRatePolicy.requested(59.94001f, rates), 0f);
    }

    @Test public void rejectsInvalidModesAndDoesNotInventSixtyHertz() {
        final List<Float> rates = RefreshRatePolicy.supported(1080, 2400, Arrays.asList(
                mode(1080, 2400, 90), mode(1080, 2400, Float.NaN), mode(1080, 2400, 0),
                mode(1080, 2400, Float.POSITIVE_INFINITY), mode(1080, 2400, -120)));
        assertEquals(Collections.singletonList(90f), rates);
        assertEquals(0f, RefreshRatePolicy.normalize(60, rates), 0f);
    }

    @Test public void followSystemClearsTheRequestAndHighestTracksAvailableRates() {
        assertEquals(0f, RefreshRatePolicy.requested(0, Arrays.asList(60f, 120f)), 0f);
        assertEquals(60f, RefreshRatePolicy.requested(-1, Collections.singletonList(60f)), 0f);
        assertEquals(144f, RefreshRatePolicy.requested(-1, Arrays.asList(60f, 120f, 144f)), 0f);
    }

    @Test public void staleAndCorruptedPreferencesFallBackWithoutForcingAnUnsupportedMode() {
        final List<Float> rates = Collections.singletonList(60f);
        for (float preference : new float[]{120, -2, Float.NaN, Float.NEGATIVE_INFINITY}) {
            assertEquals(0f, RefreshRatePolicy.normalize(preference, rates), 0f);
        }
        assertEquals(0f, RefreshRatePolicy.requested(-1, Collections.emptyList()), 0f);
        assertTrue(RefreshRatePolicy.supported(0, 0, Collections.singletonList(mode(0, 0, 60))).isEmpty());
    }
}
