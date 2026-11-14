package app.playdrive.ui

import app.playdrive.album.AlbumNaming
import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.MoodState

/** 무드(주행) 화면 한 프레임(순수 데이터). 같은 프레임이면 화면도 같다(렌더러는 이것만 본다). */
data class MoodFrame(
    /** (Canvas 대체 렌더러용) 바탕색. */
    val background: Int,
    /** 음악 상태(정차 중 반영). */
    val mood: MoodState = MoodState(0.2f, 0.5f, 0f, false),
    /** 시간대 색: from → to 를 mix 만큼. */
    val dayFrom: PartOfDay = PartOfDay.NIGHT,
    val dayTo: PartOfDay = PartOfDay.NIGHT,
    val dayMix: Float = 1f,
    /** 전체 세기: 웰컴(0→1, 3초) · 굿바이(→0.3, 2초) · 깨어남(→1). */
    val master: Float = 1f,
    /** 장면 시각(초, 0~600 순환) — 비 잔물결 결의 느린 움직임. */
    val timeSec: Float = 0f,
    /** 물결(악기별, [WaveMotion]). */
    val waves: WaveState = WaveState(emptyList()),
)

/**
 * 무드(주행) 화면의 상태 기계(순수 Kotlin, 시간은 밖에서 넣는다).
 *  - 물결([WaveMotion])은 늘 지금 들리는 소리를 따른다(주행 중·정차 중 모두). 효과 강도: LIVE(박자 파동·보상 반응) / CALM(들어오고 빠지는 것만).
 *  - 바탕(시간대 색·음악 상태)은 주행 중(restricted)에는 보관만 하고 정차하는 순간 반영(색이 주행 중에 바뀌지 않게).
 *  - 효과 강도는 다음 출발부터 적용(주행 중에 바꿔도 그 주행은 그대로).
 */
class MoodScene(
    startMs: Long,
    initial: MoodState = MoodState(0.2f, 0.5f, 0f, false),
    private val fadeMs: Long = 3_000,
    score: WaveScore = WaveScore.EMPTY,
) {
    private val origin = startMs

    private var from = initial
    private var to = initial
    private var fadeStart = Long.MIN_VALUE / 2
    var pending: MoodState? = null; private set

    private var dayFrom = PartOfDay.NIGHT
    private var dayTo = PartOfDay.NIGHT
    private var dayStart = Long.MIN_VALUE / 2
    var pendingDay: PartOfDay? = null; private set

    private var masterFrom = 1f
    private var masterTo = 1f
    private var masterStart = Long.MIN_VALUE / 2
    private var masterDur = 1L

    var restricted: Boolean = false; private set

    /** 효과 강도(설정). 주행 중에 바꾸면 다음 출발부터. */
    var effect: EffectLevel = EffectLevel.LIVE
    private var driveEffect = EffectLevel.LIVE
    val activeEffect: EffectLevel get() = if (restricted) driveEffect else effect

    val waves = WaveMotion(score, startMs)

    fun setRestricted(r: Boolean, nowMs: Long) {
        if (r == restricted) return
        restricted = r
        if (r) {
            // 주행 시작: 진행 중이던 바탕 전환은 완성된 모습으로
            from = to; fadeStart = Long.MIN_VALUE / 2
            dayFrom = dayTo; dayStart = Long.MIN_VALUE / 2
            masterFrom = 1f; masterTo = 1f; masterStart = Long.MIN_VALUE / 2
            driveEffect = effect
            return
        }
        pending?.let { pending = null; startFade(it, nowMs) }
        pendingDay?.let { pendingDay = null; if (it != dayTo) startDay(it, nowMs) }
    }

    /** 새 음악 상태. 주행 중이면 보관만 한다. immediate = 전환 없이 바로(디버그·스크린샷용). */
    fun show(s: MoodState, nowMs: Long, immediate: Boolean = false) {
        when {
            restricted -> pending = s
            immediate -> { from = s; to = s; fadeStart = Long.MIN_VALUE / 2 }
            else -> startFade(s, nowMs)
        }
    }

    /** 시간대(색). 정차 중이면 6초에 걸쳐 바뀐다. */
    fun setDayPart(p: PartOfDay, nowMs: Long, immediate: Boolean = false) {
        when {
            restricted -> pendingDay = p
            immediate -> { dayFrom = p; dayTo = p; dayStart = Long.MIN_VALUE / 2 }
            p != dayTo -> startDay(p, nowMs)
        }
    }

    /** 웰컴: 검은 화면에서 3초 동안 물결이 피어남. */
    fun welcome(nowMs: Long) { if (!restricted) startMaster(0f, 1f, WELCOME_MS, nowMs) }

    /** 굿바이: 2초 동안 가라앉음(완전히 끄지 않고 0.3까지). */
    fun goodbye(nowMs: Long) { if (!restricted) startMaster(masterAt(nowMs), GOODBYE_LEVEL, GOODBYE_MS, nowMs) }

    /** 다시 깨어남(굿바이 뒤 무드 화면으로 돌아올 때). */
    fun wake(nowMs: Long) { if (!restricted && masterAt(nowMs) < 1f) startMaster(masterAt(nowMs), 1f, 1_500, nowMs) }

    private fun startMaster(a: Float, b: Float, dur: Long, t: Long) { masterFrom = a; masterTo = b; masterDur = dur; masterStart = t }
    private fun masterAt(t: Long): Float {
        val k = ((t - masterStart).toFloat() / masterDur).coerceIn(0f, 1f)
        return masterFrom + (masterTo - masterFrom) * smooth(k)
    }

    private fun startDay(p: PartOfDay, t: Long) { dayFrom = dayTo; dayTo = p; dayStart = t }
    private fun startFade(s: MoodState, t: Long) { from = moodAt(t); to = s; fadeStart = t }

    private fun moodAt(t: Long): MoodState {
        val k = ((t - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
        if (k >= 1f) return to
        return MoodState(lerp(from.energy, to.energy, k), lerp(from.calm, to.calm, k), lerp(from.brightness, to.brightness, k), if (k > 0.5f) to.rain else from.rain)
    }

    fun mood(nowMs: Long): MoodState = moodAt(nowMs)

    /** sound = 지금 들리는 소리(없으면 음악이 없는 화면: 패드·코드 물결만 잔잔하게). */
    fun frame(nowMs: Long, sound: Sound? = null): MoodFrame {
        val s = moodAt(nowMs)
        val bg = AlbumNaming.lerpColor(0xFF0E1420.toInt(), 0xFF1A2B3A.toInt(), s.brightness * 0.6f + if (s.rain) 0.2f else 0f)
        val dayK = ((nowMs - dayStart).toFloat() / DAY_FADE_MS).coerceIn(0f, 1f)
        val snd = sound ?: Sound(rain = s.rain)
        return MoodFrame(
            background = bg, mood = s,
            dayFrom = dayFrom, dayTo = dayTo, dayMix = smooth(dayK),
            master = masterAt(nowMs),
            timeSec = ((nowMs - origin).mod(LOOP_MS)).toFloat() / 1000f,
            waves = waves.step(nowMs, snd, activeEffect),
        )
    }

    companion object {
        const val WELCOME_MS = 3_000L
        const val GOODBYE_MS = 2_000L
        const val GOODBYE_LEVEL = 0.3f
        const val DAY_FADE_MS = 6_000L
        const val LOOP_MS = 600_000L
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smooth(k: Float) = k * k * (3 - 2 * k)
    }
}
