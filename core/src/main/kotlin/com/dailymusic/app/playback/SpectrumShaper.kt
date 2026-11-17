package com.dailymusic.app.playback

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

/**
 * 把 [SpectrumTap.bins] 那 56 个原始值（0..255）套成 0..1 的「柱高」。
 *
 * 逐行对齐 vinyl-player 的 `src/ui/spectrum.js` → `draw()` 里那段处理，
 * 两端视觉才能一致。顺序不能换：
 *
 * 1. **幅值压缩** `pow(m/255, 0.72)` + 轻微高频抬升 `1 + 0.3*(i/56)`
 *    —— 幂次把 dB 映射后偏低的段抬起来；抬升只给 0~30%，给多了会「整排顶满」。
 * 2. **横向 3 点平滑** `0.25 / 0.5 / 0.25` —— 相邻柱一高一低会像梳子。
 * 3. **自适应增益**：让本帧最高的一根稳定落在 ~0.85，而不是各自去撞天花板。
 *    增益**降得快（0.22）升得慢（0.07）**，跟压缩器一样，避免安静段落被过度放大。
 * 4. **tanh 软饱和** `tanh(x*gain) * 0.96` —— 越接近满格压得越狠，柱顶不会齐刷刷一条线。
 * 5. **时间平滑** 上升 0.55 / 下降 0.14 —— 起得快、落得慢，才像电平表。
 *
 * 这个类**不持锁、不做快照**：只在渲染帧里单线程调用，[out] 直接读。
 */
class SpectrumShaper(val count: Int = SpectrumTap.BARS) {

    /** 处理后的柱高，0..1。复用同一个数组，每帧原地更新。 */
    val out = FloatArray(count)

    private val raw = FloatArray(count)
    private var gain = 1f

    fun update(src: IntArray) {
        val n = count
        if (n <= 0) return
        val bars = SpectrumTap.BARS

        // 1. 幅值压缩 + 高频轻微抬升
        for (i in 0 until n) {
            val idx = if (i < src.size) i else src.size - 1
            val m = if (src.isNotEmpty()) src[idx] else 0
            val tilt = 1f + 0.3f * (idx.toFloat() / bars)
            raw[i] = (m / 255f).pow(0.72f) * tilt
        }

        // 2. 横向 3 点平滑（注意 raw[i + 1] 读的是**未修改**的原值）
        var prev = raw[0]
        for (i in 1 until n - 1) {
            val cur = raw[i]
            raw[i] = prev * 0.25f + cur * 0.5f + raw[i + 1] * 0.25f
            prev = cur
        }

        // 3. 自适应增益
        var peak = 0f
        for (i in 0 until n) if (raw[i] > peak) peak = raw[i]
        val want = if (peak > 0.02f) min(4f, 1.35f / peak) else 1f
        val k = if (want < gain) 0.22f else 0.07f
        gain += (want - gain) * k
        if (!gain.isFinite() || gain <= 0f) gain = 1f

        // 4 + 5. 软饱和 + 时间平滑
        for (i in 0 until n) {
            val target = min(1f, tanh(raw[i] * gain) * 0.96f)
            val v = out[i]
            val next = v + (target - v) * (if (target > v) 0.55f else 0.14f)
            out[i] = if (next < 0.003f) 0f else next
        }
    }

    /** 暂停 / 切歌时清零，避免下一首开头还挂着上一首的残余高度。 */
    fun reset() {
        out.fill(0f)
        raw.fill(0f)
        gain = 1f
    }

    /** 某一段 bin 的平均柱高（[from, to)）。用来把 56 维压成「低频/中频/高频」三个数。 */
    fun band(from: Int, to: Int): Float {
        val a = from.coerceIn(0, count)
        val b = to.coerceIn(a, count)
        if (b <= a) return 0f
        var s = 0f
        for (i in a until b) s += out[i]
        return s / (b - a)
    }
}
