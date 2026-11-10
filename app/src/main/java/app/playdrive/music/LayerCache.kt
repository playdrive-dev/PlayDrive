package app.playdrive.music

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 레이어 PCM 로더: 캐시 파일이 있으면 바로 읽고, 없으면 여러 코어로 병렬 합성한 뒤 저장한다.
 * version을 올리면(합성 코드·작곡 변경 시) 캐시가 무효화된다.
 */
class LayerCache(private val dir: File, private val version: String) {

    fun loadAll(sources: List<LayerSource>, timing: MusicTiming): Map<Layer, FloatArray> {
        val d = File(dir, "layers-$version-${timing.sampleRate}-${timing.bpm}").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("layers-") && f != d }?.forEach { it.deleteRecursively() }
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        try {
            return sources.map { src ->
                pool.submit(Callable {
                    val f = File(d, "${src.layer.name}.f32")
                    val cached = if (f.length() == timing.loopFrames * 4L) read(f, timing.loopFrames) else null
                    src.layer to (cached ?: src.load(timing).also { write(f, it) })
                })
            }.associate { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun read(f: File, n: Int): FloatArray = RandomAccessFile(f, "r").use { raf ->
        val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, n * 4L).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(n).also { buf.get(it) }
    }

    private fun write(f: File, data: FloatArray) {
        val tmp = File(f.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            val bb = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, data.size * 4L).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(data)
        }
        tmp.renameTo(f)
    }
}
