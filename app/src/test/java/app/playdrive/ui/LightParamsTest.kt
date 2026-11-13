package app.playdrive.ui

import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.CoverArt
import app.playdrive.album.MoodState
import app.playdrive.ui.light.LightParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 셰이더 입력도 프레임만으로 정해진다 → 주행 중 정지 프레임은 화면(uniform)까지 그대로. */
class LightParamsTest {
    private fun same(a: Map<String, FloatArray>, b: Map<String, FloatArray>) =
        a.keys == b.keys && a.all { (k, v) -> v.contentEquals(b.getValue(k)) }

    @Test fun `주행 중 셰이더 uniform 불변 - 음악·비·커버·박이 바뀌어도`() {
        val sc = MoodScene(startMs = 0, initial = MoodState(0.3f, 0.7f, 0.2f, false))
        val cover = CoverArt.spec(7L, List(3) { CoverArt.layer(it, 7L, 0.8f, 30f, 0.4f, it % 2) })
        sc.setCover(cover, 0, immediate = true)
        sc.setRestricted(true, 1_234)
        val glass = listOf(LightParams.Glass(700f, 1000f, 812f, 1112f, 56f))
        val u0 = LightParams.mood(sc.frame(1_234), 1682f, 1210f, glass)
        repeat(300) { k ->
            val now = 1_234L + k * 2_000
            if (k % 5 == 0) sc.show(MoodState(k % 3 / 3f, 0.2f, 1f, k % 2 == 0), now)
            if (k % 7 == 0) sc.setCover(CoverArt.spec(7L, cover.layers + CoverArt.layer(3, 7L, 0.2f, 10f, 1f, 2)), now)
            assertTrue("t=$now", same(u0, LightParams.mood(sc.frame(now, beatPhase = (k % 10) / 10f), 1682f, 1210f, glass)))
        }
    }

    @Test fun `시간대마다 색온도가 다르다 - 아침 청회색, 낮 청록, 노을 남색+지평선 띠, 밤 가장 어둡게`() {
        fun lum(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
        val m = LightParams.palette(PartOfDay.MORNING); val d = LightParams.palette(PartOfDay.DAY)
        val e = LightParams.palette(PartOfDay.EVENING); val n = LightParams.palette(PartOfDay.NIGHT)
        assertTrue(kotlin.math.abs(m.top[0] - m.top[2]) < 0.15f)        // 청회색: 채도 낮음
        assertTrue(d.top[1] > d.top[0] * 3)                              // 청록
        assertTrue(e.top[2] > e.top[0] * 2 && e.bottom[2] > e.bottom[0] * 1.5f) // 노을 바탕은 남색(위·아래 모두 파랑 우세)
        assertTrue(e.band[0] > e.band[2] * 1.4f && e.band[3] in 0.01f..0.08f)     // 주황은 지평선 근처 얇은 띠로만
        assertTrue(listOf(m, d, e).all { lum(it.top) > lum(n.top) })     // 밤이 가장 어둡다
    }

    @Test fun `커버 - 부드러울수록 빛이 크고 선명, 비는 물방울, 테이프스톱은 자국`() {
        val calm = CoverArt.spec(1L, listOf(CoverArt.layer(0, 1L, 0.95f, 40f, 0f, 0)))
        val busy = CoverArt.spec(1L, listOf(CoverArt.layer(0, 1L, 0.15f, 40f, 0.8f, 2)))
        val uc = LightParams.cover(calm, 400f); val ub = LightParams.cover(busy, 400f)
        assertTrue(uc.getValue("lights")[2] > ub.getValue("lights")[2])   // 크기
        assertTrue(uc.getValue("lights")[3] < ub.getValue("lights")[3])   // 깊이(흐림)
        assertEquals(0f, uc.getValue("rain")[0], 0f); assertTrue(ub.getValue("rain")[0] > 0f)
        assertEquals(0f, uc.getValue("scarCount")[0], 0f); assertEquals(1f, ub.getValue("scarCount")[0], 0f)
    }

    @Test fun `그라데이션 - OKLCh 보간이라 끝점이 그대로 돌아오고 중간도 채도를 지킨다`() {
        val e = LightParams.palette(PartOfDay.EVENING)
        fun chroma(c: FloatArray) = LightParams.oklch(c)[1]
        val mid = LightParams.gradientAt(e.top, e.bottom, 0.5f)
        assertTrue(chroma(mid) > minOf(chroma(e.top), chroma(e.bottom)) * 0.85f)
        val top = LightParams.gradientAt(e.top, e.bottom, 0f)
        for (i in 0..2) assertEquals(e.top[i], top[i], 0.01f)
        // 띠가 없는 시간대는 두께 0
        assertEquals(0f, LightParams.palette(PartOfDay.NIGHT).band[3], 0f)
    }

    @Test fun `아침에만 오른쪽 위 햇빛`() {
        val m = LightParams.palette(PartOfDay.MORNING)
        assertTrue(m.sun[3] > 0f && m.sun[0] > 0.8f && m.sun[1] < 0.1f)
        assertTrue(m.sunCol[0] > m.sunCol[2])                               // 따뜻한 금빛
        listOf(PartOfDay.DAY, PartOfDay.EVENING, PartOfDay.NIGHT, PartOfDay.DAWN).forEach { assertEquals(0f, LightParams.palette(it).sun[3], 0f) }
    }

    @Test fun `실시간 무드 화면에는 테이프스톱 흔적이 없다 - 커버에만`() {
        val broken = CoverArt.spec(7L, List(3) { CoverArt.layer(it, 7L, 0.3f, 30f, 0f, 2) })
        val sc = MoodScene(startMs = 0, initial = MoodState(0.3f, 0.7f, 0.2f, false))
        sc.setCover(broken, 0, immediate = true)
        assertEquals(0f, LightParams.mood(sc.frame(10_000), 1682f, 1210f).getValue("scarCount")[0], 0f)
        assertTrue(LightParams.cover(broken, 400f).getValue("scarCount")[0] > 0f)
    }
}
