package app.playdrive.ui

import app.playdrive.album.AlbumNaming
import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.CoverSpec
import app.playdrive.album.MoodState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 무드 화면 한 프레임(순수 데이터). 같은 프레임이면 화면도 같다(렌더러는 이것만 본다). */
data class MoodFrame(
    val background: Int,
    /** (Canvas 대체 렌더러용) 바깥 → 안쪽 순서의 (반지름 비율, 중심 색 ARGB). */
    val circles: List<Pair<Float, Int>>,
    val tone: Int,
    val waveColor: Int,
    val waveAmp: Float,
    val wavePhase: Float,
    val rain: Boolean,
    /** 오늘의 커버(없으면 null). 셰이더에선 겹마다 깊이가 다른 보조 빛. */
    val cover: CoverSpec?,
    val coverAlpha: Float,
    /** 이 번호부터의 겹이 지금 그려지는 중. */
    val revealFrom: Int,
    /** 새 겹이 그려진 정도 0~1. */
    val reveal: Float,
    /** 무드(메인 빛) 불투명도 배수(커버 장면 동안 잠깐 옅게). */
    val moodAlpha: Float,
    /** 커버 장면의 밝기 곡선 0~1(장면 밖에서는 0). */
    val bright: Float = 0f,
    /** 음악 상태(메인 빛의 크기·색·선명도). */
    val mood: MoodState = MoodState(0.2f, 0.5f, 0f, false),
    /** 시간대 색온도: from → to 를 mix 만큼. */
    val dayFrom: PartOfDay = PartOfDay.NIGHT,
    val dayTo: PartOfDay = PartOfDay.NIGHT,
    val dayMix: Float = 1f,
    /** 전체 빛의 세기: 웰컴(0→1, 3초) · 굿바이(→0.3, 2초) · 깨어남(→1). */
    val master: Float = 1f,
    /** 음악 박에 맞춘 메인 빛 밝기 배수(1 ~ 1.03). */
    val pulse: Float = 1f,
    /** 장면 시각(초, 0~600 순환). 모든 느린 움직임의 주기(10·12초 등)는 600초를 나눈다 → 순환해도 튀지 않음. */
    val timeSec: Float = 0f,
    /** 주행 중 이벤트 반응([DriveMotion]): 메인 빛 크기·밝기 배수. 정차 중엔 1(정차 직후 3초에 걸쳐 1로). */
    val mainScale: Float = 1f,
    val mainGain: Float = 1f,
    /** 물방울 세기 0~1(와이퍼 ON/OFF 때 2초 페이드). */
    val rainAmount: Float = if (rain) 1f else 0f,
)

/**
 * 무드 화면의 상태 기계(순수 Kotlin, 시간은 밖에서 넣는다). "운전 중엔 귀로, 멈추면 눈으로".
 *
 * 주행 중 화면 정책 "이벤트 때만 한 번 반응(최소 변화)"(Pleos 운전자 주의 분산 가이드: 장식적·반복적 움직임 지양):
 *  - restricted(주행 중, requiresDO=true): **장면 시계가 멈춘다** → 느린 움직임·숨쉬기·박 동기 밝기·색·물결·커버 변화 0.
 *    멈추는 순간 진행 중이던 전환(음악 전환·커버 장면·웰컴·굿바이·시간대)은 **완성된 모습으로 맞춘 뒤** 멈춘다
 *    → 정지 프레임이 그 자체로 완성된 한 장. 음악 상태·커버의 새 겹·시간대 변화·박 신호는 반영하지 않고 보관한다.
 *    예외는 [DriveMotion]: 레이어 증감·테이프스톱·와이퍼 때만 메인 빛 크기·밝기, 물방울을 한 번 전환(≤2초, 간격 ≥10초).
 *    전환 중이 아니면 프레임이 변하지 않는다.
 *  - restricted가 풀리는 순간(정차): 시계가 다시 흐르고, 보관한 음악 상태로 3초 전환 + 지난 구간이 커버에 한 겹 추가되는 장면([REVEAL_MS]).
 *  - 정차·주차 중: 10·12초 주기의 아주 느린 움직임 + 박에 맞춰 메인 빛 밝기 3% 숨쉬기.
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

    private var dayFrom = PartOfDay.NIGHT
    private var dayTo = PartOfDay.NIGHT
    private var dayStart = Long.MIN_VALUE / 2
    var pendingDay: PartOfDay? = null; private set

    private var masterFrom = 1f
    private var masterTo = 1f
    private var masterStart = Long.MIN_VALUE / 2
    private var masterDur = 1L

    val restricted: Boolean get() = frozenAt != null

    /** 정차 중에는 계속 다시 그려야 한다(느린 움직임). 주행 중에는 다시 그릴 필요가 없다(이벤트 전환 중만 [animatingAt]). */
    val animating: Boolean get() = !restricted

    // ---- 주행 중 이벤트 반응
    private var drive: DriveMotion? = null
    private var lastLayers = 0
    private var exitFrom = DriveMotion.NEUTRAL.copy(rain = 0f)
    private var exitStart = Long.MIN_VALUE / 2

    /** 지금 프레임을 계속 다시 그려야 하는지(정차 중, 또는 주행 중 이벤트 전환 중). */
    fun animatingAt(nowMs: Long) = !restricted || drive?.transitioning(nowMs) == true

    /**
     * 음악 이벤트(주행 중 반응용). 정차 중에도 레이어 수는 기억해 둔다(출발 순간 기준).
     * tapeStop = 이번에 새로 난 테이프스톱(급제동·급가속).
     */
    fun music(layers: Int, tapeStop: Boolean, rain: Boolean, nowMs: Long) {
        lastLayers = layers
        val d = drive ?: return
        if (tapeStop) d.tapeStop()
        d.layers(layers); d.rain(rain)
        d.update(nowMs)
    }

    /** 주행 중: 미뤄 둔 전환을 시작할 수 있으면 시작. 반환 = 전환 중. */
    fun tick(nowMs: Long): Boolean = drive?.update(nowMs) ?: false

    /** 주행 중 다음에 [tick]해야 할 시각(없으면 null = 완전 정지). */
    fun nextWakeMs(nowMs: Long): Long? = drive?.nextWakeMs(nowMs)

    /** 주행 중 시작한 전환들(시작, 길이) — 테스트·로그용. */
    val driveTransitions: List<Pair<Long, Long>> get() = drive?.transitions.orEmpty()

    fun setRestricted(r: Boolean, nowMs: Long) {
        if (r == restricted) return
        if (r) {
            // 완성된 한 장으로 맞춘 뒤 멈춘다
            from = to; fadeStart = Long.MIN_VALUE / 2
            cover?.let { revealFrom = it.layers.size }; revealStart = Long.MIN_VALUE / 2
            dayFrom = dayTo; dayStart = Long.MIN_VALUE / 2
            masterFrom = 1f; masterTo = 1f; masterStart = Long.MIN_VALUE / 2
            exitStart = Long.MIN_VALUE / 2
            frozenAt = nowMs
            drive = DriveMotion(initialRain = to.rain, initialLayers = lastLayers)
            return
        }
        // 정차: 주행 중 반응(크기·밝기·물방울)을 보관한 음악 상태와 함께 3초에 걸쳐 정차 모습으로
        val d = drive!!
        exitFrom = d.values(nowMs)
        drive = null
        pausedTotal += nowMs - frozenAt!!
        frozenAt = null
        val t = st(nowMs)
        exitStart = t
        pending?.let { pending = null; startFade(it, t) }
        pendingCover?.let { pendingCover = null; applyCover(it, t) }
        pendingDay?.let { pendingDay = null; if (it != dayTo) startDay(it, t) }
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

    /** 시간대(색온도). 정차 중이면 6초에 걸쳐 바뀐다. */
    fun setDayPart(p: PartOfDay, nowMs: Long, immediate: Boolean = false) {
        when {
            restricted -> pendingDay = p
            immediate -> { dayFrom = p; dayTo = p; dayStart = Long.MIN_VALUE / 2 }
            p != dayTo -> startDay(p, st(nowMs))
        }
    }

    /** 웰컴: 검은 화면에서 3초 동안 빛이 피어남. */
    fun welcome(nowMs: Long) { if (!restricted) startMaster(0f, 1f, WELCOME_MS, st(nowMs)) }

    /** 굿바이: 2초 동안 빛이 가라앉음(완전히 끄지 않고 0.3까지). */
    fun goodbye(nowMs: Long) { if (!restricted) { val t = st(nowMs); startMaster(masterAt(t), GOODBYE_LEVEL, GOODBYE_MS, t) } }

    /** 다시 깨어남(굿바이 뒤 무드 화면으로 돌아올 때). */
    fun wake(nowMs: Long) { if (!restricted) { val t = st(nowMs); if (masterAt(t) < 1f) startMaster(masterAt(t), 1f, 1_500, t) } }

    private fun startMaster(a: Float, b: Float, dur: Long, t: Long) { masterFrom = a; masterTo = b; masterDur = dur; masterStart = t }
    private fun masterAt(t: Long): Float {
        val k = ((t - masterStart).toFloat() / masterDur).coerceIn(0f, 1f)
        return masterFrom + (masterTo - masterFrom) * smooth(k)
    }

    private fun startDay(p: PartOfDay, t: Long) { dayFrom = dayTo; dayTo = p; dayStart = t }

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

    /**
     * beatPhase = 지금 박 안의 위치(0~1, 박 머리 = 0), 음악이 쉬면 null. 주행 중에는 무시한다(1로 고정).
     */
    fun frame(nowMs: Long, beatPhase: Float? = null): MoodFrame {
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
        val pulse = if (restricted) 1f else beatPhase?.let { pulseShape(it) } ?: 1f
        // 주행 중 = 이벤트 반응 값, 정차 직후 3초 = 그 값에서 1로, 그 외 = 1
        val d = drive
        val dv = when {
            d != null -> d.values(nowMs)
            else -> {
                val k = smooth(((t - exitStart).toFloat() / fadeMs).coerceIn(0f, 1f))
                val baseRain = if (s.rain) 1f else 0f
                DriveMotion.Values(lerp(exitFrom.scale, 1f, k), lerp(exitFrom.gain, 1f, k), if (k >= 1f) baseRain else lerp(exitFrom.rain, baseRain, k))
            }
        }
        val rainOn = if (d != null) dv.rain > 0.5f else s.rain
        val dayK = ((t - dayStart).toFloat() / DAY_FADE_MS).coerceIn(0f, 1f)
        return MoodFrame(
            bg, circles, tone, withAlpha(tone, 70), amp, phase, rainOn,
            cover = c,
            coverAlpha = COVER_ALPHA + (COVER_ALPHA_REVEAL - COVER_ALPHA) * bright,
            revealFrom = if (inReveal) revealFrom else (c?.layers?.size ?: 0),
            reveal = if (inReveal) drawn else 1f,
            moodAlpha = 1f - 0.65f * bright,
            bright = bright,
            mood = s,
            dayFrom = dayFrom, dayTo = dayTo, dayMix = smooth(dayK),
            master = masterAt(t),
            pulse = pulse,
            timeSec = ((t - origin).mod(LOOP_MS)).toFloat() / 1000f,
            mainScale = dv.scale, mainGain = dv.gain, rainAmount = dv.rain,
        )
    }

    companion object {
        const val BASE_RADIUS = 0.18f
        const val REVEAL_MS = 4_000L
        const val WELCOME_MS = 3_000L
        const val GOODBYE_MS = 2_000L
        const val GOODBYE_LEVEL = 0.3f
        const val DAY_FADE_MS = 6_000L
        const val LOOP_MS = 600_000L
        const val COVER_ALPHA = 0.22f
        const val COVER_ALPHA_REVEAL = 0.6f
        const val PULSE_DEPTH = 0.03f
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smooth(k: Float) = k * k * (3 - 2 * k)
        fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0x00FFFFFF)
        /** 박 안 위치 → 밝기 배수. 박 머리에서 0.3박(약 0.2초) 뒤 최고(1 + depth), 박 끝에서 다시 1(박과 박 사이 연속, 튀지 않음). */
        fun pulseShape(beatPhase: Float): Float {
            val x = beatPhase.coerceIn(0f, 1f)
            val rise = if (x < 0.3f) (1 - cos(PI * x / 0.3f).toFloat()) / 2 else 1f
            val fall = if (x < 0.3f) 1f else (1 + cos(PI * (x - 0.3f) / 0.7f).toFloat()) / 2
            return 1f + PULSE_DEPTH * rise * fall
        }
    }
}
