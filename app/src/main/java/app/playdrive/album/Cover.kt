package app.playdrive.album

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 커버의 한 겹 = 출발에서 다음 정차까지 한 구간.
 *  - 둥근 원: 부드럽게 오래 달릴수록 크고 청록, 분주할수록 작고 주황
 *  - rain: 그 구간에 비가 온 정도만큼 빗줄기
 *  - scars: 깨진 순간(급가속·급제동 = 테이프스톱) 수만큼 물결이 흐트러진 자국
 * 좌표·크기는 커버 한 변 기준 0~1.
 */
data class CoverLayer(
    val x: Float, val y: Float, val r: Float,
    val color: Int,
    val rain: Int,
    val scars: Int,
    val seed: Int,
)

/** 커버 그림 사양(색은 0xAARRGGBB). id = 주행 시작 시각(같은 커버인지 구분). */
data class CoverSpec(
    val id: Long,
    val background: Int,
    val glow: Int,
    val layers: List<CoverLayer>,
    /** 바탕 물결 출렁임: 0 = 잔잔, 1 = 출렁임. */
    val wave: Float,
) {
    val rainLines: Int get() = layers.sumOf { it.rain }
    val scars: Int get() = layers.sumOf { it.scars }
}

object CoverArt {
    private const val ORANGE = 0xFFE59A5C.toInt()
    private const val TEAL = 0xFF6CC3B5.toInt()

    fun background(startEpochMs: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(tz).apply { timeInMillis = startEpochMs }
        return when (AlbumNaming.partOfDay(c.get(Calendar.HOUR_OF_DAY))) {
            AlbumNaming.PartOfDay.DAWN -> 0xFF1B2440.toInt()
            AlbumNaming.PartOfDay.MORNING -> 0xFFF2D7B6.toInt()
            AlbumNaming.PartOfDay.DAY -> 0xFFBFD9E8.toInt()
            AlbumNaming.PartOfDay.EVENING -> 0xFF5A3F63.toInt()
            AlbumNaming.PartOfDay.NIGHT -> 0xFF101826.toInt()
        }
    }

    fun circleColor(smooth: Float) = AlbumNaming.lerpColor(ORANGE, TEAL, smooth.coerceIn(0f, 1f))

    /** n번째 구간의 겹: 위치는 황금각 나선으로 퍼뜨려 겹이 한곳에 몰리지 않게. */
    fun layer(index: Int, seed: Long, smooth: Float, seconds: Float, rainFraction: Float, breaks: Int): CoverLayer {
        val rnd = Random(seed * 31 + index)
        val angle = index * 2.39996f + rnd.nextFloat() * 0.6f
        val dist = if (index == 0) 0.04f + rnd.nextFloat() * 0.06f else 0.14f + ((index * 0.37f) % 1f) * 0.16f
        val x = (0.5f + dist * cos(angle)).coerceIn(0.18f, 0.82f)
        val y = (0.45f + dist * 0.9f * sin(angle)).coerceIn(0.18f, 0.72f)
        val s = smooth.coerceIn(0f, 1f)
        val r = 0.06f + 0.16f * s * (seconds / 30f).coerceIn(0.3f, 1f)
        val rain = if (rainFraction <= 0f) 0 else (6 + 14 * rainFraction.coerceIn(0f, 1f)).toInt()
        return CoverLayer(x, y, r, circleColor(s), rain, breaks.coerceIn(0, 3), rnd.nextInt())
    }

    fun spec(id: Long, layers: List<CoverLayer>, tz: TimeZone = TimeZone.getDefault()): CoverSpec {
        val smooth = if (layers.isEmpty()) 0.6f else layers.map { (it.r - 0.06f) / 0.16f }.average().toFloat().coerceIn(0f, 1f)
        val glowBase = circleColor(smooth)
        val scarWave = (layers.sumOf { it.scars } * 0.15f).coerceAtMost(0.5f)
        return CoverSpec(id, background(id, tz), (glowBase and 0x00FFFFFF) or 0x55000000, layers, ((1f - smooth) * 0.6f + scarWave).coerceIn(0f, 1f))
    }
}

/**
 * 주행하며 커버를 계속 그려 두는 기록기(순수 Kotlin). 화면 반영은 UI가 정차 때만 한다.
 * - 첫 출발: 새 커버(이전 겹 지움)
 * - 정차(Stopped)·주차(Parked): 지난 구간을 한 겹으로 닫는다(2초 이상 달린 구간만)
 * - 주차 후에도 겹은 남아 있다 → 그대로 마음 앨범의 새 앨범 커버가 된다
 */
class CoverSketch(private val clock: () -> Long) {
    var id: Long? = null; private set
    private val done = mutableListOf<CoverLayer>()
    val layers: List<CoverLayer> get() = done.toList()
    private var inDrive = false
    private var segOpen = false
    private var harmonySum = 0.0
    private var harmonyN = 0
    private var movingMs = 0L
    private var rainMs = 0L
    private var breaks = 0
    private var lastMs: Long? = null

    /** 새 겹이 생겼거나 새 커버가 시작되면 true. */
    fun update(state: DrivingState, events: List<DrivingEvent>): Boolean {
        var changed = false
        val dt = lastMs?.let { (state.timeMs - it).coerceIn(0, 1000) } ?: 0
        lastMs = state.timeMs
        if (segOpen && state.moving) {
            harmonySum += state.harmony; harmonyN++; movingMs += dt
            if (state.raining) rainMs += dt
        }
        for (e in events) when (e) {
            is DrivingEvent.Departed -> {
                if (!inDrive) { inDrive = true; id = clock(); done.clear(); changed = true }
                openSegment()
            }
            is DrivingEvent.HarshBraking, is DrivingEvent.HarshAcceleration -> if (segOpen) breaks++
            is DrivingEvent.Stopped -> changed = closeSegment() || changed
            is DrivingEvent.Parked -> { changed = closeSegment() || changed; inDrive = false }
            else -> {}
        }
        return changed
    }

    private fun openSegment() { segOpen = true; harmonySum = 0.0; harmonyN = 0; movingMs = 0; rainMs = 0; breaks = 0 }

    private fun closeSegment(): Boolean {
        if (!segOpen) return false
        segOpen = false
        if (movingMs < 2_000) return false
        val smooth = if (harmonyN == 0) 0.5f else (harmonySum / harmonyN / 100.0).toFloat()
        done += CoverArt.layer(done.size, id ?: 0L, smooth, movingMs / 1000f, rainMs.toFloat() / movingMs, breaks)
        return true
    }

    fun spec(tz: TimeZone = TimeZone.getDefault()): CoverSpec? = id?.let { CoverArt.spec(it, layers, tz) }
}
