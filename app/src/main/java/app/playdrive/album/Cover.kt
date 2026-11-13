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
            AlbumNaming.PartOfDay.EVENING -> 0xFF1F2F5C.toInt()   // 노을 = 남색(무드 화면과 같은 팔레트)
            AlbumNaming.PartOfDay.NIGHT -> 0xFF101826.toInt()
        }
    }

    fun circleColor(smooth: Float) = AlbumNaming.lerpColor(ORANGE, TEAL, smooth.coerceIn(0f, 1f))

    const val MAX_LIGHTS = 5

    /**
     * 주행 시간 → 빛이 퍼지는 반경(커버 한 변 기준). 짧은 주행은 가운데 모이고, 길수록 퍼진다.
     * 1분 ≈ 0.11, 10분 ≈ 0.25, 30분 이상 ≈ 0.33.
     */
    fun spread(drivenSeconds: Float): Float {
        val k = (kotlin.math.ln(1f + drivenSeconds.coerceAtLeast(0f) / 60f) / kotlin.math.ln(31f)).coerceIn(0f, 1f)
        return 0.06f + 0.27f * k
    }

    /**
     * n번째 구간의 겹. 같은 주행(seed = 주행 ID)이면 항상 같은 자리.
     *  - 위치: 주행마다 다른 시작 각도에서 황금각 나선. 중심에서의 거리 = 이 구간이 끝날 때까지 달린 시간의 [spread]
     *    (이미 그린 겹은 움직이지 않는다 → 정차 때 새 겹만 더해짐)
     *  - 크기·선명도: 부드러울수록 크고 또렷, 분주할수록 작고 흐림
     */
    fun layer(index: Int, seed: Long, smooth: Float, seconds: Float, rainFraction: Float, breaks: Int, elapsedBefore: Float = 0f): CoverLayer {
        val base = Random(seed).nextFloat() * 6.2831855f
        val rnd = Random(seed * 31 + index)
        val angle = base + index * 2.39996f + (rnd.nextFloat() - 0.5f) * 0.7f
        val reach = spread(elapsedBefore + seconds)
        val dist = if (index == 0) reach * (0.15f + 0.25f * rnd.nextFloat()) else reach * (0.6f + 0.4f * rnd.nextFloat())
        val x = (0.5f + dist * cos(angle)).coerceIn(0.15f, 0.85f)
        val y = (0.46f + dist * 0.85f * sin(angle)).coerceIn(0.15f, 0.72f)
        val s = smooth.coerceIn(0f, 1f)
        val r = R_MIN + R_RANGE * s * (seconds / 30f).coerceIn(0.4f, 1f)
        val rain = if (rainFraction <= 0f) 0 else (6 + 14 * rainFraction.coerceIn(0f, 1f)).toInt()
        return CoverLayer(x, y, r, circleColor(s), rain, breaks.coerceIn(0, 3), rnd.nextInt())
    }

    const val R_MIN = 0.045f
    const val R_RANGE = 0.15f

    /** 0 = 흐림, 1 = 또렷(크기로부터). */
    fun sharpness(l: CoverLayer) = ((l.r - R_MIN) / R_RANGE).coerceIn(0f, 1f)

    /** 6번째 정차부터는 빛을 늘리지 않고 기존 빛에 합친다(빛 1~5개). */
    fun merge(into: CoverLayer, add: CoverLayer) = into.copy(
        r = maxOf(into.r, add.r), rain = maxOf(into.rain, add.rain), scars = (into.scars + add.scars).coerceAtMost(3),
    )

    /**
     * 요약만 있을 때(예시 앨범·겹 기록 이전 앨범): 주행을 정차 횟수만큼 구간으로 나눠 겹을 만든다.
     * 구간 길이·부드러움은 주행 ID로 고정한 난수로 조금씩 흔든다.
     */
    fun layersFor(id: Long, stops: Int, smooth: Float, drivenSeconds: Int, rainFraction: Float, breaks: Int): List<CoverLayer> {
        val n = stops.coerceIn(1, MAX_LIGHTS)
        val rnd = Random(id xor 0x5EED)
        val w = List(n) { 0.6f + rnd.nextFloat() }
        val sum = w.sum()
        var before = 0f
        val breakAt = List(breaks.coerceAtLeast(0)) { rnd.nextInt(n) }
        val rainy = if (rainFraction <= 0f) emptySet() else (0 until n).filter { rnd.nextFloat() < rainFraction.coerceIn(0.3f, 1f) || it == n - 1 }.toSet()
        return List(n) { i ->
            val sec = drivenSeconds * w[i] / sum
            val sm = (smooth + (rnd.nextFloat() - 0.5f) * 0.2f).coerceIn(0f, 1f)
            layer(i, id, sm, sec, if (i in rainy) rainFraction.coerceAtLeast(0.4f) else 0f, breakAt.count { it == i }, before).also { before += sec }
        }
    }

    fun spec(id: Long, layers: List<CoverLayer>, tz: TimeZone = TimeZone.getDefault()): CoverSpec {
        val smooth = if (layers.isEmpty()) 0.6f else layers.map { sharpness(it) }.average().toFloat().coerceIn(0f, 1f)
        val glowBase = circleColor(smooth)
        val scarWave = (layers.sumOf { it.scars } * 0.15f).coerceAtMost(0.5f)
        return CoverSpec(id, background(id, tz), (glowBase and 0x00FFFFFF) or 0x55000000, layers, ((1f - smooth) * 0.6f + scarWave).coerceIn(0f, 1f))
    }
}

/**
 * 주행하며 커버를 계속 그려 두는 기록기(순수 Kotlin). 화면 반영은 UI가 정차 때만 한다.
 * - 첫 출발: 새 커버(이전 겹 지움)
 * - 정차(Stopped)·주차(Parked): 지난 구간을 한 겹으로 닫는다(2초 이상 달린 구간만)
 * - 빛은 1~5개: 6번째 정차부터는 기존 빛에 합친다
 * - 주차 후에도 겹은 남아 있다 → 그대로 드라이브 앨범의 새 앨범 커버가 된다
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
    private var segments = 0
    private var drivenMs = 0L

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
                if (!inDrive) { inDrive = true; id = clock(); done.clear(); segments = 0; drivenMs = 0; changed = true }
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
        val l = CoverArt.layer(segments, id ?: 0L, smooth, movingMs / 1000f, rainMs.toFloat() / movingMs, breaks, drivenMs / 1000f)
        if (done.size < CoverArt.MAX_LIGHTS) done += l else { val k = segments % CoverArt.MAX_LIGHTS; done[k] = CoverArt.merge(done[k], l) }
        segments++; drivenMs += movingMs
        return true
    }

    fun spec(tz: TimeZone = TimeZone.getDefault()): CoverSpec? = id?.let { CoverArt.spec(it, layers, tz) }
}
