package app.playdrive.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

/** CarUxRestrictions 구독: 주행 최적화(DO)가 필요하면 restricted = true. */
class UxRestrictionsWatcher(context: Context, private val onChange: (restricted: Boolean) -> Unit) {
    private var car: Car? = null
    private var mgr: CarUxRestrictionsManager? = null
    var restricted = false; private set

    init {
        // 메인 스레드에서 동기 createCar는 null을 줄 수 있어, 연결 콜백 방식으로 받는다.
        runCatching {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) { Log.w(TAG, "car service 연결 끊김"); return@createCar }
                runCatching {
                    val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    mgr = m
                    apply(m.currentCarUxRestrictions)
                    m.registerListener { apply(it) }
                }.onFailure { Log.w(TAG, "CarUxRestrictions 사용 불가: $it") }
            }
        }.onFailure { Log.w(TAG, "Car 연결 실패: $it") }
    }

    private fun apply(r: CarUxRestrictions?) {
        val req = r?.isRequiresDistractionOptimization ?: false
        Log.i(TAG, "UX 제한 변경: requiresDO=$req activeRestrictions=0x${Integer.toHexString(r?.activeRestrictions ?: 0)}")
        restricted = req
        onChange(req)
    }

    fun release() {
        runCatching { mgr?.unregisterListener() }
        runCatching { car?.disconnect() }
    }

    companion object { const val TAG = "PlayDrive.Ux" }
}
