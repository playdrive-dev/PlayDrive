package app.playdrive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import app.playdrive.music.LoopMixer

/** LoopMixer → AudioTrack(Float, 48kHz 스테레오). 전용 오디오 스레드에서 20ms 블록 단위로 렌더링. */
class AudioOutput(private val mixer: LoopMixer, private val sampleRate: Int) {

    private val blockFrames = sampleRate / 50 // 20ms
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = true

    /** 디버그 표시용: 오디오 스레드 렌더링 시간 비율(%) — 실시간 대비 CPU 사용량 가늠. */
    @Volatile var renderLoadPercent = 0f
        private set

    /** 시연 영상용 WAV 저장(디버그 빌드에서만 붙인다). */
    @Volatile var tap: WavTap? = null

    val underruns: Int get() = track?.underrunCount ?: 0

    /**
     * 출력 지연(프레임): 믹서가 만든 소리가 실제로 들리기까지. AudioTrack 타임스탬프(HAL 지연 포함)로 잰다.
     * 화면 효과는 믹서 위치에서 이만큼 뺀 "지금 들리는 위치"를 본다.
     */
    @Volatile var latencyFrames = 0L
        private set
    private var framesWritten = 0L
    private val ts = AudioTimestamp()
    private var lastSyncMark = -1_000_000L

    // ---- 재생 시계: AudioTrack 타임스탬프(HAL 지연 포함, 가끔 못 받거나 흔들림)를 PLL처럼 걸러 만든 매끄러운 "지금 들리는 트랙 프레임".
    //      화면(박·드럼)과 WAV 저장이 같은 시계를 쓴다 → 녹화에서도 실제 차에서도 화면이 소리보다 먼저 튀지 않는다.
    @Volatile private var clockFrame = -1.0    // 기준 시각의 들리는 트랙 프레임
    @Volatile private var clockNs = 0L
    /** 믹서 격자 위치 − 트랙에 쓴 프레임 수(연속 재생 중엔 일정). */
    @Volatile private var gridMinusTrack = 0L

    private fun clockAt(nowNs: Long) = clockFrame + (nowNs - clockNs) * sampleRate / 1e9

    /** 지금 들리는 믹서 격자 위치. 재생 중이 아니거나 시계가 아직 없으면 null. */
    fun audibleGridFrame(nowNs: Long = System.nanoTime()): Long? =
        if (paused || clockFrame < 0) null else (clockAt(nowNs).toLong() + gridMinusTrack)

    private fun updateClock(t: AudioTrack, nowNs: Long) {
        val measured = if (t.getTimestamp(ts) && ts.nanoTime > 0) ts.framePosition + (nowNs - ts.nanoTime) * sampleRate / 1e9 else return
        if (clockFrame < 0 || kotlin.math.abs(measured - clockAt(nowNs)) > sampleRate * 0.1) { clockFrame = measured; clockNs = nowNs; return }   // 처음·재개 후
        val predicted = clockAt(nowNs)
        clockFrame = predicted + 0.02 * (measured - predicted)   // 약 1초에 걸쳐 따라감(측정 흔들림 ±수십 ms를 걸러냄)
        clockNs = nowNs
    }

    fun start() {
        if (running) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, blockFrames * 2 * 4 * 4))
            .build()
        track = t
        running = true
        thread = Thread({ loop(t) }, "PlayDrive-audio").also { it.start() }
        Log.i(TAG, "AudioTrack 준비: ${sampleRate}Hz float stereo, buffer=${t.bufferSizeInFrames} frames, minBytes=$minBytes")
    }

    fun play() { track?.play(); paused = false }

    fun pause() { paused = true; track?.pause(); clockFrame = -1.0 }

    fun release() {
        running = false
        thread?.join(500)
        track?.release()
        track = null
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(blockFrames * 2)
        val blockNs = 1_000_000_000L * blockFrames / sampleRate
        var avg = 0f
        while (running) {
            if (paused) { Thread.sleep(20); continue }   // WAV 저장은 다시 들릴 때 빈 시간만큼 무음을 채운다(WavTap)
            val t0 = System.nanoTime()
            val g0 = mixer.gridFrame
            mixer.render(buf, blockFrames)
            avg = avg * 0.95f + 0.05f * (System.nanoTime() - t0) * 100f / blockNs
            renderLoadPercent = avg
            t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            framesWritten += blockFrames
            gridMinusTrack = mixer.gridFrame - framesWritten
            val now = System.nanoTime()
            updateClock(t, now)
            val played = if (clockFrame >= 0) clockAt(now) else (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL).toDouble()
            val queued = (framesWritten - played).coerceAtLeast(blockFrames.toDouble())
            latencyFrames = queued.toLong()
            // 이 블록 첫 샘플이 들릴 벽시계 시각(같은 재생 시계)
            tap?.let { tp ->
                val audibleMs = System.currentTimeMillis() + (queued - blockFrames) * 1000.0 / sampleRate
                tp.write(buf, buf.size, audibleMs)
                // 1초마다 (WAV 위치, 들리는 시각) — 녹화 뒤 오디오 시계와 벽시계의 미세한 차이(드리프트)를 맞출 때 쓴다
                if (tp.position - lastSyncMark >= sampleRate) { lastSyncMark = tp.position; tp.mark(tp.position - blockFrames, "sync audibleMs=${"%.1f".format(audibleMs)}") }
                // 드럼이 켜진 정확한 WAV 위치(블록 끝 기준으로 거슬러) — tools/check_sync.py
                val blockStart = tp.position - blockFrames
                var prev: Set<app.playdrive.music.Layer>? = null
                for ((f, set) in mixer.changesBetween(g0, g0 + blockFrames)) {
                    val before = prev ?: mixer.activeLayersAt(f - 1)
                    if (app.playdrive.music.Layer.DRUMS in set && app.playdrive.music.Layer.DRUMS !in before) tp.mark(blockStart + (f - g0), "drums-on")
                    prev = set
                }
            }
        }
    }

    companion object { const val TAG = "PlayDrive.Audio" }
}
