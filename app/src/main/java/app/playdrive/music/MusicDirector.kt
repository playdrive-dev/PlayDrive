package app.playdrive.music

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import app.playdrive.model.TurnSignal
import kotlin.math.abs

/**
 * 편곡: 단계별로 더해지는 레이어, 신호 대기 때 빠지는 레이어, 비 처리 방식.
 * 세트(SONG/LOOPS/SYNTH)마다 StemConfig에서 정한다.
 */
data class Arrangement(
    /** stages[0] = 정차·출발 전, [1] = 부드러운 가속, [2] = 안정 주행, [3] = 오래 부드러운 주행 에 더해지는 레이어. */
    val stages: List<List<Layer>>,
    val breakdownDrop: Set<Layer> = setOf(Layer.DRUMS, Layer.HIHAT),
    val rain: RainMode = RainMode.NONE,
    /** true면 출발(D + 속도 증가) 시 단계1 레이어를 다음 다운비트에 페이드 없이 '드롭'. */
    val dropOnDepart: Boolean = false,
    /** 방향지시등 때 합류시킬 필인 레이어(신스 세트). 없으면 연주 레이어 셰이커만. */
    val turnFillLayer: Layer? = null,
) {
    enum class RainMode { NONE, LAYER, FX }

    /** 게이지(남은 악기 수)로 켤 때의 순서: 단계 순서를 펼친 것. SONG = keys → synth → drums → bass → perc → guitar. */
    val order: List<Layer> get() = stages.flatten()

    companion object {
        /**
         * 한 곡 스템: 정차 = keys+synth → 출발 = drums+bass '드롭'(다음 다운비트) → 안정 주행 = +perc → 오래 부드러운 주행 = +guitar.
         * 신호 대기(정차 2초 이상) = keys+synth만(+ 속도 0 로우패스로 브레이크다운). 비 = 로우패스+리버브.
         */
        val SONG = Arrangement(
            stages = listOf(listOf(Layer.CHORDS, Layer.PAD), listOf(Layer.DRUMS, Layer.BASS), listOf(Layer.HIHAT), listOf(Layer.MELODY)),
            breakdownDrop = setOf(Layer.DRUMS, Layer.BASS, Layer.HIHAT, Layer.MELODY),
            rain = RainMode.FX,
            dropOnDepart = true,
        )
        /** 개별 루프(v1): pad_rain → +drums+hat → +bass+chords → +melody. */
        val LOOPS = Arrangement(
            stages = listOf(listOf(Layer.PAD), listOf(Layer.DRUMS, Layer.HIHAT), listOf(Layer.BASS, Layer.CHORDS), listOf(Layer.MELODY)),
        )
        /** 신스 플레이스홀더: LOOPS와 같고 빗소리 레이어 사용. */
        val SYNTH = LOOPS.copy(rain = RainMode.LAYER, turnFillLayer = Layer.HIHAT_FILL)
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
 * 반응형 연주(PerformanceLayer, 모두 8분음표 격자):
 *  - 속도 → SpeedTone(1km/h 이상 바뀔 때)
 *  - 부드러운 가속(SmoothAcceleration ~ 가속도 0.3 m/s² 미만) → PerfArp
 *  - 주행 중 브레이크를 밟기 시작 → PerfRootFifth
 *  - 방향지시등 → PerfShaker
 *
 * @param available 실제로 소리가 있는 레이어
 */
class MusicDirector(
    private val timing: MusicTiming = MusicTiming(),
    private val available: Set<Layer> = Layer.entries.toSet(),
    private val arrangement: Arrangement = Arrangement.SYNTH,
) {
    private var rainFxOn = false
    private var lastSpeedKmh = Float.NaN
    private var arpOn = false
    private var shakerOn = false
    private var brakeWas = false
    private var dropPending = false
    /** 브레이크를 밟은 시각 — 0.4초 동안 급제동이 아니면 근음+5도. */
    private var rootFifthCheckAt: Long? = null
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

    // --- 게이지 모드(연주 기록: 곡 + 라이프 게이지). null이면 예전 단계 방식.
    /** 남은 악기 수(0~6). 설정되면 레이어 = [Arrangement.order] 앞에서 gauge개. */
    var gauge: Int? = null
    /** 연주가 멈춤(게이지 0): 잔잔한 keys+synth만(믹서가 먹먹하게·작게). */
    var stopped = false
    /** 다음 update에서 줄어드는 악기를 '다음 박'에 빼도록(흔들림). */
    var lossNextBeat = false
    /** 급 이벤트 뒤 한 마디씩 복구하는 중(음성 '다시 연주를 시작할게요' 시점 판단용). */
    val recovering: Boolean get() = recoverSinceMs != null

    fun update(state: DrivingState, events: List<DrivingEvent>): List<MusicCommand> {
        val out = mutableListOf<MusicCommand>()
        val now = state.timeMs
        for (e in events) when (e) {
            is DrivingEvent.Departed -> {
                parked = false
                if (!departedOnce) { departedOnce = true; stageSinceMs = now; calmSinceMs = now }
                if (arrangement.dropOnDepart) {
                    if (stage < 1) setStage(1, now)
                    if (state.gear == Gear.DRIVE) dropPending = true
                }
            }
            is DrivingEvent.SmoothAcceleration -> {
                if (departedOnce && stage < 1) setStage(1, now)
                if (state.moving && !arpOn) { arpOn = true; out += MusicCommand.PerfArp(true) }
            }
            is DrivingEvent.CruiseStarted -> if (departedOnce && stage < 2) setStage(2, now)
            is DrivingEvent.Parked -> { parked = true; departedOnce = false; stage = 0; recoverSinceMs = null }
            is DrivingEvent.HarshBraking, is DrivingEvent.HarshAcceleration -> {
                // 게이지 모드: 악기가 빠지는 것(다음 박)이 반응, 짧은 로우패스 딥만. 테이프스톱은 연주가 멈출 때(게이지 0)
                if (gauge != null) out += MusicCommand.LowpassDip else harsh(Cue.TAPE_STOP, now, out)
                arpOn = false; rootFifthCheckAt = null
            }
            is DrivingEvent.HarshSteering -> if (gauge != null) out += MusicCommand.PlayCue(Cue.SCRATCH) else harsh(Cue.SCRATCH, now, out)
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

        // --- 반응형 연주
        val kmh = state.speedMps * 3.6f
        if (lastSpeedKmh.isNaN() || abs(kmh - lastSpeedKmh) >= 1f) { lastSpeedKmh = kmh; out += MusicCommand.SpeedTone(kmh) }
        if (arpOn && (!state.moving || state.accelMps2 < 0.3f || state.brakePressed)) { arpOn = false; out += MusicCommand.PerfArp(false) }
        // 부드러운 브레이크 시작: 밟고 0.4초 동안 감속이 약하면(급제동이 아니면) 근음+5도
        if (state.brakePressed && !brakeWas && state.moving && state.speedMps >= 2f) rootFifthCheckAt = now + ROOT_FIFTH_CONFIRM_MS
        rootFifthCheckAt?.let { at ->
            if (!state.brakePressed || state.accelMps2 <= -HARD_DECEL_MPS2) rootFifthCheckAt = null
            else if (now >= at) { rootFifthCheckAt = null; out += MusicCommand.PerfRootFifth }
        }
        brakeWas = state.brakePressed
        val turn = state.turnSignal != TurnSignal.OFF
        if (turn != shakerOn) { shakerOn = turn; out += MusicCommand.PerfShaker(turn) }

        if (arrangement.rain == Arrangement.RainMode.FX && state.raining != rainFxOn) {
            rainFxOn = state.raining
            out += MusicCommand.RainFx(rainFxOn)
        }

        val want = desiredLayers(state, now)
        val drop = if (dropPending && state.moving) arrangement.stages.getOrNull(1).orEmpty().toSet() else emptySet()
        if (dropPending && state.moving) dropPending = false
        val beatOff = lossNextBeat; lossNextBeat = false
        for (l in Layer.entries) {
            val on = l in want
            if (on != (l in current)) out += MusicCommand.SetLayer(l, on, fast = (on && l in drop) || (!on && beatOff))
        }
        current = want
        return out
    }

    private fun setStage(s: Int, now: Long) { stage = s; stageSinceMs = now }

    companion object {
        /** 브레이크 시작 후 급제동 여부를 지켜보는 시간과, '부드럽지 않다'로 보는 감속(급제동 2.94보다 낮게). */
        const val ROOT_FIFTH_CONFIRM_MS = 400L
        const val HARD_DECEL_MPS2 = 2.0f
    }

    private fun harsh(cue: Cue, now: Long, out: MutableList<MusicCommand>) {
        out += MusicCommand.PlayCue(cue)
        recoverSinceMs = now
        calmSinceMs = now
        stageSinceMs = now
    }

    private fun desiredLayers(s: DrivingState, now: Long): Set<Layer> {
        gauge?.let { g ->
            val base = arrangement.stages[0]
            val set = when {
                parked || !departedOnce || stopped -> base.toSet()
                else -> arrangement.order.take(g).filter { !(!s.moving && it in arrangement.breakdownDrop) }.toSet()
            }.toMutableSet()
            if (s.raining && arrangement.rain == Arrangement.RainMode.LAYER) set += Layer.RAIN
            return set.filter { it in available }.toSet()
        }
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
        val fill = arrangement.turnFillLayer
        if (fill != null && s.turnSignal != TurnSignal.OFF && !parked && departedOnce) set += fill
        return set.filter { it in available }.toSet()
    }
}
