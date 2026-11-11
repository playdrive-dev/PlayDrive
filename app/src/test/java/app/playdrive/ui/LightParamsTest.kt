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

    @Test fun `시간대마다 색온도가 다르다 - 아침 청회색, 낮 청록, 노을 앰버, 밤 가장 어둡게`() {
        fun lum(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
        val m = LightParams.palette(PartOfDay.MORNING); val d = LightParams.palette(PartOfDay.DAY)
        val e = LightParams.palette(PartOfDay.EVENING); val n = LightParams.palette(PartOfDay.NIGHT)
        assertTrue(kotlin.math.abs(m.top[0] - m.top[2]) < 0.15f)        // 청회색: 채도 낮음
        assertTrue(d.top[1] > d.top[0] * 3)                              // 청록
        assertTrue(e.bottom[0] > e.bottom[2] * 2 && e.top[2] > e.top[1]) // 아래 앰버, 위 자주
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
}
