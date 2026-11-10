package app.playdrive.album

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/** 주행 한 번의 요약(점수는 저장하지 않고 '부드러움' 정도만 내부적으로 쓴다). */
data class DriveSummary(
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** 주행 중 하모니 평균(0~100) — 화면에 숫자로 보여주지 않는다. */
    val smoothness: Float,
    val rained: Boolean,
    val movingSeconds: Int,
)

/** 마음 앨범 한 장 = 주행 한 번. */
data class Album(
    val id: Long,
    val summary: DriveSummary,
    val title: String,
    val reflection: String = "",
)

/**
 * 주행 기록기(순수 Kotlin). DriveController가 매 업데이트마다 부르고,
 * 출발 후 P단이 되면 요약을 돌려준다.
 */
class DriveRecorder(private val clock: () -> Long) {
    private var startEpoch: Long? = null
    private var harmonySum = 0.0
    private var harmonyN = 0
    private var rained = false
    private var movingMs = 0L
    private var lastMs: Long? = null

    val inDrive: Boolean get() = startEpoch != null

    /** 주행이 끝나면(출발 후 P단) 요약, 아니면 null. */
    fun update(state: DrivingState, events: List<DrivingEvent>): DriveSummary? {
        if (events.any { it is DrivingEvent.Departed } && startEpoch == null) {
            startEpoch = clock(); harmonySum = 0.0; harmonyN = 0; rained = false; movingMs = 0
        }
        val dt = lastMs?.let { (state.timeMs - it).coerceIn(0, 1000) } ?: 0
        lastMs = state.timeMs
        if (startEpoch != null) {
            if (state.moving) { harmonySum += state.harmony; harmonyN++; movingMs += dt }
            if (state.raining) rained = true
        }
        if (events.any { it is DrivingEvent.Parked } && state.gear == Gear.PARK) {
            val s = startEpoch ?: return null
            startEpoch = null
            return DriveSummary(s, clock(), if (harmonyN == 0) 50f else (harmonySum / harmonyN).toFloat(), rained, (movingMs / 1000).toInt())
        }
        return null
    }
}

/** '주차 후 1분' 제안 조건: P단 + 운전석만 착석 + 방금 주행을 마침. 동승자가 있으면 제안하지 않음. */
object BreathCoach {
    const val DURATION_MS = 60_000L
    const val INHALE_MS = 4_000L
    const val EXHALE_MS = 6_000L

    fun shouldSuggest(state: DrivingState, justFinishedDrive: Boolean): Boolean =
        justFinishedDrive && state.gear == Gear.PARK && state.driverPresent && state.solo

    /** 0~1: 원의 크기(들숨에 커지고 날숨에 작아짐). */
    fun phase(elapsedMs: Long): Pair<Boolean, Float> {
        val cycle = INHALE_MS + EXHALE_MS
        val t = elapsedMs % cycle
        return if (t < INHALE_MS) true to (t.toFloat() / INHALE_MS) else false to (1f - (t - INHALE_MS).toFloat() / EXHALE_MS)
    }
}

/** 앨범 제목·커버 생성(순수). 숫자·점수 없이 문장과 추상 그래픽으로만. */
object AlbumNaming {
    private val days = listOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")

    enum class PartOfDay(val word: String) { DAWN("새벽"), MORNING("아침"), DAY("한낮"), EVENING("저녁"), NIGHT("밤") }

    fun partOfDay(hour: Int): PartOfDay = when (hour) {
        in 0..4 -> PartOfDay.DAWN
        in 5..10 -> PartOfDay.MORNING
        in 11..16 -> PartOfDay.DAY
        in 17..19 -> PartOfDay.EVENING
        else -> PartOfDay.NIGHT
    }

    fun title(s: DriveSummary, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = s.startEpochMs }
        val day = days[c.get(Calendar.DAY_OF_WEEK) - 1]
        val part = partOfDay(c.get(Calendar.HOUR_OF_DAY))
        val mood = when {
            s.rained -> "빗속의"
            s.smoothness >= 70f -> "고요한"
            s.smoothness >= 45f -> "잔잔한"
            else -> "분주했던"
        }
        val trip = when (part) {
            PartOfDay.MORNING -> "출근길"
            PartOfDay.EVENING, PartOfDay.NIGHT -> "귀갓길"
            PartOfDay.DAWN -> "새벽길"
            PartOfDay.DAY -> "드라이브"
        }
        return "$day ${part.word}, $mood $trip"
    }

    /** 커버 그래픽 사양(색은 0xAARRGGBB). */
    data class CoverSpec(
        val background: Int,
        val glow: Int,
        val circles: List<Triple<Float, Float, Float>>, // (x, y, r) 0~1
        val circleColor: Int,
        val rainLines: Int,
        val wave: Float, // 0 = 잔잔, 1 = 출렁임
    )

    fun cover(s: DriveSummary, tz: TimeZone = TimeZone.getDefault()): CoverSpec {
        val c = Calendar.getInstance(tz).apply { timeInMillis = s.startEpochMs }
        val part = partOfDay(c.get(Calendar.HOUR_OF_DAY))
        val bg = when (part) {
            PartOfDay.DAWN -> 0xFF1B2440.toInt()
            PartOfDay.MORNING -> 0xFFF2D7B6.toInt()
            PartOfDay.DAY -> 0xFFBFD9E8.toInt()
            PartOfDay.EVENING -> 0xFF5A3F63.toInt()
            PartOfDay.NIGHT -> 0xFF101826.toInt()
        }
        val calm = (s.smoothness / 100f).coerceIn(0f, 1f)
        // 부드러울수록 차분한 청록, 분주할수록 따뜻한 주황
        val circleColor = lerpColor(0xFFE59A5C.toInt(), 0xFF6CC3B5.toInt(), calm)
        val glow = lerpColor(0x55E59A5C, 0x556CC3B5, calm)
        val rnd = Random(s.startEpochMs)
        val n = 3 + (calm * 3).toInt()
        val circles = List(n) { Triple(0.2f + rnd.nextFloat() * 0.6f, 0.2f + rnd.nextFloat() * 0.6f, 0.08f + rnd.nextFloat() * (0.12f + 0.12f * calm)) }
        return CoverSpec(bg, glow, circles, circleColor, if (s.rained) 24 else 0, 1f - calm)
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, sh: Int) = (c ushr sh) and 0xFF
        fun mix(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * t).toInt().coerceIn(0, 255) shl sh
        return mix(24) or mix(16) or mix(8) or mix(0)
    }
}

/**
 * 무드 그래픽 상태(순수). 주행 중(UX 제한)에는 최소 간격을 두고 천천히만 바뀌게 한다.
 */
data class MoodState(val energy: Float, val calm: Float, val brightness: Float, val rain: Boolean) {
    companion object {
        fun from(state: DrivingState, activeLayers: Int, totalLayers: Int) = MoodState(
            energy = (activeLayers.toFloat() / totalLayers.coerceAtLeast(1)).coerceIn(0f, 1f),
            calm = (state.harmony / 100f).coerceIn(0f, 1f),
            brightness = (state.speedMps * 3.6f / 60f).coerceIn(0f, 1f),
            rain = state.raining,
        )
    }
}

/** 주행 중 무드 그래픽 갱신 규칙: 최소 10초 간격, 전환은 3초 크로스페이드. */
class MoodThrottle(private val minIntervalMs: Long = 10_000L) {
    private var lastChangeMs = Long.MIN_VALUE / 2
    var shown: MoodState? = null; private set

    /** 새 상태를 보여줘야 하면 그 상태를, 아니면 null. restricted = 주행 중(UX 제한). */
    fun offer(s: MoodState, nowMs: Long, restricted: Boolean): MoodState? {
        val cur = shown
        if (cur == null) { shown = s; lastChangeMs = nowMs; return s }
        if (cur.differsFrom(s) && (!restricted || nowMs - lastChangeMs >= minIntervalMs)) {
            shown = s; lastChangeMs = nowMs; return s
        }
        return null
    }

    private fun MoodState.differsFrom(o: MoodState) =
        kotlin.math.abs(energy - o.energy) > 0.1f || kotlin.math.abs(calm - o.calm) > 0.1f ||
            kotlin.math.abs(brightness - o.brightness) > 0.15f || rain != o.rain
}
