package app.playdrive.play

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingEventEngine
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import app.playdrive.model.Seat
import app.playdrive.model.SignalSample
import app.playdrive.model.TurnSignal
import app.playdrive.music.Arrangement
import app.playdrive.music.Layer
import app.playdrive.music.MusicCommand
import app.playdrive.music.MusicTiming
import app.playdrive.sim.DriveScenario
import app.playdrive.voice.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTrackerTest {
    private val timing = MusicTiming(bars = 8)
    private val bar = timing.barMs // 2666ms

    /** 상태를 직접 넣는 작은 운전(100ms 간격). */
    private class Drive(val t: PlayTracker) {
        var now = 0L
        var state = DrivingState(gear = Gear.DRIVE)
        val out = mutableListOf<PlayEvent>()
        fun step(ev: List<DrivingEvent> = emptyList(), f: (DrivingState) -> DrivingState = { it }) {
            state = f(state).copy(timeMs = now); out += t.update(state, ev); now += 100
        }
        fun depart(accel: Float = 1.0f) { state = state.copy(moving = true, speedMps = 1f, accelMps2 = accel); step(listOf(DrivingEvent.Departed(now))) }
        fun run(ms: Long, f: (DrivingState) -> DrivingState = { it.copy(speedMps = 12f, accelMps2 = 0f, moving = true) }) { val end = now + ms; while (now < end) step(f = f) }
        fun miss(kind: MissKind) = step(listOf(when (kind) {
            MissKind.HARSH_ACCEL -> DrivingEvent.HarshAcceleration(now, 3.5f)
            MissKind.HARSH_BRAKE -> DrivingEvent.HarshBraking(now, 4f, true)
            else -> DrivingEvent.HarshSteering(now, 300f)
        }))
        fun park(): DriveResult? { state = state.copy(gear = Gear.PARK, moving = false, speedMps = 0f); t.update(state.copy(timeMs = now), listOf(DrivingEvent.Parked(now))); return t.lastResult }
    }

    private fun drive() = Drive(PlayTracker(bar) { 1_700_000_000_000L })

    // ------------------------------------------------------------------ 게이지
    @Test fun `곡은 악기 4개로 시작, 흔들림 없이 4마디마다 +1(최대 6)`() {
        val d = drive(); d.depart(2.0f)   // 부드러운 출발 판정(+1)이 끼지 않게
        assertEquals(4, d.t.gauge)
        assertEquals(PlayEvent.SongStart(0, 1, 1, 4), d.out.filterIsInstance<PlayEvent.SongStart>().single())
        d.run(bar * 4 + 50); assertEquals(5, d.t.gauge)
        d.run(bar * 4); assertEquals(6, d.t.gauge)
        d.run(bar * 12); assertEquals(6, d.t.gauge)
    }

    @Test fun `급가속·급조향 -1, 급제동 -2, 판정은 즉시 +1, 흔들림 뒤 4마디를 다시 센다`() {
        val d = drive(); d.depart(0.5f)
        d.run(bar * 9)                                         // 워밍업(8마디) 지나고 악기 6
        assertEquals(6, d.t.gauge)
        d.miss(MissKind.HARSH_ACCEL); assertEquals(5, d.t.gauge)
        d.miss(MissKind.HARSH_STEER); assertEquals(4, d.t.gauge)
        d.miss(MissKind.HARSH_BRAKE); assertEquals(2, d.t.gauge)
        assertTrue(d.out.filterIsInstance<PlayEvent.Gauge>().filter { it.loss }.size == 3)
        d.run(bar * 3); assertEquals(2, d.t.gauge)              // 아직 4마디 안 됨
        d.run(bar + 100); assertEquals(3, d.t.gauge)
        d.step(listOf(DrivingEvent.TurnSignalOn(d.now, TurnSignal.LEFT)))
        d.run(3_000)
        d.step(listOf(DrivingEvent.TurnSignalOff(d.now), DrivingEvent.LaneChange(d.now, TurnSignal.LEFT)))
        assertEquals(4, d.t.gauge)                              // 깔끔한 차선 변경 → 즉시 +1
    }

    @Test fun `이어진 마디는 흔들림이면 0, 게이지와 별개`() {
        val d = drive(); d.depart(0.5f)
        d.run(bar * 10 + 50)
        assertEquals(10, d.t.combo)
        d.miss(MissKind.HARSH_ACCEL)
        assertEquals(0, d.t.combo); assertEquals(5, d.t.gauge)
        assertEquals(10, d.out.filterIsInstance<PlayEvent.Miss>().single().lostCombo)
        d.run(bar * 2 + 50); assertEquals(2, d.t.combo)
        assertEquals(10, d.t.maxCombo)
    }

    @Test fun `출발 후 8마디 워밍업 — 게이지가 1 밑으로 안 내려감`() {
        val d = drive(); d.depart(0.5f)
        d.run(bar)
        repeat(4) { d.miss(MissKind.HARSH_BRAKE) }
        assertEquals(1, d.t.gauge)
        assertEquals(PlayMode.RUNNING, d.t.mode)
        assertTrue(d.out.any { it is PlayEvent.Danger && it.on && it.firstInSong })
        d.run(bar * 7)                                          // 워밍업 끝(8마디 지남)
        d.miss(MissKind.HARSH_BRAKE)
        assertEquals(0, d.t.gauge); assertEquals(PlayMode.FAILED, d.t.mode)
    }

    @Test fun `위험 음성은 곡당 1회`() {
        val d = drive(); d.depart(0.5f); d.run(bar * 9)
        d.miss(MissKind.HARSH_BRAKE); d.miss(MissKind.HARSH_BRAKE); d.miss(MissKind.HARSH_ACCEL) // 6 → 4 → 2 → 1
        d.run(bar * 4 + 100)                                    // 2
        d.miss(MissKind.HARSH_ACCEL)                            // 다시 1
        val dangers = d.out.filterIsInstance<PlayEvent.Danger>().filter { it.on }
        assertEquals(2, dangers.size)
        assertEquals(listOf(true, false), dangers.map { it.firstInSong })
    }

    // ------------------------------------------------------------------ 연주 정지와 재시작
    private fun failedDrive(): Drive {
        val d = drive(); d.depart(0.5f); d.run(bar * 9)
        repeat(3) { d.miss(MissKind.HARSH_BRAKE) }              // 6 → 0
        assertEquals(PlayMode.FAILED, d.t.mode)
        return d
    }

    @Test fun `연주 정지 후 흔들림 없이 15초 주행하면 카운트인 → 같은 곡을 악기 3개로`() {
        val d = failedDrive()
        val failed = d.out.filterIsInstance<PlayEvent.Failed>().single()
        assertFalse(failed.song.completed); assertEquals(Grade.FAILED, failed.song.grade)
        d.run(14_800); assertEquals(PlayMode.FAILED, d.t.mode)
        d.run(300); assertEquals(PlayMode.COUNT_IN, d.t.mode)
        assertEquals(1, d.out.filterIsInstance<PlayEvent.CountIn>().single().songNumber)
        d.run((PlayRules.COUNT_IN_BARS * bar).toLong() + 100)
        assertEquals(PlayMode.RUNNING, d.t.mode)
        assertEquals(3, d.t.gauge)
        val start = d.out.filterIsInstance<PlayEvent.SongStart>().last()
        assertEquals(1, start.number); assertEquals(2, start.attempt); assertEquals(3, start.gauge)
    }

    @Test fun `연주 정지 중 흔들림이면 15초를 다시 센다`() {
        val d = failedDrive()
        d.run(10_000); d.miss(MissKind.HARSH_ACCEL); d.run(10_000)
        assertEquals(PlayMode.FAILED, d.t.mode)
        d.run(5_200)
        assertEquals(PlayMode.COUNT_IN, d.t.mode)
    }

    @Test fun `연주 정지 후 정차했다가 다시 출발하면 바로 카운트인`() {
        val d = failedDrive()
        d.run(2_000) { it.copy(speedMps = 0f, moving = false) }
        d.step(listOf(DrivingEvent.Stopped(d.now))) { it.copy(speedMps = 0f, moving = false) }
        d.run(3_000) { it.copy(speedMps = 0f, moving = false) }
        assertEquals(PlayMode.FAILED, d.t.mode)
        d.state = d.state.copy(moving = true, speedMps = 1f)
        d.step(listOf(DrivingEvent.Departed(d.now)))
        assertEquals(PlayMode.COUNT_IN, d.t.mode)
    }

    // ------------------------------------------------------------------ 곡 완주·전환·점수
    @Test fun `64마디까지 게이지가 남으면 완주 → 다음 곡이 악기 4개로 자동 시작`() {
        val d = drive(); d.depart(0.5f)
        d.run(bar * 64 + 100)
        val done = d.out.filterIsInstance<PlayEvent.SongComplete>().single().song
        assertTrue(done.completed); assertEquals(1, done.number)
        assertEquals(2, d.t.songNumber); assertEquals(4, d.t.gauge)
        d.run(bar * 10)
        val r = d.park()!!
        assertEquals(1, r.completedSongs); assertEquals(1, r.attemptedSongs) // 연주 중이던 곡 2는 세지 않음
    }

    @Test fun `점수 = 평균 악기 비율×70 + 최장 연속 나누기 64×20 + 판정 보너스(최대 10), 등급 경계`() {
        assertEquals(100, PlayRules.score(1f, 64, 5))
        assertEquals(100, PlayRules.score(1f, 64, 9))           // 보너스 상한 10
        assertEquals(90, PlayRules.score(1f, 64, 0))
        assertEquals(45, PlayRules.score(0.5f, 32, 0))           // 35 + 10
        assertEquals(Grade.S, PlayRules.grade(90, true)); assertEquals(Grade.A, PlayRules.grade(89, true))
        assertEquals(Grade.B, PlayRules.grade(70, true)); assertEquals(Grade.C, PlayRules.grade(69, true))
        assertEquals(Grade.C, PlayRules.grade(5, true)); assertEquals(Grade.FAILED, PlayRules.grade(99, false))
    }

    // ------------------------------------------------------------------ 판정 경계값
    @Test fun `부드러운 출발 경계 — 0_15g 이하면 판정`() {
        fun startWith(a: Float): Boolean {
            val d = drive(); d.depart(a)
            d.run(PlayRules.START_WINDOW_MS + 200) { it.copy(speedMps = it.speedMps + 0.1f, accelMps2 = a, moving = true) }
            return d.out.any { it is PlayEvent.Perfect && it.kind == PerfectKind.START }
        }
        assertTrue(startWith(PlayRules.START_MAX_MPS2))
        assertFalse(startWith(PlayRules.START_MAX_MPS2 + 0.01f))
    }

    @Test fun `깔끔한 차선 변경 경계 — 깜빡이 3초 이상, 급조향 없이`() {
        fun change(signalMs: Long, harsh: Boolean = false): Boolean {
            val d = drive(); d.depart(0.5f); d.run(1_000)
            val on = d.now
            d.step(listOf(DrivingEvent.TurnSignalOn(on, TurnSignal.LEFT)))
            if (harsh) d.step(listOf(DrivingEvent.HarshSteering(d.now, 200f)))
            while (d.now < on + signalMs) d.step()
            d.step(listOf(DrivingEvent.TurnSignalOff(on + signalMs), DrivingEvent.LaneChange(on + signalMs, TurnSignal.LEFT)))
            return d.out.any { it is PlayEvent.Perfect && it.kind == PerfectKind.CHANGE }
        }
        assertTrue(change(3_000)); assertFalse(change(2_999)); assertFalse(change(5_000, harsh = true))
    }

    @Test fun `부드러운 정지 경계 — 감속 0_2g 이하로 멈추면 판정`() {
        fun stopWith(decel: Float): Boolean {
            val d = drive(); d.depart(0.5f); d.run(1_000) { it.copy(speedMps = 14f, accelMps2 = 0f, moving = true) }
            var v = 14f
            while (v > 0f) { v = (v - decel * 0.1f).coerceAtLeast(0f); val vv = v; d.step { it.copy(speedMps = vv, accelMps2 = -decel) } }
            d.run(300) { it.copy(speedMps = 0f, accelMps2 = 0f) }
            return d.out.any { it is PlayEvent.Perfect && it.kind == PerfectKind.STOP }
        }
        assertTrue(stopWith(PlayRules.STOP_MAX_MPS2)); assertFalse(stopWith(PlayRules.STOP_MAX_MPS2 + 0.01f))
    }

    // ------------------------------------------------------------------ 내비: 회전·구간·도착
    private fun navi(d: Drive, n: app.playdrive.model.NaviSignal) = d.step(listOf(DrivingEvent.Navi(d.now, n)))

    @Test fun `깔끔한 회전 — 300m 안에서 그 방향 깜빡이, 급조향 없이 회전하면 +1`() {
        fun turnWith(signal: TurnSignal?, harsh: Boolean = false, armAt: Int = 280): List<PlayEvent> {
            val d = drive(); d.depart(2f); d.run(bar * 9)
            navi(d, app.playdrive.model.NaviSignal.NextTurn(TurnSignal.RIGHT, armAt))
            if (signal != null) d.step(listOf(DrivingEvent.TurnSignalOn(d.now, signal)))
            if (harsh) d.step(listOf(DrivingEvent.HarshSteering(d.now, 200f)))
            d.run(2_000)
            navi(d, app.playdrive.model.NaviSignal.NextTurn(TurnSignal.LEFT, 2_000))
            return d.out
        }
        assertTrue(turnWith(TurnSignal.RIGHT).any { it is PlayEvent.Perfect && it.kind == PerfectKind.TURN })
        assertTrue(turnWith(null).any { it is PlayEvent.Miss && it.kind == MissKind.NO_SIGNAL_TURN })   // 깜빡이 없이 회전 = 흔들림
        assertTrue(turnWith(TurnSignal.LEFT).any { it is PlayEvent.Miss && it.kind == MissKind.NO_SIGNAL_TURN }) // 반대 방향
        turnWith(TurnSignal.RIGHT, harsh = true).let { ev -> assertTrue(ev.none { it is PlayEvent.Perfect && it.kind == PerfectKind.TURN }) }
        assertTrue(turnWith(TurnSignal.RIGHT, armAt = 301).none { it is PlayEvent.Perfect || (it is PlayEvent.Miss && it.kind == MissKind.NO_SIGNAL_TURN) }) // 300m 밖
    }

    @Test fun `보호구역 — 내내 준수하면 깔끔한 구간 통과, 5초 넘게 초과하면 악기 -1(그 전엔 경고만)`() {
        fun zone(kmh: Float, overMs: Long): List<PlayEvent> {
            val d = drive(); d.depart(2f); d.run(bar * 9)
            d.run(1_000) { it.copy(speedMps = 28f / 3.6f, moving = true, accelMps2 = 0f) }   // 보호구역 앞에서 감속
            navi(d, app.playdrive.model.NaviSignal.SpeedLimit(30, schoolZone = true, demo = true))
            d.run(10_000) { it.copy(speedMps = 28f / 3.6f, moving = true, accelMps2 = 0f) }
            d.run(overMs) { it.copy(speedMps = kmh / 3.6f, moving = true, accelMps2 = 0f) }
            d.run(1_000) { it.copy(speedMps = 28f / 3.6f, moving = true, accelMps2 = 0f) }
            navi(d, app.playdrive.model.NaviSignal.SpeedLimit(50, schoolZone = false, demo = true))
            return d.out
        }
        val clean = zone(28f, 0)
        assertTrue(clean.any { it is PlayEvent.Perfect && it.kind == PerfectKind.ZONE })
        val brief = zone(36f, 4_900)                        // 4.9초 초과: 경고만, 악기 그대로, 구간 판정 없음
        assertTrue(brief.any { it is PlayEvent.OverSpeed && it.on })
        assertTrue(brief.none { it is PlayEvent.Miss })
        assertTrue(brief.none { it is PlayEvent.Perfect && it.kind == PerfectKind.ZONE })
        val long = zone(36f, 5_100)
        assertEquals(1, long.count { it is PlayEvent.Miss && it.kind == MissKind.OVER_SPEED })
    }

    @Test fun `안내 시작은 카운트인 뒤 첫 곡, 도착하면 16마디 이상 연주한 곡을 마무리`() {
        val d = drive()
        navi(d, app.playdrive.model.NaviSignal.RouteStarted(5_000))
        assertEquals(PlayMode.COUNT_IN, d.t.mode)
        assertFalse((d.out.single { it is PlayEvent.CountIn } as PlayEvent.CountIn).retry)
        d.run(5_000) { it.copy(speedMps = 0f, moving = false) }
        assertEquals(PlayMode.RUNNING, d.t.mode); assertEquals(4, d.t.gauge)
        d.depart(2f); d.run(bar * 20)
        navi(d, app.playdrive.model.NaviSignal.Remaining(900))
        assertTrue(d.out.any { it is PlayEvent.FinalStretch })
        navi(d, app.playdrive.model.NaviSignal.Arrived)
        val arrived = d.out.filterIsInstance<PlayEvent.Arrived>().single()
        assertTrue(arrived.song!!.completed)
        d.run(bar * 70)                                       // 도착 뒤에는 곡이 넘어가지 않는다
        val r = d.park()!!
        assertEquals(1, r.completedSongs); assertEquals(1, r.attemptedSongs)
    }

    // ------------------------------------------------------------------ 엔진 + 지휘(속도·시간 무관, 데모)
    private class Played(val result: DriveResult, val events: List<Pair<Long, PlayEvent>>, val commands: List<Pair<Long, MusicCommand>>, val lines: List<Pair<Long, Line>>)

    private fun play(s: DriveScenario): Played {
        val engine = DrivingEventEngine()
        val song = setOf(Layer.CHORDS, Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.MELODY)
        val c = Conductor(timing, song, Arrangement.SONG) { 0L }
        val events = mutableListOf<Pair<Long, PlayEvent>>(); val cmds = mutableListOf<Pair<Long, MusicCommand>>(); val lines = mutableListOf<Pair<Long, Line>>()
        var result: DriveResult? = null
        var last = -1L; var t = 0L
        while (t <= s.durationMs) {
            for (x in s.samples(last, t, 0)) {
                val e = engine.onSample(x)
                val o = c.update(engine.state, e)
                events += o.events.map { t to it }; cmds += o.commands.map { t to it }; lines += o.lines.map { t to it }
                o.result?.let { result = it }
            }
            last = t; t += 100
        }
        return Played(result ?: error("주차 결과 없음"), events, cmds, lines)
    }

    /** 같은 가속도(1.0 m/s²)로 [kmh]까지 → [cruiseS]초 정속 → 1.2 m/s²로 정지 → P. */
    private fun cruise(kmh: Float, cruiseS: Int): DriveScenario {
        val v = kmh / 3.6f
        val up = (v / 1.0f * 1000).toLong(); val down = (v / 1.2f * 1000).toLong()
        val t0 = 5_000L; val t1 = t0 + up; val t2 = t1 + cruiseS * 1000L; val t3 = t2 + down
        val pts = listOf(0L to 0f, t0 to 0f, t1 to kmh, t2 to kmh, t3 to 0f, t3 + 3_000 to 0f)
        val base = listOf<Pair<Long, (Long) -> SignalSample>>(
            0L to { at -> SignalSample.GearChanged(at, Gear.PARK) }, 0L to { at -> SignalSample.SeatOccupancy(at, Seat.DRIVER, true) },
            3_000L to { at -> SignalSample.GearChanged(at, Gear.DRIVE) }, t3 + 2_500 to { at -> SignalSample.GearChanged(at, Gear.PARK) },
        )
        return DriveScenario.of("cruise $kmh", pts, base)
    }

    @Test fun `곡 점수는 속도와 무관 — 40km_h와 110km_h가 같은 점수`() {
        val slow = play(cruise(40f, 200)).result
        val fast = play(cruise(110f, 200)).result
        assertEquals(1, slow.completedSongs); assertEquals(1, fast.completedSongs)
        assertEquals(slow.songs.map { it.score }, fast.songs.map { it.score })
        assertTrue("빠른 주행이 더 멀리 감", fast.safeDistanceM > slow.safeDistanceM * 2)
    }

    @Test fun `곡 점수는 시간과 무관 — 오래 달려 곡이 늘어도 곡마다 같은 점수`() {
        val one = play(cruise(50f, 200)).result
        val three = play(cruise(50f, 560)).result
        assertEquals(1, one.completedSongs); assertEquals(3, three.completedSongs)
        assertEquals(one.songs[0].score, three.songs[0].score)
        assertEquals(three.songs[1].score, three.songs[2].score)
        assertEquals(one.bestScore, three.bestScore)
    }

    @Test fun `demo safe — 두 곡 연속 완주(S, A)`() {
        val p = play(DriveScenario.SAFE)
        val r = p.result
        println("safe: " + r.songs.joinToString { "${it.title} ${it.grade} ${it.score} (악기 ${"%.2f".format(it.avgGauge)}, 최장 ${it.longestBars}, 판정 ${it.perfects})" } + " 흔들림 ${r.misses} 최장 ${r.maxCombo}")
        p.events.filter { it.second !is PlayEvent.Combo || (it.second as PlayEvent.Combo).isFill }.forEach { (t, e) -> println("%6.1fs %s".format(t / 1000.0, e)) }
        assertEquals(listOf(Grade.S, Grade.A), r.songs.map { it.grade })
        assertEquals(1, r.misses)
        assertTrue(p.lines.none { it.second == Line.Stop })
        // 내비: 안내 시작 카운트인(음성 없음), 깔끔한 회전, 깔끔한 구간 통과, 마지막 1km 클라이맥스, 도착(곡 3은 세지 않음), 시연용 구간 데이터 표기
        assertTrue(p.events.any { it.second is PlayEvent.CountIn && !(it.second as PlayEvent.CountIn).retry && it.first < 2_000 })
        assertEquals(1, r.perfects[PerfectKind.TURN]); assertEquals(1, r.perfects[PerfectKind.ZONE])
        assertTrue(p.events.any { it.second is PlayEvent.FinalStretch && it.first in 329_000..331_000 })
        assertTrue(p.events.any { it.second is PlayEvent.Arrived && (it.second as PlayEvent.Arrived).song == null })
        assertTrue(r.demoZoneData)
        assertTrue(p.events.none { it.second is PlayEvent.OverSpeed })
    }

    @Test fun `demo rough — 악기 빠짐 → 위험 → 정지 → 15초 → 카운트인 → 완주(C)`() {
        val p = play(DriveScenario.ROUGH)
        val r = p.result
        println("rough: " + r.songs.joinToString { "${it.title} ${it.grade} ${it.score} (악기 ${"%.2f".format(it.avgGauge)}, 최장 ${it.longestBars})" } + " 흔들림 ${r.misses}")
        p.events.filter { it.second !is PlayEvent.Combo }.forEach { (t, e) -> println("%6.1fs %s".format(t / 1000.0, e)) }
        p.lines.forEach { (t, l) -> println("%6.1fs 음성 %s".format(t / 1000.0, l)) }
        assertEquals(listOf(Grade.FAILED, Grade.C), r.songs.map { it.grade })
        assertEquals(1, r.completedSongs); assertEquals(2, r.attemptedSongs)
        val lineOrder = p.lines.map { it.second }
        assertEquals(listOf(Line.Danger, Line.Stop, Line.Resume), lineOrder.take(3))
        val failAt = p.events.first { it.second is PlayEvent.Failed }.first
        val countAt = p.events.first { it.second is PlayEvent.CountIn }.first
        assertTrue("정지 ${failAt} → 카운트인 ${countAt}", countAt - failAt in 15_000..16_000)
        // 정지 = 테이프스톱 + 먹먹하게, 카운트인 = 곡 처음부터
        assertTrue(p.commands.any { (t, c) -> t == failAt && c == MusicCommand.Muffle(2) })
        assertTrue(p.commands.any { (t, c) -> t == countAt && c == MusicCommand.RestartSong(true) })
    }
}
