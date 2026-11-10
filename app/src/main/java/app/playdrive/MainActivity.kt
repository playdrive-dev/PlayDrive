package app.playdrive

import ai.pleos.playground.vehicle.Vehicle
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.playdrive.signal.SignalProbes
import app.playdrive.signal.TurnSignalProbe

class MainActivity : ComponentActivity() {

    private lateinit var output: TextView
    private val lines = ArrayDeque<String>()
    private var vehicle: Vehicle? = null
    private var turnProbe: TurnSignalProbe? = null
    private var signalProbes: SignalProbes? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("권한 $PERM_SPEED 요청 결과: granted=$granted")
            startProbes(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        setContentView(output)

        if (checkSelfPermission(PERM_SPEED) == PackageManager.PERMISSION_GRANTED) {
            log("권한 $PERM_SPEED: 이미 허용됨")
            startProbes(true)
        } else {
            log("권한 $PERM_SPEED: 요청")
            permissionLauncher.launch(PERM_SPEED)
        }
    }

    private fun startProbes(speedPermitted: Boolean) {
        if (vehicle != null) return
        val v = Vehicle(this)
        runCatching { v.initialize() }
            .onSuccess { log("Vehicle.initialize() 완료") }
            .onFailure { log("Vehicle.initialize() 실패: ${it.javaClass.simpleName}: ${it.message}") }
        vehicle = v
        turnProbe = TurnSignalProbe(v) { log("[Turn] $it") }.also { it.start() }
        signalProbes = SignalProbes(this, v) { log(it) }.also { it.start(speedPermitted) }
    }

    override fun onDestroy() {
        turnProbe?.stop()
        signalProbes?.stop()
        vehicle?.release()
        super.onDestroy()
    }

    /** 모든 스레드에서 호출 가능. logcat에 남기고 화면에는 최근 줄만 보여준다. */
    private fun log(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread {
            lines.addLast(msg)
            while (lines.size > MAX_LINES) lines.removeFirst()
            output.text = lines.joinToString("\n")
        }
    }

    companion object {
        const val TAG = "PlayDrive.Sig"
        private const val MAX_LINES = 30
        private const val PERM_SPEED = "android.car.permission.CAR_SPEED"
    }
}
