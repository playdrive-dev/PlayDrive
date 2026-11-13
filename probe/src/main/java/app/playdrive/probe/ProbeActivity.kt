package app.playdrive.probe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/**
 * adb shell am start -n app.playdrive.probe/.ProbeActivity --es cmd navi|route|state|cancel|tts
 * 로그 태그: PDProbe
 */
class ProbeActivity : Activity() {
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) = log("onRouteStarted $info")
        override fun onRouteEnded() = log("onRouteEnded")
        override fun onRoutingCompleted() = log("onRoutingCompleted")
        override fun onRouteCancelled() = log("onRouteCancelled")
        override fun onDestinationArrived(info: DestinationArrivedInfo) = log("onDestinationArrived $info")
        override fun onError(code: NaviErrorCode) = log("onError $code")
        override fun onRouteStateInfo(info: RouteStateInfo) = log("onRouteStateInfo $info")
        override fun onDestinationInfo(info: DestinationInfo) = log("onDestinationInfo $info")
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = log("onCurrentLocationInfo $info")
        override fun onCurrentRoadSpeedLimit(limit: Int) = log("onCurrentRoadSpeedLimit $limit")
        override fun onCameraAlert(list: List<CameraAlert>) = log("onCameraAlert $list")
        override fun onCautionPointInfo(list: List<CautionPoint>) = log("onCautionPointInfo $list")
        override fun onDrivingInfo(info: DrivingInfo) = log("onDrivingInfo $info")
        override fun onNavigationStatus(status: NavigationStatus) = log("onNavigationStatus $status")
        override fun onTBTInfo(list: List<TBTInfo>) = log("onTBTInfo $list")
        override fun onTripInfo(info: TripInfo) = log("onTripInfo $info")
    }

    private fun log(s: String) { Log.i(TAG, s) }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); handle(intent?.getStringExtra("cmd")) }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent.getStringExtra("cmd")) }

    private fun naviReady(then: (NaviHelper) -> Unit) {
        navi?.let { then(it); return }
        val n = NaviHelper(applicationContext)
        n.initialize({ log("NaviHelper.initialize OK"); n.addListener(listener); navi = n; then(n) }, { e -> log("NaviHelper.initialize FAIL $e") })
    }

    private fun handle(cmd: String?) {
        log("cmd=$cmd")
        try {
            when (cmd) {
                "navi" -> naviReady { it.getRouteStateInfo(); it.getCurrentLocationInfo(); it.getDestinationInfo(); it.getTBTInfo() }
                "state" -> naviReady { it.getRouteStateInfo(); it.getDestinationInfo(); it.getTBTInfo(); it.getCurrentLocationInfo() }
                // 서울시청 → (예) 광화문 근처. 좌표는 공개 지명.
                "route" -> naviReady { it.requestRoute(RouteInfo(37.3947, 127.1112, null, null, "판교역", "", "경기 성남시 분당구 판교역로 160", "", RouteOption.RECOMMENDED, "", false)); log("requestRoute sent") }
                "cancel" -> naviReady { it.cancelRoute() }
                "tts" -> {
                    val t = tts ?: TextToSpeech(applicationContext, Mode.ON_DEVICE).also { tts = it }
                    val t0 = System.currentTimeMillis()
                    t.initialize(object : OnInitializeListener {
                        override fun onSuccess() {
                            log("TTS initialize OK ${System.currentTimeMillis() - t0}ms")
                            t.addEventListener(object : EventListener {
                                override fun onStart() = log("TTS onStart")
                                override fun onReady() = log("TTS onReady")
                                override fun onDone() = log("TTS onDone")
                                override fun onError(type: ErrorType) = log("TTS onError $type")
                                override fun onStop() = log("TTS onStop")
                                override fun onUpdatedRms(rms: Double) {}
                            })
                            runCatching { t.setLocale(Locale.KOREA) }.onFailure { log("setLocale fail $it") }
                            t.speak("다시 시작할게요. 조금만 부드럽게요.", true)
                            log("TTS speak sent")
                        }
                        override fun onFailure(type: ErrorType) = log("TTS initialize FAIL $type")
                    })
                }
            }
        } catch (t: Throwable) { log("예외 $cmd: $t") }
    }

    override fun onDestroy() { navi?.release(); tts?.release(); super.onDestroy() }
    companion object { const val TAG = "PDProbe" }
}
