package org.rocks.geckodemo

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class BottomBarRect(val left: Double, val top: Double, val right: Double, val bottom: Double, val color: Int)
internal data class BottomBarReport(val documentId: String, val left: Double, val top: Double,
    val width: Double, val height: Double, val bars: List<BottomBarRect>) {
    /** 原生端按真实 Gecko 坐标映射得到可用底边，不把 layout viewport 底边当成可见底边。 */
    fun colorAt(visibleBottom: Double, fixedOffset: Double = 0.0): Int? {
        if (!visibleBottom.isFinite() || !fixedOffset.isFinite() || fixedOffset < 0 ||
            visibleBottom <= top || visibleBottom > top + height - fixedOffset + 4) return null
        return bars.firstOrNull { bar ->
            min(bar.right, left + width) - max(bar.left, left) >= width * 0.8 &&
                bar.top - fixedOffset < visibleBottom && bar.bottom > bar.top && abs(bar.bottom - fixedOffset - visibleBottom) <= 4
        }?.color
    }

    companion object {
        /** 通道只接受有界几何和 RGB 数据；类型错误、非有限数及超大消息全部丢弃。 */
        fun parse(message: Any): BottomBarReport? = runCatching {
            val json = message as? JSONObject ?: return null
            if (json.toString().length > 32768) return null
            val id = json.get("documentId") as? String ?: return null
            if (!id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) return null
            fun number(obj: JSONObject, key: String): Double {
                val value = (obj.get(key) as? Number)?.toDouble() ?: error("坐标类型错误")
                require(value.isFinite() && abs(value) <= 1_000_000)
                return value
            }
            val viewport = json.getJSONObject("viewport")
            val width = number(viewport, "width"); val height = number(viewport, "height")
            require(width > 0 && height > 0)
            val array = json.getJSONArray("bars")
            require(array.length() <= 32)
            val bars = (0 until array.length()).map { index ->
                val bar = array.getJSONObject(index)
                val rgb = bar.getJSONArray("color")
                require(rgb.length() == 3)
                val channels = (0..2).map {
                    val value = (rgb.get(it) as? Number)?.toDouble() ?: error("颜色类型错误")
                    require(value.isFinite() && value in 0.0..255.0 && value == value.toInt().toDouble())
                    value.toInt()
                }
                BottomBarRect(number(bar, "left"), number(bar, "top"), number(bar, "right"), number(bar, "bottom"),
                    (0xFF shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2])
            }
            BottomBarReport(id, number(viewport, "left"), number(viewport, "top"), width, height, bars)
        }.getOrNull()
    }
}

/** 消息必须绑定当前会话、文档和顶层来源；独立策略便于覆盖延迟消息回归。 */
internal class BottomBarDocumentGate {
    private var scope: BrowserPageScope? = null
    private var documentId: String? = null
    fun reset(scope: BrowserPageScope?) { this.scope = scope; documentId = null }
    fun accepts(expected: BrowserPageScope?, source: BrowserPageScope, id: String): Boolean {
        if (expected == null || scope != expected || source != expected) return false
        if (documentId == null) documentId = id
        return documentId == id
    }
}
