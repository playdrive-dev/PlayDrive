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
    /** 주행하며 그린 커버의 겹(CoverSketch). 비어 있으면 요약으로 커버를 만든다(이전 앨범). */
    val layers: List<CoverLayer> = emptyList(),
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

    private val ordinals = listOf("두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")

    /**
     * 같은 날·같은 시간대에 제목이 겹치면 두 번째부터 "… 빗속의 두 번째 길"처럼 구분한다(숫자 없이).
     * existing = 이미 저장된 앨범들(순서 무관). 이 주행보다 먼저 시작한 같은 제목만 센다.
     */
    fun uniqueTitle(s: DriveSummary, existing: List<DriveSummary>, tz: TimeZone = TimeZone.getDefault()): String {
        val base = title(s, tz)
        val day = dayKey(s.startEpochMs, tz)
        val before = existing.count { it.startEpochMs < s.startEpochMs && dayKey(it.startEpochMs, tz) == day && title(it, tz) == base }
        if (before == 0) return base
        val head = base.substringBeforeLast(' ') // "화요일 아침, 빗속의"
        val nth = ordinals.getOrNull(before - 1)?.let { "$it 번째 길" } ?: "또 다른 길"
        return "$head $nth"
    }

    private fun dayKey(ms: Long, tz: TimeZone): Int {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** 상세 화면의 날짜 한 줄: "11월 10일 화요일 · 오후 9:10". */
    fun dateLine(ms: Long, tz: TimeZone = TimeZone.getDefault(), sep: String = " · "): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일 ${days[c.get(Calendar.DAY_OF_WEEK) - 1]}$sep$ampm $h12:${"%02d".format(c.get(Calendar.MINUTE))}"
    }

    /** 앨범 커버: 주행 중 그린 겹이 있으면 그것, 없으면 요약으로 만든 커버. */
    fun cover(a: Album, tz: TimeZone = TimeZone.getDefault()): CoverSpec =
        if (a.layers.isNotEmpty()) CoverArt.spec(a.summary.startEpochMs, a.layers, tz) else cover(a.summary, tz)

    /** 요약만 있을 때의 커버(겹 기록 이전 앨범): 정차 횟수는 주행 시간으로 어림한다(약 7분에 한 번, 1~5). */
    fun cover(s: DriveSummary, tz: TimeZone = TimeZone.getDefault()): CoverSpec {
        val stops = (s.movingSeconds / 420 + 1).coerceIn(1, CoverArt.MAX_LIGHTS)
        val layers = CoverArt.layersFor(s.startEpochMs, stops, (s.smoothness / 100f).coerceIn(0f, 1f), s.movingSeconds, if (s.rained) 0.7f else 0f, 0)
        return CoverArt.spec(s.startEpochMs, layers, tz)
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, sh: Int) = (c ushr sh) and 0xFF
        fun mix(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * t).toInt().coerceIn(0, 255) shl sh
        return mix(24) or mix(16) or mix(8) or mix(0)
    }
}

/** 무드 그래픽 상태(순수). 주행 중에는 화면에 반영되지 않는다(ui/MoodScene, SA-1). */
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

/** 무드 상태 변화 거르기: 눈에 띄게 달라졌을 때만 화면(MoodScene)에 넘긴다. 주행 중 정지는 MoodScene이 맡는다. */
class MoodThrottle {
    var shown: MoodState? = null; private set

    fun offer(s: MoodState): MoodState? {
        val cur = shown
        if (cur == null || cur.differsFrom(s)) { shown = s; return s }
        return null
    }

    private fun MoodState.differsFrom(o: MoodState) =
        kotlin.math.abs(energy - o.energy) > 0.1f || kotlin.math.abs(calm - o.calm) > 0.1f ||
            kotlin.math.abs(brightness - o.brightness) > 0.15f || rain != o.rain
}
