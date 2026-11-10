package app.playdrive.music

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.TurnSignal

/**
 * 편곡: 단계별로 더해지는 레이어, 신호 대기 때 빠지는 레이어, 비 처리 방식.
 * 세트(SONG/LOOPS/SYNTH)마다 StemConfig에서 정한다.
 */
data class Arrangement(
    /** stages[0] = 정차·출발 전, [1] = 부드러운 가속, [2] = 안정 주행, [3] = 오래 부드러운 주행 에 더해지는 레이어. */
    val stages: List<List<Layer>>,
    val breakdownDrop: Set<Layer> = setOf(Layer.DRUMS, Layer.HIHAT),
    val rain: RainMode = RainMode.NONE,
) {
    enum class RainMode { NONE, LAYER, FX }

    companion object {
        /** 한 곡 스템: keys+synth → +drums(+perc) → +bass → +guitar. 비 = 로우패스+리버브. */
        val SONG = Arrangement(
            stages = listOf(listOf(Layer.CHORDS, Layer.PAD), listOf(Layer.DRUMS, Layer.HIHAT), listOf(Layer.BASS), listOf(Layer.MELODY)),
            rain = RainMode.FX,
        )
        /** 개별 루프(v1): pad_rain → +drums+hat → +bass+chords → +melody. */
        val LOOPS = Arrangement(
            stages = listOf(listOf(Layer.PAD), listOf(Layer.DRUMS, Layer.HIHAT), listOf(Layer.BASS, Layer.CHORDS), listOf(Layer.MELODY)),
        )
        /** 신스 플레이스홀더: LOOPS와 같고 빗소리 레이어 사용. */
        val SYNTH = LOOPS.copy(rain = RainMode.LAYER)
    }
}

/**
 * 운전 이벤트·상태 → 음악 명령(순수 Kotlin). 운전은 음을 만들지 않고 레이어를 고르고 섞기만 한다.
 *
 * 단계 전환: 0 → 1 SmoothAcceleration(또는 출발 후 2마디 동안 급 이벤트 없음)
 *           1 → 2 CruiseStarted(또는 4마디 유지) / 2 → 3 4마디 동안 급 이벤트 없음
 * 신호 대기(주행 후 정차) = breakdownDrop 빠짐 / 주차 = 단계 0.
 * 급제동·급가속 → Cue.TAPE_STOP, 급조향(급차선변경) → Cue.SCRATCH. 직후엔 단계0 레이어만 남기고
 * 다음 마디부터 한 마디에 하나씩(단계 순서대로) 복구. 단계는 유지하되 승급 시간은 다시 잰다.
 * 비: LAYER = RAIN 레이어, FX = 전체 로우패스+리버브(RainFx), NONE = 무시.
 *
 * @param available 실제로 소리가 있는 레이어
 */
class MusicDirector(
    private val timing: MusicTiming = MusicTiming(),
    private val available: Set<Layer> = Layer.entries.toSet(),
    private val arrangement: Arrangement = Arrangement.SYNTH,
) {
    private var rainFxOn = false
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

        if (arrangement.rain == Arrangement.RainMode.FX && state.raining != rainFxOn) {
            rainFxOn = state.raining
            out += MusicCommand.RainFx(rainFxOn)
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
        val st = arrangement.stages
        val ordered = st[0].toMutableList()
        val base = ordered.size
        if (!parked && departedOnce) {
            val waiting = !s.moving
            for (k in 1..stage.coerceAtMost(st.size - 1)) {
                ordered += st[k].filter { !(waiting && it in arrangement.breakdownDrop) }
            }
        }
        // 급 이벤트 후: 단계0만 남기고 다음 마디부터 한 마디에 하나씩 복구
        recoverSinceMs?.let { t0 ->
            val restored = base + ((now - t0) / timing.barMs).toInt()
            if (restored >= ordered.size) recoverSinceMs = null
            else while (ordered.size > restored) ordered.removeAt(ordered.size - 1)
        }
        val set = ordered.toMutableSet()
        if (s.raining && arrangement.rain == Arrangement.RainMode.LAYER) set += Layer.RAIN
        if (s.turnSignal != TurnSignal.OFF && !parked && departedOnce) {
            // 방향지시등: 필인 레이어가 있으면 필인, 없으면 하이햇(perc)을 합류
            set += if (Layer.HIHAT_FILL in available) Layer.HIHAT_FILL else Layer.HIHAT
        }
        return set.filter { it in available }.toSet()
    }
}
