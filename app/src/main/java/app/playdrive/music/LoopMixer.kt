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
 * 수직 레이어링 믹서(순수 Kotlin). 오디오 스레드에서 render()를 부르고,
 * 다른 스레드는 submit()으로 명령만 넣는다.
 *
 * - 레이어 on/off는 레이어별 퀀타이즈 단위(다음 마디/박) 경계에서 짧은 페이드로 적용
 * - 효과: 테이프 스톱(급제동), 로우패스 딥(급가속), 피치 흔들림(급조향)
 * - 출력: 스테레오 인터리브 Float, 소프트 리미터로 |x| ≤ 1
 */
class LoopMixer(private val timing: MusicTiming, layerPcm: Map<Layer, FloatArray>) {

    private val sr = timing.sampleRate
    private val loop = timing.loopFrames
    private val n = Layer.entries.size
    private val pcm: Array<FloatArray?> = Array(n) { i -> layerPcm[Layer.entries[i]]?.also { require(it.size == loop) } }

    private val gain = FloatArray(n)
    private val target = FloatArray(n)
    private val panL = FloatArray(n) { cos((Layer.entries[it].pan + 1) * PI / 4).toFloat() * sqrt(2f) }
    private val panR = FloatArray(n) { sin((Layer.entries[it].pan + 1) * PI / 4).toFloat() * sqrt(2f) }
    private val fadeInStep = 1f / (0.06f * sr)
    private val fadeOutStep = 1f / (0.15f * sr)

    private val queue = ConcurrentLinkedQueue<MusicCommand>()
    private data class Pending(val layer: Int, val on: Boolean, val atFrame: Long)
    private val pending = ArrayList<Pending>()

    /** 박자 격자 시계(프레임). 테이프 스톱 중에도 계속 흐른다. */
    @Volatile var gridFrame = 0L
        private set
    private var readPos = 0.0

    var masterGain = 0.75f

    // 테이프 스톱
    private val tapeRampFrames = (0.6 * sr).toLong()
    private var tapeStart = -1L
    private var tapeResume = -1L
    private var resumeFadeLeft = 0

    // 로우패스 딥
    private var lpStart = -1L
    private val lpL = Biquad(); private val lpR = Biquad()

    // 피치 흔들림
    private var wobbleStart = -1L

    /** 현재 켜져 있거나 켜지기로 예약된 레이어(UI 표시용). */
    val activeLayers: Set<Layer>
        get() = Layer.entries.filter { target[it.ordinal] > 0f }.toSet()

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
                    rate = (1 - p) * (1 - p)
                    tapeGain = (1 - p).toFloat()
                } else if (gridFrame < tapeResume) {
                    rate = 0.0; tapeGain = 0f
                } else {
                    tapeStart = -1; readPos = (gridFrame % loop).toDouble()
                    resumeFadeLeft = (0.02 * sr).toInt()
                }
            }
            if (resumeFadeLeft > 0) { tapeGain *= 1f - resumeFadeLeft.toFloat() / (0.02f * sr); resumeFadeLeft-- }

            // 2) 피치 흔들림 오프셋(멜로디만)
            var wobble = 0.0
            if (wobbleStart >= 0) {
                val e = gridFrame - wobbleStart
                if (e >= timing.barFrames) wobbleStart = -1 else {
                    val env = sin(PI * e / timing.barFrames)
                    wobble = 0.0025 * sr * env * sin(2 * PI * 6.0 * e / sr)
                }
            }

            // 3) 믹스
            var l = 0f; var r = 0f
            for (i in 0 until n) {
                val g = stepGain(i)
                if (g <= 0f) continue
                val buf = pcm[i] ?: continue
                val pos = if (i == Layer.MELODY.ordinal) readPos + wobble else readPos
                val s = sample(buf, pos) * g * Layer.entries[i].level
                l += s * panL[i]; r += s * panR[i]
            }
            l *= masterGain * tapeGain; r *= masterGain * tapeGain

            // 4) 로우패스 딥
            if (lpStart >= 0) {
                if (lpCounter-- <= 0) { lpCounter = 32; updateLowpass() }
                if (lpStart >= 0) { l = lpL.process(l); r = lpR.process(r) }
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
        if (g < t) gain[i] = (g + fadeInStep).coerceAtMost(t)
        else if (g > t) gain[i] = (g - fadeOutStep).coerceAtLeast(t)
        return gain[i]
    }

    private fun drainCommands() {
        while (true) {
            when (val c = queue.poll() ?: break) {
                is MusicCommand.SetLayer -> schedule(c.layer, c.on)
                MusicCommand.TapeStop -> if (tapeStart < 0) {
                    tapeStart = gridFrame
                    // 멈춘 뒤 최소 0.15초 쉬고, 그다음 마디 머리에서 다시 들어온다
                    tapeResume = timing.nextBoundary(gridFrame + tapeRampFrames + (0.15 * sr).toLong(), timing.barFrames)
                }
                MusicCommand.LowpassDip -> { lpStart = gridFrame; lpL.reset(); lpR.reset() }
                MusicCommand.PitchWobble -> wobbleStart = gridFrame
                is MusicCommand.MasterGain -> masterGain = c.gain
            }
        }
    }

    private fun schedule(layer: Layer, on: Boolean) {
        val i = layer.ordinal
        pending.removeAll { it.layer == i }
        val at = when (layer.quantize) {
            Quantize.NOW -> gridFrame
            Quantize.BEAT -> timing.nextBoundary(gridFrame, timing.beatFrames)
            Quantize.BAR -> timing.nextBoundary(gridFrame, timing.barFrames)
        }
        // 테이프 스톱 중이면 재진입 시점에 맞춘다
        val due = if (tapeStart >= 0) maxOf(at, tapeResume) else at
        pending += Pending(i, on, due)
    }

    private fun applyDue() {
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.atFrame <= gridFrame) { target[p.layer] = if (p.on) 1f else 0f; it.remove() }
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
