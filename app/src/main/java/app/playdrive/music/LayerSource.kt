package app.playdrive.music

/**
 * 레이어 한 개의 PCM 공급원. 코드 합성(SynthLayerSource) 또는 실제 스템(OggLayerSource).
 *
 * 계약: 반환 PCM은 48kHz Float, 길이 = timing.loopFrames(정확히 4마디), 90 BPM·A단조,
 * 루프 끝과 처음이 자연스럽게 이어질 것, 피크 ≤ 1.
 */
interface LayerSource {
    val layer: Layer
    /** 캐시 키(내용이 바뀌면 달라져야 함). */
    val cacheKey: String
    fun load(timing: MusicTiming): StereoPcm
}

/** 원샷 효과음 공급원. */
interface CueSource {
    val cue: Cue
    val cacheKey: String
    fun load(sampleRate: Int): StereoPcm
}
