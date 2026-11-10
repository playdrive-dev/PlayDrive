package app.playdrive.music.stems

import app.playdrive.R
import app.playdrive.music.Arrangement
import app.playdrive.music.ChordTimeline
import app.playdrive.music.Cue
import app.playdrive.music.Layer
import app.playdrive.music.MusicTiming

/**
 * 음원 설정은 이 파일 한 곳에서 바꾼다.
 * - SET: SONG(한 곡에서 분리한 스템, 8마디 — 기본) / LOOPS(개별 생성 루프 A/B, 4마디) / SYNTH(코드 합성 플레이스홀더)
 * - LOOPS 세트의 스템별 A/B, 효과음 A/B, 테이프스톱 방식
 * 원본·가공 방법: tools/analyze_song.py, tools/process_stems.py, docs/dev-notes.md
 */
object StemConfig {
    enum class Set { SONG, LOOPS, SYNTH }
    enum class Variant { A, B }
    /** SFX = Suno 효과음 원샷, DSP = 음악 자체를 테이프처럼 느려지며 멈추게(진짜 피치 하강). */
    enum class TapeStopMode { SFX, DSP }

    val SET = Set.SONG

    // --- LOOPS 세트(v1) 스템별 선택
    val PAD_RAIN = Variant.A
    val DRUMS = Variant.A
    val HAT = Variant.A
    val BASS = Variant.A
    val CHORDS = Variant.A
    val MELODY = Variant.A

    // --- 효과음(모든 세트 공통, suno_raw 원샷)
    val SFX_TAPESTOP = Variant.A
    val SFX_SCRATCH = Variant.A
    val TAPESTOP_MODE = TapeStopMode.SFX

    /** process_stems.py의 LOOP_PAD와 같아야 한다(루프 OGG 앞뒤 순환 패딩 샘플 수). */
    const val LOOP_PAD_FRAMES = 4096

    /** 효과음 최대 재생 길이(ms). 테이프스톱은 1.1~1.4초로 잘라 두었고, 스크래치는 원본이 길어 앞부분만. */
    const val CUE_MAX_MS = 2_500L

    /** SONG 세트 레이어 음량(−20 LUFS로 맞춘 스템 기준, synth −22 / perc는 피크 제한으로 약 −32 LUFS). */
    val SONG_LEVELS = mapOf(
        Layer.DRUMS to 0.80f, Layer.BASS to 0.80f, Layer.CHORDS to 0.70f,
        Layer.MELODY to 0.60f, Layer.PAD to 0.55f, Layer.HIHAT to 0.50f,
    )

    val timing: MusicTiming get() = when (SET) {
        Set.SONG -> MusicTiming(bars = 8)
        Set.LOOPS, Set.SYNTH -> MusicTiming(bars = 4)
    }

    val arrangement: Arrangement get() = when (SET) {
        Set.SONG -> Arrangement.SONG
        Set.LOOPS -> Arrangement.LOOPS
        Set.SYNTH -> Arrangement.SYNTH
    }

    /** 반응형 연주 레이어 음량(배경보다 살짝 작게). */
    const val PERF_LEVEL = 0.45f

    /** 연주 레이어가 따르는 코드 타임라인. */
    val chordTimeline: ChordTimeline get() = when (SET) {
        Set.SONG -> ChordTimeline.SONG
        Set.LOOPS -> ChordTimeline.LOOPS
        Set.SYNTH -> ChordTimeline.SYNTH
    }

    val levels: Map<Layer, Float> get() = if (SET == Set.SONG) SONG_LEVELS else emptyMap()

    private fun pick(v: Variant, a: Int, b: Int) = if (v == Variant.A) a else b

    /** 레이어 → res/raw (SYNTH 세트면 비어 있음). */
    val layers: Map<Layer, Int> get() = when (SET) {
        Set.SONG -> mapOf(
            Layer.CHORDS to R.raw.song_keys,   // 로즈 코드
            Layer.PAD to R.raw.song_synth,     // 패드
            Layer.DRUMS to R.raw.song_drums,
            Layer.HIHAT to R.raw.song_perc,    // 퍼커션
            Layer.BASS to R.raw.song_bass,
            Layer.MELODY to R.raw.song_guitar, // 기타 멜로디
        )
        Set.LOOPS -> mapOf(
            Layer.PAD to pick(PAD_RAIN, R.raw.lofi_pad_rain_a, R.raw.lofi_pad_rain_b),
            Layer.DRUMS to pick(DRUMS, R.raw.lofi_drums_a, R.raw.lofi_drums_b),
            Layer.HIHAT to pick(HAT, R.raw.lofi_hat_a, R.raw.lofi_hat_b),
            Layer.BASS to pick(BASS, R.raw.lofi_bass_a, R.raw.lofi_bass_b),
            Layer.CHORDS to pick(CHORDS, R.raw.lofi_chords_a, R.raw.lofi_chords_b),
            Layer.MELODY to pick(MELODY, R.raw.lofi_melody_a, R.raw.lofi_melody_b),
        )
        Set.SYNTH -> emptyMap()
    }

    /** 효과음 → res/raw. TAPESTOP_MODE=DSP이거나 SYNTH 세트면 테이프스톱 원샷을 싣지 않는다(→ DSP 대체). */
    val cues: Map<Cue, Int> get() = buildMap {
        if (SET == Set.SYNTH) return@buildMap
        if (TAPESTOP_MODE == TapeStopMode.SFX) put(Cue.TAPE_STOP, pick(SFX_TAPESTOP, R.raw.lofi_sfx_tapestop_a, R.raw.lofi_sfx_tapestop_b))
        put(Cue.SCRATCH, pick(SFX_SCRATCH, R.raw.lofi_sfx_scratch_a, R.raw.lofi_sfx_scratch_b))
    }
}
