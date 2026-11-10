package app.playdrive.music

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.TurnSignal

/**
 * 운전 이벤트·상태 → 음악 명령(순수 Kotlin). 운전은 음을 만들지 않고 레이어를 고르고 섞기만 한다.
 *
 * 단계(stage)별 레이어:
 *  0 정차·출발 전      : PAD(pad_rain)
 *  1 부드러운 가속      : + DRUMS + HIHAT      (SmoothAcceleration, 또는 출발 후 2마디 동안 급 이벤트 없음)
 *  2 안정 주행          : + BASS + CHORDS      (CruiseStarted, 또는 단계1에서 4마디 유지)
 *  3 오래 부드러운 주행 : + MELODY             (단계2에서 4마디 동안 급 이벤트 없음)
 *  신호 대기(주행 후 정차): 브레이크다운 = DRUMS·HIHAT 빠짐 / 주차: PAD만, 단계 0
 *
 * 급제동·급가속 → Cue.TAPE_STOP, 급조향(급차선변경) → Cue.SCRATCH.
 * 그 직후 레이어는 PAD만 남기고, 다음 마디부터 한 마디에 하나씩(PAD→DRUMS→HIHAT→BASS→CHORDS→MELODY 순) 복구.
 * 단계 자체는 유지되지만, 다음 단계로 오르는 시간은 처음부터 다시 잰다.
 *
 * @param available 실제로 소리가 있는 레이어(스템 모드에는 HIHAT_FILL·RAIN이 없다)
 */
class MusicDirector(
    private val timing: MusicTiming = MusicTiming(),
    private val available: Set<Layer> = Layer.entries.toSet(),
) {
    var stage = 0
        private set
    private var departedOnce = false
    private var parked = true
    private var stageSinceMs = 0L
    private var calmSinceMs = 0L
    /** 급 이벤트 후 복구 중이면 그 시각, 아니면 null. */
    private var recoverSinceMs: Long? = null
    private var current: Set<Layer> = emptySet()

    val layers: Set<Layer> get() = current

    fun update(state: DrivingState, events: List<DrivingEvent>): List<MusicCommand> {
        val out = mutableListOf<MusicCommand>()
        val now = state.timeMs
        for (e in events) when (e) {
            is DrivingEvent.Departed -> {
                parked = false
                if (!departedOnce) { departedOnce = true; stageSinceMs = now; calmSinceMs = now }
            }
            is DrivingEvent.SmoothAcceleration -> if (departedOnce && stage < 1) setStage(1, now)
            is DrivingEvent.CruiseStarted -> if (departedOnce && stage < 2) setStage(2, now)
            is DrivingEvent.Parked -> { parked = true; departedOnce = false; stage = 0; recoverSinceMs = null }
            is DrivingEvent.HarshBraking, is DrivingEvent.HarshAcceleration -> harsh(Cue.TAPE_STOP, now, out)
            is DrivingEvent.HarshSteering -> harsh(Cue.SCRATCH, now, out)
            else -> Unit
        }

        // 시간 경과로 단계 올리기(급 이벤트 없이 일정 마디가 지나면)
        if (departedOnce && !parked && state.moving) {
            val calmBars = (now - calmSinceMs) / timing.barMs
            val inStageBars = (now - stageSinceMs) / timing.barMs
            when {
                stage == 0 && calmBars >= 2 -> setStage(1, now)
                stage == 1 && inStageBars >= 4 && calmBars >= 4 -> setStage(2, now)
                stage == 2 && inStageBars >= 4 && calmBars >= 4 -> setStage(3, now)
            }
        }

        val want = desiredLayers(state, now)
        for (l in Layer.entries) {
            val on = l in want
            if (on != (l in current)) out += MusicCommand.SetLayer(l, on)
        }
        current = want
        return out
    }

    private fun setStage(s: Int, now: Long) { stage = s; stageSinceMs = now }

    private fun harsh(cue: Cue, now: Long, out: MutableList<MusicCommand>) {
        out += MusicCommand.PlayCue(cue)
        recoverSinceMs = now
        calmSinceMs = now
        stageSinceMs = now
    }

    private fun desiredLayers(s: DrivingState, now: Long): Set<Layer> {
        val ordered = mutableListOf(Layer.PAD)
        if (!parked && departedOnce) {
            val waiting = !s.moving
            if (stage >= 1 && !waiting) { ordered += Layer.DRUMS; ordered += Layer.HIHAT }
            if (stage >= 2) { ordered += Layer.BASS; ordered += Layer.CHORDS }
            if (stage >= 3) ordered += Layer.MELODY
        }
        // 급 이벤트 후: 다음 마디부터 한 마디에 하나씩 복구
        recoverSinceMs?.let { t0 ->
            val restored = 1 + ((now - t0) / timing.barMs).toInt()
            if (restored >= ordered.size) recoverSinceMs = null
            else while (ordered.size > restored) ordered.removeAt(ordered.size - 1)
        }
        val set = ordered.toMutableSet()
        if (s.raining && Layer.RAIN in available) set += Layer.RAIN
        if (s.turnSignal != TurnSignal.OFF && !parked && departedOnce) {
            // 방향지시등: 필인 레이어가 있으면 필인, 없으면(스템) 하이햇을 합류
            set += if (Layer.HIHAT_FILL in available) Layer.HIHAT_FILL else Layer.HIHAT
        }
        return set.filter { it in available }.toSet()
    }
}
