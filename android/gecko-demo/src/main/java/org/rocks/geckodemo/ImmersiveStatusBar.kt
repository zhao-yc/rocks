package org.rocks.geckodemo

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Match system icon contrast to the rendered page without a page script or a stored screenshot. */
internal class ImmersiveStatusBar(private val window: Window) : DefaultLifecycleObserver {
    private val view = window.decorView
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var inFlight = false
    private var epoch = 0L
    private var darkIcons = true
    private val sample = object : Runnable {
        override fun run() {
            if (!active) return
            updateContrast()
            view.postDelayed(this, 600L)
        }
    }
    override fun onResume(owner: LifecycleOwner) {
        active = true; epoch++
        view.removeCallbacks(sample); view.post(sample)
    }
    override fun onPause(owner: LifecycleOwner) { stop() }
    override fun onDestroy(owner: LifecycleOwner) { stop() }
    private fun stop() { active = false; epoch++; view.removeCallbacks(sample) }

    private fun updateContrast() {
        if (inFlight || !view.hasWindowFocus() || view.width == 0 || view.height == 0) return
        val top = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: return
        if (top <= 0) return // Website fullscreen hides the status bar.
        val bitmap = Bitmap.createBitmap(24, 4, Bitmap.Config.ARGB_8888)
        val requestEpoch = epoch
        inFlight = true
        runCatching {
            // Only a 24x4 downsample of the app's status-bar region is read. System icons are
            // separate surfaces. The bitmap is recycled here, never saved, logged or transmitted.
            PixelCopy.request(window, Rect(0, 0, view.width, top.coerceAtMost(view.height)), bitmap, { result ->
                try {
                    if (result == PixelCopy.SUCCESS && active && epoch == requestEpoch) {
                        var luminance = 0.0
                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                            luminance += ColorUtils.calculateLuminance(bitmap.getPixel(x, y))
                        luminance /= bitmap.width * bitmap.height
                        // Hysteresis avoids icon flicker while text or pictures pass underneath.
                        darkIcons = if (darkIcons) luminance >= 0.18 else luminance > 0.28
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
                    }
                } finally { bitmap.recycle(); inFlight = false }
            }, handler)
        }.onFailure { bitmap.recycle(); inFlight = false }
    }
}
