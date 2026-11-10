package app.playdrive.ui

import app.playdrive.album.AlbumNaming
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
)

/**
 * 무드 화면의 상태 기계(순수 Kotlin, 시간은 밖에서 넣는다).
 *
 * Android 차량 앱 품질 기준 SA-1(미디어 앱은 주행 중 움직이는 그래픽 금지, 주차 중만 예외)에 따라:
 *  - restricted(주행 중, requiresDO=true): 화면이 **완전히 멈춘다**. 색·크기·물결 변화 0.
 *    음악 상태가 바뀌어도 화면에 반영하지 않고 마지막 것만 [pending]에 보관한다.
 *  - restricted가 풀리는 순간(정차): 보관한 상태로 부드럽게 전환하고, 숨 쉬는 움직임을 멈췄던 위치에서 이어간다.
 */
class MoodScene(
    startMs: Long,
    initial: MoodState = MoodState(0.2f, 0.5f, 0f, false),
    private val fadeMs: Long = 3_000,
    private val breathCycleMs: Long = 12_000,
) {
    private var from = initial
    private var to = initial
    private var fadeStart = Long.MIN_VALUE / 2
    private var breathAccumMs = 0L
    private var breathResumedAt: Long? = startMs
    var pending: MoodState? = null; private set
    var restricted = false; private set

    /** 정차 중에는 계속 다시 그려야 한다(숨 쉬기). 주행 중에는 다시 그릴 필요가 없다. */
    val animating: Boolean get() = !restricted

    fun setRestricted(r: Boolean, nowMs: Long) {
        if (r == restricted) return
        if (r) {
            val cur = mood(nowMs)
            from = cur; to = cur; fadeStart = Long.MIN_VALUE / 2
            breathResumedAt?.let { breathAccumMs += nowMs - it }
            breathResumedAt = null
        } else {
            breathResumedAt = nowMs
            pending?.let { pending = null; startFade(it, nowMs) }
        }
        restricted = r
    }

    /** 새 음악 상태. 주행 중이면 보관만 한다. immediate = 전환 없이 바로(정차 중 디버그·스크린샷용). */
    fun show(s: MoodState, nowMs: Long, immediate: Boolean = false) {
        when {
            restricted -> pending = s
            immediate -> { from = s; to = s; fadeStart = Long.MIN_VALUE / 2 }
            else -> startFade(s, nowMs)
        }
    }

    private fun startFade(s: MoodState, nowMs: Long) { from = mood(nowMs); to = s; fadeStart = nowMs }

    fun mood(nowMs: Long): MoodState {
        val t = ((nowMs - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
        if (t >= 1f) return to
        return MoodState(lerp(from.energy, to.energy, t), lerp(from.calm, to.calm, t), lerp(from.brightness, to.brightness, t), if (t > 0.5f) to.rain else from.rain)
    }

    fun breathPhase(nowMs: Long): Float {
        val el = breathAccumMs + (breathResumedAt?.let { nowMs - it } ?: 0L)
        return (el % breathCycleMs).toFloat() / breathCycleMs
    }

    fun frame(nowMs: Long): MoodFrame {
        val s = mood(nowMs)
        val phase = breathPhase(nowMs)
        val bg = AlbumNaming.lerpColor(0xFF0E1420.toInt(), 0xFF1A2B3A.toInt(), s.brightness * 0.6f + if (s.rain) 0.2f else 0f)
        val tone = AlbumNaming.lerpColor(0xFFE59A5C.toInt(), 0xFF6CC3B5.toInt(), s.calm)
        val motion = sin(2 * PI * phase).toFloat() * 0.04f
        val circles = (2 downTo 0).map { i ->
            val r = BASE_RADIUS * (1f + i * (0.35f + 0.35f * s.energy)) * (1f + motion * (i + 1))
            r to withAlpha(tone, (90 - i * 25).coerceAtLeast(20))
        }
        val amp = (0.01f + 0.04f * (1f - s.calm)) * (0.5f + s.energy)
        return MoodFrame(bg, circles, tone, withAlpha(tone, 70), amp, phase, s.rain)
    }

    companion object {
        const val BASE_RADIUS = 0.18f
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0x00FFFFFF)
    }
}
