package app.playdrive.music

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import app.playdrive.model.TurnSignal

/**
 * 운전 이벤트·상태 → 음악 명령(순수 Kotlin). 운전은 음을 만들지 않고 레이어를 고르고 섞기만 한다.
 *
 * 레이어 구성:
 * - 출발 전(P단 또는 아직 출발 안 함): 인트로 = 패드
 * - 주행: 레벨1 = 드럼·베이스·패드 → 레벨2 = +하이햇 → 레벨3 = +멜로디
 *   급 이벤트 없이 2마디가 지날 때마다 레벨 +1
 * - 신호 대기(주행 후 정차): 브레이크다운 = 드럼·하이햇 빠짐
 * - 주차: 패드만(아웃트로)
 * - 비: +빗소리 / 방향지시등: +하이햇 필인(박 단위로 합류)
 * - 급제동: 테이프 스톱 + 레벨 −2 / 급가속: 로우패스 딥 + 레벨 −2 / 급조향: 피치 흔들림 + 레벨 −1
 */
class MusicDirector(private val timing: MusicTiming = MusicTiming()) {

    var level = 0
        private set
    private var departedOnce = false
    private var parked = true
    private var lastLevelChangeMs = 0L
    private var current: Set<Layer> = emptySet()

    val layers: Set<Layer> get() = current

    fun update(state: DrivingState, events: List<DrivingEvent>): List<MusicCommand> {
        val out = mutableListOf<MusicCommand>()
        val now = state.timeMs
        for (e in events) when (e) {
            is DrivingEvent.Departed -> {
                departedOnce = true; parked = false
                if (level < 1) { level = 1; lastLevelChangeMs = now }
            }
            is DrivingEvent.GearChanged -> if (e.gear != Gear.PARK) parked = false
            is DrivingEvent.Parked -> { parked = true; departedOnce = false; level = 0 }
            is DrivingEvent.HarshBraking -> { out += MusicCommand.TapeStop; drop(2, now) }
            is DrivingEvent.HarshAcceleration -> { out += MusicCommand.LowpassDip; drop(2, now) }
            is DrivingEvent.HarshSteering -> { out += MusicCommand.PitchWobble; drop(1, now) }
            else -> Unit
        }

        if (state.moving && level in 1..2 && now - lastLevelChangeMs >= timing.barMs * 2) {
            level++; lastLevelChangeMs = now
        }

        val want = desiredLayers(state)
        for (l in Layer.entries) {
            val on = l in want
            if (on != (l in current)) out += MusicCommand.SetLayer(l, on)
        }
        current = want
        return out
    }

    private fun drop(by: Int, now: Long) {
        if (level > 0) level = (level - by).coerceAtLeast(1)
        lastLevelChangeMs = now
    }

    private fun desiredLayers(s: DrivingState): Set<Layer> {
        val set = mutableSetOf<Layer>()
        when {
            parked || !departedOnce -> set += Layer.PAD
            !s.moving -> { // 신호 대기: 브레이크다운
                set += Layer.PAD; set += Layer.BASS
                if (level >= 3) set += Layer.MELODY
            }
            else -> {
                set += Layer.DRUMS; set += Layer.BASS; set += Layer.PAD
                if (level >= 2) set += Layer.HIHAT
                if (level >= 3) set += Layer.MELODY
            }
        }
        if (s.raining) set += Layer.RAIN
        if (s.turnSignal != TurnSignal.OFF && !parked) set += Layer.HIHAT_FILL
        return set
    }
}
