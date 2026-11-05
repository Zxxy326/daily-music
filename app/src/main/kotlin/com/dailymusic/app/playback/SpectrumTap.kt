package com.dailymusic.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 实时频谱。
 *
 * 为什么不走 `android.media.audiofx.Visualizer`：那东西在 Android 9+ 抓输出混音要
 * RECORD_AUDIO 运行时授权，部分 OEM 直接返回空数据，一个播放器为了画个波纹去弹录音
 * 权限，用户第一反应是关掉。
 *
 * 这里直接在 ExoPlayer 的音频链路里插一个 [AudioProcessor]：数据本来就以 PCM 流过，
 * 顺手做一次 FFT 就行，零权限、零额外解码。
 *
 * 对外提供两套读数：
 *
 * 1. **[bins]** —— 56 个对数分频、值域 0..255 的原始幅值。与 vinyl-player 的
 *    `buildBins()`（JS 侧）用**同一套公式**，所以两端套同一条曲线后形态一致：
 *    分段对数（38Hz..min(16.5k, nyq*0.92)）、每段取峰值 bin、dB 固定映射 -100..-30。
 * 2. **bass / mid / high** —— 三档标量，给需要「一个数」的地方用（波纹推进速度等）。
 *    它走的是自适应动态范围，不同响度的歌都能用满 0..1。
 *
 * 注意：**必须关掉音频直通（offload）**，否则 MP3/AAC 会绕过整条 AudioProcessor 链，
 * 表现是「有声音，但这里一次都收不到数据」。见 PlaybackService 里的
 * `setAudioOffloadSupportProvider`。
 */
object SpectrumTap {

    /** 分频段数。改这个值 JS 侧也要一起改，否则两端曲线对不上。 */
    const val BARS = 56

    /** 56 个对数分频 bin，值域 0..255。每帧替换引用，读线程不会读到写了一半的数组。 */
    @Volatile
    var bins: IntArray = IntArray(BARS)
        private set

    @Volatile
    var bass: Float = 0f
        private set

    @Volatile
    var mid: Float = 0f
        private set

    @Volatile
    var high: Float = 0f
        private set

    /**
     * 2048 而不是 1024：低频分辨率从 43Hz 提到 21.5Hz，38Hz 起分的那几段才有意义。
     * 代价是每次分析要多等一个窗口（约 46ms@44.1k），视觉上察觉不到。
     */
    private const val FFT_SIZE = 2048

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat()
    }
    private val mono = FloatArray(FFT_SIZE)

    /** 每档自己的「最近动态范围」（上限 / 下限），见 [mapBand]。 */
    private val hiDb = floatArrayOf(-28f, -28f, -28f)
    private val loDb = floatArrayOf(-60f, -60f, -60f)

    /** 双缓冲：音频线程写一个，发布另一个，UI 线程随便读。 */
    private val binA = IntArray(BARS)
    private val binB = IntArray(BARS)
    private var binFlip = false

    /** 对数分频的 [start, end) 频点，sampleRate 变了才重建。 */
    private val segStart = IntArray(BARS)
    private val segEnd = IntArray(BARS)
    private var segRate = 0

    private var fill = 0
    private var sampleRate = 44_100
    private var channels = 2

    /** 暂停 / 切歌时清干净，免得波纹停在最后一帧不动。 */
    fun reset() {
        fill = 0
        bass = 0f
        mid = 0f
        high = 0f
        hiDb.fill(-28f)
        loDb.fill(-60f)
        binA.fill(0)
        binB.fill(0)
        bins = binA
    }

    /**
     * 从音频链路里喂一段 PCM。**只读**，不动 buffer 的 position。
     *
     * 调用方在播放线程上，所以这里不能分配对象、不能做重活：
     * 2048 点 FFT 大约十几万次浮点运算，够快。
     */
    fun feed(buffer: ByteBuffer, format: AudioProcessor.AudioFormat) {
        val ch = format.channelCount.coerceAtLeast(1)
        if (format.sampleRate > 0) sampleRate = format.sampleRate
        channels = ch

        val dup = buffer.duplicate().order(ByteOrder.nativeOrder())
        when (format.encoding) {
            // 24bit / 32bit 也要认：Hi-Res 曲目在某些机器上会走到这两档，
            // 漏掉的话表现就是「有声音但频谱全是 0」，波纹自然不动。
            C.ENCODING_PCM_24BIT -> {
                val frames = dup.remaining() / (3 * ch)
                for (f in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until ch) {
                        val i = (f * ch + c) * 3
                        val v = (dup.get(i).toInt() and 0xFF) or
                            ((dup.get(i + 1).toInt() and 0xFF) shl 8) or
                            (dup.get(i + 2).toInt() shl 16)
                        acc += v / 8_388_608f
                    }
                    push(acc / ch)
                }
            }
            C.ENCODING_PCM_32BIT -> {
                val ints = dup.asIntBuffer()
                val frames = ints.remaining() / ch
                for (f in 0 until frames) {
                    var acc = 0f
                    val base = f * ch
                    for (c in 0 until ch) acc += ints.get(base + c) / 2_147_483_648f
                    push(acc / ch)
                }
            }
            C.ENCODING_PCM_8BIT -> {
                val frames = dup.remaining() / ch
                for (f in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until ch) acc += ((dup.get(f * ch + c).toInt() and 0xFF) - 128) / 128f
                    push(acc / ch)
                }
            }
            C.ENCODING_PCM_16BIT -> {
                val shorts = dup.asShortBuffer()
                val frames = shorts.remaining() / ch
                for (f in 0 until frames) {
                    var acc = 0
                    val base = f * ch
                    for (c in 0 until ch) acc += shorts.get(base + c).toInt()
                    push(acc / (ch * 32_768f))
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floats = dup.asFloatBuffer()
                val frames = floats.remaining() / ch
                for (f in 0 until frames) {
                    var acc = 0f
                    val base = f * ch
                    for (c in 0 until ch) acc += floats.get(base + c)
                    push(acc / ch)
                }
            }
        }
    }

    private fun push(sample: Float) {
        mono[fill++] = sample
        if (fill == FFT_SIZE) {
            fill = 0
            analyse()
        }
    }

    private fun analyse() {
        for (i in 0 until FFT_SIZE) {
            re[i] = mono[i] * window[i]
            im[i] = 0f
        }
        fft(re, im)

        val binHz = sampleRate.toFloat() / FFT_SIZE
        bass = smooth(bass, mapBand(0, bandDb(20f, 250f, binHz)))
        mid = smooth(mid, mapBand(1, bandDb(250f, 2_000f, binHz)))
        high = smooth(high, mapBand(2, bandDb(2_000f, 8_000f, binHz)))

        publishBins()
    }

    /**
     * 56 个对数分频 bin，与 vinyl-player 的 JS `buildBins()` 逐行对应。
     *
     * 为什么固定 dB 区间（-100..-30）而不是像 [mapBand] 那样自适应：
     * 这条数据是给「柱状图/波纹」这类连续视觉用的，跨设备、跨曲目要能直接比大小；
     * 自适应一旦介入，安静的歌也会被拉满，就分不出「真的响」和「被放大的安静」。
     * 需要相对强度的地方，交给 [SpectrumShaper] 里那道自适应增益。
     */
    private fun publishBins() {
        val half = FFT_SIZE / 2
        val nyq = sampleRate / 2.0
        if (segRate != sampleRate) {
            rebuildSegments(nyq, half)
            segRate = sampleRate
        }

        // 写另一个缓冲，写完再换引用 —— 读线程不会看到写了一半的数组
        val target = if (binFlip) binA else binB
        binFlip = !binFlip

        for (b in 0 until BARS) {
            var peak = 0f
            for (k in segStart[b] until segEnd[b]) {
                val mag = hypot(re[k], im[k]) * 2f / FFT_SIZE
                if (mag > peak) peak = mag
            }
            val db = 20.0 * log10(peak + 1e-9)
            target[b] = (((db + 100.0) / 70.0) * 255.0).toInt().coerceIn(0, 255)
        }
        bins = target
    }

    /** 对数分频：低频不会被挤成一根柱子。公式与 JS 侧完全一致，改一处就要改两处。 */
    private fun rebuildSegments(nyq: Double, half: Int) {
        val fMin = 38.0
        val fMax = min(16_500.0, nyq * 0.92)
        for (b in 0 until BARS) {
            val f0 = fMin * (fMax / fMin).pow(b.toDouble() / BARS)
            val f1 = fMin * (fMax / fMin).pow((b + 1).toDouble() / BARS)
            val s = (f0 / nyq * half).toInt().coerceIn(0, half - 1)
            val e = ceil(f1 / nyq * half).toInt().coerceIn(s + 1, half)
            segStart[b] = s
            segEnd[b] = e
        }
    }

    /**
     * 频段能量，取**峰值 bin** 而不是平均。
     *
     * 平均会让宽频段（高频 140 个 bin）天然吃亏、窄频段（低频 5 个 bin）天然占便宜，
     * 同一首歌里三档永远对不上；取峰值只跟「这一段里最响的成分」有关。
     */
    private fun bandDb(lo: Float, hi: Float, binHz: Float): Float {
        val from = (lo / binHz).toInt().coerceAtLeast(1)
        val to = (hi / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)
        if (to < from) return -100f
        var peak = 0f
        for (i in from..to) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            if (mag > peak) peak = mag
        }
        return 20f * log10(peak + 1e-7f)
    }

    /**
     * dB → 0..1，用**每档自己的动态范围**做归一化。
     *
     * 踩过的两个坑：
     * 1. 写死分贝区间不行 —— 不同歌的响度差十几 dB，要么全程贴 1 要么全程贴 0；
     * 2. 只用「最近峰值 + 固定窗口」也不行 —— 流行歌的峰值 bin 波动往往只有 ±3dB，
     *    映射到 24dB 的窗口里就是 0.87~1.0，看起来像「一直满格、完全不动」。
     *
     * 所以这里同时跟踪峰值和谷值（各带很慢的回落 / 上抬），用「这一档自己的
     * 实际起伏范围」当分母：哪怕整首歌只波动 4dB，这 4dB 也会被拉满 0..1。
     */
    private fun mapBand(index: Int, db: Float): Float {
        hiDb[index] = maxOf(db, hiDb[index] - 0.08f)
        loDb[index] = minOf(db, loDb[index] + 0.05f)
        val span = (hiDb[index] - loDb[index]).coerceAtLeast(8f)
        return ((db - loDb[index]) / span).coerceIn(0f, 1f)
    }

    /** 起得快、落得慢，波纹才跟得上鼓点又不会一闪一闪。 */
    private fun smooth(prev: Float, next: Float): Float =
        prev + (next - prev) * (if (next > prev) 0.55f else 0.14f)

    /** 迭代版 Cooley-Tukey，原地计算。 */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            val half = len shr 1
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until half) {
                    val ur = real[i + k]
                    val ui = imag[i + k]
                    val vr = real[i + k + half] * curR - imag[i + k + half] * curI
                    val vi = real[i + k + half] * curI + imag[i + k + half] * curR
                    real[i + k] = ur + vr
                    imag[i + k] = ui + vi
                    real[i + k + half] = ur - vr
                    imag[i + k + half] = ui - vi
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * 直通式 AudioProcessor：把 PCM 抄一份给 [SpectrumTap] 分析，然后原样输出。
 *
 * 继承 [BaseAudioProcessor] 而不是自己实现接口，是因为 configure / flush / reset
 * 这些生命周期它已经处理好了，这里只需要补 `queueInput`。
 */
class SpectrumProcessor : BaseAudioProcessor() {

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return

        runCatching { SpectrumTap.feed(inputBuffer, inputAudioFormat) }

        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }
}
