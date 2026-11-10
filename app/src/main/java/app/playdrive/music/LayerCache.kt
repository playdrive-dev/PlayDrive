package app.playdrive.music

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 레이어·효과음 PCM 로더: 캐시 파일(cacheKey별)이 있으면 바로 읽고, 없으면 여러 코어로 병렬 로드
 * (합성 또는 OGG 디코딩)한 뒤 저장한다. 파일 형식: [left Float LE × n][right Float LE × n] (모노면 left만).
 */
class LayerCache(private val dir: File) {

    fun loadAll(layers: List<LayerSource>, cues: List<CueSource>, timing: MusicTiming): Pair<Map<Layer, StereoPcm>, Map<Cue, StereoPcm>> {
        val d = File(dir, "pcm-v2").apply { mkdirs() }
        val keep = (layers.map { it.cacheKey } + cues.map { it.cacheKey }).map { "$it.f32" }.toSet()
        d.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        dir.listFiles { f -> f.name.startsWith("layers-") }?.forEach { it.deleteRecursively() } // v1 캐시 정리
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        try {
            val lf = layers.map { s -> pool.submit(Callable { s.layer to cached(File(d, "${s.cacheKey}.f32")) { s.load(timing) } }) }
            val cf = cues.map { s -> pool.submit(Callable { s.cue to cached(File(d, "${s.cacheKey}.f32")) { s.load(timing.sampleRate) } }) }
            return lf.associate { it.get() } to cf.associate { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun cached(f: File, load: () -> StereoPcm): StereoPcm {
        read(f)?.let { return it }
        return load().also { write(f, it) }
    }

    private fun read(f: File): StereoPcm? {
        if (!f.exists() || f.length() < 8) return null
        return RandomAccessFile(f, "r").use { raf ->
            val bb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            val stereo = bb.getInt(0) == 2
            val n = bb.getInt(4)
            bb.position(8)
            val fb = bb.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val l = FloatArray(n).also { fb.get(it) }
            if (stereo) StereoPcm(l, FloatArray(n).also { fb.get(it) }) else StereoPcm.mono(l)
        }
    }

    private fun write(f: File, p: StereoPcm) {
        val tmp = File(f.path + ".tmp")
        val ch = if (p.isMono) 1 else 2
        RandomAccessFile(tmp, "rw").use { raf ->
            val bb = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, 8L + 4L * p.frames * ch).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(ch); bb.putInt(p.frames)
            val fb = bb.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            fb.put(p.left); if (ch == 2) fb.put(p.right)
        }
        tmp.renameTo(f)
    }
}
