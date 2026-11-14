package app.playdrive.ui

import app.playdrive.model.TurnSignal
import app.playdrive.music.VisualCue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** 주행 중 화면 효과 강도(설정 화면에서 고름, 정차 중에만). */
enum class EffectLevel {
    /** 물결이 지금 연주되는 소리(킥·스네어·베이스 음·코드·멜로디·보상)에 맞춰 움직인다. 기본값. */
    LIVE,
    /** 악기별 물결은 유지하되 박자 파동 없이 들어오고 빠지는 것만, 물결은 아주 천천히. */
    CALM,
}

/** 물결 한 줄 = 악기 하나(게이지와 1:1). 뒤 → 앞 그리는 순서. */
enum class Inst { PAD, HIHAT, CHORDS, BASS, DRUMS, MELODY }

/**
 * 화면이 읽는 '지금 들리는 소리'(매 프레임, 오디오 렌더 시각 = 들리는 격자 위치 기준).
 * step·stepFrac = 물결 악보([WaveScore]) 칸 위치(곡 시작부터, 모르면 step -1). 칸이 뒤로 가면 곡을 처음부터 연 것.
 */
data class Sound(
    val playing: Boolean = false,
    val on: Set<Inst> = emptySet(),
    val step: Int = -1,
    val stepFrac: Float = 0f,
    /** 곧(또는 방금) 들리는 연주 순간: (순간, 들리기까지 ms — 음수면 이미 지남). 같은 순간은 한 번만 온다. */
    val cues: List<Pair<VisualCue, Float>> = emptyList(),
    /** 테이프 스톱 진행 0~1(멈춰 있는 동안 1), 없으면 -1. */
    val tape: Float = -1f,
    /** 카운트인 중(곡을 처음부터 여는 순간 전까지). */
    val countIn: Boolean = false,
    /** 남은 악기 수(0~6, 모르면 -1), 연주 정지(FAILED). */
    val life: Int = -1,
    val stopped: Boolean = false,
    /** 이번 곡의 마디(0~64), 클라이맥스(마지막 1km·이어진 마디 48). */
    val songBar: Int = 0,
    val climax: Boolean = false,
    val moving: Boolean = false,
    val rain: Boolean = false,
    val turn: TurnSignal = TurnSignal.OFF,
)

/**
 * 물결 한 줄. 좌표 = 화면 높이 비율(y 0 위 ~ 1 아래).
 * edge = 물결 마루 빛의 보임(악기가 들어오면 먼저), glow = 물결 면의 밝기(밝기 변화 제한을 지키며 천천히).
 */
data class WaveLine(
    val y: Float, val amp: Float, val phase: Float, val freq: Float,
    val edge: Float, val glow: Float,
    /** 부풀어 오름 0~1(베이스 음·패드 마디·보상). */
    val swell: Float = 0f,
    /** 잦아듦 0~1(빠지는 악기: 진폭과 빛이 서서히 줄어듦). */
    val fold: Float = 0f,
    /** 색 변화 -1(차갑게)~1(따뜻하게) — 코드. */
    val tint: Float = 0f,
    /** 가장자리 잔물결 결 0~1(하이햇·비). */
    val ripple: Float = 0f,
    /** 모양 위상(코드가 바뀔 때 새로 잡힘, 스프링으로 미끄러지듯). */
    val shape: Float = 0f,
)

/**
 * 박자 파동 하나: 바닥에서 시작해 좌우로 퍼지는 진행파. t = 정점(박자)으로부터 ms(음수 = 아직 차오르는 중).
 * amp = 화면 높이 비율, cx = 시작 가로 위치(0~1, 화면 폭 비율), kind = 0 킥 · 1 넓은 파동(필인·보상·카운트인) · 2 스네어.
 */
data class Pulse(val t: Float, val amp: Float, val cx: Float, val kind: Int)

/** 한 프레임의 물결 전체(순수 데이터, 렌더러는 이것만 본다). */
data class WaveState(
    val lines: List<WaveLine>,
    /** 멜로디 리본: 오래된 것 → 지금([MEL_POINTS]개, 화면 왼쪽 → 오른쪽). y 0~1(음 높이), a 0~1. */
    val melY: List<Float> = List(MEL_POINTS) { 0.5f },
    val melA: List<Float> = List(MEL_POINTS) { 0f },
    /** 멜로디 리본이 다음 점까지 흘러간 정도 0~1(부드럽게 스크롤). */
    val melShift: Float = 0f,
    val pulses: List<Pulse> = emptyList(),
    /** 연주 정지: 평평한 선으로 눕는 정도, 어두워짐. */
    val flat: Float = 0f,
    val dim: Float = 0f,
    /** 테이프 스톱: 느려지며 아래로 처짐. */
    val sag: Float = 0f,
    /** 위험(악기 1개): 색이 바램. */
    val fade: Float = 0f,
    /** 곡 진행 0~1(높아지고 색이 깊어짐) + 클라이맥스. */
    val rise: Float = 0f,
    val climax: Float = 0f,
    val rain: Float = 0f,
    val edgeL: Float = 0f,
    val edgeR: Float = 0f,
    /** 바닥 앰비언트 선이 킥에 맞춰 아주 살짝 밝아지는 정도 0~1. */
    val ambKick: Float = 0f,
) {
    /**
     * 화면 평균 밝기 모형(0~1, 악기 6개 = 1 근처). 셰이더의 면 밝기와 같은 가중치(면은 물결 높이와 무관하게 정규화).
     * 광과민성 검사([WaveMotionTest])가 이 값의 초당 변화를 본다(녹화 영상의 실제 밝기도 tools/check_luma.py로 확인).
     */
    fun luma(): Float {
        var s = BASE_LUMA
        lines.forEachIndexed { i, l -> s += WEIGHT[i] * l.glow * (1f + SWELL_GLOW * l.swell) }
        s += 0.02f * melA.average().toFloat()
        return s * (1f - dim)
    }

    companion object {
        const val MEL_POINTS = 32
        const val MAX_PULSES = 6
        const val SWELL_GLOW = 0.05f
        const val BASE_LUMA = 0.30f
        /** 악기별 물결 면의 밝기 몫(Inst 순서). */
        val WEIGHT = floatArrayOf(0.16f, 0.06f, 0.15f, 0.13f, 0.14f, 0.04f)
    }
}

/**
 * 물결 모양(셰이더 LightShader와 같은 식, 순수 Kotlin) — 프레임별 물결 높이 점검(테스트·로그)에 쓴다.
 * 파동 포락선: 정점 [PRE_MS] 전부터 부드럽게 차오름(ease-in) → 박자에 정점 → (1+t/τ)e^(−t/τ)로 약 0.8초에 걸쳐 풀림.
 * 진행파: 시작점에서 좌우로 [SPEED](화면 높이/초)로 번지고, 앞쪽 물결 먼저·뒤쪽 물결 나중([DELAY_MS]).
 * 여러 파동은 겹쳐 쌓이되 부드러운 상한([CAP]).
 */
object WaveGeom {
    const val PRE_MS = 80f
    const val TAU_MS = 170f
    const val SPEED = 1.3f
    const val CAP = 0.012f
    /** 물결별 반응 지연(Inst 순서: 패드·하이햇·코드·베이스·드럼) — 앞쪽 먼저. */
    val DELAY_MS = floatArrayOf(180f, 135f, 90f, 45f, 0f)
    /** 파동 종류별 물결 반응 비율(Inst 순서). */
    val KICK_W = floatArrayOf(0.30f, 0.20f, 0.45f, 0.70f, 1.0f)
    val WIDE_W = floatArrayOf(0.80f, 0.60f, 0.90f, 1.0f, 1.0f)
    val SNARE_W = floatArrayOf(0f, 0f, 0.30f, 0.70f, 1.0f)

    fun env(t: Float): Float = when {
        t < -PRE_MS -> 0f
        t < 0f -> { val u = (t + PRE_MS) / PRE_MS; u * u * (3 - 2 * u) }
        else -> { val k = t / TAU_MS; if (k > 12f) 0f else (1 + k) * exp(-k) }
    }

    /** 물결 i의 가로 위치 x(화면 높이 단위)에서 파동이 들어 올리는 양(음수 = 위로). */
    fun disp(ws: WaveState, i: Int, x: Float, aspect: Float): Float {
        var s = 0f
        for (p in ws.pulses) {
            val dx = abs(x - p.cx * aspect)
            val w = when (p.kind) { 0 -> KICK_W[i]; 1 -> WIDE_W[i]; else -> SNARE_W[i] }
            if (w <= 0f) continue
            val reach = if (p.kind == 1) 1.6f else 0.9f
            val fall = 0.4f + 0.6f * exp(-(dx / reach) * (dx / reach))
            s += p.amp * w * fall * env(p.t - dx / SPEED * 1000f - DELAY_MS[i])
        }
        return -s / (1f + abs(s) / CAP)
    }

    /** 물결 i의 가로 위치 x에서의 높이(결·비 질감 제외). */
    fun height(ws: WaveState, i: Int, x: Float, aspect: Float): Float {
        val l = ws.lines[i]
        var y = l.y
        var a = l.amp * (1f - l.fold)
        y += (0.80f - y) * ws.flat; a *= 1f - ws.flat
        y += ws.sag * 0.06f; a *= 1f - 0.6f * ws.sag
        a *= 1f + 0.3f * l.swell; y -= 0.004f * l.swell
        val k = l.freq
        val c = y + a * (0.62f * sin(k * x + l.phase) + 0.38f * sin(1.73f * k * x - 0.7f * l.phase + l.shape + 1.1f * i))
        return c + disp(ws, i, x, aspect) * (1f - 0.7f * ws.flat)
    }
}

/** 임계감쇠 스프링: 목표가 바뀌어도 값은 한 프레임에 점프하지 않고(속도까지 이어짐) 넘치지 않게 따라간다. dt 기반. */
class Spring(var value: Float, private val omega: Float) {
    private var vel = 0f
    fun step(target: Float, dt: Float): Float {
        if (dt <= 0f) return value
        val x = value - target
        val e = exp(-omega * dt)
        val tmp = (vel + omega * x) * dt
        value = target + (x + tmp) * e
        vel = (vel - omega * tmp) * e
        return value
    }
    fun snap(v: Float) { value = v; vel = 0f }
}

/**
 * 물결 움직임(순수 Kotlin, 시간은 밖에서 넣는다). 물결은 장식이 아니라 지금 연주되는 소리:
 *  - 패드(맨 뒤 넓은 물결): 마디마다 한 번 아주 천천히 밝아졌다 가라앉음
 *  - 하이햇(맨 위 가는 물결): 타격마다 가장자리 고운 결이 흔들림(반짝이지 않음)
 *  - 코드(가운데): 코드가 바뀌면 색이 살짝 바뀌며 모양이 새로 잡힘
 *  - 베이스(가장 진하고 두툼): 음을 칠 때마다 숨 쉬듯 부풂, 음 높이 = 물결 높이
 *  - 드럼(바닥 가까이): 킥마다 바닥 가운데서 좌우로 번지는 파동, 스네어는 한쪽에서 얕게. 마디 첫 박은 조금 더
 *  - 멜로디: 포인트 색 리본이 음 높이를 따라 오르내리며 흐름, 음이 없으면 사라짐
 * 움직임 원칙: 모든 값은 임계감쇠 스프링(dt 기반, 순간 변화 없음). 박자 파동은 악보를 80ms 미리 읽어 정점이 박자에 오게 차오르고 길게 풀린다.
 * 연속 파동은 겹쳐 쌓인다(상한). 흐름 위상은 끊기지 않고, 악기가 들고 날 때는 진폭·빛만 서서히.
 * 운전 방해 기준: 화면 밝기 변화 초당 10% 이내([MAX_LUMA_PER_S]), 반짝이는 입자 없음, 밝기 점멸 없음. 흐름 한 주기 20초 이상([PERIODS]).
 * CALM = 박자 파동·보상 반응 없이 들어오고 빠지는 것만.
 */
class WaveMotion(private val score: WaveScore, startMs: Long = 0) {
    private var last = startMs
    private val phase = FloatArray(6)
    private fun springs(v: Float, w: Float) = Array(6) { Spring(v, w) }
    private val edge = springs(0f, 6f)
    private val glowRaw = FloatArray(6)
    private val glow = springs(0f, 5f)
    private val fold = springs(0f, 6f)
    private val swellS = springs(0f, 14f)
    private val flow = Spring(0.3f, 3f)
    private val ampS = Spring(1f, 3f)
    private val bassY = Spring(0f, 10f)
    private val keysAmp = Spring(0f, 6f)
    private val tint = Spring(-1f, 4f)
    private val shape = Spring(0f, 4.5f)
    private var shapeTarget = 0f
    private var tintTarget = -1f
    private var lastBar = -1
    private val flat = Spring(0f, 2.6f)
    private var dimRaw = 0f
    private val dim = Spring(0f, 5f)
    private val sag = Spring(0f, 7f)
    private var sagTarget = 0f
    private val fade = Spring(0f, 3f)
    private val rise = Spring(0f, 1.2f)
    private val climax = Spring(0f, 1.5f)
    private val rain = Spring(0f, 2.5f)
    private val edgeL = Spring(0f, 6f)
    private val edgeR = Spring(0f, 6f)
    private val hatSway = Spring(0f, 10f)

    /** 예정·진행 중인 박자 파동: (정점 ms, 세기, 시작 가로 위치, 종류). */
    private val waves = ArrayList<FloatArray>()
    /** 부풂 사건(정점 ms, 세기, 물결). */
    private val swells = ArrayList<FloatArray>()
    /** 악보를 미리 읽은 칸(곡 처음부터 이어 센 절대 칸). */
    private var loops = 0
    private var lastStep = -1
    private var scheduledTo = Long.MIN_VALUE
    private var snareSide = 0
    private var sticks = 0

    private val melY = FloatArray(WaveState.MEL_POINTS) { 0.5f }
    private val melA = FloatArray(WaveState.MEL_POINTS)
    private var melSince = 0f
    private val melNow = Spring(0.5f, 16f)

    /** 시작한 파동(테스트·로그용): (정점 ms, 종류). */
    val pulses = ArrayList<Pair<Long, String>>()

    fun step(nowMs: Long, s: Sound, effect: EffectLevel = EffectLevel.LIVE): WaveState {
        if (nowMs < last) return state(nowMs)
        val dt = ((nowMs - last).coerceIn(0, 100)) / 1000f
        last = nowMs
        val live = effect == EffectLevel.LIVE
        val audioRunning = s.playing && s.tape < 0f

        // ---- 악기가 들고 남: 마루 빛은 스프링으로(약 0.6초), 면 밝기는 밝기 제한 안의 램프를 다시 스프링으로
        // 음악이 없거나(일시정지·출발 전 악기 0개) = 패드·코드 물결만 잔잔하게
        val idle = !s.playing || s.on.isEmpty()
        val target = FloatArray(6) { i -> val k = Inst.entries[i]; if (!idle) (if (k in s.on) 1f else 0f) else if (k == Inst.PAD || k == Inst.CHORDS) 0.6f else 0f }
        var want = 0f
        val d = FloatArray(6) { i -> val dd = (target[i] - glowRaw[i]).coerceIn(-dt / 1.2f, dt / 1.6f); want += WaveState.WEIGHT[i] * abs(dd); dd }
        val budget = GLOW_BUDGET * dt
        val k = if (want > budget) budget / want else 1f
        for (i in 0 until 6) {
            glowRaw[i] = (glowRaw[i] + d[i] * k).coerceIn(0f, 1f)
            glow[i].step(glowRaw[i], dt)
            edge[i].step(target[i], dt)
            fold[i].step(if (target[i] < edge[i].value - 0.02f) 1f else 0f, dt)
        }
        val spent = minOf(want, budget)

        // ---- 상황
        val dimTarget = if (s.stopped || s.countIn) 0.30f else 0f
        val raw = WaveState.BASE_LUMA + (0 until 6).sumOf { (WaveState.WEIGHT[it] * glowRaw[it]).toDouble() }.toFloat() + 0.02f
        val dimStep = ((LUMA_BUDGET * dt - spent * (1f - dimRaw)) / raw).coerceIn(0f, dt * DIM_PER_S)
        dimRaw = approach(dimRaw, dimTarget, dimStep)
        dim.step(dimRaw, dt)
        flat.step(if (s.stopped || s.countIn) 1f else 0f, dt)
        val danger = s.life == 1 && !s.stopped && s.playing
        fade.step(if (danger) 1f else 0f, dt)
        sagTarget = if (s.tape >= 0f) maxOf(sagTarget, s.tape) else 0f
        sag.step(sagTarget, dt)
        val flowTarget = when {
            !s.playing -> 0.15f
            s.stopped || s.countIn -> 0.08f
            !s.moving -> 0.22f
            else -> 1f
        } * (if (danger) 0.5f else 1f) * (if (live) 1f else 0.6f) * (1f - 0.9f * maxOf(sagTarget, sag.value).coerceIn(0f, 1f))
        flow.step(flowTarget, dt)
        ampS.step((if (s.moving || !s.playing) 1f else 0.65f) * (if (danger) 0.55f else 1f), dt)
        rise.step((s.songBar / 64f).coerceIn(0f, 1f), dt)
        climax.step(if (s.climax) 1f else 0f, dt)
        rain.step(if (s.rain) 1f else 0f, dt)
        edgeL.step(if (s.turn == TurnSignal.LEFT) 1f else 0f, dt)
        edgeR.step(if (s.turn == TurnSignal.RIGHT) 1f else 0f, dt)

        // ---- 가로 흐름: 위상은 끊기지 않는다(속도만 스프링)
        for (i in 0 until 6) phase[i] = ((phase[i] + dt * flow.value * (2 * PI / PERIODS[i]).toFloat()) % (2 * PI * 1000).toFloat())

        // ---- 소리: 악보를 [LOOK_STEPS]칸(약 83ms) 미리 읽어 정점이 박자에 오게
        val haveScore = s.step >= 0 && score.steps > 0
        if (haveScore) {
            if (lastStep >= 0 && s.step < lastStep) loops++   // 한 바퀴 돌았거나 곡을 처음부터 열었음 → 절대 칸은 계속 앞으로
            lastStep = s.step
        }
        val absStep = loops.toLong() * score.steps + s.step
        fun heard(x: Inst) = edge[x.ordinal].value > 0.3f && fold[x.ordinal].value < 0.5f
        if (haveScore && audioRunning) {
            val pos = absStep + s.stepFrac
            val from = maxOf(scheduledTo + 1, absStep)
            val to = absStep + LOOK_STEPS
            if (live) for (st in from..to) {
                val at = nowMs + (st - pos) * STEP_MS
                val local = Math.floorMod(st, score.steps.toLong()).toInt()
                val downbeat = local % (score.steps / 8) < 4
                if (heard(Inst.DRUMS)) {
                    val kv = score.kick[local]
                    if (kv > 0.25f) { addWave(at, KICK_AMP * kv * (if (downbeat) 1.25f else 0.9f), 0.5f, 0, "kick"); swells += floatArrayOf(at, kv, Inst.DRUMS.ordinal.toFloat()) }
                    val sv = score.snare[local]
                    if (sv > 0.25f) { addWave(at, SNARE_AMP * sv, if (snareSide++ % 2 == 0) 0.12f else 0.88f, 2, "snare") }
                }
                if (heard(Inst.HIHAT) && score.hat[local] > 0.25f) swells += floatArrayOf(at, score.hat[local], Inst.HIHAT.ordinal.toFloat())
                if (heard(Inst.BASS) && score.bassOn[local] > 0.25f) swells += floatArrayOf(at, score.bassOn[local], Inst.BASS.ordinal.toFloat())
            }
            scheduledTo = to
            val step = s.step
            val bar = Math.floorMod(step, score.steps) / (score.steps / 8)
            if (live) {
                val bp = score.at(score.bassPitch, step)
                if (bp > 0f) bassY.step(((bp - 36f) / 12f).coerceIn(-1f, 1f), dt) else bassY.step(bassY.value, dt)
                keysAmp.step(score.at(score.keys, step), dt)
            }
            // 코드(SONG: 마디마다 Am7 ↔ Fmaj7): 색이 살짝 바뀌며 모양이 새로 잡힘(스프링으로 미끄러지듯)
            if (bar != lastBar) {
                tintTarget = if (bar % 2 == 0) -1f else 1f
                if (lastBar >= 0) shapeTarget += 1.3f else tint.snap(tintTarget)
                lastBar = bar
            }
            val gp = score.at(score.guitarPitch, step)
            if (heard(Inst.MELODY) && gp > 0f && score.at(score.guitar, step) > 0.12f) melNow.step(if (live) ((gp - 55f) / 12f).coerceIn(0f, 1f) else 0.5f, dt) else melNow.step(melNow.value, dt)
        } else if (haveScore) scheduledTo = absStep + LOOK_STEPS   // 멈춘 동안의 타격은 건너뜀
        tint.step(tintTarget, dt)
        shape.step(shapeTarget, dt)

        // ---- 연주 순간(보상·필인·카운트인·다음 곡): 들리는 시각에 정점
        if (!s.countIn) sticks = 0
        if (live) for ((c, inMs) in s.cues) {
            val at = nowMs + inMs
            when (c) {
                VisualCue.FILL -> addWave(at, WIDE_AMP, 0.5f, 1, "fill")
                VisualCue.DROP -> { addWave(at, WIDE_AMP * 0.6f, 0.5f, 1, "drop"); swells += floatArrayOf(at, 1f, Inst.DRUMS.ordinal.toFloat()); swells += floatArrayOf(at, 1f, Inst.BASS.ordinal.toFloat()) }
                VisualCue.SWELL -> { addWave(at, WIDE_AMP * 0.5f, 0.5f, 1, "swell"); swells += floatArrayOf(at, 1f, Inst.PAD.ordinal.toFloat()); swells += floatArrayOf(at, 1f, Inst.CHORDS.ordinal.toFloat()) }
                VisualCue.CADENCE -> { addWave(at, WIDE_AMP * 0.4f, 0.5f, 1, "cadence"); swells += floatArrayOf(at, 1f, Inst.CHORDS.ordinal.toFloat()) }
                VisualCue.STICK -> { addWave(at, WIDE_AMP * (0.30f + 0.15f * sticks.coerceAtMost(3)), 0.5f, 1, "stick"); sticks++ }   // 4박이 점점 커짐
                VisualCue.SONG -> addWave(at, WIDE_AMP * 0.5f, 0.5f, 1, "song")
            }
        }
        waves.removeAll { nowMs - it[0] > LIFE_MS }; while (waves.size > WaveState.MAX_PULSES) waves.removeAt(0)
        swells.removeAll { nowMs - it[0] > LIFE_MS }

        // ---- 부풂: 파동과 같은 포락선(정점 = 소리)
        val sw = FloatArray(6)
        for (e in swells) sw[e[2].toInt()] += e[1] * WaveGeom.env(nowMs - e[0])
        if (haveScore && audioRunning && live) {
            val inBar = (Math.floorMod(s.step, score.steps / 8) + s.stepFrac) / (score.steps / 8f)
            sw[Inst.PAD.ordinal] += 0.5f - 0.5f * cos(2 * PI * inBar).toFloat()   // 패드: 마디마다 한 번
        }
        for (i in 0 until 6) swellS[i].step((sw[i] / (1f + sw[i])) * 1.6f, dt)
        hatSway.step(sw[Inst.HIHAT.ordinal].coerceAtMost(1f), dt)

        // ---- 멜로디 리본 스크롤
        melSince += dt
        val step = s.step
        val melAlphaNow = if (s.playing && s.tape < 0f && haveScore && score.at(score.guitar, step) > 0.12f && score.at(score.guitarPitch, step) > 0f) edge[Inst.MELODY.ordinal].value * (1f - fold[Inst.MELODY.ordinal].value) else 0f
        while (melSince >= MEL_DT) {
            melSince -= MEL_DT
            for (j in 0 until WaveState.MEL_POINTS - 1) { melY[j] = melY[j + 1]; melA[j] = melA[j + 1] }
            melY[WaveState.MEL_POINTS - 1] = melNow.value
            melA[WaveState.MEL_POINTS - 1] = melA[WaveState.MEL_POINTS - 2] + (melAlphaNow - melA[WaveState.MEL_POINTS - 2]) * 0.5f
        }
        return state(nowMs)
    }

    /** 결과 화면(주차 중): S등급이면 물결 전체가 한 번 밝게 차오름 — 넓은 파동 + 모든 물결 부풂. */
    fun celebrate(nowMs: Long, strength: Float = 1f) {
        val at = nowMs + WaveGeom.PRE_MS
        addWave(at, WIDE_AMP * 1.6f * strength, 0.5f, 1, "celebrate")
        for (i in 0 until 5) swells += floatArrayOf(at, strength, i.toFloat())
    }

    private fun addWave(at: Float, amp: Float, cx: Float, kind: Int, tag: String) {
        waves += floatArrayOf(at, amp, cx, kind.toFloat()); pulses += at.toLong() to tag
    }

    private fun state(nowMs: Long): WaveState {
        val r = rise.value; val c = climax.value
        val lift = 0.05f * r + 0.03f * c
        val ampK = ampS.value * (1f + 0.35f * r + 0.25f * c)
        val lines = Inst.entries.mapIndexed { i, x ->
            val base = BASE_Y[i] - lift + (if (x == Inst.BASS) -0.018f * bassY.value else 0f) + 0.05f * fade.value
            val a = BASE_AMP[i] * ampK * (if (x == Inst.CHORDS) 0.8f + 0.4f * keysAmp.value else 1f)
            WaveLine(
                y = base, amp = a, phase = phase[i], freq = FREQ[i],
                edge = edge[i].value.coerceIn(0f, 1f), glow = glow[i].value.coerceIn(0f, 1f), swell = swellS[i].value.coerceIn(0f, 1.6f), fold = fold[i].value.coerceIn(0f, 1f),
                tint = if (x == Inst.CHORDS) tint.value else 0f,
                ripple = if (x == Inst.HIHAT) 0.35f + 0.65f * hatSway.value else 0f,
                shape = if (x == Inst.CHORDS) shape.value else 0f,
            )
        }
        val ps = waves.map { Pulse(nowMs - it[0], it[1], it[2], it[3].toInt()) }
        return WaveState(
            lines = lines,
            melY = melY.toList(), melA = melA.toList(), melShift = (melSince / MEL_DT).coerceIn(0f, 1f),
            pulses = ps,
            flat = flat.value.coerceIn(0f, 1f), dim = dim.value.coerceIn(0f, 1f), sag = sag.value.coerceIn(0f, 1f), fade = fade.value.coerceIn(0f, 1f),
            rise = r, climax = c, rain = rain.value.coerceIn(0f, 1f), edgeL = edgeL.value.coerceIn(0f, 1f), edgeR = edgeR.value.coerceIn(0f, 1f),
            ambKick = ps.filter { it.kind == 0 }.sumOf { (WaveGeom.env(it.t) * it.amp / KICK_AMP).toDouble() }.toFloat().coerceIn(0f, 1f),
        )
    }

    private fun approach(v: Float, target: Float, step: Float) = if (v < target) minOf(target, v + step) else maxOf(target, v - step)

    companion object {
        /** 물결 기준 높이(Inst 순서): 뒤의 넓은 패드, 맨 위 하이햇, 가운데 코드, 아래 베이스, 바닥 드럼, 멜로디(음 높이로 움직임). */
        val BASE_Y = floatArrayOf(0.52f, 0.34f, 0.62f, 0.72f, 0.83f, 0.47f)
        val BASE_AMP = floatArrayOf(0.046f, 0.014f, 0.040f, 0.034f, 0.026f, 0f)
        /** 가로 파수(화면 높이당 라디안). */
        val FREQ = floatArrayOf(3.0f, 6.2f, 4.3f, 3.7f, 5.0f, 0f)
        /** 가로 흐름 한 주기(초) — 모두 20초 이상. */
        val PERIODS = floatArrayOf(34f, 22f, 26f, 24f, 20f, 30f)
        /** 파동 진폭(화면 높이 비율) — 숨 쉬듯 작게. */
        const val KICK_AMP = 0.006f
        const val SNARE_AMP = 0.003f
        const val WIDE_AMP = 0.010f
        /** 악보 한 칸 = 1000프레임 / 48kHz. */
        const val STEP_MS = 1000f * WaveScore.HOP / 48_000f
        /** 미리 읽는 칸 수(≈ [WaveGeom.PRE_MS]). */
        const val LOOK_STEPS = 4
        /** 파동 하나가 남아 있는 시간(진행파가 화면 끝까지 + 풀림). */
        const val LIFE_MS = 2_400f
        const val MEL_DT = 0.2f
        const val GLOW_BUDGET = 0.06f
        const val LUMA_BUDGET = 0.075f
        const val DIM_PER_S = 0.07f
        const val MAX_LUMA_PER_S = 0.10f
    }
}
