package org.rocks.geckodemo

import kotlin.math.pow
import kotlin.math.roundToInt

/** 顶部效果的纯像素策略，便于在 JVM 上验证，不依赖网站脚本或 Android 图形对象。 */
internal object TopChromePolicy {
    const val WHITE = -1
    const val SAMPLE_INTERVAL_MS = 67L // 取整后严格低于每秒 15 次。

    data class Frame(val pixels: IntArray, val width: Int, val height: Int, val background: Int, val average: Int)

    /** 只认可占明显多数的近似颜色；文字、Logo 和照片不能把整个顶部染成其平均色。 */
    fun background(pixels: IntArray, fallback: Int): Int {
        if (pixels.isEmpty()) return fallback
        val buckets = pixels.groupBy { pixel ->
            ((pixel ushr 19 and 31) shl 10) or ((pixel ushr 11 and 31) shl 5) or (pixel ushr 3 and 31)
        }
        val candidate = average(buckets.maxBy { it.value.size }.value.toIntArray())
        val similar = pixels.filter { pixel ->
            (0..16 step 8).all { shift ->
                kotlin.math.abs((pixel ushr shift and 255) - (candidate ushr shift and 255)) <= 16
            }
        }
        return if (similar.size * 3 >= pixels.size * 2) average(similar.toIntArray()) else fallback
    }

    /** 完全回顶时没有纹理延展；脚本滚动也反映真实位置，但不影响底栏的滚动策略。 */
    fun strength(scrollY: Float): Float = if (scrollY.isFinite()) (scrollY / 24f).coerceIn(0f, 1f) else 0f

    /** 三次小半径盒式模糊接近高斯效果，旧 Android 同样可用，且始终只处理窄条小图。 */
    fun frame(pixels: IntArray, width: Int, height: Int, fallback: Int): Frame {
        require(width > 0 && height > 0 && pixels.size == width * height)
        // 最上方的少量像素用于识别页头底色，下面的横幅和正文不参与主色计算。
        val background = background(pixels.copyOfRange(0, width * minOf(2, height)), fallback)
        var blurred = pixels.copyOf()
        repeat(3) { blurred = blur(blur(blurred, width, height, horizontal = true), width, height, horizontal = false) }
        for (y in 0 until height) {
            // 顶部强遮蔽保证时间和电量清晰，下缘保留少量模糊纹理以柔和衔接网页。
            val texture = 0.06f + 0.24f * y / (height - 1).coerceAtLeast(1)
            for (x in 0 until width) {
                val index = y * width + x
                blurred[index] = blend(background, blurred[index], texture)
            }
        }
        return Frame(blurred, width, height, background, average(blurred))
    }

    /** 用最终绘制的背景决定图标深浅，保留小幅滞回以避免边缘纹理造成闪烁。 */
    fun darkIcons(background: Int, previous: Boolean): Boolean {
        val luminance = listOf(16, 8, 0).map { shift ->
            val channel = (background ushr shift and 255) / 255.0
            if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        }.let { it[0] * 0.2126 + it[1] * 0.7152 + it[2] * 0.0722 }
        return if (previous) luminance >= 0.17 else luminance > 0.21
    }

    /** 背景始终不透明，避免透明位图在状态栏中露出底层内容。 */
    fun blend(background: Int, foreground: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        var result = -0x1000000
        for (shift in 0..16 step 8) {
            val back = background ushr shift and 255
            val front = foreground ushr shift and 255
            result = result or ((back + (front - back) * amount).roundToInt() shl shift)
        }
        return result
    }

    private fun average(pixels: IntArray): Int {
        var red = 0L; var green = 0L; var blue = 0L
        for (pixel in pixels) {
            red += pixel ushr 16 and 255; green += pixel ushr 8 and 255; blue += pixel and 255
        }
        return -0x1000000 or ((red / pixels.size).toInt() shl 16) or
            ((green / pixels.size).toInt() shl 8) or (blue / pixels.size).toInt()
    }

    /** 滑动窗口避免为每个像素分配临时数组，并在边缘重复最近像素，防止黑边。 */
    private fun blur(pixels: IntArray, width: Int, height: Int, horizontal: Boolean): IntArray {
        val result = IntArray(pixels.size)
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        for (line in 0 until lines) {
            fun offset(position: Int): Int = if (horizontal) line * width + position.coerceIn(0, length - 1)
                else position.coerceIn(0, length - 1) * width + line
            var red = 0; var green = 0; var blue = 0
            for (position in -3..3) {
                val pixel = pixels[offset(position)]
                red += pixel ushr 16 and 255; green += pixel ushr 8 and 255; blue += pixel and 255
            }
            for (position in 0 until length) {
                result[offset(position)] = -0x1000000 or ((red / 7) shl 16) or ((green / 7) shl 8) or (blue / 7)
                val removed = pixels[offset(position - 3)]
                val added = pixels[offset(position + 4)]
                red += (added ushr 16 and 255) - (removed ushr 16 and 255)
                green += (added ushr 8 and 255) - (removed ushr 8 and 255)
                blue += (added and 255) - (removed and 255)
            }
        }
        return result
    }
}

/** 采样与后台处理共用一个在途名额；失效不会提前释放名额，避免旋转/切页后并发采集。 */
internal class TopChromeRequestGate {
    class Ticket(val page: BrowserPageScope, val generation: Long)
    private var generation = 0L
    private var running: Ticket? = null
    private var lastStarted = -TopChromePolicy.SAMPLE_INTERVAL_MS
    val busy: Boolean get() = running != null

    fun invalidate() { generation++ }
    fun delay(now: Long): Long = (TopChromePolicy.SAMPLE_INTERVAL_MS - (now - lastStarted)).coerceAtLeast(0L)

    /** 单调时钟限频，旧回调未结束时不能启动下一次采集。 */
    fun begin(page: BrowserPageScope, now: Long): Ticket? {
        if (busy || delay(now) > 0) return null
        return Ticket(page, generation).also { running = it; lastStarted = now }
    }

    fun accepts(ticket: Ticket, current: BrowserPageScope?): Boolean =
        running === ticket && ticket.generation == generation && ticket.page == current

    /** 即使结果已失效也要释放原请求，但旧请求不能释放后来请求的名额。 */
    fun finish(ticket: Ticket) { if (running === ticket) running = null }
}
