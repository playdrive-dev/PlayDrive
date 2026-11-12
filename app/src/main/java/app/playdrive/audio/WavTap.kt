package app.playdrive.audio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 시연 영상용(디버그 빌드 전용): 앱이 AudioTrack에 쓰는 최종 믹스(스템 + 연주음 + 효과음)를 그대로
 * **32bit float 스테레오 WAV**(믹서와 같은 샘플레이트, 48kHz)로 저장한다 — 변환·손실 없음.
 * 에뮬레이터 화면 녹화(adb emu screenrecord)·scrcpy는 AAOS 차량 오디오 버스의 앱 소리를 담지 못해 따로 뜬다.
 * WAV의 시간축 = **들리는 시각**: [startEpochMs](녹음 시작 = WAV 0번 샘플) + 프레임/샘플레이트.
 * 블록마다 "그 블록이 들릴 벽시계 시각"(AudioTrack 타임스탬프로 잰 출력 지연 포함)을 받아, 일시정지·음악 시작 전처럼
 * 빈 시간이 있으면 그만큼 무음을 채운다 → 영상(화면은 지금 들리는 위치를 보고 반응)과 같은 시계로 맞는다.
 * 로그 "record start epochMs=" 로도 남긴다.
 */
class WavTap(private val file: File, private val sampleRate: Int) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 18)
    private var frames = 0L
    private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    /** WAV 0번 샘플이 들리는(들렸어야 할) 벽시계 시각 = 녹음 시작 시각. */
    val startEpochMs = System.currentTimeMillis()
    @Volatile private var closed = false

    init { out.write(ByteArray(HEADER)); Log.i(TAG, "record start epochMs=$startEpochMs file=${file.name}") }

    /** 인터리브드 스테레오 float. audibleEpochMs = 이 블록 첫 샘플이 들릴 시각. 오디오 스레드에서만 호출. */
    /** 지금까지 쓴 프레임 수(= 다음 블록이 놓일 WAV 위치). */
    val position: Long get() = frames

    /** 이 WAV 위치(프레임)에서 일어난 일을 로그로(시연 영상 싱크 확인용, 정확한 샘플 위치). */
    fun mark(frame: Long, what: String) = Log.i(TAG, "mark $what wavMs=${"%.1f".format(frame * 1000.0 / sampleRate)}")

    fun write(buf: FloatArray, len: Int, audibleEpochMs: Double) {
        if (closed) return
        // 빈 시간(음악 시작 전·일시정지)만큼 무음. 30ms 이하의 흔들림은 무시(연속 재생 중엔 샘플 수가 곧 시간)
        val expected = ((audibleEpochMs - startEpochMs) * sampleRate / 1000.0).toLong()
        if (expected - frames > sampleRate * 3 / 100) { val gap = (expected - frames).toInt(); writeRaw(FloatArray(gap * 2), gap * 2) }
        writeRaw(buf, len)
    }

    private fun writeRaw(buf: FloatArray, len: Int) {
        if (bytes.capacity() < len * 4) bytes = ByteBuffer.allocate(len * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        for (i in 0 until len) bytes.putFloat(buf[i])
        out.write(bytes.array(), 0, len * 4)
        frames += len / 2
    }

    fun close() {
        if (closed) return
        closed = true
        out.flush(); out.close()
        val dataLen = frames * 8
        RandomAccessFile(file, "rw").use { f ->
            val h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((HEADER - 8 + dataLen).toInt()).put("WAVE".toByteArray())
            // fmt: WAVE_FORMAT_IEEE_FLOAT(3), 2ch, 32bit
            h.put("fmt ".toByteArray()).putInt(18).putShort(3).putShort(2).putInt(sampleRate).putInt(sampleRate * 8).putShort(8).putShort(32).putShort(0)
            h.put("fact".toByteArray()).putInt(4).putInt(frames.toInt())
            h.put("data".toByteArray()).putInt(dataLen.toInt())
            f.seek(0); f.write(h.array())
        }
        Log.i(TAG, "record stop frames=$frames (${frames * 1000 / sampleRate}ms) startEpochMs=$startEpochMs")
    }

    companion object {
        const val TAG = "PlayDrive.Rec"
        /** RIFF(12) + fmt(8+18) + fact(8+4) + data(8) */
        private const val HEADER = 12 + 26 + 12 + 8
    }
}
