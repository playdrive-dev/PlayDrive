package app.playdrive.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 시연 영상용(디버그 빌드 전용): 앱이 AudioTrack에 쓰는 소리를 그대로 16bit 스테레오 WAV로 저장.
 * 에뮬레이터 화면 녹화(adb emu screenrecord)·scrcpy는 AAOS 차량 오디오 버스의 앱 소리를 담지 못해 따로 뜬다.
 * 일시정지 동안은 같은 길이의 무음을 써서 영상과 시간이 어긋나지 않게 한다.
 * [startEpochMs] = 첫 샘플을 쓴 벽시계 시각(영상과 맞출 때 사용).
 */
class WavTap(private val file: File, private val sampleRate: Int) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 18)
    private var frames = 0L
    private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile var startEpochMs = 0L; private set
    @Volatile private var closed = false

    init { out.write(ByteArray(44)) }

    /** 인터리브드 스테레오 float(-1~1). 오디오 스레드에서만 호출. */
    fun write(buf: FloatArray, len: Int) {
        if (closed) return
        if (startEpochMs == 0L) startEpochMs = System.currentTimeMillis()
        if (bytes.capacity() < len * 2) bytes = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        for (i in 0 until len) bytes.putShort((buf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(bytes.array(), 0, len * 2)
        frames += len / 2
    }

    fun silence(frameCount: Int) { val z = FloatArray(frameCount * 2); write(z, z.size) }

    fun close() {
        if (closed) return
        closed = true
        out.flush(); out.close()
        val dataLen = frames * 4
        RandomAccessFile(file, "rw").use { f ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
            h.put("data".toByteArray()).putInt(dataLen.toInt())
            f.seek(0); f.write(h.array())
        }
    }
}
