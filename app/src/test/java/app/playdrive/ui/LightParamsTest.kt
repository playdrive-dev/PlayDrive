package app.playdrive.ui

import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.CoverArt
import app.playdrive.album.MoodState
import app.playdrive.ui.light.LightParams
import app.playdrive.ui.light.LightShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 셰이더 입력: 시간대 색(채도 낮게 2~3색), 물결 uniform, 커버도 같은 물결 스타일. */
class LightParamsTest {
    private fun lum(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
    private fun hex(c: FloatArray) = c.map { (it * 255 + 0.5f).toInt() }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b }

    @Test fun `시간대 색 - 아침 청회색·금빛, 한낮 청록, 노을 남색+지평선 주황, 밤 검은 남색+청록 앰비언트`() {
        val m = LightParams.palette(PartOfDay.MORNING); val d = LightParams.palette(PartOfDay.DAY)
        val e = LightParams.palette(PartOfDay.EVENING); val n = LightParams.palette(PartOfDay.NIGHT)
        assertTrue(lum(m.top) < lum(m.bottom) * 0.5f && m.bottom[2] > m.bottom[0] * 1.5f)       // 짙은 청회색 → 맑은 하늘빛 청색(위 짙게, 아래 밝게)
        assertTrue(m.accent[0] > m.accent[2])                                                    // 연한 금빛
        assertTrue(d.top[1] > d.top[0] * 3 && d.wave[1] > d.wave[0] * 2)                        // 청록
        assertEquals(0x0E1A3A, hex(e.top)); assertTrue(lum(e.bottom) > lum(e.top) * 2 && e.bottom[2] > e.bottom[0] * 2)   // 남색 → 밝은 남색
        assertEquals(0xC9805A, hex(e.band)); assertTrue(e.band[3] > 0f)
        assertEquals(0x3FD0C0, hex(n.accent)); assertEquals(0x3FD0C0, hex(n.ambient))               // 바닥 앰비언트 선 = 청록
        assertTrue(listOf(m, d, e).all { lum(it.top) > lum(n.top) })                             // 밤이 가장 어둡다
        listOf(PartOfDay.MORNING, PartOfDay.DAY, PartOfDay.NIGHT, PartOfDay.DAWN).forEach { assertEquals(0f, LightParams.palette(it).band[3], 0f) }
    }

    @Test fun `그라데이션 - OKLCh 보간이라 끝점이 그대로 돌아오고 중간도 채도를 지킨다`() {
        val e = LightParams.palette(PartOfDay.EVENING)
        fun chroma(c: FloatArray) = LightParams.oklch(c)[1]
        val mid = LightParams.gradientAt(e.top, e.bottom, 0.5f)
        assertTrue(chroma(mid) > minOf(chroma(e.top), chroma(e.bottom)) * 0.85f)
        val top = LightParams.gradientAt(e.top, e.bottom, 0f)
        for (i in 0..2) assertEquals(e.top[i], top[i], 0.01f)
    }

    @Test fun `무드 uniform - 물결 6줄과 멜로디 32점, 빛 방울·입자·링 uniform 없음`() {
        val sc = MoodScene(startMs = 0, initial = MoodState(0.3f, 0.7f, 0.2f, false))
        val u = LightParams.mood(sc.frame(1_000, Sound(playing = true, on = Inst.entries.toSet(), moving = true)), 1682f, 1210f)
        assertEquals(24, u.getValue("L").size); assertEquals(32, u.getValue("mel").size)
        for (gone in listOf("lights", "mainLight", "ring", "sparkle", "ripple", "sun")) assertFalse(gone, gone in u)
        for (word in listOf("bloom", "lens", "drops", "sparkle", "ring")) assertFalse(word, LightShader.SRC.contains(word))
        // 셰이더가 선언한 uniform은 모두 값을 받는다
        val declared = Regex("uniform\\s+\\w+\\s+(\\w+)").findAll(LightShader.SRC).map { it.groupValues[1] }.toSet()
        assertEquals(declared, u.keys)
    }

    @Test fun `커버 - 구간 하나 = 물결 한 줄, 부드러울수록 잔잔·또렷, 비 = 잔물결, 흔들림 = 접힌 자국`() {
        val calm = CoverArt.spec(1L, listOf(CoverArt.layer(0, 1L, 0.95f, 40f, 0f, 0)))
        val busy = CoverArt.spec(1L, listOf(CoverArt.layer(0, 1L, 0.15f, 40f, 0.8f, 2)))
        val uc = LightParams.cover(calm, 400f); val ub = LightParams.cover(busy, 400f)
        val pad = Inst.CHORDS.ordinal * 4   // 첫 구간 = 코드 자리(패드는 늘 바탕 물결)
        assertTrue(uc.getValue("L")[pad + 1] < ub.getValue("L")[pad + 1])          // 진폭
        assertTrue(uc.getValue("M")[pad + 1] > ub.getValue("M")[pad + 1])          // 또렷(면 밝기)
        assertEquals(0f, uc.getValue("G2")[1], 0f); assertTrue(ub.getValue("G2")[1] > 0f)
        assertEquals(0f, uc.getValue("scarCount")[0], 0f); assertEquals(1f, ub.getValue("scarCount")[0], 0f)
        val five = CoverArt.spec(7L, List(5) { CoverArt.layer(it, 7L, 0.6f, 30f, 0f, 0) })
        val m = LightParams.cover(five, 400f).getValue("M")
        assertEquals(5, (0 until 6).count { m[it * 4] > 0f })
        assertEquals(1f, LightParams.cover(five, 400f).getValue("coverMode")[0], 0f)
    }
}
