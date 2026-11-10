package app.playdrive.music.stems

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.playdrive.music.Cue
import app.playdrive.music.CueSource
import app.playdrive.music.Layer
import app.playdrive.music.LayerSource
import app.playdrive.music.MusicTiming
import app.playdrive.music.StereoPcm
import java.nio.ByteOrder

/** res/raw의 OGG Vorbis 루프 스템. 앞뒤 순환 패딩을 잘라 정확히 4마디만 쓴다. */
class OggLayerSource(
    private val context: Context,
    override val layer: Layer,
    private val resId: Int,
) : LayerSource {
    override val cacheKey: String = "ogg-${context.resources.getResourceEntryName(resId)}-${resLength(context, resId)}"

    override fun load(timing: MusicTiming): StereoPcm {
        val p = OggDecoder.decode(context, resId, timing.sampleRate)
        val pad = StemConfig.LOOP_PAD_FRAMES
        val n = timing.loopFrames
        check(p.frames >= pad + n) { "$cacheKey: ${p.frames} frames < pad+loop" }
        return StereoPcm(p.left.copyOfRange(pad, pad + n), p.right.copyOfRange(pad, pad + n))
    }
}

/** res/raw의 OGG 원샷 효과음. */
class OggCueSource(private val context: Context, override val cue: Cue, private val resId: Int) : CueSource {
    override val cacheKey: String = "ogg-${context.resources.getResourceEntryName(resId)}-${resLength(context, resId)}"
    override fun load(sampleRate: Int): StereoPcm = OggDecoder.decode(context, resId, sampleRate)
}

private fun resLength(context: Context, resId: Int): Long =
    context.resources.openRawResourceFd(resId).use { it.length }

/** MediaExtractor + MediaCodec으로 OGG를 Float 스테레오 PCM으로 디코딩. */
object OggDecoder {
    fun decode(context: Context, resId: Int, expectRate: Int): StereoPcm {
        val afd = context.resources.openRawResourceFd(resId)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            check(rate == expectRate) { "sample rate $rate != $expectRate" }
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var outFloat = false
            var outCh = ch
            val left = FloatList(); val right = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        outCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        if (outFloat) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= outCh) { val a = fb.get(); val b = if (outCh > 1) fb.get() else a; repeat(outCh - 2) { fb.get() }; left.add(a); right.add(b) }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= outCh) { val a = sb.get() / 32768f; val b = if (outCh > 1) sb.get() / 32768f else a; repeat(outCh - 2) { sb.get() }; left.add(a); right.add(b) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            codec.stop(); codec.release()
            return StereoPcm(left.toArray(), right.toArray())
        } finally {
            ex.release(); afd.close()
        }
    }

    private class FloatList {
        private var a = FloatArray(1 shl 20); private var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}
