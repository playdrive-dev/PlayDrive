package app.playdrive.engine

import app.playdrive.model.Gear
import app.playdrive.model.TurnSignal

sealed interface DrivingEvent {
    val timeMs: Long

    data class Departed(override val timeMs: Long) : DrivingEvent
    data class Stopped(override val timeMs: Long) : DrivingEvent
    data class CruiseStarted(override val timeMs: Long, val speedMps: Float) : DrivingEvent
    data class CruiseEnded(override val timeMs: Long) : DrivingEvent
    data class SmoothAcceleration(override val timeMs: Long, val accelMps2: Float) : DrivingEvent
    data class SmoothDeceleration(override val timeMs: Long, val decelMps2: Float) : DrivingEvent
    data class HarshAcceleration(override val timeMs: Long, val accelMps2: Float) : DrivingEvent
    data class HarshBraking(override val timeMs: Long, val decelMps2: Float, val brakePressed: Boolean) : DrivingEvent
    data class TurnSignalOn(override val timeMs: Long, val direction: TurnSignal) : DrivingEvent
    data class TurnSignalOff(override val timeMs: Long) : DrivingEvent
    data class LaneChange(override val timeMs: Long, val direction: TurnSignal) : DrivingEvent
    data class HarshSteering(override val timeMs: Long, val rateDegPerSec: Float) : DrivingEvent
    data class RainStarted(override val timeMs: Long) : DrivingEvent
    data class RainStopped(override val timeMs: Long) : DrivingEvent
    data class GearChanged(override val timeMs: Long, val gear: Gear) : DrivingEvent
    data class Parked(override val timeMs: Long) : DrivingEvent
    data class OccupancyChanged(override val timeMs: Long, val driverPresent: Boolean, val solo: Boolean) : DrivingEvent
    /** 내비 신호를 그대로 넘김(연주 기록이 회전·구간·도착을 판정). */
    data class Navi(override val timeMs: Long, val signal: app.playdrive.model.NaviSignal) : DrivingEvent
}

/** 엔진의 현재 상태 스냅샷. harmony는 정차 중에만 화면에 보여준다(UI 정책). */
data class DrivingState(
    val timeMs: Long = 0,
    val speedMps: Float = 0f,
    val accelMps2: Float = 0f,
    val gear: Gear = Gear.UNKNOWN,
    val moving: Boolean = false,
    val cruising: Boolean = false,
    val brakePressed: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val raining: Boolean = false,
    val driverPresent: Boolean = false,
    val solo: Boolean = false,
    val harmony: Float = 50f,
)
