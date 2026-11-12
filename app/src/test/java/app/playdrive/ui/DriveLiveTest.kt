package app.playdrive.ui

import app.playdrive.album.MoodState
import app.playdrive.model.TurnSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE: 주행 중에도 음악에 맞춰 반응. 광과민성 제한(밝기 최대 +35%, 초당 3회 이상 점멸 금지 = 어느 1초에도 2회까지)과 각 효과를 검사한다.
 * 레벨 전환(LIVE ↔ CALM)도 여기서.
 */
class DriveLiveTest {
    private val calm = MoodState(0.2f, 0.9f, 0f, false)
    private fun liveScene() = MoodScene(startMs = 0, initial = calm).apply { effect = EffectLevel.LIVE }

    /** 메인 빛 실제 밝기 배수(속도 색 포함, 테이프스톱 어두워짐은 빼고 = 밝아지는 쪽만 본다). */
    private fun MoodFrame.light() = mainGain * (1f + LiveMotion.HEAT_GAIN * heat)

    /** 60fps로 주행을 흘리며 프레임을 모은다. beat = 시각 → 박 정보. */
    private fun drive(sc: MoodScene, from: Long, to: Long, beat: (Long) -> Beat?, signals: (Long) -> Unit = {}): List<Pair<Long, MoodFrame>> {
        val out = ArrayList<Pair<Long, MoodFrame>>()
        var t = from
        var nextSig = from
        while (t <= to) {
            if (t >= nextSig) { signals(t); nextSig += 250 }   // 차량 신호는 4Hz
            out += t to sc.frame(t, beat = beat(t))
            t += 16
        }
        return out
    }

    private fun beatAt(bpm: Float, drums: Boolean, layers: Int) = { t: Long -> val ms = 60_000f / bpm; Beat((t % ms.toLong()) / ms, ms, drums, layers) }

    /** 밝아지는 점멸 시각들: 최저점에서 3% 이상 오르면 1회, 꼭짓점에서 3% 이상 내려와야 다음을 센다(히스테리시스). */
    private fun flashes(fr: List<Pair<Long, MoodFrame>>): List<Long> {
        val out = mutableListOf<Long>()
        var armed = true; var low = Float.MAX_VALUE; var high = 0f
        for ((t, f) in fr) {
            val v = f.light()
            if (armed) { low = minOf(low, v); if (v - low >= 0.03f) { out += t; armed = false; high = v } }
            else { high = maxOf(high, v); if (high - v >= 0.03f) { armed = true; low = v } }
        }
        return out
    }

    private fun assertPhotosensitiveSafe(fr: List<Pair<Long, MoodFrame>>) {
        fr.forEach { (t, f) -> assertTrue("t=$t 밝기 ${f.light()}", f.light() <= LiveMotion.MAX_GAIN + 1e-4f) }
        val fl = flashes(fr)
        fl.forEach { t -> assertTrue("t=$t 1초 안 점멸 ${fl.count { it in t until t + 1_000 }}회", fl.count { it in t until t + 1_000 } < 3) }   // 초당 3회 이상 금지
        fr.zipWithNext().forEach { (a, b) -> assertTrue("t=${b.first} 한 프레임 밝기 변화", kotlin.math.abs(b.second.light() - a.second.light()) <= 0.12f) }
    }

    @Test fun `LIVE 600초 - 밝기 상한 1·35배, 1초에 2회까지만 점멸, 급한 밝기 변화 없음`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        var layers = 2; var drums = false
        val fr = drive(sc, 0, 600_000, { t -> beatAt(90f, drums, layers)(t) }) { t ->
            val sec = t / 1000
            drums = sec % 40 >= 5                          // 40초마다 5초 쉬었다가 드롭
            layers = if (drums) (2 + (sec % 40) / 5).toInt().coerceAtMost(6) else 2
            val tape = sec % 60 == 30L && t % 1000 == 0L
            sc.music(layers, tape, sec % 90 > 45, t, speedKmh = (sec % 120).toFloat(), turn = if (sec % 30 < 4) TurnSignal.LEFT else TurnSignal.OFF)
        }
        assertTrue("맥박이 침", flashes(fr).size > 300)
        assertPhotosensitiveSafe(fr)
    }

    @Test fun `빠른 곡(240BPM)이어도 맥박은 1초에 2회까지`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        val fr = drive(sc, 0, 60_000, beatAt(240f, true, 4))
        val n = flashes(fr).size
        assertTrue("1분에 $n 회", n in 75..121)
        assertPhotosensitiveSafe(fr)
    }

    @Test fun `킥 맥박 - 밝기 +10% 크기 +4%, 드럼이 빠지면 멈춤`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        val on = drive(sc, 0, 5_000, beatAt(90f, true, 4))
        val peak = on.filter { it.first > 2_000 }.maxOf { it.second.mainGain }
        assertEquals(1.10f, peak, 0.01f)
        assertEquals(1.04f, on.filter { it.first > 2_000 }.maxOf { it.second.mainScale }, 0.005f)
        val off = drive(sc, 5_016, 8_000, beatAt(90f, false, 2))
        off.filter { it.first > 5_700 }.forEach { assertEquals(1f, it.second.mainGain, 1e-4f); assertEquals(1f, it.second.mainScale, 1e-4f) }
    }

    @Test fun `출발 드롭 - 한 번 크게(+35%) 밝아져 0·6초에 가라앉고, 바닥 물결이 1·2초 퍼짐`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        drive(sc, 0, 2_000, beatAt(90f, false, 2))
        val fr = drive(sc, 2_016, 4_000, beatAt(90f, true, 4)).toMap()
        assertTrue(fr.getValue(2_160).mainGain >= 1.30f)
        assertTrue(fr.getValue(2_016).ripple in 0f..0.05f)
        assertTrue(fr.getValue(2_608).ripple in 0.4f..0.5f)
        assertEquals(-1f, fr.getValue(3_232).ripple, 0f)
        assertTrue(fr.getValue(2_800).mainGain < 1.12f)               // 0.75초 뒤엔 맥박 수준
    }

    @Test fun `출발 직후(0·4초)에 드럼이 들어와도 드롭, 주행 시작 때 이미 드럼이 있었으면 드롭 아님`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        drive(sc, 0, 400, beatAt(90f, false, 2))
        assertTrue(drive(sc, 416, 1_000, beatAt(90f, true, 4)).maxOf { it.second.mainGain } >= 1.30f)
        val sc2 = liveScene()
        sc2.setRestricted(true, 0)
        assertTrue(drive(sc2, 0, 2_000, beatAt(90f, true, 4)).maxOf { it.second.mainGain } <= 1.101f)
    }

    @Test fun `악기 빛 - 들리는 레이어 수만큼 하나씩 켜짐`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        for (n in 1..6) {
            val f = drive(sc, (n - 1) * 2_000L, n * 2_000L, beatAt(90f, true, n)).last().second
            assertEquals(n, f.liveLights.count { it > 0.99f })
            assertEquals(6 - n, f.liveLights.count { it < 0.01f })
        }
    }

    @Test fun `속도 색 - 80km_h에서 +15%, 2초 스무딩`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        sc.music(2, false, false, 0, speedKmh = 100f)
        val fr = drive(sc, 0, 4_000, { null }).toMap()
        assertTrue(fr.getValue(1_008).heat in 0.6f..0.9f)
        assertTrue(fr.getValue(2_000).heat > 0.94f)
        assertEquals(1f, fr.getValue(4_000).heat, 0.01f)
    }

    @Test fun `테이프스톱 - 0·7초에 어두워지며 가라앉고, 레이어가 돌아오는 만큼 떠오름`() {
        val sc = liveScene()
        sc.music(6, false, false, 0)
        sc.setRestricted(true, 0)
        drive(sc, 0, 1_000, beatAt(90f, true, 6))
        sc.music(2, true, false, 1_000)                               // 테이프스톱 → 2개만
        val down = drive(sc, 1_016, 2_000, beatAt(90f, false, 2)).toMap()
        assertTrue(down.getValue(1_400).sink in 0.4f..0.75f)
        assertEquals(1f, down.getValue(1_720).sink, 0.03f)
        var t = 2_000L
        val sinks = (3..6).map { n ->                                  // 마디마다 하나씩 복구
            sc.music(n, false, false, t)
            drive(sc, t + 16, t + 2_667, beatAt(90f, true, n)).last().second.sink.also { t += 2_667 }
        }
        assertEquals(listOf(0.75f, 0.5f, 0.25f, 0f), sinks.map { (it * 4).let(Math::round) / 4f })
    }

    @Test fun `테이프스톱 직후 믹서가 아직 레이어를 내고 있어도(효과 0·8초) 가라앉은 채로`() {
        val sc = liveScene()
        sc.music(6, false, false, 0)
        sc.setRestricted(true, 0)
        drive(sc, 0, 1_000, beatAt(90f, true, 6))
        sc.music(2, true, false, 1_000)                               // 신호상 2개로, 들리는 건 아직 6개
        val hold = drive(sc, 1_016, 1_900, beatAt(90f, false, 6)).toMap()
        assertEquals(1f, hold.getValue(1_896).sink, 0.03f)
        val after = drive(sc, 1_916, 2_500, beatAt(90f, false, 2)).last().second
        assertEquals(1f, after.sink, 0.03f)                           // 줄어든 뒤에도 회복 전까지 그대로
    }

    @Test fun `깜빡이 - 그 방향 가장자리만 박에 맞춰 번짐`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        sc.music(4, false, false, 0, turn = TurnSignal.RIGHT)
        val fr = drive(sc, 0, 3_000, beatAt(90f, true, 4)).filter { it.first > 1_000 }
        assertTrue(fr.all { it.second.edgeL == 0f })
        assertTrue(fr.maxOf { it.second.edgeR } > 0.95f && fr.minOf { it.second.edgeR } in 0.5f..0.6f)
    }

    @Test fun `비 - 물방울 2초 페이드, 킥에 맞춰 반짝임`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        sc.music(4, false, true, 0)
        val fr = drive(sc, 0, 3_000, beatAt(90f, true, 4)).toMap()
        assertTrue(fr.getValue(992).rainAmount in 0.45f..0.55f)
        assertEquals(1f, fr.getValue(2_000).rainAmount, 0.01f)
        assertTrue(fr.filterKeys { it > 2_000 }.values.map { it.sparkle }.let { it.max() > 0.9f && it.min() < 0.01f })
    }

    @Test fun `LIVE에서도 바탕(음악 색·커버·시간대)은 주행 중 보관만`() {
        val sc = liveScene()
        sc.setDayPart(app.playdrive.album.AlbumNaming.PartOfDay.EVENING, 0, immediate = true)
        sc.setRestricted(true, 1_000)
        val a = sc.frame(1_000)
        sc.show(MoodState(1f, 0.1f, 1f, true), 2_000)
        sc.setDayPart(app.playdrive.album.AlbumNaming.PartOfDay.NIGHT, 2_000)
        val b = sc.frame(30_000, beat = Beat(0.5f, 667f, true, 4))
        assertEquals(a.mood, b.mood); assertEquals(a.background, b.background); assertEquals(a.dayTo, b.dayTo); assertEquals(a.timeSec, b.timeSec, 0f)
        assertEquals(1f, b.pulse, 0f)                                  // 정차용 숨쉬기는 꺼져 있음(킥 맥박은 mainGain)
    }

    @Test fun `레벨 전환 - 다음 출발부터 적용, LIVE는 계속 그리고 CALM은 이벤트 때만`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        assertTrue(sc.animatingAt(5_000))
        sc.effect = EffectLevel.CALM                                   // 주행 중에 바꿔도 이번 주행은 그대로
        assertTrue(sc.animatingAt(6_000))
        sc.setRestricted(false, 10_000)
        sc.setRestricted(true, 20_000)                                 // 다음 출발
        assertFalse(sc.animatingAt(25_000))
        assertEquals(sc.frame(25_000), sc.frame(40_000, beat = Beat(0.3f, 667f, true, 4)))
        sc.setRestricted(false, 50_000)
        sc.effect = EffectLevel.LIVE
        sc.setRestricted(true, 60_000)
        assertTrue(sc.animatingAt(61_000))
        assertTrue(drive(sc, 60_000, 63_000, beatAt(90f, true, 4)).map { it.second.mainGain }.distinct().size > 10)
    }

    @Test fun `LIVE 주행 뒤 정차 - 효과가 3초에 걸쳐 정차 모습으로`() {
        val sc = liveScene()
        sc.setRestricted(true, 0)
        sc.music(4, false, false, 0, speedKmh = 80f)                  // 차량 신호는 주행 중 4Hz로 들어온다
        drive(sc, 0, 3_000, beatAt(90f, true, 4))
        sc.setRestricted(false, 3_000)
        val a = sc.frame(3_000)
        assertTrue(a.heat > 0.9f && a.liveLights.count { it > 0.9f } == 4)
        val b = sc.frame(6_000)
        assertEquals(0f, b.heat, 0f); assertTrue(b.liveLights.isEmpty()); assertEquals(1f, b.mainGain, 1e-4f)
    }
}
