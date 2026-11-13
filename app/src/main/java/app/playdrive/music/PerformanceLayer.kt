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
    private val voices = ArrayList<Voice>(MAX_VOICES)
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

    // --- 연주 기록 보상(이어진 마디·판정). 음높이 있는 소리는 울리는 순간의 코드에서 고른다.
    private enum class Hit { FILL_ROLL, SWELL, CYMBAL, CYMBAL_SOFT, DROP, CADENCE, STICK, STICK_ACCENT }
    /** 루프 처음이 시작된 격자 위치(곡 처음부터 이후). 코드는 (격자 − 이 값)으로 찾는다. */
    var loopOrigin = 0L
    private val stick = stick(1.0f, 71)
    private val stickSoft = stick(0.7f, 73)

    /** 4박 스틱 카운트인([bar]부터 박마다, 첫 박 강세). 음높이 없음. */
    fun countIn(bar: Long) { for (k in 0 until 4) scheduled += Scheduled(bar + k.toLong() * timing.beatFrames, if (k == 0) Hit.STICK_ACCENT else Hit.STICK) }
    /** 다음 곡으로 넘어가는 다운비트 심벌. */
    fun crash(at: Long) { scheduled += Scheduled(at, Hit.CYMBAL) }
    private data class Scheduled(val at: Long, val hit: Hit)
    private val scheduled = ArrayList<Scheduled>()
    /** 0 없음, 1 연주 악기 합류(박마다 아르페지오), 2 클라이맥스(8분음표 아르페지오 + 다운비트 화음 + 종). */
    var bedLevel = 0; private set
    private val swell = swell(timing.beatFrames)
    private val cymbal = cymbal(1.0f, 31)
    private val cymbalSoft = cymbal(0.5f, 37)
    private val boomCache = HashMap<Int, FloatArray>()
    private val bellCache = HashMap<Int, FloatArray>()
    /** 음높이 없는 보상 소리 시작 프레임(테스트용). */
    val noiseHits = ArrayList<Pair<Long, String>>()

    init { // 오디오 스레드에서 합성하지 않도록 쓸 음을 미리 만든다
        for (c in timeline.bars.distinct()) {
            (0..3).forEach { ep(arpNote(c, it)) }; rootFifth(c).forEach { ep(it) }
            (0..7).forEach { ep(climaxNote(c, it)) }; cadence(c).forEach { ep(it) }; stab(c).forEach { ep(it) }; dropStab(c).forEach { ep(it) }
            boom(c.root); bell(midiAtOrAbove(c.root, 81))
        }
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
            MusicCommand.PerfFill -> {
                // 마디의 마지막 박에 16분음표 롤 + 스웰 → 다음 다운비트에 심벌. 이미 마지막 박이 지났으면 다음 마디.
                var bar = timing.nextBoundary(grid, timing.barFrames)
                if (bar - timing.beatFrames < grid) bar += timing.barFrames
                val lastBeat = bar - timing.beatFrames
                for (k in 0 until 4) scheduled += Scheduled(lastBeat + k * timing.beatFrames / 4L, Hit.FILL_ROLL)
                scheduled += Scheduled(lastBeat, Hit.SWELL)
                scheduled += Scheduled(bar, Hit.CYMBAL)
            }
            is MusicCommand.PerfBed -> bedLevel = cmd.level.coerceIn(0, 2)
            is MusicCommand.PerfReward -> when (cmd.kind) {
                MusicCommand.Reward.DROP -> { val b = timing.nextBoundary(grid - 1, timing.beatFrames); scheduled += Scheduled(b, Hit.DROP); scheduled += Scheduled(b, Hit.CYMBAL) }
                MusicCommand.Reward.SWELL -> {
                    val end = timing.nextBoundary(grid + timing.beatFrames, timing.beatFrames)
                    scheduled += Scheduled(end - timing.beatFrames, Hit.SWELL); scheduled += Scheduled(end, Hit.CYMBAL_SOFT)
                }
                MusicCommand.Reward.CADENCE -> scheduled += Scheduled(quantize(grid), Hit.CADENCE)
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
        scheduled.clear(); bedLevel = 0
    }

    fun process(grid: Long) {
        val muted = grid < mutedUntil
        if (!muted) {
            if (arpOn && grid == nextArp) {
                val c = timeline.chordAt(grid - loopOrigin, timing)
                if (bedLevel == 0) trigger(grid, listOf(arpNote(c, arpStep % 4)), 0.9f)  // 합류한 아르페지오가 있으면 그쪽이 연주
                arpStep++
                nextArp += timing.beatFrames
            }
            if (rootFifthAt == grid) {
                val c = timeline.chordAt(grid - loopOrigin, timing)
                trigger(grid, rootFifth(c), 1.0f)
                rootFifthAt = -1
            }
            if (bedLevel > 0) bed(grid)
            if (scheduled.isNotEmpty()) fireDue(grid)
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

    /** 합류/클라이맥스 아르페지오: 마디 안 위치로 음을 고른다(언제 켜도 박자·코드가 맞음). */
    private fun bed(grid: Long) {
        val inBar = grid % timing.barFrames
        val c = timeline.chordAt(grid - loopOrigin, timing)
        if (bedLevel == 1) {
            if (inBar % timing.beatFrames == 0L) trigger(grid, listOf(arpNote(c, (inBar / timing.beatFrames).toInt())), 0.55f)
        } else if (inBar % eighth == 0L) {
            trigger(grid, listOf(climaxNote(c, (inBar / eighth).toInt())), 0.5f)
            if (inBar == 0L) {
                trigger(grid, stab(c), 0.55f)
                addVoice(bell(midiAtOrAbove(c.root, 81)), 0.35f, 0.3f)
            }
        }
    }

    private fun fireDue(grid: Long) {
        val it = scheduled.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.at > grid) continue
            it.remove()
            if (s.at < grid) continue // 테이프스톱 등으로 지나간 예약은 버린다
            val c = timeline.chordAt(grid - loopOrigin, timing)
            when (s.hit) {
                Hit.FILL_ROLL -> { addVoice(if (grid % timing.beatFrames == 0L) shakerAccent else shakerSoft, 0.8f, -0.3f); note(grid, "roll") }
                Hit.SWELL -> { addVoice(swell, 0.9f, 0f); note(grid, "swell") }
                Hit.CYMBAL -> { addVoice(cymbal, 0.8f, 0.2f); note(grid, "cymbal") }
                Hit.CYMBAL_SOFT -> { addVoice(cymbalSoft, 0.7f, -0.2f); note(grid, "cymbal") }
                Hit.DROP -> { addVoice(boom(c.root), 1.0f, 0f); trigger(grid, dropStab(c), 1.0f) }
                Hit.CADENCE -> trigger(grid, cadence(c), 1.0f)
                Hit.STICK_ACCENT -> { addVoice(stick, 1.0f, 0f); note(grid, "stick") }
                Hit.STICK -> { addVoice(stickSoft, 1.0f, 0f); note(grid, "stick") }
            }
        }
    }

    private fun note(grid: Long, what: String) { if (recordPlayed) noiseHits += grid to what }

    private fun trigger(grid: Long, notes: List<Int>, vel: Float) {
        if (recordPlayed) played += grid to notes
        notes.forEachIndexed { i, m -> addVoice(ep(m), vel / notes.size.coerceAtLeast(1).toFloat().pow(0.5f), if (i % 2 == 0) -0.15f else 0.15f) }
    }

    private fun addVoice(pcm: FloatArray, gain: Float, pan: Float) {
        if (voices.size >= MAX_VOICES) voices.removeAt(0)
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

    /** 클라이맥스: 두 옥타브에 걸친 코드톤 상행 8음(8분음표). */
    fun climaxNote(c: Chord, step: Int): Int { val lo = voicing(c, 64); val hi = lo.map { it + 12 }; return (lo + hi)[step.coerceIn(0, 7)] }

    /** 마무리 화음: 근음(낮은 옥타브) + 코드 구성음 4개. */
    fun cadence(c: Chord): List<Int> = listOf(midiAtOrAbove(c.root, 45)) + voicing(c, 57)

    /** 클라이맥스 다운비트 화음. */
    fun stab(c: Chord): List<Int> = voicing(c, 60).take(3)

    /** 드롭 강화: 근음·5도(구성음일 때)·옥타브. */
    fun dropStab(c: Chord): List<Int> { val rf = rootFifth(c); return rf + (rf[0] + 12) }

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

    /** 낮은 붐: 코드 근음(A1/F1 근처) 사인 + 짧은 어택 클릭. */
    private fun boom(rootPc: Int): FloatArray = boomCache.getOrPut(rootPc) {
        val hz = 440.0 * 2.0.pow((midiAtOrAbove(rootPc, 33) - 69) / 12.0)
        val n = (0.9 * sr).toInt()
        FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val env = (if (t < 0.004) t / 0.004 else 1.0) * exp(-t / 0.28)
            (0.55 * sin(2 * PI * hz * t) * env + 0.12 * sin(2 * PI * hz * 2 * t) * exp(-t / 0.08)).toFloat()
        }
    }

    /** 종소리(사인 + 비정수배 배음 약하게, 길게). */
    private fun bell(midi: Int): FloatArray = bellCache.getOrPut(midi) {
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val n = (1.4 * sr).toInt()
        FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val env = (if (t < 0.002) t / 0.002 else 1.0) * exp(-t / 0.5)
            ((0.5 * sin(2 * PI * hz * t) + 0.12 * sin(2 * PI * hz * 2.0 * t) * exp(-t / 0.2)) * env).toFloat()
        }
    }

    /** 스웰: [frames] 동안 커지는 고역 노이즈(끝에서 최대 → 바로 다운비트). */
    private fun swell(frames: Int): FloatArray {
        val rnd = Random(53); var p = 0f
        return FloatArray(frames) { i ->
            val x = rnd.nextFloat() * 2 - 1; val d = x - p; p = x
            val a = i.toFloat() / frames
            d * 0.22f * a * a * a
        }
    }

    /** 심벌: 고역 노이즈, 1.4초 감쇠. */
    private fun cymbal(amp: Float, seed: Int): FloatArray {
        val rnd = Random(seed); var p1 = 0f; var p2 = 0f
        val n = (1.4 * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toFloat() / sr
            val x = rnd.nextFloat() * 2 - 1; val d1 = x - p1; p1 = x; val d2 = d1 - p2; p2 = d1
            val env = (if (t < 0.003f) t / 0.003f else 1f) * exp(-t / 0.45f)
            d2 * 0.16f * env * amp
        }
    }

    /** 스틱(림 클릭): 아주 짧은 대역 노이즈 두 겹. 음높이 없음. */
    private fun stick(amp: Float, seed: Int): FloatArray {
        val rnd = Random(seed); var p1 = 0f; var lp = 0f
        val n = (0.05 * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toFloat() / sr
            val x = rnd.nextFloat() * 2 - 1; val d = x - p1; p1 = x
            lp += (d - lp) * 0.35f   // 고역 노이즈를 살짝 둥글게(나무 느낌)
            val env = (if (t < 0.0008f) t / 0.0008f else 1f) * exp(-t / 0.012f)
            lp * 0.9f * env * amp
        }
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

    companion object { const val MAX_VOICES = 24 }
}
