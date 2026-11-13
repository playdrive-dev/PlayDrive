package app.playdrive.signal

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import app.playdrive.model.NaviSignal
import app.playdrive.model.SignalSample
import app.playdrive.model.SignalSink
import app.playdrive.model.TurnSignal

/**
 * NaviHelper 3.0.5 → [NaviSignal]. 권한 pleos.car.permission.NAVI_ROUTE(normal). 콜백은 바인더 스레드 → sink가 엔진 스레드로 넘긴다.
 * 확인(2026-11-13, 에뮬레이터): requestRoute 후 onRouteStarted·onNavigationStatus(ROUTE_DRIVE)·onTBTInfo·onDrivingInfo 수신.
 * onRouteEnded는 안내 시작 직후에도 한 번 와서 도착 판단에 쓰지 않는다(→ onDestinationArrived).
 * 에뮬레이터는 내비 위치가 움직이지 않아(geo fix 무시, 모의 주행 API 없음) 회전 거리·도착은 실차에서만 바뀐다.
 * 실패해도 앱은 계속(내비 판정만 빠짐).
 */
class PleosNaviFeed(private val context: Context) {
    private var navi: NaviHelper? = null
    private var sink: SignalSink? = null
    private var schoolZone = false
    private var roadLimit = 0

    private fun emit(s: NaviSignal) { sink?.onSample(SignalSample.Navi(SystemClock.elapsedRealtime(), s)) }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) = emit(NaviSignal.RouteStarted(info.distance))
        override fun onDestinationArrived(info: DestinationArrivedInfo) = emit(NaviSignal.Arrived)
        override fun onTBTInfo(list: List<TBTInfo>) {
            val t = list.firstOrNull() ?: return
            val dir = when (t.type) {
                TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_KEEP_L, TurnType.TURN_UTURN -> TurnSignal.LEFT
                TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R, TurnType.TURN_KEEP_R -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            }
            emit(NaviSignal.NextTurn(dir, t.distance.toInt()))
        }
        override fun onDrivingInfo(info: DrivingInfo) { info.destination?.let { emit(NaviSignal.Remaining(it.distance)) } }
        override fun onCurrentRoadSpeedLimit(limit: Int) { if (limit > 0 && limit != roadLimit) { roadLimit = limit; emit(NaviSignal.SpeedLimit(limit, schoolZone)) } }
        override fun onCameraAlert(list: List<CameraAlert>) {
            val school = list.firstOrNull { (it.type == CameraType.SPEED_CAMERA_SCHOOL_ZONE || it.type == CameraType.SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT) && it.remainDistance <= 50 }
            if (school != null && school.speedLimit > 0) { schoolZone = true; emit(NaviSignal.SpeedLimit(school.speedLimit, true)) }
        }
        override fun onCautionPointInfo(list: List<CautionPoint>) {
            val inZone = list.any { it.type == CautionPointType.SCHOOL_ZONE && it.remainDistance <= 50 }
            if (inZone != schoolZone) { schoolZone = inZone; if (roadLimit > 0) emit(NaviSignal.SpeedLimit(roadLimit, inZone)) }
        }
    }

    fun start(sink: SignalSink) {
        this.sink = sink
        val n = runCatching { NaviHelper(context.applicationContext) }.getOrElse { Log.w(TAG, "NaviHelper 생성 실패: $it"); return }
        navi = n
        runCatching {
            n.initialize({ Log.i(TAG, "NaviHelper 준비"); n.addListener(listener); n.getRouteStateInfo(); n.getTBTInfo() }, { e -> Log.w(TAG, "NaviHelper 초기화 실패: $e") })
        }.onFailure { Log.w(TAG, "NaviHelper 예외: $it") }
    }

    fun stop() {
        sink = null
        runCatching { navi?.removeListener(listener) }
        runCatching { navi?.release() }
        navi = null
    }

    companion object { const val TAG = "PlayDrive.Navi" }
}
