package app.playdrive.sim

import app.playdrive.model.Gear
import app.playdrive.model.Seat
import app.playdrive.model.SignalSample
import app.playdrive.model.TurnSignal

/**
 * 시연용 약 60초 주행 시나리오(순수 Kotlin). 시간 t는 시나리오 시작 기준 ms.
 *
 *  0s  P단, 운전자 혼자 착좌, 브레이크 밟음  → 인트로(패드)
 *  3s  D단 / 4.5s 브레이크 놓음
 *  5~15s  0→60km/h 부드러운 가속(1.67 m/s²)   → 비트 시작, 레이어 쌓임
 * 15~30s  60km/h 정속                         → 정속
 * 21~25s  왼쪽 방향지시등 + 완만한 조향 → 차선 변경 → 하이햇 필인
 * 30~31.5s 급제동 60→35km/h(4.6 m/s²)         → 테이프 스톱, 레이어 감소
 * 32.5~38.5s 35→50km/h 부드러운 가속            → 회복
 * 35s  와이퍼 ON(비)                           → 빗소리
 * 46~52s 50→0km/h 부드러운 감속(2.3 m/s²)       → 정차 → 브레이크다운
 * 55s  와이퍼 OFF / 56s P단                     → 주차, 아웃트로
 */
object DemoDriveScenario {
    const val DURATION_MS = 60_000L
    private const val KMH = 1f / 3.6f

    /** (시각 ms, 속도 m/s) 꼭짓점 — 사이는 선형. */
    private val speedPoints = listOf(
        0L to 0f, 5_000L to 0f,
        15_000L to 60 * KMH, 30_000L to 60 * KMH,
        31_500L to 35 * KMH, 32_500L to 35 * KMH,
        38_500L to 50 * KMH, 46_000L to 50 * KMH,
        52_000L to 0f, DURATION_MS to 0f,
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
        21_000L to { at -> SignalSample.TurnSignalChanged(at, TurnSignal.LEFT) },
        22_500L to { at -> SignalSample.SteeringAngle(at, 8f) },
        23_500L to { at -> SignalSample.SteeringAngle(at, -6f) },
        24_500L to { at -> SignalSample.SteeringAngle(at, 0f) },
        25_000L to { at -> SignalSample.TurnSignalChanged(at, TurnSignal.OFF) },
        30_000L to { at -> SignalSample.Brake(at, true) },
        31_500L to { at -> SignalSample.Brake(at, false) },
        35_000L to { at -> SignalSample.Wiper(at, true) },
        46_000L to { at -> SignalSample.Brake(at, true) },
        55_000L to { at -> SignalSample.Wiper(at, false) },
        56_000L to { at -> SignalSample.GearChanged(at, Gear.PARK) },
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
