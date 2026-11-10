package app.playdrive.music

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 수직 레이어링 믹서(순수 Kotlin). 오디오 스레드에서 render()를 부르고, 다른 스레드는 submit()만 한다.
 *
 * - 모든 레이어는 같은 격자 위치(readPos)에서 동시에 재생되고, 켜고 끄는 것은 볼륨 페이드뿐 → 박자가 어긋날 수 없다
 * - on/off는 레이어별 퀀타이즈 경계(다음 마디/박)에서 시작해 fadeBars 동안 페이드
 * - PlayCue: 효과음 원샷이 있으면 즉시 재생 + 음악 덕킹(다음 마디에서 복귀), 없으면 DSP 효과로 대체
 * - 출력: 스테레오 인터리브 Float, 소프트 리미터로 |x| ≤ 1
 */
class LoopMixer(
    private val timing: MusicTiming,
    layerPcm: Map<Layer, StereoPcm>,
    cuePcm: Map<Cue, StereoPcm> = emptyMap(),
    private val config: Config = Config(),
    /** 반응형 연주 레이어(없으면 연주 명령 무시). */
    val performance: PerformanceLayer? = null,
) {
    data class Config(
        /** 레이어 페이드 길이(마디). 요구: 0.5~1마디. */
        val fadeInBars: Float = 0.5f,
        val fadeOutBars: Float = 0.5f,
        /** 효과음 최대 재생 길이와 끝 페이드. */
        val cueMaxMs: Long = 2_500,
        val cueFadeOutMs: Long = 400,
        val cueLevel: Float = 0.9f,
        /** 효과음 동안 음악 덕킹 정도와 진입 시간. */
        val duckGain: Float = 0.3f,
        val duckAttackMs: Long = 80,
        /** 세트별 레이어 음량(없으면 Layer.level). */
        val levels: Map<Layer, Float> = emptyMap(),
        /** 비 효과: 로우패스 차단 주파수와 리버브 젖은 비율. */
        val rainCutoffHz: Double = 1_800.0,
        val rainReverbWet: Float = 0.18f,
        /** 속도 톤: 0km/h 차단 주파수, 밝아지는 속도, 정차 시 리버브. 스무딩 시간(초). */
        val speedMinCutoffHz: Double = 700.0,
        val speedBrightKmh: Float = 60f,
        val speedReverbWetAtStop: Float = 0.16f,
        val speedSmoothingS: Float = 0.5f,
    )

    private val sr = timing.sampleRate
    private val loop = timing.loopFrames
    private val n = Layer.entries.size
    private val pcm: Array<StereoPcm?> = Array(n) { i ->
        layerPcm[Layer.entries[i]]?.also { require(it.frames == loop) { "${Layer.entries[i]} frames=${it.frames} != $loop" } }
    }
    private val cues: Map<Cue, StereoPcm> = cuePcm

    private val level = FloatArray(n) { config.levels[Layer.entries[it]] ?: Layer.entries[it].level }
    private val gain = FloatArray(n)
    private val target = FloatArray(n)
    private val panL = FloatArray(n) { cos((Layer.entries[it].pan + 1) * PI / 4).toFloat() * sqrt(2f) }
    private val panR = FloatArray(n) { sin((Layer.entries[it].pan + 1) * PI / 4).toFloat() * sqrt(2f) }
    private val fadeInStep = 1f / (config.fadeInBars * timing.barFrames)
    private val fadeOutStep = 1f / (config.fadeOutBars * timing.barFrames)
    /** 드롭: 1/16박 페이드(90 BPM이면 약 42ms). */
    private val fastStep = 1f / (timing.beatFrames / 16f)
    private val fastIn = BooleanArray(n)

    private val queue = ConcurrentLinkedQueue<MusicCommand>()
    private data class Pending(val layer: Int, val on: Boolean, val atFrame: Long, val fast: Boolean)
    private val pending = ArrayList<Pending>()

    /** 박자 격자 시계(프레임). 테이프 스톱 중에도 계속 흐른다. */
    @Volatile var gridFrame = 0L
        private set
    private var readPos = 0.0

    var masterGain = 0.8f

    // 효과음 원샷
    private var cueVoice: StereoPcm? = null
    private var cuePos = 0
    private var cueEnd = 0
    private val cueFadeFrames = (config.cueFadeOutMs * sr / 1000).toInt()
    // 덕킹
    private var duck = 1f
    private var duckTarget = 1f
    private var duckReleaseAt = -1L
    private val duckAttackStep = (1f - config.duckGain) / (config.duckAttackMs * sr / 1000f)
    private val duckReleaseStep = (1f - config.duckGain) / (0.5f * timing.barFrames)

    // 속도 톤(0 = 정차·먹먹, 1 = 밝음). 명령이 없으면 1(필터 없음)
    private var speedNorm = 1f
    private var speedTarget = 1f
    private val speedAlpha = 1f - exp(-1.0 / (config.speedSmoothingS * sr)).toFloat()

    // 비 효과(로우패스 + 리버브), rainMix 0→1을 1마디에 걸쳐
    private var rainTarget = 0f
    private var rainMix = 0f
    private val rainStep = 1f / timing.barFrames
    private val rainL = Biquad(); private val rainR = Biquad()
    private val reverb = Reverb(sr)
    private var rainCounter = 0

    // DSP 효과(신스 모드)
    /** 테이프 스톱: 0.7초 동안 재생 속도·음높이가 함께 0까지, 그사이 로우패스가 18k → 300Hz로 닫힘. */
    private val tapeRampFrames = (0.7 * sr).toLong()
    private val tapeL = Biquad(); private val tapeR = Biquad()
    private var tapeLpCounter = 0
    private var tapeStart = -1L
    private var tapeResume = -1L
    private var resumeFadeLeft = 0
    private var lpStart = -1L
    private val lpL = Biquad(); private val lpR = Biquad()
    private var wobbleStart = -1L

    /** 현재 켜져 있거나 켜지기로 예약된 레이어(UI 표시용). */
    val activeLayers: Set<Layer>
        get() = Layer.entries.filter { target[it.ordinal] > 0f }.toSet()

    val availableLayers: Set<Layer> = Layer.entries.filter { pcm[it.ordinal] != null }.toSet()
    val availableCues: Set<Cue> = cues.keys

    fun submit(cmd: MusicCommand) { queue.add(cmd) }

    fun render(out: FloatArray, frames: Int) {
        drainCommands()
        var lpCounter = 0
        for (f in 0 until frames) {
            if (pending.isNotEmpty()) applyDue()

            // 1) 재생 위치(테이프 스톱이면 느려졌다가 멈춤)
            var rate = 1.0
            var tapeGain = 1f
            if (tapeStart >= 0) {
                val e = gridFrame - tapeStart
                if (e < tapeRampFrames) {
                    val p = e.toDouble() / tapeRampFrames
                    rate = (1 - p) * (1 - p); tapeGain = (1 - p).toFloat()
                } else if (gridFrame < tapeResume) {
                    rate = 0.0; tapeGain = 0f
                } else {
                    tapeStart = -1; readPos = (gridFrame % loop).toDouble()
                    resumeFadeLeft = (0.02 * sr).toInt()
                }
            }
            if (resumeFadeLeft > 0) { tapeGain *= 1f - resumeFadeLeft.toFloat() / (0.02f * sr); resumeFadeLeft-- }

            // 2) 피치 흔들림(멜로디만)
            var wobble = 0.0
            if (wobbleStart >= 0) {
                val e = gridFrame - wobbleStart
                if (e >= timing.barFrames) wobbleStart = -1 else {
                    wobble = 0.0025 * sr * sin(PI * e / timing.barFrames) * sin(2 * PI * 6.0 * e / sr)
                }
            }

            // 3) 덕킹
            if (duckReleaseAt in 0..gridFrame) { duckTarget = 1f; duckReleaseAt = -1 }
            if (duck > duckTarget) duck = (duck - duckAttackStep).coerceAtLeast(duckTarget)
            else if (duck < duckTarget) duck = (duck + duckReleaseStep).coerceAtMost(duckTarget)

            // 4) 레이어 믹스
            var l = 0f; var r = 0f
            for (i in 0 until n) {
                val g = stepGain(i)
                if (g <= 0f) continue
                val p = pcm[i] ?: continue
                val pos = if (i == Layer.MELODY.ordinal) readPos + wobble else readPos
                val k = g * level[i]
                if (p.isMono) {
                    val s = sample(p.left, pos) * k
                    l += s * panL[i]; r += s * panR[i]
                } else {
                    l += sample(p.left, pos) * k; r += sample(p.right, pos) * k
                }
            }
            // 4b) 반응형 연주 레이어(같은 격자 시계)
            val perf = performance
            if (perf != null) { perf.process(gridFrame); l += perf.outL; r += perf.outR }

            val mg = masterGain * tapeGain * duck
            l *= mg; r *= mg

            // 5a) 톤: 속도(0.5초 스무딩)와 비가 함께 정하는 로우패스 + 리버브
            speedNorm += (speedTarget - speedNorm) * speedAlpha
            if (rainMix != rainTarget) rainMix = if (rainMix < rainTarget) (rainMix + rainStep).coerceAtMost(rainTarget) else (rainMix - rainStep).coerceAtLeast(rainTarget)
            val toneActive = speedNorm < 0.999f || rainMix > 0f
            if (toneActive || reverb.active) {
                if (rainCounter-- <= 0) {
                    rainCounter = 64
                    val speedHz = config.speedMinCutoffHz * exp(ln(18_000.0 / config.speedMinCutoffHz) * speedNorm)
                    val hz = (speedHz * exp(ln(config.rainCutoffHz / 18_000.0) * rainMix)).coerceIn(300.0, 18_000.0)
                    rainL.setLowpass(hz, sr, 0.707); rainR.setLowpass(hz, sr, 0.707)
                }
                val fl = rainL.process(l); val fr = rainR.process(r)
                val wet = config.rainReverbWet * rainMix + config.speedReverbWetAtStop * (1f - speedNorm)
                reverb.process(fl, fr, wet)
                l = fl + reverb.outL; r = fr + reverb.outR
            }

            // 5b) 테이프 스톱 로우패스(램프 동안 닫힘)
            if (tapeStart >= 0 && gridFrame - tapeStart < tapeRampFrames) {
                if (tapeLpCounter-- <= 0) {
                    tapeLpCounter = 32
                    val p = (gridFrame - tapeStart).toDouble() / tapeRampFrames
                    tapeL.setLowpass(18_000.0 * exp(ln(300.0 / 18_000.0) * p * p), sr, 0.707)
                    tapeR.setLowpass(18_000.0 * exp(ln(300.0 / 18_000.0) * p * p), sr, 0.707)
                }
                l = tapeL.process(l); r = tapeR.process(r)
            }

            // 5c) 로우패스 딥
            if (lpStart >= 0) {
                if (lpCounter-- <= 0) { lpCounter = 32; updateLowpass() }
                if (lpStart >= 0) { l = lpL.process(l); r = lpR.process(r) }
            }

            // 6) 효과음(덕킹·필터 영향 없음)
            val cv = cueVoice
            if (cv != null) {
                if (cuePos >= cueEnd) cueVoice = null else {
                    val fade = if (cuePos > cueEnd - cueFadeFrames) (cueEnd - cuePos).toFloat() / cueFadeFrames else 1f
                    val k = config.cueLevel * fade * masterGain
                    l += cv.left[cuePos] * k; r += cv.right[cuePos] * k
                    cuePos++
                }
            }

            out[2 * f] = limit(l)
            out[2 * f + 1] = limit(r)

            readPos += rate
            if (readPos >= loop) readPos -= loop
            gridFrame++
        }
    }

    private fun stepGain(i: Int): Float {
        val g = gain[i]; val t = target[i]
        if (g < t) { gain[i] = (g + if (fastIn[i]) fastStep else fadeInStep).coerceAtMost(t); if (gain[i] >= t) fastIn[i] = false }
        else if (g > t) gain[i] = (g - fadeOutStep).coerceAtLeast(t)
        return gain[i]
    }

    private fun drainCommands() {
        while (true) {
            when (val c = queue.poll() ?: break) {
                is MusicCommand.SetLayer -> schedule(c.layer, c.on, c.fast)
                is MusicCommand.PlayCue -> playCue(c.cue)
                MusicCommand.TapeStop -> startTapeStop()
                MusicCommand.LowpassDip -> { lpStart = gridFrame; lpL.reset(); lpR.reset() }
                MusicCommand.PitchWobble -> wobbleStart = gridFrame
                is MusicCommand.MasterGain -> masterGain = c.gain
                is MusicCommand.RainFx -> rainTarget = if (c.on) 1f else 0f
                is MusicCommand.SpeedTone -> speedTarget = (c.kmh / config.speedBrightKmh).coerceIn(0f, 1f)
                is MusicCommand.PerfArp, MusicCommand.PerfRootFifth, is MusicCommand.PerfShaker -> performance?.command(c, gridFrame)
            }
        }
    }

    private fun playCue(cue: Cue) {
        val p = cues[cue]
        if (p == null) { // 효과음이 없으면(신스 모드) DSP 효과로 대체
            when (cue) { Cue.TAPE_STOP -> startTapeStop(); Cue.SCRATCH -> wobbleStart = gridFrame }
            return
        }
        cueVoice = p
        cuePos = 0
        if (cue == Cue.TAPE_STOP) performance?.silence(gridFrame, timing.nextBoundary(gridFrame + minOf(p.frames, (config.cueMaxMs * sr / 1000).toInt()), timing.barFrames))
        cueEnd = minOf(p.frames, (config.cueMaxMs * sr / 1000).toInt())
        duckTarget = config.duckGain
        // 효과음이 거의 끝나는 시점 이후의 첫 마디 경계에서 음악 복귀
        duckReleaseAt = timing.nextBoundary(gridFrame + cueEnd - cueFadeFrames, timing.barFrames)
    }

    private fun startTapeStop() {
        if (tapeStart >= 0) return
        tapeStart = gridFrame
        tapeL.reset(); tapeR.reset(); tapeLpCounter = 0
        tapeResume = timing.nextBoundary(gridFrame + tapeRampFrames + (0.15 * sr).toLong(), timing.barFrames)
        performance?.silence(gridFrame, tapeResume)
    }

    private fun schedule(layer: Layer, on: Boolean, fast: Boolean = false) {
        val i = layer.ordinal
        pending.removeAll { it.layer == i }
        // 드롭(fast로 켜기)은 다음 '박'에서 연다(최대 1박 = 0.67초). 브레이크다운 등 일반 전환은 레이어 단위(마디).
        val q = if (fast && on) Quantize.BEAT else layer.quantize
        val at = when (q) {
            Quantize.NOW -> gridFrame
            Quantize.BEAT -> timing.nextBoundary(gridFrame, timing.beatFrames)
            Quantize.BAR -> timing.nextBoundary(gridFrame, timing.barFrames)
        }
        val due = if (tapeStart >= 0) maxOf(at, tapeResume) else at
        pending += Pending(i, on, due, fast)
    }

    private fun applyDue() {
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.atFrame <= gridFrame) { target[p.layer] = if (p.on) 1f else 0f; fastIn[p.layer] = p.on && p.fast; it.remove() }
        }
    }

    private fun updateLowpass() {
        val e = (gridFrame - lpStart).toDouble() / sr
        val beatS = 60.0 / timing.bpm
        val minHz = 380.0; val maxHz = 18_000.0
        val hz = when {
            e < 0.06 -> maxHz * exp(ln(minHz / maxHz) * e / 0.06)
            e < beatS -> minHz
            else -> minHz * exp(ln(maxHz / minHz) * ((e - beatS) / (2 * timing.barMs / 1000.0)))
        }
        if (e >= beatS && hz >= maxHz) { lpStart = -1; return } // 회복 끝 → 필터 해제
        lpL.setLowpass(hz, sr, 0.9); lpR.setLowpass(hz, sr, 0.9)
    }

    private fun sample(buf: FloatArray, pos: Double): Float {
        var p = pos % loop; if (p < 0) p += loop
        val i = floor(p).toInt()
        val frac = (p - i).toFloat()
        val a = buf[i]; val b = buf[if (i + 1 == loop) 0 else i + 1]
        return a + (b - a) * frac
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }

    /** 가벼운 스테레오 리버브(Freeverb 축소판: 콤 4 + 올패스 2, 채널당). */
    private class Reverb(private val sr: Int) {
        private val scale = sr / 44_100.0
        private val combL = intArrayOf(1116, 1188, 1277, 1356).map { Comb((it * scale).toInt()) }
        private val combR = intArrayOf(1139, 1211, 1300, 1379).map { Comb((it * scale).toInt()) }
        private val apL = intArrayOf(556, 441).map { Allpass((it * scale).toInt()) }
        private val apR = intArrayOf(579, 464).map { Allpass((it * scale).toInt()) }
        var outL = 0f; var outR = 0f
        private var silentFrames = Int.MAX_VALUE
        /** 젖은 신호가 남아 있는 동안 true(입력이 끊겨도 꼬리 처리). */
        val active: Boolean get() = silentFrames < sr * 3

        fun process(l: Float, r: Float, wet: Float) {
            val inp = (l + r) * 0.015f * (if (wet > 0f) 1f else 0f)
            var sl = 0f; var sr2 = 0f
            for (c in combL) sl += c.process(inp)
            for (c in combR) sr2 += c.process(inp)
            for (a in apL) sl = a.process(sl)
            for (a in apR) sr2 = a.process(sr2)
            outL = sl * wet.coerceAtLeast(0.02f); outR = sr2 * wet.coerceAtLeast(0.02f)
            silentFrames = if (wet > 0f) 0 else silentFrames + 1
            if (!active) { outL = 0f; outR = 0f }
        }

        private class Comb(size: Int) {
            private val buf = FloatArray(size); private var i = 0; private var store = 0f
            fun process(x: Float): Float {
                val y = buf[i]
                store = y * 0.8f + store * 0.2f // 댐핑
                buf[i] = x + store * 0.78f
                if (++i == buf.size) i = 0
                return y
            }
        }

        private class Allpass(size: Int) {
            private val buf = FloatArray(size); private var i = 0
            fun process(x: Float): Float {
                val b = buf[i]
                buf[i] = x + b * 0.5f
                if (++i == buf.size) i = 0
                return b - x
            }
        }
    }

    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
        fun setLowpass(hz: Double, sr: Int, q: Double) {
            val w = 2 * PI * hz / sr
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0
            a1 = -2 * c / a0; a2 = (1 - alpha) / a0
        }
        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
            return y.toFloat()
        }
    }
}
