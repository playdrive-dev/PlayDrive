package app.playdrive.album

import app.playdrive.engine.DrivingEventEngine
import app.playdrive.sim.DemoDriveScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSketchTest {
    /** 데모 74초: 출발 5.8s → 신호 대기 30s(1겹) → 재출발 38s → 비 46~52s, 급제동 53.5s → 정차 71s(2겹) → 주차 72s. */
    @Test fun `데모 주행 - 정차마다 한 겹, 비 온 구간엔 빗줄기, 급제동은 흐트러진 자국, 주차 후 그대로 앨범 커버`() {
        val engine = DrivingEventEngine()
        var clock = 1_762_000_000_000L
        val sketch = CoverSketch { clock }
        val rec = DriveRecorder { clock }
        val changes = mutableListOf<Pair<Long, Int>>()
        var album: Album? = null
        var last = -1L; var t = 0L
        while (t <= DemoDriveScenario.DURATION_MS) {
            for (s in DemoDriveScenario.samples(last, t, 0)) {
                val e = engine.onSample(s)
                if (sketch.update(engine.state, e)) changes += t to sketch.layers.size
                rec.update(engine.state, e)?.let { album = Album(it.startEpochMs, it, AlbumNaming.title(it), layers = sketch.layers) }
            }
            last = t; t += 100; clock += 100
        }
        // 출발(새 커버, 0겹) → 신호 대기(1겹) → 마지막 정차(2겹)
        assertEquals(listOf(0, 1, 2), changes.map { it.second })
        assertTrue("첫 겹은 신호 대기 무렵: ${changes[1].first}", changes[1].first in 29_000L..33_000L)
        val (first, second) = sketch.layers
        assertEquals(0, first.rain); assertEquals(0, first.scars)
        assertTrue(second.rain > 0)
        assertTrue(second.scars >= 1)
        // 주차 후에도 커버가 남아 그대로 새 앨범 커버
        assertEquals(sketch.layers, album!!.layers)
        assertEquals(sketch.layers, AlbumNaming.cover(album!!).layers)
    }

    @Test fun `다음 출발 때 새 커버를 시작하고, 2초 미만 구간은 겹을 만들지 않는다`() {
        val l = CoverArt.layer(0, 1L, 0.9f, 40f, 0f, 0)
        val busy = CoverArt.layer(0, 1L, 0.2f, 40f, 0f, 5)
        assertTrue(l.r > busy.r)
        assertEquals(3, busy.scars)
        assertTrue(l.color != busy.color)
    }
}
