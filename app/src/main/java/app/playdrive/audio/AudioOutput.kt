package app.playdrive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
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

    fun pause() { paused = true; track?.pause() }

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
            if (paused) { Thread.sleep(20); tap?.silence(blockFrames); continue }
            val t0 = System.nanoTime()
            mixer.render(buf, blockFrames)
            avg = avg * 0.95f + 0.05f * (System.nanoTime() - t0) * 100f / blockNs
            renderLoadPercent = avg
            t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            tap?.write(buf, buf.size)
        }
    }

    companion object { const val TAG = "PlayDrive.Audio" }
}
