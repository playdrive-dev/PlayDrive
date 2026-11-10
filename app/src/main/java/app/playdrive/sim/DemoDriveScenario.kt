package app.playdrive.sim

import app.playdrive.model.Gear
import app.playdrive.model.Seat
import app.playdrive.model.SignalSample
import app.playdrive.model.TurnSignal

/**
 * 시연용 약 74초 주행 시나리오(순수 Kotlin). 시간 t는 시나리오 시작 기준 ms.
 * 반응형 연주 6개 장면이 모두 한 번 이상 나오도록 구성:
 *
 *  0s     P단, 혼자 탑승, 속도 0          → keys+synth 인트로, 먹먹한 톤(① 속도 톤)
 *  3s     D단 / 4.5s 브레이크 놓음
 *  5~14s  0→50km/h 부드러운 가속(1.5 m/s²) → ⑤ 출발 드롭(drums+bass, 다음 다운비트), ② 상행 아르페지오, 톤이 밝아짐
 * 14~24s  50km/h 정속                       → +perc
 * 18~22.5s 왼쪽 방향지시등 + 완만한 조향    → ④ 셰이커 8분음표(끈 뒤 마디 끝까지), 차선 변경
 * 24s     브레이크 시작, 24~30s 50→0 부드러운 감속 → ③ 근음+5도
 * 30~37s  신호 대기(7초)                    → ⑤ 2초 후 브레이크다운(keys+synth만 + 먹먹)
 * 36.5s   브레이크 놓음, 37.5~44.5s 0→55km/h → ⑤ 출발 드롭 + ② 아르페지오
 * 44.5~53s 55km/h 정속, 46~52s 비(와이퍼)  → 비: 로우패스+리버브
 * 53~54.5s 급제동 55→30km/h(4.6 m/s²)       → ⑥ 테이프스톱(연주 레이어도 멈춤), 한 마디씩 복구
 * 55.5~60.5s 30→45km/h 부드러운 재가속, 62s 브레이크 → ③ 근음+5도, 62~69s 45→0(1.8 m/s²)
 * 69~74s  정차 → 72s P단                    → 주차
 */
object DemoDriveScenario {
    const val DURATION_MS = 74_000L
    private const val KMH = 1f / 3.6f

    /** (시각 ms, 속도 m/s) 꼭짓점 — 사이는 선형. */
    private val speedPoints = listOf(
        0L to 0f, 5_000L to 0f,
        14_000L to 50 * KMH, 24_000L to 50 * KMH,
        30_000L to 0f, 37_500L to 0f,
        44_500L to 55 * KMH, 53_000L to 55 * KMH,
        54_500L to 30 * KMH, 55_500L to 30 * KMH,
        60_500L to 45 * KMH, 62_000L to 45 * KMH,
        69_000L to 0f, DURATION_MS to 0f,
    )

    fun speedAt(t: Long): Float {
        if (t <= 0) return 0f
        for (i in 0 until speedPoints.size - 1) {
            val (t0, v0) = speedPoints[i]; val (t1, v1) = speedPoints[i + 1]
            if (t in t0..t1) return v0 + (v1 - v0) * (t - t0).toFloat() / (t1 - t0)
        }
        return 0f
    }

    /** 이산 신호: (시각, 샘플 생성기(절대시각)). */
    private val discrete: List<Pair<Long, (Long) -> SignalSample>> = listOf(
        0L to { at -> SignalSample.GearChanged(at, Gear.PARK) },
        0L to { at -> SignalSample.SeatOccupancy(at, Seat.DRIVER, true) },
        0L to { at -> SignalSample.SeatOccupancy(at, Seat.PASSENGER, false) },
        0L to { at -> SignalSample.SeatOccupancy(at, Seat.REAR_LEFT, false) },
        0L to { at -> SignalSample.SeatOccupancy(at, Seat.REAR_RIGHT, false) },
        0L to { at -> SignalSample.Brake(at, true) },
        0L to { at -> SignalSample.TurnSignalChanged(at, TurnSignal.OFF) },
        0L to { at -> SignalSample.Wiper(at, false) },
        0L to { at -> SignalSample.SteeringAngle(at, 0f) },
        3_000L to { at -> SignalSample.GearChanged(at, Gear.DRIVE) },
        4_500L to { at -> SignalSample.Brake(at, false) },
        18_000L to { at -> SignalSample.TurnSignalChanged(at, TurnSignal.LEFT) },
        19_500L to { at -> SignalSample.SteeringAngle(at, 6f) },
        20_500L to { at -> SignalSample.SteeringAngle(at, -4f) },
        21_500L to { at -> SignalSample.SteeringAngle(at, 0f) },
        22_500L to { at -> SignalSample.TurnSignalChanged(at, TurnSignal.OFF) },
        24_000L to { at -> SignalSample.Brake(at, true) },
        36_500L to { at -> SignalSample.Brake(at, false) },
        46_000L to { at -> SignalSample.Wiper(at, true) },
        52_000L to { at -> SignalSample.Wiper(at, false) },
        53_000L to { at -> SignalSample.Brake(at, true) },
        54_500L to { at -> SignalSample.Brake(at, false) },
        62_000L to { at -> SignalSample.Brake(at, true) },
        72_000L to { at -> SignalSample.GearChanged(at, Gear.PARK) },
    )

    /** (fromExclusive, toInclusive] 구간의 이산 신호 + to 시점 속도 샘플. baseMs는 절대시각 원점. */
    fun samples(fromExclusive: Long, toInclusive: Long, baseMs: Long): List<SignalSample> {
        val out = discrete.filter { (t, _) -> t > fromExclusive && t <= toInclusive }
            .map { (t, f) -> f(baseMs + t) }
            .toMutableList()
        out += SignalSample.Speed(baseMs + toInclusive, speedAt(toInclusive))
        return out
    }
}
