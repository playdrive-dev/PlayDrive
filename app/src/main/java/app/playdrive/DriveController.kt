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
import app.playdrive.play.PlayStore
import app.playdrive.play.ResultReport
import app.playdrive.voice.Line
import app.playdrive.voice.VoiceClips
import app.playdrive.voice.VoicePolicy

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
        /** 주행 한 번이 끝남(출발 후 P단): 새 앨범, '주차 후 1분' 제안 여부, 연주 기록(결과·새 배지·주간 최고). */
        fun onDriveFinished(album: Album, suggestBreath: Boolean, report: ResultReport?) {}
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
    private var conductor = app.playdrive.play.Conductor(timing, Layer.entries.toSet(), StemConfig.arrangement)
    private val director get() = conductor.director
    private var source: VehicleSignalSource? = null
    private val recent = ArrayDeque<DrivingEvent>()
    private var lastUiMs = 0L
    private var lastDbgLogMs = 0L
    private val recorder = DriveRecorder { System.currentTimeMillis() }
    private var sketch = CoverSketch { System.currentTimeMillis() }
    val albums = AlbumStore(java.io.File(appContext.filesDir, "albums.json"))
    /** 연주 기록(결과·배지·해금 진행도), 기기 안에만. */
    val play = PlayStore(java.io.File(appContext.filesDir, "play.json"))
    private val voicePolicy = VoicePolicy()
    private var voiceClips: VoiceClips? = null
    private var pleosVoice: app.playdrive.voice.PleosVoice? = null
    /** 음성 엔진: true = Pleos TTS 우선(실패하면 녹음 음성), false = 녹음 음성만(시연 녹화 — TTS 소리는 앱 믹스에 담기지 않음). */
    @Volatile var preferTts = true
    /** 화면용(빛 원 = 남은 악기 수, 연주 정지 때 어둡게). 엔진 스레드가 쓰고 UI가 읽는다. */
    @Volatile var lifeGauge = 0; private set
    @Volatile var lifeStopped = false; private set
    /** 설정의 '음성 안내'(기본 켜짐). */
    var voiceEnabled: Boolean
        get() = voicePolicy.enabled
        set(v) { engineHandler.post { voicePolicy.enabled = v } }
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
        voiceClips = VoiceClips(appContext, timing.sampleRate).also { it.warmUp() }
        conductor = app.playdrive.play.Conductor(timing, m.availableLayers, StemConfig.arrangement)
        pleosVoice = app.playdrive.voice.PleosVoice(appContext, engineHandler,
            onTalking = { on -> mixer?.submit(MusicCommand.VoiceDuck(on)) },
            onFallback = { line -> speakOgg(line) }).also { it.init() }
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
        conductor = app.playdrive.play.Conductor(timing, mixer?.availableLayers ?: Layer.entries.toSet(), StemConfig.arrangement)
        mixer?.submit(MusicCommand.PerfBed(0)); mixer?.submit(MusicCommand.Muffle(0))
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

    /** 시연 영상용(디버그): 앱 소리를 WAV로 저장 시작. 반환 = 저장 파일. */
    fun startAudioTap(file: java.io.File): java.io.File? {
        val a = audio ?: return null
        a.tap?.close()
        a.tap = app.playdrive.audio.WavTap(file, timing.sampleRate)
        return file
    }

    /** 저장을 끝내고 첫 샘플 시각(벽시계 ms)을 돌려준다. */
    fun stopAudioTap(): Long? {
        val a = audio ?: return null
        val t = a.tap ?: return null
        a.tap = null
        Thread.sleep(40)  // 오디오 스레드가 쓰던 블록을 마치도록
        t.close()
        return t.startEpochMs
    }

    fun release() {
        engineHandler.post {
            source?.stop()
            session?.release()
            pleosVoice?.release()
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
    /** 지금 들리는 격자 위치 = 믹서 위치 − 출력 지연(화면이 소리보다 먼저 튀지 않게). */
    private fun audibleFrame(m: LoopMixer) = (audio?.audibleGridFrame() ?: (m.gridFrame - (audio?.latencyFrames ?: 0L))).coerceIn(0L, m.gridFrame)

    fun beatPhase(): Float? {
        val m = mixer ?: return null
        if (!playing) return null
        val f = audibleFrame(m)
        val a = m.activeLayersAt(f)
        if (Layer.DRUMS !in a && Layer.BASS !in a) return null
        return (f % timing.beatFrames).toFloat() / timing.beatFrames
    }

    /** 주행 중 LIVE 화면 효과용: 박 위치·박 길이·드럼이 들리는지·들리는 레이어 수(오디오 믹서 기준). */
    fun beatInfo(): app.playdrive.ui.Beat? {
        val m = mixer ?: return null
        val a = m.activeLayersAt(audibleFrame(m))
        val on = playing
        return app.playdrive.ui.Beat(
            phase = beatPhase(),
            beatMs = timing.beatFrames * 1000f / timing.sampleRate,
            drums = on && Layer.DRUMS in a,
            layers = if (on) a.size else 0,
            life = if (on) lifeGauge else -1, stopped = on && lifeStopped,
        )
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

    /** 주차 결과 음성(결과 화면이 뜰 때 UI가 부른다): 최고 등급·그 곡의 점(없으면 격려) → 주간 최고 → 새 배지. */
    fun announce(report: ResultReport) = engineHandler.post {
        speak(resultLine(report.result))
        if (report.weeklyBest) speak(Line.WeeklyBest)
        if (report.newBadges.isNotEmpty()) speak(Line.NewBadge)
    }

    private fun resultLine(r: app.playdrive.play.DriveResult): Line = r.best?.let { Line.Result(it.grade, it.score) } ?: Line.NoSong

    /** 엔진 스레드에서만. 주행 중(출발 후 P단 전)에는 VoicePolicy가 운전 관련 문장만, 같은 문장은 60초 간격으로 허락한다. */
    private fun speak(line: Line) {
        if (mixer == null) return
        if (!playing) { Log.i(TAG, "음성 생략(일시정지 중): $line"); return }
        if (!voicePolicy.allow(line, SystemClock.elapsedRealtime(), conductor.tracker.inDrive)) { Log.i(TAG, "음성 생략(정책): $line"); return }
        if (preferTts && pleosVoice?.speak(line) == true) return
        speakOgg(line)
    }

    /** 녹음 음성(OGG)을 믹서로: 앱 믹스에 들어가 덕킹·녹화가 그대로 된다. */
    private fun speakOgg(line: Line) {
        val m = mixer ?: return
        val clips = voiceClips?.load(line.clips()).orEmpty()
        if (clips.isEmpty()) return
        Log.i(TAG, "음성(녹음) ${line.clips()}")
        m.submit(MusicCommand.Speak(clips, line.key))
    }

    private fun process(sample: SignalSample) {
        val events = engine.onSample(sample)
        val co = conductor.update(engine.state, events)
        val cmds = co.commands
        val result = co.result
        lifeGauge = conductor.gauge; lifeStopped = conductor.mode == app.playdrive.play.PlayMode.FAILED
        val newCover = if (sketch.update(engine.state, events)) sketch.spec() else null
        recorder.update(engine.state, events)?.let { summary ->
            // 주행하며 그린 커버가 그대로 새 앨범의 커버가 된다
            val album = Album(summary.startEpochMs, summary, AlbumNaming.uniqueTitle(summary, albums.all().map { it.summary }), layers = sketch.layers)
            albums.add(album)
            val suggest = BreathCoach.shouldSuggest(engine.state, justFinishedDrive = true)
            val report = result?.let { play.record(it) }
            report?.let { rp -> // 결과 음성을 미리 디코딩(결과 화면이 뜨는 순간 바로 말하게)
                voiceClips?.load(resultLine(rp.result).clips() + Line.WeeklyBest.clips() + Line.NewBadge.clips())
            }
            Log.i(TAG, "주행 끝 → 앨범 '${album.title}', 주차 후 1분 제안=$suggest, 결과=${report?.result}, 새 배지=${report?.newBadges}, 주간 최고=${report?.weeklyBest}")
            main.post { listener.onDriveFinished(album, suggest, report) }
        }
        val m = mixer
        if (m != null) cmds.forEach(m::submit)
        co.events.forEach { pe -> if (pe !is app.playdrive.play.PlayEvent.Combo || pe.isFill || pe.isJoin || pe.isClimax) Log.i(TAG, "연주 $pe") }
        co.lines.forEach { speak(it) }
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
            val dbg = "악기=${conductor.gauge} ${conductor.mode} 곡=${conductor.tracker.songNumber}-${conductor.tracker.attempt} 이어진=${conductor.tracker.combo} 렌더부하=${"%.1f".format(a?.renderLoadPercent ?: 0f)}% underrun=${a?.underruns ?: 0} 출력지연=${(a?.latencyFrames ?: 0) * 1000 / timing.sampleRate}ms"
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
