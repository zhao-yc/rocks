package org.rocks.geckodemo

/** Uses root content movement, not finger movement: links, zoom and scripts cannot hide controls. */
internal class ToolbarScrollState {
    var collapsed = false
        private set
    private var lastY: Float? = null
    private var lastZoom: Float? = null
    private var travel = 0f

    fun expand() {
        collapsed = false
        travel = 0f
    }

    fun reset() { expand(); lastY = null; lastZoom = null }

    fun onScroll(y: Float, zoom: Float, userInteraction: Boolean, locked: Boolean): Boolean {
        if (!y.isFinite() || !zoom.isFinite() || zoom <= 0f) return collapsed
        val position = y.coerceAtLeast(0f)
        val previous = lastY
        val zoomChanged = lastZoom != null && kotlin.math.abs(zoom - lastZoom!!) > 0.0001f
        lastY = position
        lastZoom = zoom
        if (locked || !userInteraction || previous == null || zoomChanged) {
            travel = 0f
            return collapsed
        }
        if (position <= 4f) { expand(); return collapsed }
        val delta = position - previous
        if (delta == 0f) return collapsed
        if (travel * delta < 0f) travel = 0f
        travel += delta
        // CSS pixels make thresholds independent of device density. A small reverse scroll
        // restores controls promptly, while downward jitter does not collapse them.
        if (!collapsed && travel >= 56f) { collapsed = true; travel = 0f }
        else if (collapsed && travel <= -16f) expand()
        return collapsed
    }
}
