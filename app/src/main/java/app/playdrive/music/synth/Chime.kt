package app.playdrive.music.synth

import app.playdrive.music.StereoPcm
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 웰컴 차임(코드에서 합성 → 라이선스 문제 없음). A5 · C6 · E6를 60ms씩 엇갈려 울리는 부드러운 종소리.
 * A·C·E는 곡의 두 코드(Am7 = A C E G, Fmaj7 = F A C E)에 모두 들어 있는 음 → 어느 마디에서 울려도 어울린다.
 */
object Chime {
    val MIDI = listOf(81, 84, 88)

    fun render(sampleRate: Int, seconds: Float = 2.4f, peak: Float = 0.28f): StereoPcm {
        val n = (seconds * sampleRate).toInt()
        val l = FloatArray(n); val r = FloatArray(n)
        MIDI.forEachIndexed { k, m ->
            val hz = 440.0 * 2.0.pow((m - 69) / 12.0)
            val start = (k * 0.06 * sampleRate).toInt()
            val pan = (k - 1) * 0.35f
            for (i in start until n) {
                val t = (i - start).toDouble() / sampleRate
                val env = (1 - exp(-t / 0.006)) * exp(-t / 0.9)
                // 배음은 정수배만(종소리처럼 맑게, 음정 흔들림 없음)
                val v = (sin(2 * PI * hz * t) + 0.25 * sin(2 * PI * 2 * hz * t) * exp(-t / 0.35) + 0.08 * sin(2 * PI * 3 * hz * t) * exp(-t / 0.2)) * env
                l[i] += (v * (1 - pan) * 0.5).toFloat(); r[i] += (v * (1 + pan) * 0.5).toFloat()
            }
        }
        val max = maxOf(l.maxOf { kotlin.math.abs(it) }, r.maxOf { kotlin.math.abs(it) })
        val g = peak / max
        // 끝 200ms 페이드아웃
        val fade = (0.2 * sampleRate).toInt()
        for (i in 0 until n) { val f = if (i > n - fade) (n - i).toFloat() / fade else 1f; l[i] *= g * f; r[i] *= g * f }
        return StereoPcm(l, r)
    }
}
