package app.playdrive.music.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** 루프 버퍼: 인덱스가 끝을 넘으면 앞으로 감겨서(꼬리가 루프 처음에 겹쳐) 이음매가 자연스럽다. */
class LoopBuffer(val size: Int) {
    val data = FloatArray(size)
    fun add(index: Long, v: Float) {
        val i = ((index % size) + size) % size
        data[i.toInt()] += v
    }

    fun normalize(peak: Float) {
        val max = data.maxOf { abs(it) }
        if (max > 0f) { val k = peak / max; for (i in data.indices) data[i] *= k }
    }
}

object Wavetable {
    const val SIZE = 4096
    private fun build(harmonics: (Int) -> Float): FloatArray {
        val t = FloatArray(SIZE)
        for (h in 1..16) {
            val a = harmonics(h)
            if (a == 0f) continue
            for (i in 0 until SIZE) t[i] += (a * sin(2 * PI * h * i / SIZE)).toFloat()
        }
        val max = t.maxOf { abs(it) }
        for (i in t.indices) t[i] /= max
        return t
    }

    val sine = build { if (it == 1) 1f else 0f }
    /** 대역 제한 톱니파(8배음) — 패드·베이스. */
    val softSaw = build { if (it <= 8) 1f / it else 0f }
    /** 부드러운 삼각파 계열 — 멜로디. */
    val softTri = build { if (it % 2 == 1 && it <= 7) 1f / (it * it) else if (it == 2) 0.15f else 0f }

    fun lookup(table: FloatArray, phase: Double): Float {
        val p = phase - kotlin.math.floor(phase)
        val x = p * SIZE
        val i = x.toInt() % SIZE
        val f = (x - kotlin.math.floor(x)).toFloat()
        return table[i] + (table[(i + 1) % SIZE] - table[i]) * f
    }
}

fun midiHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

data class Envelope(val attackS: Float, val decayS: Float, val sustain: Float, val releaseS: Float)

/** 음 하나를 버퍼에 더한다(ADSR, 선택적 비브라토). */
fun LoopBuffer.tone(
    sr: Int, start: Long, lengthFrames: Int, hz: Double, table: FloatArray, amp: Float, env: Envelope,
    detuneCents: Double = 0.0, vibratoHz: Double = 0.0, vibratoCents: Double = 0.0,
) {
    val a = (env.attackS * sr).toInt().coerceAtLeast(1)
    val d = (env.decayS * sr).coerceAtLeast(1f)
    val r = (env.releaseS * sr).coerceAtLeast(1f)
    val total = lengthFrames + (env.releaseS * sr * 5).toInt()
    val baseHz = hz * 2.0.pow(detuneCents / 1200.0)
    var phase = 0.0
    var level = 0f
    for (i in 0 until total) {
        level = when {
            i < a -> i.toFloat() / a
            i < lengthFrames -> env.sustain + (1f - env.sustain) * exp(-(i - a) / d)
            else -> {
                val held = if (lengthFrames > a) env.sustain + (1f - env.sustain) * exp(-(lengthFrames - a) / d) else 1f
                held * exp(-(i - lengthFrames) / r)
            }
        }
        if (i > lengthFrames && level < 1e-4f) break
        val vib = if (vibratoHz > 0) 2.0.pow(vibratoCents / 1200.0 * sin(2 * PI * vibratoHz * i / sr)) else 1.0
        phase += baseHz * vib / sr
        add(start + i, Wavetable.lookup(table, phase) * level * amp)
    }
}

/** 한 극 저역통과. 두 번 돌려 루프 이음매에서도 필터 상태가 이어지게 한다. */
fun LoopBuffer.lowpass(sr: Int, cutoffHz: Double) {
    val k = (1 - exp(-2 * PI * cutoffHz / sr)).toFloat()
    var y = 0f
    repeat(2) { pass ->
        for (i in data.indices) {
            y += k * (data[i] - y)
            if (pass == 1) data[i] = y
        }
    }
}

/** 한 극 고역통과(같은 방식). */
fun LoopBuffer.highpass(sr: Int, cutoffHz: Double) {
    val k = (1 - exp(-2 * PI * cutoffHz / sr)).toFloat()
    var low = 0f
    val copy = data.copyOf()
    repeat(2) { pass ->
        for (i in data.indices) {
            low += k * (copy[i] - low)
            if (pass == 1) data[i] = copy[i] - low
        }
    }
}

/** 피드백 딜레이(루프를 두 바퀴 돌려 꼬리가 감기도록). */
fun LoopBuffer.echo(delayFrames: Int, feedback: Float, mix: Float) {
    val wet = FloatArray(size)
    repeat(3) {
        for (i in 0 until size) {
            val j = (i - delayFrames + size) % size
            wet[i] = data[j] + wet[j] * feedback
        }
    }
    for (i in 0 until size) data[i] += wet[i] * mix
}

object Drums {
    fun kick(buf: LoopBuffer, sr: Int, at: Long, amp: Float) {
        val n = (0.4 * sr).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 48 + 90 * exp(-t / 0.03)
            phase += f / sr
            val v = sin(2 * PI * phase) * exp(-t / 0.14)
            buf.add(at + i, (v * amp).toFloat())
        }
    }

    fun snare(buf: LoopBuffer, sr: Int, at: Long, amp: Float, rnd: Random) {
        val n = (0.25 * sr).toInt()
        var prev = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val noise = rnd.nextFloat() * 2 - 1
            val hp = noise - prev * 0.6f; prev = noise
            val v = hp * exp(-t / 0.055) * 0.55 + sin(2 * PI * 185 * t) * exp(-t / 0.04) * 0.5
            buf.add(at + i, (v * amp).toFloat())
        }
    }

    fun hat(buf: LoopBuffer, sr: Int, at: Long, amp: Float, decayS: Double, rnd: Random) {
        val n = (decayS * 6 * sr).toInt()
        var p1 = 0f; var p2 = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val x = rnd.nextFloat() * 2 - 1
            val d1 = x - p1; p1 = x
            val d2 = d1 - p2; p2 = d1 // 2차 차분 = 강한 고역통과(쇳소리)
            buf.add(at + i, (d2 * 0.5 * exp(-t / decayS) * amp).toFloat())
        }
    }
}
