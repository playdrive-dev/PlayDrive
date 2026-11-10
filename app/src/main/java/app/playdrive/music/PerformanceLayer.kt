package app.playdrive.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** 코드 한 개: 근음 피치 클래스와 구성음(근음부터 위로). */
data class Chord(val name: String, val root: Int, val tones: List<Int>) {
    val pitchClasses: Set<Int> get() = tones.toSet()
}

/**
 * 루프 구간의 코드 타임라인(마디 단위). 박 단위 조회도 마디로 귀결된다(마디 안에서 코드 불변).
 * SONG(선택 구간 44~51마디): Am7 / Fmaj7 가 마디마다 교대(docs/dev-notes.md 분석).
 */
class ChordTimeline(val bars: List<Chord>) {
    fun chordAtBar(bar: Int): Chord = bars[((bar % bars.size) + bars.size) % bars.size]

    /** 루프 위치(프레임) → 코드. */
    fun chordAt(frame: Long, timing: MusicTiming): Chord = chordAtBar(((frame % timing.loopFrames) / timing.barFrames).toInt())

    companion object {
        val AM7 = Chord("Am7", 9, listOf(9, 0, 4, 7))     // A C E G
        val FMAJ7 = Chord("Fmaj7", 5, listOf(5, 9, 0, 4)) // F A C E
        val F = Chord("F", 5, listOf(5, 9, 0))
        val C = Chord("C", 0, listOf(0, 4, 7))
        val G = Chord("G", 7, listOf(7, 11, 2))
        /** A 마이너 펜타토닉: A C D E G */
        val A_MINOR_PENTATONIC = setOf(9, 0, 2, 4, 7)

        val SONG = ChordTimeline(List(8) { if (it % 2 == 0) AM7 else FMAJ7 })
        /** 개별 루프 세트는 진행이 스템마다 달라 가장 안전한 Am7(전부 펜타토닉 음)만 쓴다. */
        val LOOPS = ChordTimeline(List(4) { AM7 })
        val SYNTH = ChordTimeline(listOf(AM7, F, C, G))
    }
}

/**
 * 반응형 연주 레이어(순수 Kotlin). 믹서가 프레임마다 process()를 부른다.
 * - 모든 음은 8분음표 격자에 맞춰 시작(즉시 재생 없음, 최대 지연 = 8분음표 1개)
 * - 음은 현재 마디 코드의 구성음(+A 마이너 펜타토닉)만
 * - 음색: 2-오퍼레이터 FM 일렉트릭 피아노(로즈풍), 셰이커 = 고역 노이즈 버스트
 */
class PerformanceLayer(
    private val timing: MusicTiming,
    val timeline: ChordTimeline,
    private val level: Float = 0.5f,
) {
    private val sr = timing.sampleRate
    private val eighth = timing.beatFrames / 2

    // --- 미리 합성한 소리
    private val epCache = HashMap<Int, FloatArray>()
    private val shakerAccent = shaker(1.0f, 11)
    private val shakerSoft = shaker(0.55f, 23)

    // --- 보이스
    private class Voice(val pcm: FloatArray, val gain: Float, val panL: Float, val panR: Float) {
        var pos = 0; var release = 1f; var releasing = false
    }
    private val voices = ArrayList<Voice>(12)
    private val releaseStep = 1f / (0.03f * sr)

    // --- 예약 상태
    private var arpOn = false
    private var arpStep = 0
    private var nextArp = -1L
    private var shakerOn = false
    private var shakerStopAt = -1L
    private var nextShaker = -1L
    private var rootFifthAt = -1L
    private var mutedUntil = -1L

    init { // 오디오 스레드에서 합성하지 않도록 쓸 음을 미리 만든다
        for (c in timeline.bars.distinct()) { (0..3).forEach { ep(arpNote(c, it)) }; rootFifth(c).forEach { ep(it) } }
    }

    /** 마지막으로 시작한 음(테스트·디버그용): (시작 프레임, MIDI 목록). */
    val played = ArrayList<Pair<Long, List<Int>>>()
    val shakerHits = ArrayList<Long>()
    var recordPlayed = false

    var outL = 0f; private set
    var outR = 0f; private set

    /** 다음 8분음표 격자(지금이 격자면 지금). */
    fun quantize(frame: Long): Long = ((frame + eighth - 1) / eighth) * eighth

    fun command(cmd: MusicCommand, grid: Long) {
        when (cmd) {
            is MusicCommand.PerfArp -> if (cmd.on && !arpOn) { arpOn = true; arpStep = 0; nextArp = quantize(grid) } else if (!cmd.on) arpOn = false
            MusicCommand.PerfRootFifth -> rootFifthAt = quantize(grid)
            is MusicCommand.PerfShaker -> if (cmd.on) {
                if (!shakerOn || shakerStopAt >= 0) { if (!shakerOn) nextShaker = quantize(grid); shakerOn = true; shakerStopAt = -1 }
            } else if (shakerOn) {
                shakerStopAt = timing.nextBoundary(grid, timing.barFrames) // 마디 끝까지 치고 멈춤
            }
            else -> Unit
        }
    }

    /** 테이프 스톱: 울리는 음을 30ms에 걸쳐 끄고 until 프레임까지 새 음을 내지 않는다. */
    fun silence(grid: Long, until: Long) {
        mutedUntil = until
        voices.forEach { it.releasing = true }
        arpOn = false; rootFifthAt = -1
        if (shakerOn) { shakerOn = false; shakerStopAt = -1 }
    }

    fun process(grid: Long) {
        val muted = grid < mutedUntil
        if (!muted) {
            if (arpOn && grid == nextArp) {
                val c = timeline.chordAt(grid, timing)
                trigger(grid, listOf(arpNote(c, arpStep % 4)), 0.9f)
                arpStep++
                nextArp += timing.beatFrames
            }
            if (rootFifthAt == grid) {
                val c = timeline.chordAt(grid, timing)
                trigger(grid, rootFifth(c), 1.0f)
                rootFifthAt = -1
            }
            if (shakerOn && grid == nextShaker) {
                if (shakerStopAt in 0..grid) { shakerOn = false; shakerStopAt = -1 } else {
                    val onBeat = grid % timing.beatFrames == 0L
                    addVoice(if (onBeat) shakerAccent else shakerSoft, 0.7f, 0.35f)
                    if (recordPlayed) shakerHits += grid
                    nextShaker += eighth
                }
            }
        } else {
            if (arpOn && nextArp <= grid) nextArp = quantize(mutedUntil)
            if (shakerOn && nextShaker <= grid) nextShaker = quantize(mutedUntil)
        }

        var l = 0f; var r = 0f
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (v.releasing) { v.release -= releaseStep; if (v.release <= 0f) { it.remove(); continue } }
            if (v.pos >= v.pcm.size) { it.remove(); continue }
            val s = v.pcm[v.pos++] * v.gain * v.release
            l += s * v.panL; r += s * v.panR
        }
        outL = l * level; outR = r * level
    }

    private fun trigger(grid: Long, notes: List<Int>, vel: Float) {
        if (recordPlayed) played += grid to notes
        notes.forEachIndexed { i, m -> addVoice(ep(m), vel / notes.size.coerceAtLeast(1).toFloat().pow(0.5f), if (i % 2 == 0) -0.15f else 0.15f) }
    }

    private fun addVoice(pcm: FloatArray, gain: Float, pan: Float) {
        if (voices.size >= 12) voices.removeAt(0)
        val a = (pan + 1) * PI / 4
        voices += Voice(pcm, gain, (kotlin.math.cos(a) * 1.414).toFloat(), (sin(a) * 1.414).toFloat())
    }

    // ------------------------------------------------------------------ 음 선택
    /** 아르페지오: 코드 구성음을 근음부터 위로 4음(A4/F4 근처). */
    fun arpNote(c: Chord, step: Int): Int = voicing(c, 64)[step.coerceIn(0, 3)]

    /** 근음 + 5도(5도가 구성음에 있으면 그것, 없으면 3도). 근음은 A3/F3 근처. */
    fun rootFifth(c: Chord): List<Int> {
        val root = midiAtOrAbove(c.root, 52)
        val fifthPc = (c.root + 7) % 12
        val second = if (fifthPc in c.pitchClasses) fifthPc else c.tones[1]
        return listOf(root, midiAtOrAbove(second, root + 1))
    }

    /** 구성음을 근음부터 오름차순으로 [low, low+12) 근처에서 배치(4음 미만이면 옥타브 위 근음 추가). */
    private fun voicing(c: Chord, low: Int): List<Int> {
        val out = mutableListOf(midiAtOrAbove(c.root, low))
        for (pc in c.tones.drop(1)) out += midiAtOrAbove(pc, out.last() + 1)
        while (out.size < 4) out += out[out.size - 3] + 12
        return out.take(4)
    }

    private fun midiAtOrAbove(pc: Int, low: Int): Int { var m = low; while (m % 12 != pc) m++; return m }

    // ------------------------------------------------------------------ 합성
    private fun ep(midi: Int): FloatArray = epCache.getOrPut(midi) { renderEp(midi) }

    /** 2-오퍼레이터 FM 로즈: 모듈레이터 1:1, 지수 감소 인덱스(어택의 '팅' 소리) + 14:1 약한 틴. */
    private fun renderEp(midi: Int): FloatArray {
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val n = (1.6 * sr).toInt()
        val out = FloatArray(n)
        var max = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val idx = 1.6 * exp(-t / 0.18) + 0.25
            val mod = sin(2 * PI * hz * t)
            val tine = 0.12 * exp(-t / 0.05) * sin(2 * PI * hz * 14 * t)
            val env = (if (t < 0.003) t / 0.003 else 1.0) * exp(-t / 0.75)
            val v = ((sin(2 * PI * hz * t + idx * mod) + tine) * env).toFloat()
            out[i] = v; max = maxOf(max, abs(v))
        }
        for (i in out.indices) out[i] *= 0.6f / max
        // 끝 20ms 페이드
        val f = (0.02 * sr).toInt()
        for (k in 0 until f) out[n - 1 - k] *= k.toFloat() / f
        return out
    }

    private fun shaker(amp: Float, seed: Int): FloatArray {
        val rnd = Random(seed)
        val n = (0.09 * sr).toInt()
        val out = FloatArray(n)
        var p1 = 0f; var p2 = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / sr
            val x = rnd.nextFloat() * 2 - 1
            val d1 = x - p1; p1 = x
            val d2 = d1 - p2; p2 = d1
            val env = (if (t < 0.006f) t / 0.006f else 1f) * exp(-(t - 0.006f).coerceAtLeast(0f) / 0.03f)
            out[i] = d2 * 0.25f * env * amp
        }
        return out
    }
}
