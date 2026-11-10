package app.playdrive.music.stems

import app.playdrive.R
import app.playdrive.music.Cue
import app.playdrive.music.Layer

/**
 * 음원 설정은 이 파일 한 곳에서 바꾼다.
 * - SOURCE: STEMS(Suno 스템 OGG) / SYNTH(코드 합성 플레이스홀더)
 * - 스템별 A/B 선택(기본 전부 A)
 * 원본·가공 방법: tools/process_stems.py, docs/dev-notes.md
 */
object StemConfig {
    enum class Source { STEMS, SYNTH }
    enum class Variant { A, B }

    val SOURCE = Source.STEMS

    val PAD_RAIN = Variant.A
    val DRUMS = Variant.A
    val HAT = Variant.A
    val BASS = Variant.A
    val CHORDS = Variant.A
    val MELODY = Variant.A
    val SFX_TAPESTOP = Variant.A
    val SFX_SCRATCH = Variant.A

    /** process_stems.py의 LOOP_PAD와 같아야 한다(루프 OGG 앞뒤 순환 패딩 샘플 수). */
    const val LOOP_PAD_FRAMES = 4096

    /** 효과음 최대 재생 길이(ms) — 원샷 원본이 6~14초로 길어서 앞부분만 쓴다. */
    const val CUE_MAX_MS = 2_500L

    private fun pick(v: Variant, a: Int, b: Int) = if (v == Variant.A) a else b

    val layers: Map<Layer, Int> get() = mapOf(
        Layer.PAD to pick(PAD_RAIN, R.raw.lofi_pad_rain_a, R.raw.lofi_pad_rain_b),
        Layer.DRUMS to pick(DRUMS, R.raw.lofi_drums_a, R.raw.lofi_drums_b),
        Layer.HIHAT to pick(HAT, R.raw.lofi_hat_a, R.raw.lofi_hat_b),
        Layer.BASS to pick(BASS, R.raw.lofi_bass_a, R.raw.lofi_bass_b),
        Layer.CHORDS to pick(CHORDS, R.raw.lofi_chords_a, R.raw.lofi_chords_b),
        Layer.MELODY to pick(MELODY, R.raw.lofi_melody_a, R.raw.lofi_melody_b),
    )

    val cues: Map<Cue, Int> get() = mapOf(
        Cue.TAPE_STOP to pick(SFX_TAPESTOP, R.raw.lofi_sfx_tapestop_a, R.raw.lofi_sfx_tapestop_b),
        Cue.SCRATCH to pick(SFX_SCRATCH, R.raw.lofi_sfx_scratch_a, R.raw.lofi_sfx_scratch_b),
    )
}
