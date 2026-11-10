package app.playdrive.signal

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener

/**
 * 3단계 확인용: 방향지시등 Capability → 현재값 → 변경 리스너.
 * 6단계에서 VehicleSignalSource 구현으로 대체된다.
 */
class TurnSignalProbe(
    private val vehicle: Vehicle,
    private val report: (String) -> Unit,
) {
    private val listener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            log("변경 이벤트: $value (id=${value?.id})")
        }

        override fun onFailed(e: Exception) {
            log("리스너 onFailed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    private var registered = false

    fun start() {
        val turnSignal = vehicle.getTurnSignal()
        turnSignal.checkTurnSignalStateCapabilities(
            { config ->
                log("Capability: accessType=${config.accessType} ($config)")
                turnSignal.getCurrentTurnSignalState(
                    { value -> log("현재값: $value (id=${value?.id})") },
                    { e -> log("현재값 실패: ${e.javaClass.simpleName}: ${e.message}") },
                )
                runCatching {
                    turnSignal.registerTurnSignalState(listener)
                    registered = true
                    log("리스너 등록 완료")
                }.onFailure { e -> log("리스너 등록 실패: ${e.javaClass.simpleName}: ${e.message}") }
            },
            { e -> log("Capability 실패: ${e.javaClass.simpleName}: ${e.message}") },
        )
    }

    fun stop() {
        if (registered) {
            runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(listener) }
            registered = false
        }
    }

    private fun log(msg: String) = report(msg)

    companion object {
        const val TAG = "PlayDrive.Turn"
    }
}
