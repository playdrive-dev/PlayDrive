package app.playdrive.signal

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context

/**
 * 신호 확인용 프로브(3단계 확장). 각 신호마다 Capability → 현재값 → 리스너를 확인한다.
 * 6단계에서 VehicleSignalSource 구현으로 대체된다.
 */
class SignalProbes(
    private val context: Context,
    private val vehicle: Vehicle,
    private val log: (String) -> Unit,
) {
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private val cleanups = mutableListOf<() -> Unit>()

    fun start(speedPermitted: Boolean) {
        startCarProperties(speedPermitted)
        startBrake()
        startWiper()
        startSeats()
        startSteering()
        startSdkGear()
    }

    fun stop() {
        cleanups.forEach { runCatching(it) }
        cleanups.clear()
        car?.disconnect()
    }

    // ---- AAOS CarPropertyManager: 속도, 기어 ----
    private fun startCarProperties(speedPermitted: Boolean) {
        val c = Car.createCar(context) ?: run { log("[CPM] Car.createCar 실패"); return }
        car = c
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        cpm = m
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val name = VehiclePropertyIds.toString(value.propertyId)
                log("[CPM] 이벤트 $name=${value.value} status=${value.status} ts=${value.timestamp / 1_000_000}ms")
            }

            override fun onErrorEvent(propId: Int, zone: Int) {
                log("[CPM] 오류 ${VehiclePropertyIds.toString(propId)} zone=$zone")
            }
        }
        cleanups += { m.unregisterCallback(cb) }

        val props = buildList {
            if (speedPermitted) add(VehiclePropertyIds.PERF_VEHICLE_SPEED)
            else log("[CPM] PERF_VEHICLE_SPEED 건너뜀: CAR_SPEED 권한 없음")
            add(VehiclePropertyIds.GEAR_SELECTION)
        }
        for (id in props) {
            val name = VehiclePropertyIds.toString(id)
            runCatching {
                val cfg = m.getCarPropertyConfig(id)
                if (cfg == null) { log("[CPM] $name config 없음(미지원)"); return@runCatching }
                log("[CPM] $name config: access=${cfg.access} changeMode=${cfg.changeMode} " +
                    "rate=${cfg.minSampleRate}~${cfg.maxSampleRate}Hz type=${cfg.propertyType.simpleName}")
                val v = m.getProperty<Any>(id, 0)
                log("[CPM] $name 현재값=${v?.value} status=${v?.status}")
                val rate = if (cfg.changeMode == 2) cfg.maxSampleRate else CarPropertyManager.SENSOR_RATE_ONCHANGE
                val ok = m.registerCallback(cb, id, rate)
                log("[CPM] $name 리스너 등록=$ok (rate=$rate)")
            }.onFailure { log("[CPM] $name 실패: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    // ---- Pleos Vehicle SDK ----
    private fun startBrake() {
        val brake = vehicle.getBrake()
        brake.checkBrakePedalStatusCapabilities(
            { cfg ->
                log("[Brake] Capability=$cfg")
                brake.getCurrentBrakePedalStatus({ log("[Brake] 현재값=$it") }, { fail("Brake 현재값", it) })
                val l = object : BrakePedalStatusListener {
                    override fun onBrakePedalStatusUpdated(value: Boolean?) = log("[Brake] 이벤트 pressed=$value")
                    override fun onFailed(e: Exception) = fail("Brake 리스너", e)
                }
                brake.registerBrakePedalStatus(l); log("[Brake] 리스너 등록")
                cleanups += { brake.unregisterBrakePedalStatus(l) }
            },
            { fail("Brake Capability", it) },
        )
    }

    private fun startWiper() {
        val wiper = vehicle.getWiper()
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        wiper.checkWiperStateCapabilities(
            { cfg: ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig,
              map: Map<WindowArea, List<WiperState>> ->
                log("[Wiper] State Capability=$cfg areas=${map.map { (a, v) -> "${a.values.toList()}=$v" }}")
                wiper.getCurrentWiperState(front, { log("[Wiper] 현재 State=$it") }, { fail("Wiper State 현재값", it) })
                val l = object : WiperStateListener {
                    override fun onWiperStateUpdated(value: WiperState?) = log("[Wiper] 이벤트 State=$value")
                    override fun onFailed(e: Exception) = fail("Wiper 리스너", e)
                }
                wiper.registerWiperState(front, l); log("[Wiper] 리스너 등록(FRONT_WINDSHIELD)")
                cleanups += { wiper.unregisterWiperState(front, l) }
            },
            { fail("Wiper State Capability", it) },
        )
        wiper.getCurrentWiperSwitch(front, { log("[Wiper] 현재 Switch=$it") }, { fail("Wiper Switch 현재값", it) })
    }

    private fun startSeats() {
        val seat = vehicle.getSeat()
        seat.checkSeatOccupancyCapabilities(
            { cfg: ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig, areas: List<SeatArea> ->
                log("[Seat] Capability=$cfg areas=${areas.map { it.values.toList() }}")
                val targets = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
                for (sv in targets) {
                    val area = SeatArea(sv)
                    seat.getCurrentSeatOccupancy(area, { log("[Seat] $sv 현재값=$it") }, { fail("Seat $sv 현재값", it) })
                    val l = object : SeatOccupancyListener {
                        override fun onSeatOccupancyUpdated(value: Boolean?) = log("[Seat] 이벤트 $sv occupied=$value")
                        override fun onFailed(e: Exception) = fail("Seat $sv 리스너", e)
                    }
                    runCatching { seat.registerSeatOccupancy(area, l) }
                        .onSuccess { log("[Seat] $sv 리스너 등록"); cleanups += { seat.unregisterSeatOccupancy(area, l) } }
                        .onFailure { fail("Seat $sv 등록", it) }
                }
            },
            { fail("Seat Capability", it) },
        )
    }

    private fun startSteering() {
        val sw = vehicle.getSteeringWheel()
        sw.checkSteeringWheelAngleCapabilities(
            { cfg, bounds ->
                log("[Steer] Capability=$cfg bounds=$bounds")
                sw.getCurrentSteeringWheelAngle({ log("[Steer] 현재값=$it") }, { fail("Steer 현재값", it) })
                val l = object : SteeringWheelAngleListener {
                    override fun onSteeringWheelAngleUpdated(value: Float?) = log("[Steer] 이벤트 angle=$value")
                    override fun onFailed(e: Exception) = fail("Steer 리스너", e)
                }
                sw.registerSteeringWheelAngle(l); log("[Steer] 리스너 등록")
                cleanups += { sw.unregisterSteeringWheelAngle(l) }
            },
            { fail("Steer Capability", it) },
        )
    }

    private fun startSdkGear() {
        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState({ log("[SdkGear] 현재값=$it") }, { fail("SdkGear 현재값", it) })
        val l = object : GearSelectionListener {
            override fun onSelected(value: VehicleGear?) = log("[SdkGear] 이벤트 $value")
            override fun onFailed(e: Exception) = fail("SdkGear 리스너", e)
        }
        runCatching { gear.registerGearSelection(l) }
            .onSuccess { log("[SdkGear] 리스너 등록"); cleanups += { gear.unregisterGearSelection(l) } }
            .onFailure { fail("SdkGear 등록", it) }
    }

    private fun fail(what: String, e: Throwable) = log("[실패] $what: ${e.javaClass.simpleName}: ${e.message}")
}
