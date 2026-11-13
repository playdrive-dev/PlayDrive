package app.playdrive.engine

import app.playdrive.model.Gear
import app.playdrive.model.Seat
import app.playdrive.model.SignalSample
import app.playdrive.model.TurnSignal
import kotlin.math.abs

/**
 * 차량 신호 → 운전 이벤트. 순수 Kotlin(안드로이드 의존 없음), 단일 스레드에서 호출한다.
 * 시간은 입력 샘플의 timeMs만 사용하므로 테스트에서 결정적으로 재현된다.
 */
class DrivingEventEngine(private val t: DrivingThresholds = DrivingThresholds()) {

    var state = DrivingState(harmony = t.harmonyInitial)
        private set

    // 속도 원시값(이동평균용)과 평활 속도 이력(가속도용)
    private val rawSpeeds = ArrayDeque<Pair<Long, Float>>()
    private val smoothHistory = ArrayDeque<Pair<Long, Float>>()
    private var lastSpeedTimeMs: Long? = null

    private var moving = false
    private var stopCandidateSinceMs: Long? = null

    private var cruising = false
    private var cruiseCandidateSinceMs: Long? = null

    private var smoothSign = 0
    private var smoothSinceMs: Long? = null
    private var smoothEmitted = false

    private var lastHarshAccelMs = Long.MIN_VALUE / 2
    private var lastHarshBrakeMs = Long.MIN_VALUE / 2
    private var lastHarshSteerMs = Long.MIN_VALUE / 2
    private var lastAnyHarshMs = Long.MIN_VALUE / 2

    private var turnOnSinceMs: Long? = null
    private var lastSteer: Pair<Long, Float>? = null
    private val occupied = mutableMapOf<Seat, Boolean>()

    private var harmony = t.harmonyInitial

    fun onSample(sample: SignalSample): List<DrivingEvent> {
        val out = mutableListOf<DrivingEvent>()
        when (sample) {
            is SignalSample.Speed -> onSpeed(sample.timeMs, sample.mps, out)
            is SignalSample.GearChanged -> onGear(sample, out)
            is SignalSample.Brake -> state = state.copy(brakePressed = sample.pressed)
            is SignalSample.TurnSignalChanged -> onTurnSignal(sample, out)
            is SignalSample.Wiper -> onWiper(sample, out)
            is SignalSample.SeatOccupancy -> onSeat(sample, out)
            is SignalSample.SteeringAngle -> onSteering(sample, out)
            is SignalSample.Navi -> out += DrivingEvent.Navi(sample.timeMs, sample.signal)
        }
        state = state.copy(timeMs = sample.timeMs, harmony = harmony)
        return out
    }

    // ------------------------------------------------------------------ speed
    private fun onSpeed(now: Long, mps: Float, out: MutableList<DrivingEvent>) {
        rawSpeeds.addLast(now to mps)
        while (rawSpeeds.size > 1 && now - rawSpeeds.first().first > t.speedSmoothingWindowMs) rawSpeeds.removeFirst()
        val smooth = rawSpeeds.map { it.second }.average().toFloat()

        smoothHistory.addLast(now to smooth)
        while (smoothHistory.size > 2 && now - smoothHistory[1].first >= t.accelWindowMs) smoothHistory.removeFirst()
        val (refT, refV) = smoothHistory.first()
        val accel = if (now - refT >= t.accelWindowMs / 2) (smooth - refV) * 1000f / (now - refT) else 0f

        val dtMs = lastSpeedTimeMs?.let { (now - it).coerceIn(0, 500) } ?: 0
        lastSpeedTimeMs = now

        updateMotion(now, smooth, out)
        updateHarsh(now, accel, out)
        updateSmooth(now, accel, out)
        updateCruise(now, smooth, accel, out)
        updateHarmony(now, accel, dtMs)

        state = state.copy(speedMps = smooth, accelMps2 = accel, moving = moving, cruising = cruising)
    }

    private fun updateMotion(now: Long, v: Float, out: MutableList<DrivingEvent>) {
        if (!moving && v >= t.departSpeedMps) {
            moving = true
            stopCandidateSinceMs = null
            out += DrivingEvent.Departed(now)
        } else if (moving) {
            if (v < t.stoppedSpeedMps) {
                val since = stopCandidateSinceMs ?: now.also { stopCandidateSinceMs = it }
                if (now - since >= t.stoppedMinDurationMs) {
                    moving = false
                    stopCandidateSinceMs = null
                    out += DrivingEvent.Stopped(now)
                }
            } else {
                stopCandidateSinceMs = null
            }
        }
    }

    private fun updateHarsh(now: Long, accel: Float, out: MutableList<DrivingEvent>) {
        if (accel >= t.harshAccelMps2 && now - lastHarshAccelMs >= t.harshCooldownMs) {
            lastHarshAccelMs = now; lastAnyHarshMs = now
            harmony -= t.harmonyPenaltyHarshAccel
            out += DrivingEvent.HarshAcceleration(now, accel)
        }
        if (-accel >= t.harshBrakeMps2 && now - lastHarshBrakeMs >= t.harshCooldownMs) {
            lastHarshBrakeMs = now; lastAnyHarshMs = now
            harmony -= t.harmonyPenaltyHarshBrake
            out += DrivingEvent.HarshBraking(now, -accel, state.brakePressed)
        }
        harmony = harmony.coerceIn(0f, 100f)
    }

    private fun updateSmooth(now: Long, accel: Float, out: MutableList<DrivingEvent>) {
        val mag = abs(accel)
        val sign = when {
            mag < t.smoothMinMps2 -> 0
            accel > 0 && accel < t.harshAccelMps2 -> 1
            accel < 0 && -accel < t.harshBrakeMps2 -> -1
            else -> 0 // 급가감속 구간은 부드러운 구간이 아니다
        }
        if (sign == 0 || sign != smoothSign) {
            smoothSign = sign
            smoothSinceMs = if (sign == 0) null else now
            smoothEmitted = false
            return
        }
        val since = smoothSinceMs ?: return
        if (!smoothEmitted && now - since >= t.smoothMinDurationMs && now - lastAnyHarshMs >= t.smoothMinDurationMs) {
            smoothEmitted = true
            out += if (sign > 0) DrivingEvent.SmoothAcceleration(now, accel) else DrivingEvent.SmoothDeceleration(now, -accel)
        }
    }

    private fun updateCruise(now: Long, v: Float, accel: Float, out: MutableList<DrivingEvent>) {
        val calm = moving && v >= t.cruiseMinSpeedMps && abs(accel) <= t.cruiseMaxAbsAccelMps2
        if (!cruising) {
            if (calm) {
                val since = cruiseCandidateSinceMs ?: now.also { cruiseCandidateSinceMs = it }
                if (now - since >= t.cruiseMinDurationMs) {
                    cruising = true
                    out += DrivingEvent.CruiseStarted(now, v)
                }
            } else {
                cruiseCandidateSinceMs = null
            }
        } else if (!moving || v < t.cruiseMinSpeedMps || abs(accel) > t.cruiseExitAbsAccelMps2) {
            cruising = false
            cruiseCandidateSinceMs = null
            out += DrivingEvent.CruiseEnded(now)
        }
    }

    private fun updateHarmony(now: Long, accel: Float, dtMs: Long) {
        if (!moving || dtMs == 0L) return
        if (now - lastAnyHarshMs < t.harmonyCalmAfterHarshMs) return
        if (accel >= t.harshAccelMps2 || -accel >= t.harshBrakeMps2) return
        val rate = if (cruising) t.harmonyGainPerSecCruise else t.harmonyGainPerSecMoving
        harmony = (harmony + rate * dtMs / 1000f).coerceIn(0f, 100f)
    }

    // ------------------------------------------------------------------ others
    private fun onGear(s: SignalSample.GearChanged, out: MutableList<DrivingEvent>) {
        if (s.gear == state.gear) return
        state = state.copy(gear = s.gear)
        out += DrivingEvent.GearChanged(s.timeMs, s.gear)
        if (s.gear == Gear.PARK) {
            if (cruising) { cruising = false; out += DrivingEvent.CruiseEnded(s.timeMs) }
            out += DrivingEvent.Parked(s.timeMs)
        }
    }

    private fun onTurnSignal(s: SignalSample.TurnSignalChanged, out: MutableList<DrivingEvent>) {
        val prev = state.turnSignal
        if (s.state == prev) return
        if (prev != TurnSignal.OFF) {
            val since = turnOnSinceMs
            out += DrivingEvent.TurnSignalOff(s.timeMs)
            if (since != null && s.timeMs - since <= t.laneChangeMaxSignalMs && moving &&
                state.speedMps >= t.laneChangeMinSpeedMps
            ) {
                out += DrivingEvent.LaneChange(s.timeMs, prev)
            }
            turnOnSinceMs = null
        }
        if (s.state != TurnSignal.OFF) {
            turnOnSinceMs = s.timeMs
            out += DrivingEvent.TurnSignalOn(s.timeMs, s.state)
        }
        state = state.copy(turnSignal = s.state)
    }

    private fun onWiper(s: SignalSample.Wiper, out: MutableList<DrivingEvent>) {
        if (s.on == state.raining) return
        state = state.copy(raining = s.on)
        out += if (s.on) DrivingEvent.RainStarted(s.timeMs) else DrivingEvent.RainStopped(s.timeMs)
    }

    private fun onSeat(s: SignalSample.SeatOccupancy, out: MutableList<DrivingEvent>) {
        occupied[s.seat] = s.occupied
        val driver = occupied[Seat.DRIVER] == true
        val solo = driver && occupied.none { (seat, occ) -> seat != Seat.DRIVER && occ }
        if (driver != state.driverPresent || solo != state.solo) {
            state = state.copy(driverPresent = driver, solo = solo)
            out += DrivingEvent.OccupancyChanged(s.timeMs, driver, solo)
        }
    }

    private fun onSteering(s: SignalSample.SteeringAngle, out: MutableList<DrivingEvent>) {
        val prev = lastSteer
        lastSteer = s.timeMs to s.degrees
        if (prev == null) return
        val dt = (s.timeMs - prev.first).coerceIn(t.steerRateMinDtMs, t.steerRateMaxDtMs)
        val rate = abs(s.degrees - prev.second) * 1000f / dt
        if (rate >= t.harshSteerRateDegPerSec && state.speedMps >= t.harshSteerMinSpeedMps &&
            s.timeMs - lastHarshSteerMs >= t.harshCooldownMs
        ) {
            lastHarshSteerMs = s.timeMs; lastAnyHarshMs = s.timeMs
            harmony = (harmony - t.harmonyPenaltyHarshSteer).coerceIn(0f, 100f)
            out += DrivingEvent.HarshSteering(s.timeMs, rate)
        }
    }
}
