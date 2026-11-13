package app.playdrive.model

/** 차량 신호 한 건. timeMs는 단조 증가 시계(ms) 기준 — 실제 차량은 SystemClock.elapsedRealtime(). */
sealed interface SignalSample {
    val timeMs: Long

    /** 차량 속도(m/s). 실제 차량에서는 약 10Hz로 들어온다. */
    data class Speed(override val timeMs: Long, val mps: Float) : SignalSample
    data class GearChanged(override val timeMs: Long, val gear: Gear) : SignalSample
    /** 브레이크 페달 밟음 여부(Pleos SDK는 깊이를 주지 않는다). */
    data class Brake(override val timeMs: Long, val pressed: Boolean) : SignalSample
    data class TurnSignalChanged(override val timeMs: Long, val state: TurnSignal) : SignalSample
    data class Wiper(override val timeMs: Long, val on: Boolean) : SignalSample
    /** 착좌 — 신호원에서 보정(SDK 2.3.2 반전 버그)을 마친 값. */
    data class SeatOccupancy(override val timeMs: Long, val seat: Seat, val occupied: Boolean) : SignalSample
    /** 조향각(도). 신호원에서 부호 보정을 마친 값. 양수 = 왼쪽. */
    data class SteeringAngle(override val timeMs: Long, val degrees: Float) : SignalSample
    /** 내비(NaviHelper 또는 시연용 경로 데이터). */
    data class Navi(override val timeMs: Long, val signal: NaviSignal) : SignalSample
}

/** 내비 신호(앱이 쓰는 것만). 실차 = NaviHelper([app.playdrive.signal.PleosNaviFeed]), 시연 = 데모 경로 데이터. */
sealed interface NaviSignal {
    /** 경로 안내 시작(onRouteStarted). */
    data class RouteStarted(val distanceM: Int) : NaviSignal
    /** 다음 회전(onTBTInfo 첫 항목). turn = LEFT/RIGHT(그 밖의 회전·직진은 OFF). */
    data class NextTurn(val turn: TurnSignal, val distanceM: Int) : NaviSignal
    /** 목적지까지 남은 거리(onDrivingInfo). */
    data class Remaining(val distanceM: Int) : NaviSignal
    /** 제한속도(onCurrentRoadSpeedLimit / CameraAlert). schoolZone = 어린이 보호구역(CameraType SCHOOL_ZONE·CautionPoint SCHOOL_ZONE). demo = 시연용 구간 데이터. */
    data class SpeedLimit(val kmh: Int, val schoolZone: Boolean, val demo: Boolean = false) : NaviSignal
    /** 목적지 도착(onDestinationArrived). */
    data object Arrived : NaviSignal
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
enum class TurnSignal { OFF, LEFT, RIGHT }
enum class Seat { DRIVER, PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT }

fun interface SignalSink {
    fun onSample(sample: SignalSample)
}

/** 실제 차량·데모·시뮬레이션이 공통으로 구현하는 신호원. */
interface VehicleSignalSource {
    val name: String
    fun start(sink: SignalSink)
    fun stop()
}
