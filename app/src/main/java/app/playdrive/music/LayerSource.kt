package app.playdrive.music

/**
 * 레이어 한 개의 PCM 공급원. 지금은 코드 합성(SynthLayerSource),
 * 나중에 실제 스템(OGG)을 디코딩하는 구현으로 갈아 끼운다.
 *
 * 계약: 반환 배열은 모노 Float PCM, 길이 = timing.loopFrames(4마디), 같은 BPM·조성,
 * 루프 끝과 처음이 자연스럽게 이어질 것(꼬리는 앞부분에 겹쳐 넣는다), 피크 ≤ 1.
 */
interface LayerSource {
    val layer: Layer
    fun load(timing: MusicTiming): FloatArray
}
