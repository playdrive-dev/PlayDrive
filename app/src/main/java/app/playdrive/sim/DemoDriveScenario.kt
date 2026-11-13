package app.playdrive.sim

import app.playdrive.model.Gear
import app.playdrive.model.NaviSignal
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

    /** 기본 데모(74초)를 [DriveScenario]로. */
    val scenario: DriveScenario by lazy { DriveScenario("데모 드라이브(74초)", DURATION_MS, { speedAt(it) }, ::samples) }

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

/** 시연용 주행 시나리오 하나(순수). 신호원은 [samples]를 10Hz로 재생한다. */
class DriveScenario(
    val name: String,
    val durationMs: Long,
    val speedAt: (Long) -> Float,
    val samples: (fromExclusive: Long, toInclusive: Long, baseMs: Long) -> List<SignalSample>,
) {
    companion object {
        private const val KMH = 1f / 3.6f

        /** (시각, 속도 km/h) 꼭짓점 + 이산 신호로 시나리오를 만든다. */
        fun of(name: String, speedKmh: List<Pair<Long, Float>>, discrete: List<Pair<Long, (Long) -> SignalSample>>): DriveScenario {
            val pts = speedKmh.map { (t, v) -> t to v * KMH }
            val end = pts.last().first
            fun speedAt(t: Long): Float {
                if (t <= 0) return pts.first().second
                for (i in 0 until pts.size - 1) {
                    val (t0, v0) = pts[i]; val (t1, v1) = pts[i + 1]
                    if (t in t0..t1) return if (t1 == t0) v1 else v0 + (v1 - v0) * (t - t0).toFloat() / (t1 - t0)
                }
                return pts.last().second
            }
            return DriveScenario(name, end, ::speedAt) { from, to, base ->
                val out = discrete.filter { (t, _) -> t > from && t <= to }.map { (t, f) -> f(base + t) }.toMutableList()
                out += SignalSample.Speed(base + to, speedAt(to))
                out
            }
        }

        private fun start(): List<Pair<Long, (Long) -> SignalSample>> = listOf(
            0L to { at -> SignalSample.GearChanged(at, Gear.PARK) },
            0L to { at -> SignalSample.SeatOccupancy(at, Seat.DRIVER, true) },
            0L to { at -> SignalSample.SeatOccupancy(at, Seat.PASSENGER, false) },
            0L to { at -> SignalSample.SeatOccupancy(at, Seat.REAR_LEFT, false) },
            0L to { at -> SignalSample.SeatOccupancy(at, Seat.REAR_RIGHT, false) },
            0L to { at -> SignalSample.Brake(at, true) },
            0L to { at -> SignalSample.TurnSignalChanged(at, TurnSignal.OFF) },
            0L to { at -> SignalSample.Wiper(at, false) },
            0L to { at -> SignalSample.SteeringAngle(at, 0f) },
        )

        private fun steer(t: Long, deg: Float): Pair<Long, (Long) -> SignalSample> = t to { at -> SignalSample.SteeringAngle(at, deg) }
        private fun brake(t: Long, on: Boolean): Pair<Long, (Long) -> SignalSample> = t to { at -> SignalSample.Brake(at, on) }
        private fun turn(t: Long, s: TurnSignal): Pair<Long, (Long) -> SignalSample> = t to { at -> SignalSample.TurnSignalChanged(at, s) }
        private fun gear(t: Long, g: Gear): Pair<Long, (Long) -> SignalSample> = t to { at -> SignalSample.GearChanged(at, g) }
        /** 완만한 차선 변경: 깜빡이 4초 + 작은 조향. */
        private fun laneChange(t: Long, s: TurnSignal): List<Pair<Long, (Long) -> SignalSample>> {
            val k = if (s == TurnSignal.LEFT) 1f else -1f
            return listOf(turn(t, s), steer(t + 1_500, 6f * k), steer(t + 2_500, -4f * k), steer(t + 3_500, 0f), turn(t + 4_000, TurnSignal.OFF))
        }

        private fun navi(t: Long, n: NaviSignal): Pair<Long, (Long) -> SignalSample> = t to { at -> SignalSample.Navi(at, n) }

        /**
         * demo:safe(6분, 내비 안내 모드) — 두 곡 연속 완주(S, A).
         *  1s 내비 안내 시작(시연용 경로: 제한 50) → 카운트인 → 곡 1 → 6s 출발(부드러운 출발)
         *  곡 1(약 5 ~ 176초): 차선 변경 ×2, 부드러운 정지·출발, 137s 우회전 300m 안 → 깜빡이 + 완만한 회전 = 깔끔한 회전 → 64마디 S
         *  곡 2(약 176 ~ 346초): 200~225s 어린이 보호구역(제한 30, 28km/h로 통과) = 깔끔한 구간 통과, 257s 급제동 한 번(악기 −2),
         *       차선 변경·정지·출발, 330s 목적지 1km 안 → 클라이맥스 → 64마디 A
         *  351s 마지막 정지 → 352s 도착(곡 3은 시작만이라 세지 않음) → 355s P
         * 보호구역·제한속도·회전·도착은 시연용 경로 데이터(에뮬레이터 내비는 위치가 움직이지 않음). 실차는 NaviHelper 값.
         */
        val SAFE: DriveScenario by lazy {
            of("demo:safe(6분, 내비 안내)", listOf(
                0L to 0f, 5_000L to 0f, 17_000L to 50f, 60_000L to 50f, 70_000L to 0f,
                78_000L to 0f, 92_000L to 48f, 188_000L to 48f, 196_000L to 28f, 228_000L to 28f, 240_000L to 48f,
                257_000L to 48f, 258_500L to 25f, 278_000L to 48f,
                300_000L to 48f, 310_000L to 0f, 318_000L to 0f, 332_000L to 48f, 340_000L to 48f, 351_000L to 0f, 360_000L to 0f,
            ), start() + listOf(
                navi(1_000, NaviSignal.RouteStarted(9_800)), navi(1_000, NaviSignal.SpeedLimit(50, schoolZone = false, demo = true)),
                navi(1_000, NaviSignal.NextTurn(TurnSignal.RIGHT, 1_900)),
                gear(3_000, Gear.DRIVE), brake(4_500, false),
                brake(60_000, true), brake(77_500, false),
                navi(130_000, NaviSignal.NextTurn(TurnSignal.RIGHT, 600)), navi(137_000, NaviSignal.NextTurn(TurnSignal.RIGHT, 280)),
                turn(139_000, TurnSignal.RIGHT), steer(141_000, -15f), steer(142_000, -35f), steer(143_000, -15f), steer(144_000, 0f),
                turn(145_000, TurnSignal.OFF), navi(146_000, NaviSignal.NextTurn(TurnSignal.LEFT, 4_200)),
                navi(200_000, NaviSignal.SpeedLimit(30, schoolZone = true, demo = true)), navi(225_000, NaviSignal.SpeedLimit(50, schoolZone = false, demo = true)),
                brake(257_000, true), brake(258_500, false),
                brake(300_000, true), brake(317_500, false),
                navi(330_000, NaviSignal.Remaining(1_000)),
                brake(340_000, true), navi(352_000, NaviSignal.Arrived), gear(355_000, Gear.PARK),
            ) + laneChange(30_000, TurnSignal.LEFT) + laneChange(110_000, TurnSignal.RIGHT) + laneChange(290_000, TurnSignal.RIGHT))
        }

        /**
         * demo:rough(약 4분) — 흔들림 → 악기 빠짐 → 연주 정지 → 15초 부드럽게 → 카운트인 → 같은 곡 다시 → 완주(C).
         *  4~7s 급가속(워밍업이라 1 밑으로 안 내려감) / 28s 급조향 / 31s 급제동(−2) / 34s 급가속 → 악기 1개(위험, "조금만 부드럽게요")
         *  39.5s 급조향 → 0 → 테이프스톱, "연주가 멈췄어요…" → 54.5s(흔들림 없이 15초) "다시 시작할게요" + 카운트인 → 같은 곡을 악기 3개로
         *  70~214s 16초마다 급조향(악기 3↔4) → 64마디 완주(C) → 232~242s 정지 → 246s P
         */
        val ROUGH: DriveScenario by lazy {
            of("demo:rough(약 4분)", listOf(
                0L to 0f, 4_000L to 0f, 5_800L to 40f, 31_000L to 40f, 32_500L to 20f, 34_000L to 20f,
                35_800L to 55f, 232_000L to 55f, 242_000L to 0f, 250_000L to 0f,
            ), start() + listOf(
                gear(3_000, Gear.DRIVE), brake(3_800, false),
                steer(28_000, 90f), brake(31_000, true), brake(32_500, false), steer(39_500, 0f),
                brake(232_000, true), gear(246_000, Gear.PARK),
            ) + (0 until 10).map { k -> steer(70_000L + k * 16_000L, if (k % 2 == 0) 90f else 0f) })
        }
    }
}
