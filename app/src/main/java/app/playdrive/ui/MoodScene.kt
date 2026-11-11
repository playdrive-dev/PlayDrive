package app.playdrive.ui

import app.playdrive.album.AlbumNaming
import app.playdrive.album.CoverSpec
import app.playdrive.album.MoodState
import kotlin.math.PI
import kotlin.math.sin

/** 무드 화면 한 프레임(순수 데이터). 길이는 화면 짧은 변(반지름) / 높이(물결) 기준 비율. */
data class MoodFrame(
    val background: Int,
    /** 바깥 → 안쪽 순서의 (반지름 비율, 중심 색 ARGB). 가장자리는 투명으로 번진다. */
    val circles: List<Pair<Float, Int>>,
    val tone: Int,
    val waveColor: Int,
    val waveAmp: Float,
    val wavePhase: Float,
    val rain: Boolean,
    /** 배경에 은은하게 깔리는 오늘의 커버(없으면 null). */
    val cover: CoverSpec?,
    /** 커버 불투명도(평소 은은하게, 새 겹이 추가되는 장면에서 잠깐 또렷하게). */
    val coverAlpha: Float,
    /** 이 번호부터의 겹이 지금 그려지는 중. */
    val revealFrom: Int,
    /** 새 겹이 그려진 정도 0~1. */
    val reveal: Float,
    /** 무드 원·물결 불투명도 배수(커버 장면 동안 잠깐 옅게). */
    val moodAlpha: Float,
)

/**
 * 무드 화면의 상태 기계(순수 Kotlin, 시간은 밖에서 넣는다). "운전 중엔 귀로, 멈추면 눈으로".
 *
 * Android 차량 앱 품질 기준 SA-1(미디어 앱은 주행 중 움직이는 그래픽 금지, 주차 중만 예외)에 따라:
 *  - restricted(주행 중, requiresDO=true): **장면 시계가 멈춘다** → 색·크기·물결·커버 모두 변화 0.
 *    음악 상태와 커버의 새 겹은 화면에 반영하지 않고 보관([pending], [pendingCover]).
 *  - restricted가 풀리는 순간(정차): 장면 시계가 멈췄던 곳에서 다시 흐르고,
 *    보관한 음악 상태로 3초 전환 + 지난 구간이 커버에 한 겹 추가되는 장면([REVEAL_MS])을 보여준다.
 */
class MoodScene(
    startMs: Long,
    initial: MoodState = MoodState(0.2f, 0.5f, 0f, false),
    private val fadeMs: Long = 3_000,
    private val breathCycleMs: Long = 12_000,
) {
    // ---- 장면 시계: 주행 중에는 멈춘다
    private val origin = startMs
    private var pausedTotal = 0L
    private var frozenAt: Long? = null
    private fun st(nowMs: Long) = (frozenAt ?: nowMs) - pausedTotal

    private var from = initial
    private var to = initial
    private var fadeStart = Long.MIN_VALUE / 2
    var pending: MoodState? = null; private set

    private var cover: CoverSpec? = null
    var pendingCover: CoverSpec? = null; private set
    private var revealFrom = 0
    private var revealStart = Long.MIN_VALUE / 2

    val restricted: Boolean get() = frozenAt != null

    /** 정차 중에는 계속 다시 그려야 한다(숨 쉬기). 주행 중에는 다시 그릴 필요가 없다. */
    val animating: Boolean get() = !restricted

    fun setRestricted(r: Boolean, nowMs: Long) {
        if (r == restricted) return
        if (r) { frozenAt = nowMs; return }
        pausedTotal += nowMs - frozenAt!!
        frozenAt = null
        val t = st(nowMs)
        pending?.let { pending = null; startFade(it, t) }
        pendingCover?.let { pendingCover = null; applyCover(it, t) }
    }

    /** 새 음악 상태. 주행 중이면 보관만 한다. immediate = 전환 없이 바로(정차 중 디버그·스크린샷용). */
    fun show(s: MoodState, nowMs: Long, immediate: Boolean = false) {
        when {
            restricted -> pending = s
            immediate -> { from = s; to = s; fadeStart = Long.MIN_VALUE / 2 }
            else -> startFade(s, st(nowMs))
        }
    }

    /** 오늘의 커버(주행하며 그린 것). 주행 중이면 보관만, 정차 중이면 새 겹을 그리는 장면으로. immediate = 장면 없이(시작 화면). */
    fun setCover(c: CoverSpec?, nowMs: Long, immediate: Boolean = false) {
        when {
            restricted -> pendingCover = c
            c == null || immediate -> { cover = c; revealFrom = c?.layers?.size ?: 0; revealStart = Long.MIN_VALUE / 2 }
            else -> applyCover(c, st(nowMs))
        }
    }

    private fun applyCover(c: CoverSpec, t: Long) {
        val old = cover
        val shown = if (old != null && old.id == c.id) old.layers.size.coerceAtMost(c.layers.size) else 0
        cover = c
        if (c.layers.size > shown) { revealFrom = shown; revealStart = t } else revealFrom = c.layers.size
    }

    private fun startFade(s: MoodState, t: Long) { from = moodAt(t); to = s; fadeStart = t }

    private fun moodAt(t: Long): MoodState {
        val k = ((t - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
        if (k >= 1f) return to
        return MoodState(lerp(from.energy, to.energy, k), lerp(from.calm, to.calm, k), lerp(from.brightness, to.brightness, k), if (k > 0.5f) to.rain else from.rain)
    }

    fun mood(nowMs: Long): MoodState = moodAt(st(nowMs))

    fun breathPhase(nowMs: Long): Float = ((st(nowMs) - origin).mod(breathCycleMs)).toFloat() / breathCycleMs

    /** 커버 장면 진행: 0~0.4초 커버가 또렷해지고, 0.4~2.8초 새 겹이 그려지고, 3~4초 다시 은은하게. */
    private fun revealEnvelope(t: Long): Pair<Float, Float> {
        val e = (t - revealStart).toFloat()
        if (e < 0 || e >= REVEAL_MS) return 0f to 1f
        val bright = when {
            e < 400 -> e / 400
            e < 3_000 -> 1f
            else -> 1f - (e - 3_000) / 1_000
        }
        val drawn = ((e - 400) / 2_400).coerceIn(0f, 1f)
        return bright to drawn
    }

    fun frame(nowMs: Long): MoodFrame {
        val t = st(nowMs)
        val s = moodAt(t)
        val phase = breathPhase(nowMs)
        val bg = AlbumNaming.lerpColor(0xFF0E1420.toInt(), 0xFF1A2B3A.toInt(), s.brightness * 0.6f + if (s.rain) 0.2f else 0f)
        val tone = AlbumNaming.lerpColor(0xFFE59A5C.toInt(), 0xFF6CC3B5.toInt(), s.calm)
        val motion = sin(2 * PI * phase).toFloat() * 0.04f
        val circles = (2 downTo 0).map { i ->
            val r = BASE_RADIUS * (1f + i * (0.35f + 0.35f * s.energy)) * (1f + motion * (i + 1))
            r to withAlpha(tone, (90 - i * 25).coerceAtLeast(20))
        }
        val amp = (0.01f + 0.04f * (1f - s.calm)) * (0.5f + s.energy)
        val (bright, drawn) = revealEnvelope(t)
        val c = cover
        val inReveal = c != null && revealFrom < c.layers.size && t >= revealStart && t - revealStart < REVEAL_MS
        return MoodFrame(
            bg, circles, tone, withAlpha(tone, 70), amp, phase, s.rain,
            cover = c,
            coverAlpha = COVER_ALPHA + (COVER_ALPHA_REVEAL - COVER_ALPHA) * bright,
            revealFrom = if (inReveal) revealFrom else (c?.layers?.size ?: 0),
            reveal = if (inReveal) drawn else 1f,
            moodAlpha = 1f - 0.65f * bright,
        )
    }

    companion object {
        const val BASE_RADIUS = 0.18f
        const val REVEAL_MS = 4_000L
        const val COVER_ALPHA = 0.22f
        const val COVER_ALPHA_REVEAL = 0.6f
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0x00FFFFFF)
    }
}
