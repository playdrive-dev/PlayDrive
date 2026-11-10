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
 * 레이어. level = 믹스 음량, pan = -1(왼쪽)~1(오른쪽), quantize = 켜고 끄는 시점.
 * 5개 기본 레이어 + 효과 레이어 2개(방향지시등 필인, 빗소리).
 */
enum class Layer(val level: Float, val pan: Float, val quantize: Quantize) {
    DRUMS(0.95f, 0f, Quantize.BAR),
    BASS(0.80f, 0f, Quantize.BAR),
    PAD(0.55f, 0f, Quantize.BAR),
    MELODY(0.60f, -0.15f, Quantize.BAR),
    HIHAT(0.45f, 0.25f, Quantize.BAR),
    HIHAT_FILL(0.50f, 0.35f, Quantize.BEAT),
    RAIN(0.45f, 0f, Quantize.BAR),
}

sealed interface MusicCommand {
    data class SetLayer(val layer: Layer, val on: Boolean) : MusicCommand
    /** 급제동: 테이프가 멈추듯 피치·속도가 떨어진 뒤 다음 마디에 다시 들어온다. */
    data object TapeStop : MusicCommand
    /** 급가속: 로우패스로 소리가 먹먹해졌다가 2마디에 걸쳐 회복. */
    data object LowpassDip : MusicCommand
    /** 급조향: 멜로디 피치가 1마디 동안 흔들린다. */
    data object PitchWobble : MusicCommand
    data class MasterGain(val gain: Float) : MusicCommand
}
