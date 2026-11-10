package app.playdrive.music.synth

import app.playdrive.music.Composition
import app.playdrive.music.Layer
import app.playdrive.music.LayerSource
import app.playdrive.music.MusicTiming
import app.playdrive.music.StereoPcm
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** 실제 음원이 준비되기 전, 코드로 합성한 플레이스홀더 레이어(같은 BPM·조성·4마디). */
class SynthLayerSource(override val layer: Layer) : LayerSource {

    override val cacheKey = "synth-$VERSION-${layer.name}"

    override fun load(timing: MusicTiming): StereoPcm = StereoPcm.mono(render(timing))

    private fun render(timing: MusicTiming): FloatArray {
        val buf = LoopBuffer(timing.loopFrames)
        val sr = timing.sampleRate
        val beat = timing.beatFrames
        val bar = timing.barFrames
        fun at(barIdx: Int, b: Float): Long = barIdx.toLong() * bar + (b * beat).toLong()
        val rnd = Random(layer.ordinal + 11)

        when (layer) {
            Layer.DRUMS -> {
                for (b in 0 until timing.bars) {
                    Composition.kicks.forEach { Drums.kick(buf, sr, at(b, it), 1f) }
                    Composition.snares.forEach { Drums.snare(buf, sr, at(b, it), 0.75f, rnd) }
                }
                buf.normalize(0.9f)
            }
            Layer.BASS -> {
                val env = Envelope(0.005f, 0.25f, 0.55f, 0.06f)
                for (b in 0 until timing.bars) for (n in Composition.bass[b]) {
                    buf.tone(sr, at(b, n.beat), (n.lengthBeats * beat).toInt(), midiHz(n.midi), Wavetable.softSaw, n.velocity, env)
                }
                buf.lowpass(sr, 700.0)
                buf.normalize(0.85f)
            }
            Layer.PAD -> {
                val env = Envelope(0.45f, 1.0f, 0.85f, 0.7f)
                for (b in 0 until timing.bars) for (m in Composition.pad[b]) for (det in listOf(-7.0, 7.0)) {
                    buf.tone(sr, at(b, 0f), bar, midiHz(m), Wavetable.softSaw, 0.5f, env, detuneCents = det)
                }
                buf.lowpass(sr, 1400.0)
                buf.normalize(0.7f)
            }
            Layer.CHORDS -> {
                // 로즈 느낌의 코드 스탭: 1박, 2.5박에 짧게
                val env = Envelope(0.004f, 0.35f, 0.25f, 0.2f)
                for (b in 0 until timing.bars) for (hit in listOf(0f to 1f, 2.5f to 0.7f)) for (m in Composition.pad[b]) {
                    buf.tone(sr, at(b, hit.first), (0.9f * beat).toInt(), midiHz(m + 12), Wavetable.softTri, hit.second, env)
                }
                buf.normalize(0.7f)
            }
            Layer.MELODY -> {
                val env = Envelope(0.01f, 0.3f, 0.5f, 0.25f)
                for (b in 0 until timing.bars) for (n in Composition.melody[b]) {
                    buf.tone(
                        sr, at(b, n.beat), (n.lengthBeats * beat).toInt(), midiHz(n.midi), Wavetable.softTri, n.velocity, env,
                        vibratoHz = 5.0, vibratoCents = 8.0,
                    )
                }
                buf.echo(delayFrames = beat * 3 / 4, feedback = 0.35f, mix = 0.3f) // 점8분 딜레이
                buf.normalize(0.75f)
            }
            Layer.HIHAT -> {
                for (b in 0 until timing.bars) for (i in 0 until timing.beatsPerBar * 2) {
                    Drums.hat(buf, sr, at(b, i * 0.5f), if (i % 2 == 0) 0.9f else 0.55f, 0.018, rnd)
                }
                buf.normalize(0.6f)
            }
            Layer.HIHAT_FILL -> {
                // 방향지시등: 16분 하이햇 롤, 박 머리에 강세, 마지막 16분은 오픈 하이햇
                for (b in 0 until timing.bars) for (i in 0 until timing.beatsPerBar * 4) {
                    val open = i % 4 == 3
                    val v = if (i % 4 == 0) 1f else 0.5f
                    Drums.hat(buf, sr, at(b, i * 0.25f), v, if (open) 0.07 else 0.012, rnd)
                }
                buf.normalize(0.6f)
            }
            Layer.RAIN -> {
                // 빗소리: 음높이 없는 필터 노이즈 + 물방울 클릭 → 화성과 충돌하지 않는다
                for (i in 0 until buf.size) {
                    val swell = 0.75f + 0.25f * sin(2 * PI * i / buf.size * 2).toFloat()
                    buf.data[i] = (rnd.nextFloat() * 2 - 1) * swell
                }
                buf.lowpass(sr, 2500.0)
                buf.highpass(sr, 300.0)
                repeat(220) {
                    val pos = rnd.nextLong(buf.size.toLong())
                    val amp = 0.6f + rnd.nextFloat()
                    val n = (0.006 * sr).toInt()
                    for (k in 0 until n) buf.add(pos + k, ((rnd.nextFloat() * 2 - 1) * amp * exp(-k / (0.0015 * sr))).toFloat())
                }
                buf.normalize(0.5f)
            }
        }
        return buf.data
    }

    companion object {
        /** 합성 코드·작곡을 바꾸면 올린다(캐시 무효화). */
        const val VERSION = "2"
        fun all(): List<LayerSource> = Layer.entries.map { SynthLayerSource(it) }
    }
}
