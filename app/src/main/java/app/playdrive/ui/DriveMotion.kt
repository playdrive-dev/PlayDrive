package app.playdrive.ui

/**
 * 주행 중(requiresDO) 무드 화면의 "이벤트 때만 한 번 반응(최소 변화)". 순수 Kotlin, 시간은 밖에서 넣는다.
 * 근거: Pleos 운전자 주의 분산 가이드 — 장식적·반복적 움직임 지양, 정보 전달에 필요한 최소한만.
 *
 * 기본은 정지. 아래 음악 이벤트에서만 한 번 전환하고, 전환이 끝나면 그 모습으로 유지한다.
 *  - 레이어 추가(출발 드롭 포함): 메인 빛 크기 +6%, 밝기 +8% (1.5초 ease-in-out)
 *  - 레이어 감소(브레이크다운 진입 등): 반대로 한 단계 (1.5초)
 *  - 테이프스톱(급제동·급가속): 밝기 −25% (0.7초). 레이어가 테이프스톱 전 수준으로 돌아오면 1.5초에 걸쳐 원래대로
 *  - 와이퍼 ON/OFF: 물방울 2초 페이드 인/아웃
 * 제한:
 *  - 전환 시작 간격 ≥ [MIN_GAP_MS](10초). 그 사이 이벤트는 모아서(마지막 상태 기준) 다음 전환 한 번에 반영
 *  - 전환 길이 ≤ [MAX_TRANSITION_MS](2초). 전환 중이 아니면 값이 변하지 않는다(→ 다시 그릴 필요 없음)
 *  - 크기·밝기 단계는 출발 순간 대비 ±[MAX_LEVEL] 안(오래 달려도 빛이 계속 커지지 않게)
 */
class DriveMotion(initialRain: Boolean, initialLayers: Int) {

    /** 전환이 끝난 뒤 유지되는 모습. */
    data class Look(val level: Int = 0, val dim: Boolean = false, val rain: Boolean = false)

    /** 렌더러에 넘길 값: 메인 빛 크기·밝기 배수, 물방울 세기(0~1). */
    data class Values(val scale: Float, val gain: Float, val rain: Float)

    private var shown = Look(rain = initialRain)
    private var from = shown
    private var start = Long.MIN_VALUE / 2
    private var dur = 0L
    private var durLevel = 0L
    private var durDim = 0L
    private var durRain = 0L

    // 입력(마지막 상태)
    private var layersNow = initialLayers
    private var layersAtLast = initialLayers
    private var rainNow = initialRain
    private var tapePending = false
    private var preTape = 0

    /** 지금까지 시작한 전환 (시작 ms, 길이 ms). 테스트·로그용. */
    val transitions = mutableListOf<Pair<Long, Long>>()

    fun layers(n: Int) { layersNow = n }

    /** 테이프스톱. [layers]보다 먼저 부른다(줄어들기 전 레이어 수를 기억). */
    fun tapeStop() { if (!tapePending && !shown.dim) preTape = layersNow; tapePending = true }

    fun rain(on: Boolean) { rainNow = on }

    fun transitioning(nowMs: Long) = nowMs >= start && nowMs < start + dur

    /** 지금 시작할 수 있으면 다음 전환을 시작한다. 반환 = 지금 전환 중인지. */
    fun update(nowMs: Long): Boolean {
        if (transitioning(nowMs)) return true
        if (nowMs < start + MIN_GAP_MS) return false
        val target = target()
        if (target == shown) { layersAtLast = layersNow; return false }
        from = shown; shown = target; start = nowMs
        durLevel = if (target.level != from.level) LEVEL_MS else 0
        durDim = when { target.dim == from.dim -> 0; target.dim -> DIM_IN_MS; else -> DIM_OUT_MS }
        durRain = if (target.rain != from.rain) RAIN_MS else 0
        dur = maxOf(durLevel, durDim, durRain)
        layersAtLast = layersNow; tapePending = false
        transitions += nowMs to dur
        return true
    }

    /** 다음에 [update]를 불러야 할 시각(전환 끝 또는 다음 전환 가능 시각). 할 일이 없으면 null(완전 정지). */
    fun nextWakeMs(nowMs: Long): Long? = when {
        transitioning(nowMs) -> start + dur
        target() == shown -> null
        else -> maxOf(nowMs, start + MIN_GAP_MS)
    }

    private fun target(): Look {
        val net = layersNow - layersAtLast
        val level = (shown.level + net.coerceIn(-1, 1)).coerceIn(-MAX_LEVEL, MAX_LEVEL)
        val dim = when {
            tapePending -> true
            shown.dim && layersNow >= preTape -> false
            else -> shown.dim
        }
        return Look(level, dim, rainNow)
    }

    fun values(nowMs: Long): Values {
        val e = (nowMs - start).toFloat()
        fun k(d: Long) = if (d <= 0) 1f else smooth((e / d).coerceIn(0f, 1f))
        val kl = k(durLevel); val kd = k(durDim); val kr = k(durRain)
        val lvl = from.level + (shown.level - from.level) * kl
        val dimF = lerp(if (from.dim) DIM else 1f, if (shown.dim) DIM else 1f, kd)
        return Values(
            scale = 1f + SCALE_STEP * lvl,
            gain = (1f + GAIN_STEP * lvl) * dimF,
            rain = lerp(if (from.rain) 1f else 0f, if (shown.rain) 1f else 0f, kr),
        )
    }

    companion object {
        const val MIN_GAP_MS = 10_000L
        const val MAX_TRANSITION_MS = 2_000L
        const val LEVEL_MS = 1_500L
        const val DIM_IN_MS = 700L
        const val DIM_OUT_MS = 1_500L
        const val RAIN_MS = 2_000L
        const val SCALE_STEP = 0.06f
        const val GAIN_STEP = 0.08f
        const val DIM = 0.75f
        const val MAX_LEVEL = 2
        val NEUTRAL = Values(1f, 1f, 0f)
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smooth(k: Float) = k * k * (3 - 2 * k)
    }
}
