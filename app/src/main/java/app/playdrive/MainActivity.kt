package app.playdrive

import ai.pleos.playground.vehicle.Vehicle
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.playdrive.signal.TurnSignalProbe

class MainActivity : ComponentActivity() {

    private lateinit var output: TextView
    private var vehicle: Vehicle? = null
    private var probe: TurnSignalProbe? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            append("권한 $PERM_LIGHTS 요청 결과: granted=$granted")
            startProbe()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 22f
            setPadding(48, 48, 48, 48)
            text = getString(R.string.app_name) + "\n"
        }
        setContentView(ScrollView(this).apply { addView(output) })

        if (checkSelfPermission(PERM_LIGHTS) == PackageManager.PERMISSION_GRANTED) {
            append("권한 $PERM_LIGHTS: 이미 허용됨")
            startProbe()
        } else {
            append("권한 $PERM_LIGHTS: 요청")
            permissionLauncher.launch(PERM_LIGHTS)
        }
    }

    private fun startProbe() {
        if (probe != null) return
        val v = Vehicle(this)
        runCatching { v.initialize() }
            .onSuccess { append("Vehicle.initialize() 완료") }
            .onFailure { append("Vehicle.initialize() 실패: ${it.javaClass.simpleName}: ${it.message}") }
        vehicle = v
        probe = TurnSignalProbe(v) { msg -> runOnUiThread { append(msg) } }.also { it.start() }
    }

    override fun onDestroy() {
        probe?.stop()
        vehicle?.release()
        super.onDestroy()
    }

    private fun append(msg: String) {
        Log.i(TurnSignalProbe.TAG, msg)
        output.append("\n$msg")
    }

    companion object {
        private const val PERM_LIGHTS = "pleos.car.permission.CAR_LIGHTS"
    }
}
