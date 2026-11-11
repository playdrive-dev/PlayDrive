package app.playdrive.music

/** 모든 레이어가 공유하는 박자 격자. 90 BPM, 4/4, 4마디 루프. */
data class MusicTiming(
    val sampleRate: Int = 48_000,
    val bpm: Int = 90,
    val beatsPerBar: Int = 4,
    val bars: Int = 4,
) {
    val beatFrames: Int = sampleRate * 60 / bpm
    val barFrames: Int = beatFrames * beatsPerBar
    val loopFrames: Int = barFrames * bars
    val barMs: Long = 60_000L * beatsPerBar / bpm

    fun nextBoundary(frame: Long, unitFrames: Int): Long = (frame / unitFrames + 1) * unitFrames
}

enum class Quantize { NOW, BEAT, BAR }

/**
 * 레이어(세트별 실제 소리: SONG = keys→CHORDS, synth→PAD, perc→HIHAT, guitar→MELODY).
 * level = 기본 믹스 음량(세트별 값은 LoopMixer.Config.levels로 덮어씀), pan = -1(왼쪽)~1(오른쪽, 모노 소스에만 적용), quantize = 켜고 끄는 시점.
 * 스템 6종(PAD=pad_rain, DRUMS, HIHAT=hat, BASS, CHORDS, MELODY) + 신스 전용 효과 레이어 2개.
 */
enum class Layer(val level: Float, val pan: Float, val quantize: Quantize) {
    PAD(0.70f, 0f, Quantize.BAR),
    DRUMS(0.95f, 0f, Quantize.BAR),
    HIHAT(0.60f, 0.25f, Quantize.BAR),
    BASS(0.85f, 0f, Quantize.BAR),
    CHORDS(0.70f, -0.1f, Quantize.BAR),
    MELODY(0.65f, -0.15f, Quantize.BAR),
    HIHAT_FILL(0.50f, 0.35f, Quantize.BEAT), // 신스 전용: 방향지시등 필인
    RAIN(0.45f, 0f, Quantize.BAR),           // 신스 전용: 빗소리 (스템은 pad_rain에 포함)
}

/** 급 이벤트에 대한 음악적 반응. 스템 모드 = 효과음 원샷, 신스 모드 = DSP 효과로 대체. */
/** CHIME = 웰컴 차임(코드에서 합성, 덕킹 없음). */
enum class Cue { TAPE_STOP, SCRATCH, CHIME }

/** 스테레오 PCM(Float). 모노 소스는 left와 right가 같은 배열. */
class StereoPcm(val left: FloatArray, val right: FloatArray) {
    val frames: Int get() = left.size
    val isMono: Boolean get() = left === right
    companion object { fun mono(x: FloatArray) = StereoPcm(x, x) }
}

sealed interface MusicCommand {
    /** fast = 드롭: 다음 '박' 경계에서 1/16박 페이드로 바로 열림(끄기는 일반 퀀타이즈). */
    data class SetLayer(val layer: Layer, val on: Boolean, val fast: Boolean = false) : MusicCommand
    /** 급제동·급가속 = TAPE_STOP, 급조향(급차선변경) = SCRATCH. 즉시 반응 + 음악 덕킹. */
    data class PlayCue(val cue: Cue) : MusicCommand
    /** DSP 효과(신스 모드 대체·디버그용). */
    data object TapeStop : MusicCommand
    data object LowpassDip : MusicCommand
    data object PitchWobble : MusicCommand
    data class MasterGain(val gain: Float) : MusicCommand
    /** 비: 전체에 로우패스 + 약한 리버브를 1마디에 걸쳐 걸고/푼다. */
    data class RainFx(val on: Boolean) : MusicCommand
    /** 속도 → 전체 로우패스·리버브(0km/h 먹먹 ↔ 60km/h 이상 밝음), 믹서에서 0.5초 스무딩. */
    data class SpeedTone(val kmh: Float) : MusicCommand

    // --- 반응형 연주 레이어(PerformanceLayer). 모두 8분음표 격자에 맞춰 시작.
    /** 부드러운 가속 구간: 박마다 코드톤 상행 아르페지오(4음 후 처음으로). */
    data class PerfArp(val on: Boolean) : MusicCommand
    /** 부드러운 브레이크 시작: 현재 코드 근음 + 5도 한 번. */
    data object PerfRootFifth : MusicCommand
    /** 방향지시등: 8분음표마다 셰이커, 끄면 마디 끝까지 치고 멈춤. */
    data class PerfShaker(val on: Boolean) : MusicCommand
}
