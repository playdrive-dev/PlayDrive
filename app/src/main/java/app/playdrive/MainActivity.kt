package app.playdrive

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.music.Layer
import app.playdrive.music.MusicCommand
import app.playdrive.signal.PleosVehicleSignalSource
import app.playdrive.sim.DemoDriveSource

/**
 * 개발·시연용 디버그 화면(임시). 주행 중 화면 정책(단색 + 재생/일시정지)은 7단계에서 별도 화면으로 구현.
 */
class MainActivity : ComponentActivity(), DriveController.Listener {

    private lateinit var controller: DriveController
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private val buttons = mutableListOf<Button>()

    private val speedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.text = if (granted) "속도 권한 허용됨" else "속도 권한 거부됨 — 속도 없이 진행"
            startReal()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.rgb(16, 18, 22))
        }
        status = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); text = "음악 준비 중…" }
        root.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("데모 드라이브 (74초)") { controller.start(DemoDriveSource(controller.engineHandler) { controller.notifyFinished() }); status.text = "데모 드라이브 재생 중" })
        row1.addView(button("실제 차량 신호") { requestSpeedThenStart() })
        row1.addView(button("정지") { controller.stop(); status.text = "정지" })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (l in Layer.entries) row2.addView(button(l.name, small = true) { toggleLayer(l) })
        row2.addView(button("테이프스톱", small = true) { controller.debugCommand(MusicCommand.TapeStop) })
        row2.addView(button("로우패스", small = true) { controller.debugCommand(MusicCommand.LowpassDip) })
        row2.addView(button("피치흔들림", small = true) { controller.debugCommand(MusicCommand.PitchWobble) })
        root.addView(row2)

        detail = TextView(this).apply { textSize = 15f; setTextColor(Color.rgb(200, 205, 210)) }
        root.addView(detail)
        setContentView(root)

        buttons.forEach { it.isEnabled = false }
        controller = DriveController(this, this)
        controller.prepare()
        pendingIntent = intent
    }

    private val debugLayers = mutableSetOf<Layer>()
    private fun toggleLayer(l: Layer) {
        val on = debugLayers.add(l) || run { debugLayers.remove(l); false }
        controller.debugCommand(MusicCommand.SetLayer(l, on))
        status.text = "레이어 수동: ${debugLayers.joinToString()}"
    }

    private fun button(label: String, small: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (small) 12f else 18f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttons += this
    }

    private fun requestSpeedThenStart() {
        if (checkSelfPermission(PERM_SPEED) == PackageManager.PERMISSION_GRANTED) startReal()
        else speedPermission.launch(PERM_SPEED)
    }

    private fun startReal() {
        controller.start(PleosVehicleSignalSource(this))
        status.text = "실제 차량 신호로 연주 중"
    }

    private var prepared = false
    private var pendingIntent: Intent? = null

    override fun onPrepared(elapsedMs: Long) {
        status.text = "준비 완료 (음원 로드 ${elapsedMs}ms)"
        buttons.forEach { it.isEnabled = true }
        prepared = true
        pendingIntent?.let { handleDebugIntent(it) }; pendingIntent = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (prepared) handleDebugIntent(intent) else pendingIntent = intent
    }

    /**
     * 디버그 빌드 전용 자동화(테스트·영상 녹화):
     *  adb shell am start -n app.playdrive/.MainActivity --es debug demo
     *  adb shell am start -n app.playdrive/.MainActivity --es debug layers:PAD,BASS,DRUMS
     *  adb shell am start -n app.playdrive/.MainActivity --es debug fx:TapeStop|LowpassDip|PitchWobble
     *  adb shell am start -n app.playdrive/.MainActivity --es debug cue:TAPE_STOP|SCRATCH
     *  adb shell am start -n app.playdrive/.MainActivity --es debug stop
     */
    private fun handleDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val cmd = intent.getStringExtra("debug") ?: return
        when {
            cmd == "demo" -> { controller.start(DemoDriveSource(controller.engineHandler) { controller.notifyFinished() }); status.text = "데모 드라이브 재생 중" }
            cmd == "real" -> requestSpeedThenStart()
            cmd == "stop" -> { controller.stop(); status.text = "정지" }
            cmd.startsWith("layers:") -> {
                val want = cmd.removePrefix("layers:").split(",").mapNotNull { n -> Layer.entries.find { it.name == n.trim() } }.toSet()
                Layer.entries.forEach { controller.debugCommand(MusicCommand.SetLayer(it, it in want)) }
                debugLayers.clear(); debugLayers += want
                status.text = "레이어 수동: ${want.joinToString()}"
            }
            cmd.startsWith("cue:") -> app.playdrive.music.Cue.entries.find { it.name == cmd.removePrefix("cue:") }
                ?.let { controller.debugCommand(MusicCommand.PlayCue(it)) }
            cmd.startsWith("fx:") -> when (cmd.removePrefix("fx:")) {
                "TapeStop" -> controller.debugCommand(MusicCommand.TapeStop)
                "LowpassDip" -> controller.debugCommand(MusicCommand.LowpassDip)
                "PitchWobble" -> controller.debugCommand(MusicCommand.PitchWobble)
            }
        }
    }

    override fun onUpdate(state: DrivingState, recentEvents: List<DrivingEvent>, layers: Set<Layer>, debug: String) {
        detail.text = buildString {
            appendLine("속도 ${"%.1f".format(state.speedMps * 3.6f)} km/h  가속 ${"%.2f".format(state.accelMps2)} m/s²  기어 ${state.gear}  브레이크 ${state.brakePressed}")
            appendLine("주행=${state.moving} 정속=${state.cruising} 방향지시등=${state.turnSignal} 비=${state.raining} 혼자=${state.solo}  하모니 ${state.harmony.toInt()}")
            appendLine("레이어: ${layers.joinToString()}   [$debug]")
            appendLine("— 최근 이벤트 —")
            recentEvents.asReversed().forEach { appendLine(it.toString().substringBefore("(") + "  " + it.toString().substringAfter("(").removeSuffix(")")) }
        }
    }

    override fun onSourceFinished() { status.text = "데모 드라이브 끝" }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    companion object { private const val PERM_SPEED = "android.car.permission.CAR_SPEED" }
}
