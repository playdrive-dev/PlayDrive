package app.playdrive

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.playdrive.audio.AudioOutput
import app.playdrive.audio.PlaybackSession
import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingEventEngine
import app.playdrive.engine.DrivingState
import app.playdrive.model.SignalSample
import app.playdrive.model.VehicleSignalSource
import app.playdrive.music.Layer
import app.playdrive.music.LayerCache
import app.playdrive.music.LoopMixer
import app.playdrive.music.MusicCommand
import app.playdrive.music.MusicDirector
import app.playdrive.music.MusicTiming
import app.playdrive.music.synth.SynthLayerSource

/**
 * 신호원 → DrivingEventEngine → MusicDirector → LoopMixer → AudioOutput.
 * 엔진·연출은 전용 스레드(engineHandler) 하나에서만 돈다. UI 콜백은 메인 스레드.
 * (7단계에서 MediaBrowserService로 옮긴다.)
 */
class DriveController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onPrepared(elapsedMs: Long)
        fun onUpdate(state: DrivingState, recentEvents: List<DrivingEvent>, layers: Set<Layer>, debug: String)
        fun onSourceFinished()
    }

    private val appContext = context.applicationContext
    private val timing = MusicTiming()
    private val thread = HandlerThread("PlayDrive-engine").apply { start() }
    val engineHandler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var mixer: LoopMixer? = null
    private var audio: AudioOutput? = null
    private var session: PlaybackSession? = null
    private var engine = DrivingEventEngine()
    private var director = MusicDirector(timing)
    private var source: VehicleSignalSource? = null
    private val recent = ArrayDeque<DrivingEvent>()
    private var lastUiMs = 0L
    private var lastDbgLogMs = 0L

    /** 레이어 합성(수 초 걸릴 수 있음 → UI는 로딩 표시). */
    fun prepare() = engineHandler.post {
        val t0 = SystemClock.elapsedRealtime()
        val pcm = LayerCache(appContext.cacheDir, SYNTH_VERSION).loadAll(SynthLayerSource.all(), timing)
        val m = LoopMixer(timing, pcm)
        mixer = m
        audio = AudioOutput(m, timing.sampleRate).also { it.start() }
        session = PlaybackSession(appContext, engineHandler, onPlay = { audio?.play() }, onPause = { audio?.pause() })
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "레이어 합성 완료 ${ms}ms")
        main.post { listener.onPrepared(ms) }
    }

    fun start(newSource: VehicleSignalSource) = engineHandler.post {
        source?.stop()
        engine = DrivingEventEngine()
        director = MusicDirector(timing)
        recent.clear()
        mixer?.let { m -> Layer.entries.forEach { m.submit(MusicCommand.SetLayer(it, false)) } }
        session?.play()
        source = newSource
        Log.i(TAG, "신호원 시작: ${newSource.name}")
        newSource.start { sample -> engineHandler.post { process(sample) } }
    }

    fun stop() = engineHandler.post {
        source?.stop(); source = null
        session?.pause()
    }

    fun release() {
        engineHandler.post {
            source?.stop()
            session?.release()
            audio?.release()
        }
        thread.quitSafely()
    }

    /** 디버그: 레이어 직접 토글(소리 확인용). */
    fun debugCommand(cmd: MusicCommand) = engineHandler.post {
        if (audio != null) { session?.play(); mixer?.submit(cmd) }
    }

    fun notifyFinished() = main.post { listener.onSourceFinished() }

    private fun process(sample: SignalSample) {
        val events = engine.onSample(sample)
        val cmds = director.update(engine.state, events)
        val m = mixer
        if (m != null) cmds.forEach(m::submit)
        events.forEach { e ->
            Log.i(TAG, "이벤트 $e")
            recent.addLast(e); while (recent.size > 12) recent.removeFirst()
        }
        cmds.forEach { Log.i(TAG, "음악 $it") }
        val now = SystemClock.elapsedRealtime()
        if (events.isNotEmpty() || now - lastUiMs >= 250) {
            lastUiMs = now
            val state = engine.state
            val list = recent.toList()
            val layers = director.layers
            val a = audio
            val dbg = "level=${director.level} 렌더부하=${"%.1f".format(a?.renderLoadPercent ?: 0f)}% underrun=${a?.underruns ?: 0}"
            if (now - lastDbgLogMs >= 5_000) { lastDbgLogMs = now; Log.i(TAG, "상태 v=${"%.1f".format(state.speedMps * 3.6f)}km/h layers=$layers $dbg") }
            main.post { listener.onUpdate(state, list, layers, dbg) }
        }
    }

    companion object {
        const val TAG = "PlayDrive.Drive"
        /** 합성 코드·작곡을 바꾸면 올린다(레이어 캐시 무효화). */
        const val SYNTH_VERSION = "1"
    }
}
