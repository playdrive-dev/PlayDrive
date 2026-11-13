package app.playdrive.ui

import app.playdrive.model.TurnSignal
import kotlin.math.exp

/** 주행 중 화면 효과 강도(설정 화면에서 고름, 정차 중에만). */
enum class EffectLevel {
    /** 주행 중에도 음악에 맞춰 반응(박 맥박·드롭·악기 빛·속도 색·테이프스톱·깜빡이·비). 기본값. */
    LIVE,
    /** 음악 상태가 바뀌는 순간에만 1~2초 한 번, 최소 10초 간격([DriveMotion]). */
    CALM,
}

/** 음악 박 정보(오디오 믹서 기준, 매 프레임). */
data class Beat(
    /** 박 안 위치 0~1(박 머리 = 0), 음악이 쉬면 null. */
    val phase: Float?,
    /** 한 박 길이(ms). */
    val beatMs: Float = 667f,
    /** 드럼(킥)이 지금 들리는지. */
    val drums: Boolean = false,
    /** 지금 들리는 레이어 수(악기 수). */
    val layers: Int = 0,
    /** 연주 기록의 남은 악기 수(0~6, 모르면 -1) — 빛 원의 밝기·크기. */
    val life: Int = -1,
    /** 연주가 멈춤(게이지 0) — 빛 원이 어두워짐, 카운트인 뒤 곡이 다시 열리면 켜짐. */
    val stopped: Boolean = false,
)

/**
 * LIVE 효과(주행 중 음악에 맞춰 반응). 순수 Kotlin, 시간은 밖에서 넣는다. 같은 시각이면 같은 값(여러 번 불러도 됨).
 *
 *  - 킥 맥박: 드럼이 들리는 동안 박마다 메인 빛 밝기 +10%, 크기 +4%(상승 80ms, 감쇠 400ms). 드럼이 빠지면 멈춤
 *  - 출발 드롭(드럼이 다시 들어오는 순간): 0.15초에 밝기 +35%까지 오른 뒤 0.6초에 걸쳐 가라앉음 + 바닥 빛 띠 물결이 좌우로 퍼짐(1.2초)
 *  - 악기 빛: 들리는 레이어 수만큼 보조 빛이 하나씩 켜짐(0.6초 페이드)
 *  - 속도 색: 0 → 80km/h 이상에서 채도·밝기 +15%(2초 스무딩)
 *  - 테이프스톱: 0.7초 동안 화면이 어두워지며 빛이 아래로 가라앉음 → 레이어가 돌아오는 만큼(마디마다 하나씩) 다시 떠오름
 *  - 깜빡이: 그 방향 화면 가장자리에 박에 맞춘 은은한 빛 번짐
 *  - 비: 물방울 2초 페이드 인/아웃, 킥에 맞춰 물방울 반짝임
 * 광과민성 제한: 메인 빛 밝기(속도 색 포함)는 기본의 1.35배를 넘지 않고, 박 맥박은 [MIN_PULSE_GAP_MS]보다 자주 치지 않는다(어느 1초에도 2회까지).
 */
class LiveMotion(startMs: Long, initialLayers: Int, initialRain: Boolean) {

    data class Out(
        /** 메인 빛 밝기 배수(속도 색 제외). (1 + [HEAT_GAIN]·heat)·gain ≤ [MAX_GAIN]. */
        val gain: Float = 1f,
        val scale: Float = 1f,
        /** 테이프스톱으로 가라앉은 정도 0~1. */
        val sink: Float = 0f,
        /** 속도 색 0~1. */
        val heat: Float = 0f,
        /** 악기 빛 [LIGHTS]개의 켜짐 정도 0~1. */
        val lights: List<Float> = List(LIGHTS) { 0f },
        /** 드롭 물결 진행 0~1(없으면 -1). */
        val ripple: Float = -1f,
        val edgeL: Float = 0f,
        val edgeR: Float = 0f,
        val rain: Float = 0f,
        /** 킥에 맞춘 물방울 반짝임 0~1. */
        val sparkle: Float = 0f,
        /** 남은 악기 비율 0~1(1.2초 스무딩, 연주 정지 = 0). 빛 원의 밝기 ×(0.62 + 0.38·life), 크기 ×(0.86 + 0.14·life) — 줄이기만 하므로 광과민성 상한과 무관. */
        val life: Float = 1f,
    ) {
        /** 메인 빛의 실제 밝기 배수(속도 색 포함). */
        val totalGain: Float get() = (1f + HEAT_GAIN * heat) * gain
    }

    private var last = startMs
    private var out = Out(lights = List(LIGHTS) { if (it < initialLayers) 1f else 0f }, rain = if (initialRain) 1f else 0f)

    // 입력(신호, 약 4Hz)
    private var speedKmh = 0f
    private var turn = TurnSignal.OFF
    private var rainOn = initialRain
    private var signalLayers = initialLayers

    // 박
    private var lastOnset = Long.MIN_VALUE / 2
    private var pulseAt = Long.MIN_VALUE / 2
    private var drumsAmt = 0f
    private var drumsWasOn = false
    /** 주행 시작 전부터 쉬고 있었으면 그 시간도 쉰 것으로(출발 직후 드롭도 드롭). */
    private var drumsOffSince = Long.MIN_VALUE / 2
    private var first = true

    // 드롭
    private var burstAt = Long.MIN_VALUE / 2

    // 테이프스톱
    private var sinkTarget = 0f
    private var preTape = 0
    private var minSince = 0
    /** 테이프스톱 직후: 믹서가 레이어를 실제로 줄일 때까지(테이프 정지 효과가 끝날 때까지) 가라앉은 채로. */
    private var sinkHoldSince = Long.MIN_VALUE

    /** 박 맥박이 실제로 친 시각들(테스트·로그용). */
    val pulses = mutableListOf<Long>()

    /** 차량 신호(약 4Hz). tapeStop = 이번에 새로 난 테이프스톱. */
    fun input(speedKmh: Float, turn: TurnSignal, rain: Boolean, layers: Int, tapeStop: Boolean) {
        this.speedKmh = speedKmh; this.turn = turn; rainOn = rain
        if (tapeStop) { if (sinkTarget <= 0f) preTape = maxOf(signalLayers, layers); minSince = layers; sinkTarget = 1f; sinkHoldSince = last }
        signalLayers = layers
    }

    fun step(nowMs: Long, beat: Beat?): Out {
        val dt = ((nowMs - last).coerceIn(0, 200)) / 1000f
        if (nowMs < last) return out
        last = nowMs
        val b = beat ?: Beat(null)
        val o = out

        // ---- 박(킥) 맥박: 박 머리 시각을 믹서 위치에서 역산, 너무 잦으면 건너뜀
        if (b.phase != null) {
            val onset = nowMs - (b.phase * b.beatMs).toLong()
            if (onset > lastOnset + 120) {
                lastOnset = onset
                if (onset - pulseAt >= MIN_PULSE_GAP_MS) { pulseAt = onset; pulses += onset }
            }
        }
        drumsAmt = approach(drumsAmt, if (b.drums) 1f else 0f, dt / 0.15f)
        val kick = env(nowMs - pulseAt, PULSE_ATTACK_MS, PULSE_DECAY_MS) * drumsAmt

        // ---- 드롭: 드럼이 1초 이상 쉬다가 다시 들어오는 순간
        // 주행이 시작될 때 이미 드럼이 들리고 있었으면 그건 드롭이 아님
        if (first && beat != null) { first = false; drumsWasOn = b.drums }
        // 드롭 = 그 자체로 맥박 하나(바로 뒤 킥과 겹쳐 두 번 번쩍이지 않게, 다음 맥박은 최소 간격 뒤)
        if (b.drums && !drumsWasOn && nowMs - drumsOffSince >= 1_000) { burstAt = nowMs; pulseAt = nowMs; pulses += nowMs }
        if (!b.drums && drumsWasOn) drumsOffSince = nowMs
        drumsWasOn = b.drums
        val be = nowMs - burstAt
        val burst = env(be, BURST_ATTACK_MS, BURST_DECAY_MS)
        val ripple = if (be in 0 until RIPPLE_MS) be.toFloat() / RIPPLE_MS else -1f

        // ---- 속도 색(2초에 95%: 시간 상수 0.67초)
        val heat = o.heat + ((speedKmh / 80f).coerceIn(0f, 1f) - o.heat) * (1f - exp(-dt / 0.67f))

        // ---- 밝기: 기본의 1.35배 상한(속도 색 포함)
        val room = MAX_GAIN / (1f + HEAT_GAIN * heat) - 1f
        val gain = 1f + minOf(room, PULSE_GAIN * kick + BURST_GAIN * burst)

        // ---- 테이프스톱: 가라앉음(0.7초) → 레이어가 돌아오는 만큼 떠오름
        val layersNow = if (beat != null) b.layers else signalLayers
        if (sinkHoldSince != Long.MIN_VALUE && (layersNow < preTape || nowMs - sinkHoldSince > 4_000)) sinkHoldSince = Long.MIN_VALUE
        if (sinkTarget > 0f && sinkHoldSince == Long.MIN_VALUE) {
            minSince = minOf(minSince, layersNow)
            val span = (preTape - minSince).coerceAtLeast(1)
            sinkTarget = ((preTape - layersNow).toFloat() / span).coerceIn(0f, 1f)
        }
        val sink = if (sinkTarget > o.sink) approach(o.sink, sinkTarget, dt / SINK_MS * 1000f) else approach(o.sink, sinkTarget, dt / 1.0f)

        // ---- 악기 빛(레이어 수 = 보조 빛 수)
        val lights = List(LIGHTS) { i -> approach(o.lights[i], if (i < layersNow) 1f else 0f, dt / 0.6f) }

        // ---- 깜빡이: 박에 맞춘 은은한 번짐(박이 없으면 고정)
        val beatGlow = if (b.phase != null) 0.55f + 0.45f * env(nowMs - pulseAt, PULSE_ATTACK_MS, PULSE_DECAY_MS) else 0.75f
        edgeBaseL = approach(edgeBaseL, if (turn == TurnSignal.LEFT) 1f else 0f, dt / 0.4f)
        edgeBaseR = approach(edgeBaseR, if (turn == TurnSignal.RIGHT) 1f else 0f, dt / 0.4f)

        // ---- 비
        val rain = approach(o.rain, if (rainOn) 1f else 0f, dt / 2f)

        val lifeTarget = when { b.stopped -> 0f; b.life >= 0 -> b.life / 6f; else -> o.life }
        val life = approach(o.life, lifeTarget, dt / LIFE_S)

        out = Out(
            life = life,
            gain = gain, scale = 1f + PULSE_SCALE * kick, sink = sink, heat = heat, lights = lights, ripple = ripple,
            edgeL = edgeBaseL * beatGlow, edgeR = edgeBaseR * beatGlow, rain = rain, sparkle = kick,
        )
        return out
    }

    // 깜빡이 번짐의 켜짐 정도(박 번짐을 곱하기 전)
    private var edgeBaseL = 0f
    private var edgeBaseR = 0f

    private fun approach(v: Float, target: Float, step: Float) = if (v < target) minOf(target, v + step) else maxOf(target, v - step)

    companion object {
        const val LIGHTS = 6
        const val MAX_GAIN = 1.35f
        const val PULSE_GAIN = 0.10f
        const val PULSE_SCALE = 0.04f
        const val BURST_GAIN = 0.35f
        const val HEAT_GAIN = 0.15f
        const val PULSE_ATTACK_MS = 80L
        const val PULSE_DECAY_MS = 400L
        /** 드롭 상승: 킥(80ms)보다 조금 길게 — +35%를 한 프레임에 0.06 이하로(번쩍이지 않게). */
        const val BURST_ATTACK_MS = 150L
        const val BURST_DECAY_MS = 600L
        const val RIPPLE_MS = 1_200L
        const val SINK_MS = 700L
        /** 남은 악기 수가 바뀔 때 빛 원이 따라가는 시간(초) — 한 프레임 밝기 변화가 아주 작게. */
        const val LIFE_S = 1.2f
        /** 맥박 최소 간격: 어느 1초 안에도 2회까지(초당 3회 이상 점멸 금지, 광과민성 기준). 90BPM(667ms)은 박마다, 120BPM 넘는 곡은 박을 건너뜀. */
        const val MIN_PULSE_GAP_MS = 520L

        /** 상승 attack ms(부드럽게) → 감쇠 decay ms. 0~1. */
        fun env(e: Long, attack: Long, decay: Long): Float = when {
            e < 0 -> 0f
            e < attack -> { val k = e.toFloat() / attack; k * k * (3 - 2 * k) }
            e < attack + decay -> { val k = 1f - (e - attack).toFloat() / decay; k * k }
            else -> 0f
        }
    }
}
