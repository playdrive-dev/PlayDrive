package app.playdrive.play

import app.playdrive.engine.DrivingEventEngine
import app.playdrive.music.Arrangement
import app.playdrive.music.Layer
import app.playdrive.music.MusicCommand
import app.playdrive.music.MusicPacks
import app.playdrive.music.MusicTiming
import app.playdrive.sim.DriveScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 듣기 모드: 흔들림이 악기에 영향 없음, 게임 요소 없음, 주행 중 전환 차단, 모드 기억, 팩 구성. */
class ListenModeTest {
    private val timing = MusicTiming(bars = 8)
    private val song = Layer.entries.filter { it != Layer.HIHAT_FILL && it != Layer.RAIN }.toSet()

    @Test fun `듣기 모드 - 흔들림 많은 주행(demo rough)에서도 달리는 동안 악기 6개 그대로, 판정·음성·결과 없음`() {
        val engine = DrivingEventEngine()
        val c = ListenConductor(timing, song, Arrangement.SONG)
        var t = 0L; var last = -1L
        val s = DriveScenario.ROUGH
        var harsh = 0; var movingFrames = 0
        val cmds = mutableListOf<MusicCommand>()
        while (t <= s.durationMs) {
            for (x in s.samples(last, t, 0)) {
                val e = engine.onSample(x)
                harsh += e.count { it is app.playdrive.engine.DrivingEvent.HarshBraking || it is app.playdrive.engine.DrivingEvent.HarshAcceleration || it is app.playdrive.engine.DrivingEvent.HarshSteering }
                val o = c.update(engine.state, e)
                assertTrue(o.events.isEmpty() && o.lines.isEmpty() && o.result == null)
                cmds += o.commands
            }
            // 출발 뒤 달리는 동안(마디 퀀타이즈 여유 3초 뒤) 늘 6개
            if (engine.state.moving && t > 20_000 && engine.state.speedMps > 3f) { movingFrames++; assertEquals("t=$t", song, c.director.layers) }
            last = t; t += 100
        }
        assertTrue("흔들림이 실제로 있었다 $harsh", harsh >= 5)
        assertTrue(movingFrames > 500)
        assertFalse(cmds.any { it is MusicCommand.TapeStop || it is MusicCommand.Muffle || it is MusicCommand.RestartSong || it is MusicCommand.PerfReward || it is MusicCommand.PlayCue || it == MusicCommand.LowpassDip })
    }

    @Test fun `듣기 모드 - 신호 대기에는 잔잔해지고(코드·패드), 와이퍼를 켜면 음색(비 효과)이 바뀐다`() {
        val engine = DrivingEventEngine()
        val c = ListenConductor(timing, song, Arrangement.SONG)
        var t = 0L; var last = -1L
        val s = DriveScenario.SAFE
        var sawCalmStop = false; var rainFx = false
        while (t <= s.durationMs) {
            for (x in s.samples(last, t, 0)) { val o = c.update(engine.state, engine.onSample(x)); if (o.commands.any { it is MusicCommand.RainFx && it.on }) rainFx = true }
            if (t == 100_000L) { val o = c.update(engine.state, engine.onSample(app.playdrive.model.SignalSample.Wiper(t, true))); if (o.commands.any { it is MusicCommand.RainFx && it.on }) rainFx = true }
            if (!engine.state.moving && c.director.layers == setOf(Layer.CHORDS, Layer.PAD) && t > 60_000) sawCalmStop = true
            last = t; t += 100
        }
        assertTrue(sawCalmStop)
        assertTrue(rainFx)
    }

    @Test fun `모드 전환 - 주행 중에는 막히고, 마지막 선택을 기억`() {
        val store = mutableMapOf<String, String>()
        val m = ModeSwitch({ store["mode"] }) { store["mode"] = it }
        assertEquals(AppMode.PLAY, m.mode)                 // 처음 = 연주
        assertFalse(m.request(AppMode.LISTEN, driving = true))
        assertEquals(AppMode.PLAY, m.mode); assertTrue(store.isEmpty())
        assertTrue(m.request(AppMode.LISTEN, driving = false))
        assertEquals("LISTEN", store["mode"])
        assertEquals(AppMode.LISTEN, ModeSwitch({ store["mode"] }) {}.mode)   // 다시 켜도 듣기
        assertEquals(AppMode.PLAY, ModeSwitch({ "엉뚱한 값" }) {}.mode)
    }

    @Test fun `곡 데이터는 팩 단위 - 기본 팩 1개, 곡마다 스템 6개와 물결 악보 파일이 있다, 잠긴 장르는 거리로 열림`() {
        assertEquals(listOf("basic"), MusicPacks.installed.map { it.id })
        val raw = File("src/main/res/raw")
        for (p in MusicPacks.installed) for (sng in p.songs) {
            assertEquals(6, sng.stems.size)
            sng.stems.values.forEach { assertTrue(it, File(raw, "$it.ogg").exists()) }
            assertTrue(File(raw, "${sng.waveScore}.txt").exists())
        }
        assertTrue(MusicPacks.lockedGenres.all { it.requiredKm > 0 })
    }
}
