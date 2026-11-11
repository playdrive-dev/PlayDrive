package app.playdrive

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.playdrive.album.Album
import app.playdrive.album.AlbumNaming
import app.playdrive.album.AlbumStore
import app.playdrive.album.BreathCoach
import app.playdrive.album.CoverSketch
import app.playdrive.album.CoverSpec
import app.playdrive.album.DriveRecorder
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
import app.playdrive.music.PerformanceLayer
import app.playdrive.music.stems.OggCueSource
import app.playdrive.music.stems.OggLayerSource
import app.playdrive.music.stems.StemConfig
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
        fun onPlayingChanged(playing: Boolean) {}
        /** 주행 한 번이 끝남(출발 후 P단): 새 앨범과 '주차 후 1분' 제안 여부. */
        fun onDriveFinished(album: Album, suggestBreath: Boolean) {}
        /** 오늘의 커버가 바뀜(새 주행 시작 또는 정차로 한 겹 추가). 화면 반영은 UI가 정차 때만. */
        fun onCover(cover: CoverSpec) {}
    }

    private val appContext = context.applicationContext
    private val timing: MusicTiming = StemConfig.timing
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
    private val recorder = DriveRecorder { System.currentTimeMillis() }
    private var sketch = CoverSketch { System.currentTimeMillis() }
    val albums = AlbumStore(java.io.File(appContext.filesDir, "albums.json"))
    @Volatile var playing = false; private set
    val totalLayers: Int get() = mixer?.availableLayers?.size ?: Layer.entries.size

    /** 레이어 로드: 스템 디코딩 또는 합성(처음엔 수 초 걸릴 수 있음 → UI는 로딩 표시). 이후엔 캐시. */
    fun prepare() = engineHandler.post {
        val t0 = SystemClock.elapsedRealtime()
        val layerSources = if (StemConfig.SET == StemConfig.Set.SYNTH) SynthLayerSource.all()
            else StemConfig.layers.map { (l, id) -> OggLayerSource(appContext, l, id) }
        val cueSources = StemConfig.cues.map { (c, id) -> OggCueSource(appContext, c, id) }
        val (pcm, cuePcm) = LayerCache(appContext.cacheDir).loadAll(layerSources, cueSources, timing)
        val perf = PerformanceLayer(timing, StemConfig.chordTimeline, StemConfig.PERF_LEVEL)
        val cuesWithChime = cuePcm + (app.playdrive.music.Cue.CHIME to app.playdrive.music.synth.Chime.render(timing.sampleRate))
        val m = LoopMixer(timing, pcm, cuesWithChime, LoopMixer.Config(cueMaxMs = StemConfig.CUE_MAX_MS, levels = StemConfig.levels), perf)
        mixer = m
        Log.i(TAG, "세트=${StemConfig.SET} ${timing.bars}마디 레이어=${m.availableLayers} 효과음=${m.availableCues}")
        audio = AudioOutput(m, timing.sampleRate).also { it.start() }
        session = PlaybackSession(appContext, engineHandler,
            onPlay = { audio?.play(); setPlaying(true) }, onPause = { audio?.pause(); setPlaying(false) })
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "레이어 준비 완료 ${ms}ms")
        main.post { listener.onPrepared(ms) }
    }

    /** autoPlay = false 면 신호원만 붙이고 음악은 [playWithChime]까지 기다린다(웰컴 시퀀스). */
    fun start(newSource: VehicleSignalSource, autoPlay: Boolean = true) = engineHandler.post {
        source?.stop()
        engine = DrivingEventEngine()
        sketch = CoverSketch { System.currentTimeMillis() }
        director = MusicDirector(timing, mixer?.availableLayers ?: Layer.entries.toSet(), StemConfig.arrangement)
        recent.clear()
        mixer?.let { m -> Layer.entries.forEach { m.submit(MusicCommand.SetLayer(it, false)) } }
        if (autoPlay) session?.play()
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

    private fun setPlaying(p: Boolean) { playing = p; main.post { listener.onPlayingChanged(p) } }

    /** 재생/일시정지(무드 화면의 유일한 조작). */
    fun togglePlay() = engineHandler.post { if (playing) session?.pause() else session?.play() }

    /** 웰컴 끝: 첫 다운비트(재생 시작 = 격자 0 또는 멈췄던 곳)에서 차임과 함께 음악 시작. */
    fun playWithChime() = engineHandler.post {
        if (playing) return@post
        mixer?.submit(MusicCommand.PlayCue(app.playdrive.music.Cue.CHIME))
        session?.play()
    }

    /** 지금 박 안의 위치(0~1). 음악이 쉬거나 베이스·드럼이 없으면 null. UI 스레드에서 읽어도 된다. */
    fun beatPhase(): Float? {
        val m = mixer ?: return null
        if (!playing) return null
        val a = m.activeLayers
        if (Layer.DRUMS !in a && Layer.BASS !in a) return null
        return (m.gridFrame % timing.beatFrames).toFloat() / timing.beatFrames
    }

    /** '주차 후 1분' 동안 오늘의 곡을 아주 작게(keys+synth 그대로, 마스터만 낮춤). */
    fun setBreathMode(on: Boolean) = engineHandler.post {
        mixer?.submit(MusicCommand.MasterGain(if (on) 0.25f else 0.8f))
        if (on && !playing) session?.play()
    }

    /** 디버그: 레이어 직접 토글(소리 확인용). */
    fun debugCommand(cmd: MusicCommand) = engineHandler.post {
        if (audio != null) { session?.play(); mixer?.submit(cmd) }
    }

    fun notifyFinished() = main.post { listener.onSourceFinished() }

    private fun process(sample: SignalSample) {
        val events = engine.onSample(sample)
        val cmds = director.update(engine.state, events)
        val newCover = if (sketch.update(engine.state, events)) sketch.spec() else null
        recorder.update(engine.state, events)?.let { summary ->
            // 주행하며 그린 커버가 그대로 새 앨범의 커버가 된다
            val album = Album(summary.startEpochMs, summary, AlbumNaming.uniqueTitle(summary, albums.all().map { it.summary }), layers = sketch.layers)
            albums.add(album)
            val suggest = BreathCoach.shouldSuggest(engine.state, justFinishedDrive = true)
            Log.i(TAG, "주행 끝 → 앨범 '${album.title}', 주차 후 1분 제안=$suggest")
            main.post { listener.onDriveFinished(album, suggest) }
        }
        val m = mixer
        if (m != null) cmds.forEach(m::submit)
        events.forEach { e ->
            Log.i(TAG, "이벤트 $e")
            recent.addLast(e); while (recent.size > 12) recent.removeFirst()
        }
        cmds.filter { it !is MusicCommand.SpeedTone }.forEach { Log.i(TAG, "음악 $it") }
        val now = SystemClock.elapsedRealtime()
        if (events.isNotEmpty() || now - lastUiMs >= 250) {
            lastUiMs = now
            val state = engine.state
            val list = recent.toList()
            val layers = director.layers
            val a = audio
            val dbg = "stage=${director.stage} 렌더부하=${"%.1f".format(a?.renderLoadPercent ?: 0f)}% underrun=${a?.underruns ?: 0}"
            if (now - lastDbgLogMs >= 5_000) { lastDbgLogMs = now; Log.i(TAG, "상태 v=${"%.1f".format(state.speedMps * 3.6f)}km/h layers=$layers $dbg") }
            main.post { listener.onUpdate(state, list, layers, dbg) }
        }
        // 커버는 상태(주행 중 여부) 다음에 넘긴다 → 출발 순간의 새 커버가 화면에 새지 않고 보관된다
        newCover?.let { c ->
            Log.i(TAG, "커버 겹 ${c.layers.size} (비 ${c.rainLines}, 깨진 자국 ${c.scars})")
            main.post { listener.onCover(c) }
        }
    }

    companion object {
        const val TAG = "PlayDrive.Drive"
    }
}
