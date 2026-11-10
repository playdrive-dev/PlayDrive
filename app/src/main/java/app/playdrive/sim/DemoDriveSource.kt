package app.playdrive.sim

import android.os.Handler
import android.os.SystemClock
import app.playdrive.model.SignalSink
import app.playdrive.model.VehicleSignalSource

/** DemoDriveScenario를 실시간(10Hz)으로 재생하는 신호원. 실제 신호원과 같은 인터페이스. */
class DemoDriveSource(
    private val handler: Handler,
    private val onFinished: () -> Unit = {},
) : VehicleSignalSource {
    override val name = "데모 드라이브(60초)"
    private var startMs = 0L
    private var lastT = -1L
    private var sink: SignalSink? = null

    private val tick = object : Runnable {
        override fun run() {
            val s = sink ?: return
            val t = (SystemClock.elapsedRealtime() - startMs).coerceAtMost(DemoDriveScenario.DURATION_MS)
            DemoDriveScenario.samples(lastT, t, startMs).forEach(s::onSample)
            lastT = t
            if (t >= DemoDriveScenario.DURATION_MS) { sink = null; onFinished(); return }
            handler.postDelayed(this, 100)
        }
    }

    override fun start(sink: SignalSink) {
        this.sink = sink
        startMs = SystemClock.elapsedRealtime()
        lastT = -1L
        handler.post(tick)
    }

    override fun stop() {
        sink = null
        handler.removeCallbacks(tick)
    }

    val elapsedMs: Long get() = if (sink == null) 0 else SystemClock.elapsedRealtime() - startMs
}
