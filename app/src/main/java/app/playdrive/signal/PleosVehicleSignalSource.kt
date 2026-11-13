package app.playdrive.signal

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.playdrive.model.Gear
import app.playdrive.model.Seat
import app.playdrive.model.SignalSample
import app.playdrive.model.SignalSink
import app.playdrive.model.TurnSignal
import app.playdrive.model.VehicleSignalSource

/**
 * 실제 차량 신호원: 속도·기어 = AAOS CarPropertyManager, 나머지 = Pleos Vehicle SDK 2.3.2.
 * 어떤 신호가 실패하면 그 신호만 빠지고 나머지는 계속 동작한다.
 */
class PleosVehicleSignalSource(
    private val context: Context,
    private val corrections: Corrections = Corrections(),
) : VehicleSignalSource {

    /** 에뮬레이터 검증(2026-11-10)에서 확인한 보정값. 실차·새 SDK에서 다시 확인할 것. */
    data class Corrections(
        /** SDK 2.3.2의 착좌 Boolean이 실제와 반대로 온다. */
        val invertSeatOccupancy: Boolean = true,
        /** SDK 조향각 부호가 Virtual Console과 반대. 콘솔 기준(양수=왼쪽 가정)으로 맞춘다. */
        val steeringSign: Float = -1f,
    )

    override val name = "실제 차량 신호"

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private val cleanups = mutableListOf<() -> Unit>()

    private fun now() = SystemClock.elapsedRealtime()

    override fun start(sink: SignalSink) {
        startCarProperties(sink)
        val v = Vehicle(context)
        runCatching { v.initialize() }.onFailure { log("Vehicle.initialize 실패: $it") }
        vehicle = v
        guard("TurnSignal") { startTurnSignal(v, sink) }
        guard("Brake") { startBrake(v, sink) }
        guard("Wiper") { startWiper(v, sink) }
        guard("Seat") { startSeats(v, sink) }
        guard("Steering") { startSteering(v, sink) }
        guard("Navi") { PleosNaviFeed(context).also { naviFeed = it }.start(sink) }
    }
    private var naviFeed: PleosNaviFeed? = null

    override fun stop() {
        cleanups.asReversed().forEach { runCatching(it) }
        cleanups.clear()
        runCatching { naviFeed?.stop() }; naviFeed = null
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        vehicle = null; car = null
    }

    // ---- 속도·기어: CarPropertyManager
    private fun startCarProperties(sink: SignalSink) {
        val c = runCatching { Car.createCar(context) }.getOrNull() ?: run { log("Car.createCar 실패"); return }
        car = c
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> sink.onSample(SignalSample.Speed(now(), (value.value as Float)))
                    VehiclePropertyIds.GEAR_SELECTION -> sink.onSample(SignalSample.GearChanged(now(), mapGear(value.value as Int)))
                }
            }
            override fun onErrorEvent(propId: Int, zone: Int) = log("CarProperty 오류 ${VehiclePropertyIds.toString(propId)}")
        }
        for (id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION)) {
            guard(VehiclePropertyIds.toString(id)) {
                val cfg = m.getCarPropertyConfig(id) ?: error("미지원")
                val rate = if (cfg.changeMode == 2) cfg.maxSampleRate else CarPropertyManager.SENSOR_RATE_ONCHANGE
                check(m.registerCallback(cb, id, rate)) { "registerCallback=false" }
                cleanups += { m.unregisterCallback(cb, id) }
            }
        }
    }

    private fun mapGear(v: Int): Gear = when (v) {
        4 -> Gear.PARK; 2 -> Gear.REVERSE; 1 -> Gear.NEUTRAL
        8 -> Gear.DRIVE; in 16..0x10000 -> Gear.DRIVE // GEAR_1.. 수동 단수는 주행으로 본다
        else -> Gear.UNKNOWN
    }

    // ---- Pleos Vehicle SDK
    private fun startTurnSignal(v: Vehicle, sink: SignalSink) {
        val api = v.getTurnSignal()
        fun map(x: TurnSignalValue?) = when (x) { TurnSignalValue.LEFT -> TurnSignal.LEFT; TurnSignalValue.RIGHT -> TurnSignal.RIGHT; else -> TurnSignal.OFF }
        val l = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = sink.onSample(SignalSample.TurnSignalChanged(now(), map(value)))
            override fun onFailed(e: Exception) = log("TurnSignal 리스너 실패: $e")
        }
        api.registerTurnSignalState(l); cleanups += { api.unregisterTurnSignalState(l) }
    }

    private fun startBrake(v: Vehicle, sink: SignalSink) {
        val api = v.getBrake()
        val l = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(value: Boolean?) { if (value != null) sink.onSample(SignalSample.Brake(now(), value)) }
            override fun onFailed(e: Exception) = log("Brake 리스너 실패: $e")
        }
        api.registerBrakePedalStatus(l); cleanups += { api.unregisterBrakePedalStatus(l) }
    }

    private fun startWiper(v: Vehicle, sink: SignalSink) {
        val api = v.getWiper()
        val area = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val l = object : WiperStateListener {
            override fun onWiperStateUpdated(value: WiperState?) { if (value != null) sink.onSample(SignalSample.Wiper(now(), value == WiperState.ON)) }
            override fun onFailed(e: Exception) = log("Wiper 리스너 실패: $e")
        }
        api.registerWiperState(area, l); cleanups += { api.unregisterWiperState(area, l) }
    }

    private fun startSeats(v: Vehicle, sink: SignalSink) {
        val api = v.getSeat()
        val seats = mapOf(
            SeatValue.ROW_1_LEFT to Seat.DRIVER, SeatValue.ROW_1_RIGHT to Seat.PASSENGER,
            SeatValue.ROW_2_LEFT to Seat.REAR_LEFT, SeatValue.ROW_2_CENTER to Seat.REAR_CENTER, SeatValue.ROW_2_RIGHT to Seat.REAR_RIGHT,
        )
        for ((sv, seat) in seats) guard("Seat $sv") {
            val area = SeatArea(sv)
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) {
                    if (value == null) return
                    val occupied = if (corrections.invertSeatOccupancy) !value else value
                    sink.onSample(SignalSample.SeatOccupancy(now(), seat, occupied))
                }
                override fun onFailed(e: Exception) = log("Seat $sv 리스너 실패: $e")
            }
            api.registerSeatOccupancy(area, l); cleanups += { api.unregisterSeatOccupancy(area, l) }
        }
    }

    private fun startSteering(v: Vehicle, sink: SignalSink) {
        val api = v.getSteeringWheel()
        val l = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(value: Float?) {
                if (value != null) sink.onSample(SignalSample.SteeringAngle(now(), value * corrections.steeringSign))
            }
            override fun onFailed(e: Exception) = log("Steering 리스너 실패: $e")
        }
        api.registerSteeringWheelAngle(l); cleanups += { api.unregisterSteeringWheelAngle(l) }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        runCatching(block).onFailure { log("$what 비활성화: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun log(msg: String) { Log.w(TAG, msg) }

    companion object { const val TAG = "PlayDrive.Signal" }
}
