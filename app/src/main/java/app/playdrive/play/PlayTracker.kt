package app.playdrive.play

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import kotlin.math.abs

/**
 * 연주 기록 규칙(순수 Kotlin) — 곡(스테이지) + 라이프 게이지(남은 악기 수).
 * 원칙: **속도·시간·거리에는 보상하지 않고 부드러움에만 보상한다.** 곡 점수는 곡 안(64마디)의 값만 쓰므로 오래·빨리 달려도 같다.
 * 화면·음성 문구는 게임 용어를 쓰지 않는다(CLAUDE.md 6절): 콤보 → "이어진 마디", 퍼펙트 → "부드러운 출발/깔끔한 차선 변경/부드러운 정지",
 * 미스 → "흔들림". 실패한 곡은 칩에 "FAILED"(사용자 결정), 결과 상단 문구는 부드럽게 — 하루 단위 실패 표시는 없다.
 */
object PlayRules {
    const val G = 9.80665f
    /** 부드러운 출발: 출발 후 이 시간 동안 |가속도| ≤ 0.15g. */
    const val START_WINDOW_MS = 5_000L
    const val START_MAX_MPS2 = 0.15f * G          // 1.471
    /** 깔끔한 차선 변경: 방향지시등을 켠 지 3초 이상 지나 차선 변경, 그사이 급조향 없음. */
    const val CHANGE_MIN_SIGNAL_MS = 3_000L
    /** 부드러운 정지: 감속이 시작된 뒤 멈출 때까지 감속 ≤ 0.2g. */
    const val STOP_MAX_MPS2 = 0.2f * G            // 1.961
    const val STOP_SPEED_MPS = 0.3f
    const val MOVE_AGAIN_MPS = 1.0f

    // --- 내비
    /** 다음 회전이 이 거리 안이면 회전 판정 준비. */
    const val TURN_ARM_M = 300
    /** 제한속도 초과가 이만큼 이어지면 악기 −1(그 전엔 고음을 줄여 경고). */
    const val OVER_SPEED_MS = 5_000L
    /** 남은 거리가 이 안이면 마지막 구간 클라이맥스. */
    const val FINAL_STRETCH_M = 1_000
    /** 도착할 때 이 마디 이상 연주한 곡은 '도착으로 마무리'(완주), 더 짧으면 세지 않음. */
    const val ARRIVAL_MIN_BARS = 16

    // --- 곡과 게이지
    const val SONG_BARS = 64
    const val MAX_GAUGE = 6
    const val START_GAUGE = 4
    const val RETRY_GAUGE = 3
    /** 출발(주행의 첫 출발) 후 이 마디 동안은 게이지가 1 밑으로 내려가지 않는다. */
    const val WARMUP_BARS = 8
    /** 흔들림 없이 이 마디가 이어질 때마다 +1. */
    const val GAIN_EVERY_BARS = 4
    /** FAILED 뒤 재시작: 흔들림 없이 이 시간 주행 또는 정차 후 다시 출발. */
    const val RESTART_SMOOTH_MS = 15_000L
    /** 카운트인(다음 마디 경계까지 + 4박)을 기다리는 시간 — 믹서가 실제 격자에 맞춰 곡을 처음부터 다시 연다. */
    const val COUNT_IN_BARS = 1.5f

    fun loss(kind: MissKind): Int = when (kind) { MissKind.HARSH_BRAKE -> 2; else -> 1 }

    // --- 이어진 마디 이정표(소리 보상)
    const val FILL_EVERY = 16
    const val JOIN_AT = 32
    const val CLIMAX_AT = 48

    // --- 곡 점수: 평균 남은 악기 비율 × 70 + 곡 안 최장 연속 마디/64 × 20 + 판정 보너스(판정마다 2, 최대 10)
    const val PERFECT_BONUS = 2
    const val PERFECT_BONUS_MAX = 10
    const val S_MIN = 90
    const val A_MIN = 80
    const val B_MIN = 70

    fun score(avgGaugeRatio: Float, longestBars: Int, perfects: Int): Int =
        Math.round(avgGaugeRatio.coerceIn(0f, 1f) * 70f + minOf(longestBars, SONG_BARS).toFloat() / SONG_BARS * 20f + minOf(perfects * PERFECT_BONUS, PERFECT_BONUS_MAX)).coerceIn(0, 100)

    fun grade(score: Int, completed: Boolean): Grade = when {
        !completed -> Grade.FAILED
        score >= S_MIN -> Grade.S
        score >= A_MIN -> Grade.A
        score >= B_MIN -> Grade.B
        else -> Grade.C
    }
}

/** 곡 등급. 실패한 곡은 칩·기록에 "FAILED"로 보인다(2026-11-13 사용자 결정 — 결과 상단 문구는 부드럽게 유지). */
enum class Grade(val label: String) { S("S"), A("A"), B("B"), C("C"), FAILED("FAILED");
    val finished: Boolean get() = this != FAILED
    fun atLeast(g: Grade): Boolean = finished && ordinal <= g.ordinal
}

enum class PerfectKind(val label: String) {
    START("부드러운 출발"), CHANGE("깔끔한 차선 변경"), STOP("부드러운 정지"),
    /** 내비 회전 300m 안에서 그 방향 깜빡이 + 급조향 없이 회전. */
    TURN("깔끔한 회전"),
    /** 어린이 보호구역을 들어가서 나올 때까지 제한속도 준수. */
    ZONE("깔끔한 구간 통과"),
}

/** 흔들림. 깜빡이 없이 회전·제한속도 5초 넘게 초과도 악기 −1. */
enum class MissKind { HARSH_ACCEL, HARSH_BRAKE, HARSH_STEER, NO_SIGNAL_TURN, OVER_SPEED }

/** 연주 상태. */
enum class PlayMode { IDLE, RUNNING, FAILED, COUNT_IN }

/** 곡 한 번(시도 한 번)의 결과. */
data class SongResult(
    /** 몇 번째 곡(완주할 때마다 +1). */
    val number: Int,
    /** 그 곡의 몇 번째 시도(1 = 처음, 2 = 다시). */
    val attempt: Int,
    val completed: Boolean,
    val score: Int,
    val grade: Grade,
    val avgGauge: Float,
    val longestBars: Int,
    val perfects: Int,
) {
    val title: String get() = "${ORDINAL.getOrElse(number - 1) { "${number}번째" }} 곡" + if (attempt > 1) " (다시)" else ""
    companion object { private val ORDINAL = listOf("첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째", "여섯 번째", "일곱 번째", "여덟 번째", "아홉 번째", "열 번째") }
}

/** 주행 한 번의 결과 = 곡별 결과의 모음(주차 후에만 화면·음성으로). 하루 단위 실패는 없다. */
data class DriveResult(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val songs: List<SongResult>,
    val maxCombo: Int,
    val perfects: Map<PerfectKind, Int>,
    val misses: Int,
    val rained: Boolean,
    /** 흔들림이 없던 구간의 거리(m) — 음악 해금 진행도에만 쓰고 점수에는 쓰지 않는다. */
    val safeDistanceM: Float,
    val movingSeconds: Int,
    val stops: Int,
    /** 보호구역·제한속도에 시연용 구간 데이터를 썼는지(화면에 표기). */
    val demoZoneData: Boolean = false,
) {
    val perfectTotal: Int get() = perfects.values.sum()
    val completedSongs: Int get() = songs.count { it.completed }
    val attemptedSongs: Int get() = songs.size
    val best: SongResult? get() = songs.filter { it.completed }.maxByOrNull { it.score }
    val bestGrade: Grade? get() = best?.grade
    val bestScore: Int get() = best?.score ?: 0
}

/** 트래커가 내는 사건. 음악·음성 반응에만 쓰고 주행 중 화면에는 글자·숫자를 띄우지 않는다. */
sealed interface PlayEvent {
    val timeMs: Long
    data class Combo(override val timeMs: Long, val count: Int) : PlayEvent {
        val isFill get() = count > 0 && count % PlayRules.FILL_EVERY == 0
        val isJoin get() = count == PlayRules.JOIN_AT
        val isClimax get() = count == PlayRules.CLIMAX_AT
    }
    data class Perfect(override val timeMs: Long, val kind: PerfectKind) : PlayEvent
    data class Miss(override val timeMs: Long, val kind: MissKind, val lostCombo: Int) : PlayEvent
    /** 게이지(남은 악기 수)가 바뀜. loss = 흔들림으로 줄었음(다음 박에 빠짐). */
    data class Gauge(override val timeMs: Long, val value: Int, val loss: Boolean) : PlayEvent
    /** 악기가 1개만 남음(위험) / 벗어남. */
    data class Danger(override val timeMs: Long, val on: Boolean, val firstInSong: Boolean) : PlayEvent
    /** 게이지 0 → 연주 정지. */
    data class Failed(override val timeMs: Long, val song: SongResult) : PlayEvent
    /** 카운트인: 재시작(같은 곡 처음부터, 악기 [PlayRules.RETRY_GAUGE]개) 또는 내비 안내 시작(첫 곡). */
    data class CountIn(override val timeMs: Long, val songNumber: Int, val retry: Boolean = true) : PlayEvent
    /** 제한속도 초과 시작/끝(고음을 줄여 경고). */
    data class OverSpeed(override val timeMs: Long, val on: Boolean) : PlayEvent
    /** 목적지까지 1km 안: 마지막 구간 클라이맥스. */
    data class FinalStretch(override val timeMs: Long) : PlayEvent
    /** 목적지 도착: 연주 중이던 곡을 마무리(완주로 기록, 다음 곡 없음). */
    data class Arrived(override val timeMs: Long, val song: SongResult?) : PlayEvent
    /** 곡 시작(첫 출발, 완주 후 다음 곡, 카운트인 끝). */
    data class SongStart(override val timeMs: Long, val number: Int, val attempt: Int, val gauge: Int) : PlayEvent
    /** 64마디까지 게이지가 남음 → 완주. 곧바로 다음 곡이 시작된다. */
    data class SongComplete(override val timeMs: Long, val song: SongResult) : PlayEvent
}

/**
 * 주행 한 번의 연주 기록기(순수). 시간은 상태의 timeMs(단조 시계)만 쓰고, 날짜는 [clock](벽시계)로 남긴다.
 */
class PlayTracker(
    private val barMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var inDrive = false; private set
    var mode = PlayMode.IDLE; private set
    var gauge = 0; private set
    var songNumber = 1; private set
    var attempt = 1; private set
    private var startEpoch = 0L
    private var driveStartMs = 0L

    // 곡
    private var songStartMs = 0L
    private var songBars = 0
    private var gaugeSum = 0
    private var songLongest = 0
    private var songPerfects = 0
    private var dangerSaidThisSong = false
    private var smoothSinceMs = 0L
    private var countInUntil = 0L
    private var failedSmoothSince = 0L
    private var stoppedSinceFail = false

    // 이어진 마디
    private var comboSinceMs = 0L
    var combo = 0; private set
    var maxCombo = 0; private set

    // 판정
    private val perfects = PerfectKind.entries.associateWith { 0 }.toMutableMap()
    private var misses = 0
    private var startWindowEnd: Long? = null
    private var startClean = true
    private var turnOnAt: Long? = null
    private var harshSteerSinceTurnOn = false
    private var decelMax = 0f
    private var decelActive = false
    private var stopArmed = false
    private var missInDecel = false

    // 결과·비교용
    private val songs = mutableListOf<SongResult>()
    private var rained = false
    private var safeSegmentM = 0f
    private var safeTotalM = 0f
    private var movingMs = 0L
    private var stops = 0
    private var lastMs: Long? = null

    // 내비
    private var turnArmed = app.playdrive.model.TurnSignal.OFF
    private var turnDist = Int.MAX_VALUE
    private var turnSignalOk = false
    private var turnHarsh = false
    private var limitKmh = 0
    private var inZone = false
    private var zoneClean = true
    private var overSinceMs: Long? = null
    private var finalStretch = false
    private var arrived = false
    private var demoZone = false
    private var countInGauge = PlayRules.RETRY_GAUGE

    var lastResult: DriveResult? = null; private set

    fun update(state: DrivingState, events: List<DrivingEvent>): List<PlayEvent> {
        val out = mutableListOf<PlayEvent>()
        val now = state.timeMs
        val dt = lastMs?.let { (now - it).coerceIn(0, 1_000) } ?: 0
        lastMs = now
        lastResult = null

        for (e in events) when (e) {
            is DrivingEvent.Departed -> {
                if (!inDrive) begin(now, out)
                startWindowEnd = now + PlayRules.START_WINDOW_MS; startClean = true
                if (mode == PlayMode.FAILED && stoppedSinceFail) countIn(now, out)
            }
            is DrivingEvent.Stopped -> if (inDrive) { stops++; if (mode == PlayMode.FAILED) stoppedSinceFail = true }
            is DrivingEvent.HarshAcceleration -> miss(now, MissKind.HARSH_ACCEL, out)
            is DrivingEvent.HarshBraking -> miss(now, MissKind.HARSH_BRAKE, out)
            is DrivingEvent.HarshSteering -> { miss(now, MissKind.HARSH_STEER, out); harshSteerSinceTurnOn = true; turnHarsh = true }
            is DrivingEvent.TurnSignalOn -> { turnOnAt = e.timeMs; harshSteerSinceTurnOn = false; if (e.direction == turnArmed) turnSignalOk = true }
            is DrivingEvent.Navi -> navi(now, e.signal, out)
            is DrivingEvent.LaneChange -> if (turnArmed != app.playdrive.model.TurnSignal.OFF) turnOnAt = null else {
                val on = turnOnAt
                if (inDrive && on != null && e.timeMs - on >= PlayRules.CHANGE_MIN_SIGNAL_MS && !harshSteerSinceTurnOn) perfect(now, PerfectKind.CHANGE, out)
                turnOnAt = null  // (엔진은 TurnSignalOff 다음에 LaneChange를 낸다)
            }
            else -> Unit
        }

        if (inDrive) {
            // 이어진 마디(게이지와 별개): 흔들림 없이 지난 마디마다 +1(시간 기준)
            val c = ((now - comboSinceMs) / barMs).toInt()
            if (c > combo) { combo = c; maxCombo = maxOf(maxCombo, c); out += PlayEvent.Combo(now, c) }

            when (mode) {
                PlayMode.RUNNING -> runSong(now, out)
                PlayMode.FAILED -> if (state.moving && now - failedSmoothSince >= PlayRules.RESTART_SMOOTH_MS) countIn(now, out)
                PlayMode.COUNT_IN -> if (now >= countInUntil) startSong(now, countInGauge, out)
                PlayMode.IDLE -> Unit
            }

            judgeStartAndStop(state, now, out)
            judgeSpeed(state, now, out)
            if (state.moving) { movingMs += dt; safeSegmentM += state.speedMps * dt / 1000f }
            if (state.raining) rained = true
        }

        if (inDrive && events.any { it is DrivingEvent.Parked } && state.gear == Gear.PARK) lastResult = finish()
        return out
    }

    /** viaNavi = 내비 안내 시작으로 주행이 시작됨 → 카운트인 뒤 첫 곡. */
    private fun begin(now: Long, out: MutableList<PlayEvent>, viaNavi: Boolean = false) {
        inDrive = true; startEpoch = clock(); driveStartMs = now; comboSinceMs = now; combo = 0; maxCombo = 0
        perfects.keys.forEach { perfects[it] = 0 }; misses = 0; songs.clear()
        rained = false; safeSegmentM = 0f; safeTotalM = 0f; movingMs = 0; stops = 0
        decelActive = false; stopArmed = false; turnOnAt = null
        turnArmed = app.playdrive.model.TurnSignal.OFF; finalStretch = false; arrived = false; overSinceMs = null; inZone = false
        songNumber = 1; attempt = 1
        if (viaNavi) {
            mode = PlayMode.COUNT_IN; countInGauge = PlayRules.START_GAUGE
            countInUntil = now + (PlayRules.COUNT_IN_BARS * barMs).toLong()
            out += PlayEvent.CountIn(now, songNumber, retry = false)
        } else startSong(now, PlayRules.START_GAUGE, out)
    }

    private fun navi(now: Long, s: app.playdrive.model.NaviSignal, out: MutableList<PlayEvent>) {
        when (s) {
            is app.playdrive.model.NaviSignal.RouteStarted -> if (!inDrive) begin(now, out, viaNavi = true)
            is app.playdrive.model.NaviSignal.NextTurn -> {
                if (!inDrive) return
                val armed = turnArmed
                // 회전이 지나감 = 다음 회전으로 바뀜(거리가 크게 늘거나 방향이 바뀜)
                if (armed != app.playdrive.model.TurnSignal.OFF && (s.distanceM > turnDist + 50 || s.turn != armed)) {
                    if (!turnSignalOk) miss(now, MissKind.NO_SIGNAL_TURN, out)
                    else if (!turnHarsh) perfect(now, PerfectKind.TURN, out)
                    turnArmed = app.playdrive.model.TurnSignal.OFF
                }
                if (turnArmed == app.playdrive.model.TurnSignal.OFF && s.turn != app.playdrive.model.TurnSignal.OFF && s.distanceM <= PlayRules.TURN_ARM_M) {
                    turnArmed = s.turn; turnSignalOk = false; turnHarsh = false
                }
                turnDist = s.distanceM
            }
            is app.playdrive.model.NaviSignal.SpeedLimit -> {
                limitKmh = s.kmh
                if (s.demo) demoZone = true
                if (s.schoolZone && !inZone) { inZone = true; zoneClean = overSinceMs == null }
                else if (!s.schoolZone && inZone) { inZone = false; if (zoneClean && inDrive) perfect(now, PerfectKind.ZONE, out) }
            }
            is app.playdrive.model.NaviSignal.Remaining -> if (inDrive && !finalStretch && s.distanceM <= PlayRules.FINAL_STRETCH_M) { finalStretch = true; out += PlayEvent.FinalStretch(now) }
            app.playdrive.model.NaviSignal.Arrived -> if (inDrive && !arrived) {
                arrived = true
                val wrap = if (mode == PlayMode.RUNNING && songBars >= PlayRules.ARRIVAL_MIN_BARS && gauge > 0) songResult(completed = true).also { songs += it } else null
                mode = PlayMode.IDLE
                out += PlayEvent.Arrived(now, wrap)
            }
        }
    }

    /** 제한속도: 넘는 동안 경고(고음 줄임), 5초 넘게 이어지면 악기 −1(그 뒤 5초마다). 보호구역 안에서 한 번이라도 넘으면 구간 통과 판정 없음. */
    private fun judgeSpeed(state: DrivingState, now: Long, out: MutableList<PlayEvent>) {
        val over = limitKmh > 0 && state.speedMps * 3.6f > limitKmh + 0.5f
        val since = overSinceMs
        if (over && since == null) { overSinceMs = now; if (inZone) zoneClean = false; out += PlayEvent.OverSpeed(now, true) }
        else if (!over && since != null) { overSinceMs = null; out += PlayEvent.OverSpeed(now, false) }
        else if (over && since != null && now - since >= PlayRules.OVER_SPEED_MS) { overSinceMs = now; miss(now, MissKind.OVER_SPEED, out) }
    }

    private fun startSong(now: Long, g: Int, out: MutableList<PlayEvent>) {
        mode = PlayMode.RUNNING
        songStartMs = now; songBars = 0; gaugeSum = 0; songLongest = 0; songPerfects = 0; dangerSaidThisSong = false
        smoothSinceMs = now
        setGauge(now, g, loss = false, out)
        out += PlayEvent.SongStart(now, songNumber, attempt, g)
    }

    private fun runSong(now: Long, out: MutableList<PlayEvent>) {
        // 곡 마디: 마디마다 게이지를 기록(평균 남은 악기 비율), 곡 안 최장 연속 마디
        val bars = ((now - songStartMs) / barMs).toInt()
        while (songBars < bars && songBars < PlayRules.SONG_BARS) {
            songBars++
            gaugeSum += gauge
            val inSongCombo = ((now - maxOf(comboSinceMs, songStartMs)) / barMs).toInt()
            songLongest = maxOf(songLongest, minOf(inSongCombo, songBars))
        }
        // 흔들림 없이 4마디마다 +1
        if (now - smoothSinceMs >= PlayRules.GAIN_EVERY_BARS * barMs) {
            smoothSinceMs += PlayRules.GAIN_EVERY_BARS * barMs
            if (gauge < PlayRules.MAX_GAUGE) setGauge(now, gauge + 1, loss = false, out)
        }
        if (songBars >= PlayRules.SONG_BARS) {
            val r = songResult(completed = true)
            songs += r
            out += PlayEvent.SongComplete(now, r)
            songNumber++; attempt = 1
            startSong(now, PlayRules.START_GAUGE, out)
        }
    }

    private fun songResult(completed: Boolean): SongResult {
        val avg = if (songBars == 0) gauge.toFloat() / PlayRules.MAX_GAUGE else gaugeSum.toFloat() / (songBars * PlayRules.MAX_GAUGE)
        val score = PlayRules.score(avg, songLongest, songPerfects)
        return SongResult(songNumber, attempt, completed, score, PlayRules.grade(score, completed), avg, songLongest, songPerfects)
    }

    private fun setGauge(now: Long, g: Int, loss: Boolean, out: MutableList<PlayEvent>) {
        val prev = gauge
        gauge = g.coerceIn(0, PlayRules.MAX_GAUGE)
        if (gauge != prev || !loss) out += PlayEvent.Gauge(now, gauge, loss)
        if (gauge == 1 && prev != 1) { out += PlayEvent.Danger(now, true, !dangerSaidThisSong); dangerSaidThisSong = true }
        else if (prev == 1 && gauge != 1) out += PlayEvent.Danger(now, false, false)
    }

    private fun countIn(now: Long, out: MutableList<PlayEvent>) {
        mode = PlayMode.COUNT_IN; countInGauge = PlayRules.RETRY_GAUGE
        countInUntil = now + (PlayRules.COUNT_IN_BARS * barMs).toLong()
        attempt++
        out += PlayEvent.CountIn(now, songNumber)
    }

    private fun miss(now: Long, kind: MissKind, out: MutableList<PlayEvent>) {
        if (!inDrive) return
        misses++
        out += PlayEvent.Miss(now, kind, combo)
        combo = 0; comboSinceMs = now
        startClean = false; missInDecel = true
        safeSegmentM = 0f   // 흔들림이 있던 구간의 거리는 해금에 넣지 않는다
        when (mode) {
            PlayMode.RUNNING -> {
                smoothSinceMs = now
                val warm = now - driveStartMs < PlayRules.WARMUP_BARS * barMs
                val floor = if (warm) 1 else 0
                setGauge(now, maxOf(floor, gauge - PlayRules.loss(kind)), loss = true, out)
                if (gauge == 0) fail(now, out)
            }
            PlayMode.FAILED -> failedSmoothSince = now
            else -> Unit
        }
    }

    private fun fail(now: Long, out: MutableList<PlayEvent>) {
        // 마지막 마디까지의 기록으로 이 시도를 '미완주'로 남긴다
        val r = songResult(completed = false)
        songs += r
        mode = PlayMode.FAILED
        failedSmoothSince = now; stoppedSinceFail = false
        out += PlayEvent.Failed(now, r)
    }

    private fun perfect(now: Long, kind: PerfectKind, out: MutableList<PlayEvent>) {
        perfects[kind] = perfects.getValue(kind) + 1
        out += PlayEvent.Perfect(now, kind)
        safeTotalM += safeSegmentM; safeSegmentM = 0f
        if (mode == PlayMode.RUNNING) {
            songPerfects++
            if (gauge < PlayRules.MAX_GAUGE) setGauge(now, gauge + 1, loss = false, out)
        }
    }

    private fun judgeStartAndStop(state: DrivingState, now: Long, out: MutableList<PlayEvent>) {
        startWindowEnd?.let { end ->
            if (abs(state.accelMps2) > PlayRules.START_MAX_MPS2) startClean = false
            if (now >= end) { if (startClean) perfect(now, PerfectKind.START, out); startWindowEnd = null }
        }
        val v = state.speedMps
        if (v >= PlayRules.MOVE_AGAIN_MPS) stopArmed = true
        if (state.accelMps2 < 0f) {
            if (!decelActive) { decelActive = true; decelMax = 0f; missInDecel = false }
            decelMax = maxOf(decelMax, -state.accelMps2)
        } else if (state.accelMps2 > 0.05f && v >= PlayRules.MOVE_AGAIN_MPS) {
            decelActive = false
        }
        if (stopArmed && v < PlayRules.STOP_SPEED_MPS) {
            stopArmed = false
            if (decelActive && decelMax <= PlayRules.STOP_MAX_MPS2 && !missInDecel) perfect(now, PerfectKind.STOP, out)
            decelActive = false
        }
    }

    private fun finish(): DriveResult {
        inDrive = false; mode = PlayMode.IDLE; gauge = 0
        safeTotalM += safeSegmentM; safeSegmentM = 0f
        // 주차할 때 연주 중이던 곡(완주도 실패도 아님)은 세지 않는다
        return DriveResult(
            startEpochMs = startEpoch, endEpochMs = clock(), songs = songs.toList(), maxCombo = maxCombo,
            perfects = perfects.toMap(), misses = misses, rained = rained, safeDistanceM = safeTotalM,
            movingSeconds = (movingMs / 1000).toInt(), stops = stops, demoZoneData = demoZone,
        )
    }
}
