package app.playdrive.engine

import app.playdrive.model.Gear
import app.playdrive.model.Seat
import app.playdrive.model.SignalSample
import app.playdrive.model.TurnSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DrivingEventEngineTest {

    /** 10Hz 속도 샘플을 흘려보내는 작은 드라이버. */
    private class Drive(val engine: DrivingEventEngine = DrivingEventEngine()) {
        var now = 0L
        var v = 0f
        val events = mutableListOf<DrivingEvent>()

        fun send(s: SignalSample) { events += engine.onSample(s) }

        /** durationMs 동안 가속도 a(m/s²)로 속도를 바꾸며 100ms마다 샘플. */
        fun ramp(a: Float, durationMs: Long, noise: (Long) -> Float = { 0f }) {
            val end = now + durationMs
            while (now < end) {
                now += 100
                v = (v + a * 0.1f).coerceAtLeast(0f)
                send(SignalSample.Speed(now, v + noise(now)))
            }
        }

        fun hold(durationMs: Long, noise: (Long) -> Float = { 0f }) = ramp(0f, durationMs, noise)

        inline fun <reified T : DrivingEvent> count() = events.count { it is T }
        inline fun <reified T : DrivingEvent> first() = events.first { it is T } as T
    }

    @Test fun `출발 후 부드러운 가속은 SmoothAcceleration, 급가속 없음`() {
        val d = Drive()
        d.hold(1000)
        d.ramp(1.5f, 6000)
        assertEquals(1, d.count<DrivingEvent.Departed>())
        assertEquals(1, d.count<DrivingEvent.SmoothAcceleration>())
        assertEquals(0, d.count<DrivingEvent.HarshAcceleration>())
        assertTrue(d.engine.state.moving)
    }

    @Test fun `0_3g 이상 가속은 HarshAcceleration`() {
        val d = Drive()
        d.hold(1000)
        d.ramp(3.5f, 2000)
        assertEquals(1, d.count<DrivingEvent.HarshAcceleration>())
        assertTrue(d.first<DrivingEvent.HarshAcceleration>().accelMps2 >= 2.94f)
    }

    @Test fun `급제동은 쿨다운 동안 한 번만, 하모니 감소`() {
        val d = Drive()
        d.ramp(1.5f, 10_000) // 15 m/s
        d.hold(8000)
        val before = d.engine.state.harmony
        d.send(SignalSample.Brake(d.now, true))
        d.ramp(-5f, 1500)
        assertEquals(1, d.count<DrivingEvent.HarshBraking>())
        val e = d.first<DrivingEvent.HarshBraking>()
        assertTrue(e.brakePressed)
        assertTrue(d.engine.state.harmony < before)
    }

    @Test fun `2_9 미만 감속은 급제동이 아니라 부드러운 감속`() {
        val d = Drive()
        d.ramp(1.5f, 10_000)
        d.hold(2000)
        d.ramp(-2.5f, 3000)
        assertEquals(0, d.count<DrivingEvent.HarshBraking>())
        assertEquals(1, d.count<DrivingEvent.SmoothDeceleration>())
    }

    @Test fun `한 샘플짜리 속도 튐은 이동평균으로 걸러진다`() {
        val d = Drive()
        d.ramp(1.5f, 10_000)
        val rnd = Random(7)
        // 30% 확률로 ±1 m/s 튐 (한 샘플)
        d.hold(20_000) { if (rnd.nextFloat() < 0.3f) (rnd.nextFloat() * 2 - 1) else 0f }
        assertEquals(0, d.count<DrivingEvent.HarshAcceleration>())
        assertEquals(0, d.count<DrivingEvent.HarshBraking>())
    }

    @Test fun `정속 5초 이상이면 CruiseStarted, 가속하면 CruiseEnded`() {
        val d = Drive()
        d.ramp(1.5f, 8000)
        d.hold(4000)
        assertEquals(0, d.count<DrivingEvent.CruiseStarted>())
        d.hold(2500)
        assertEquals(1, d.count<DrivingEvent.CruiseStarted>())
        d.ramp(1.5f, 2000)
        assertEquals(1, d.count<DrivingEvent.CruiseEnded>())
    }

    @Test fun `완전히 서고 1초 지나면 Stopped, 다시 출발하면 Departed`() {
        val d = Drive()
        d.ramp(1.5f, 6000)   // 9 m/s
        d.ramp(-2f, 4500)    // 0 m/s 도달
        d.hold(500)
        assertEquals(0, d.count<DrivingEvent.Stopped>())
        d.hold(1500)
        assertEquals(1, d.count<DrivingEvent.Stopped>())
        assertFalse(d.engine.state.moving)
        d.ramp(1.0f, 3000)
        assertEquals(2, d.count<DrivingEvent.Departed>())
    }

    @Test fun `주행 중 방향지시등 켜고 끄면 차선 변경`() {
        val d = Drive()
        d.ramp(1.5f, 8000)
        d.send(SignalSample.TurnSignalChanged(d.now, TurnSignal.LEFT))
        d.hold(3000)
        d.send(SignalSample.TurnSignalChanged(d.now, TurnSignal.OFF))
        assertEquals(1, d.count<DrivingEvent.TurnSignalOn>())
        assertEquals(TurnSignal.LEFT, d.first<DrivingEvent.LaneChange>().direction)
    }

    @Test fun `너무 오래 켠 방향지시등이나 정차 중 깜빡이는 차선 변경이 아니다`() {
        val d = Drive()
        d.send(SignalSample.TurnSignalChanged(d.now, TurnSignal.RIGHT))
        d.hold(2000)
        d.send(SignalSample.TurnSignalChanged(d.now, TurnSignal.OFF)) // 정차 중
        d.ramp(1.5f, 8000)
        d.send(SignalSample.TurnSignalChanged(d.now, TurnSignal.RIGHT))
        d.hold(12_000)
        d.send(SignalSample.TurnSignalChanged(d.now, TurnSignal.OFF)) // 12초
        assertEquals(0, d.count<DrivingEvent.LaneChange>())
    }

    @Test fun `주행 중 빠른 조향은 급조향, 느린 조향이나 정차 중 조향은 아님`() {
        val d = Drive()
        d.send(SignalSample.SteeringAngle(d.now, 0f))
        d.send(SignalSample.SteeringAngle(d.now + 300, 200f)) // 정차 중 급하게 꺾음
        d.send(SignalSample.SteeringAngle(d.now + 3000, 0f))  // 정차 중 되돌림
        assertEquals(0, d.count<DrivingEvent.HarshSteering>())
        d.ramp(1.5f, 8000)
        d.send(SignalSample.SteeringAngle(d.now, 0f))
        var a = 0f
        repeat(10) { d.now += 100; a += 5f; d.send(SignalSample.SteeringAngle(d.now, a)) } // 50°/s
        assertEquals(0, d.count<DrivingEvent.HarshSteering>())
        d.now += 200
        d.send(SignalSample.SteeringAngle(d.now, a + 90f)) // 450°/s
        assertEquals(1, d.count<DrivingEvent.HarshSteering>())
    }

    @Test fun `와이퍼로 비 시작과 종료`() {
        val d = Drive()
        d.send(SignalSample.Wiper(100, true))
        d.send(SignalSample.Wiper(200, true))
        d.send(SignalSample.Wiper(300, false))
        assertEquals(1, d.count<DrivingEvent.RainStarted>())
        assertEquals(1, d.count<DrivingEvent.RainStopped>())
    }

    @Test fun `P단이면 Parked`() {
        val d = Drive()
        d.send(SignalSample.GearChanged(0, Gear.DRIVE))
        d.send(SignalSample.GearChanged(100, Gear.PARK))
        assertEquals(1, d.count<DrivingEvent.Parked>())
        assertEquals(Gear.PARK, d.engine.state.gear)
    }

    @Test fun `운전석만 앉으면 혼자 탑승, 조수석이 앉으면 아님`() {
        val d = Drive()
        d.send(SignalSample.SeatOccupancy(0, Seat.DRIVER, true))
        assertTrue(d.engine.state.solo)
        d.send(SignalSample.SeatOccupancy(100, Seat.PASSENGER, true))
        assertFalse(d.engine.state.solo)
        d.send(SignalSample.SeatOccupancy(200, Seat.PASSENGER, false))
        assertTrue(d.engine.state.solo)
        assertEquals(3, d.count<DrivingEvent.OccupancyChanged>())
    }

    @Test fun `하모니는 부드러운 운전으로 오르고 급 이벤트로 내려가며 0~100을 지킨다`() {
        val d = Drive()
        val start = d.engine.state.harmony
        d.ramp(1.0f, 10_000)
        d.hold(60_000)
        val afterSmooth = d.engine.state.harmony
        assertTrue(afterSmooth > start)
        d.hold(120_000)
        assertTrue(d.engine.state.harmony <= 100f)
        // 급가감속 반복
        repeat(10) { d.ramp(-6f, 1000); d.ramp(4f, 1500); d.hold(2100) }
        assertTrue(d.engine.state.harmony < afterSmooth)
        assertTrue(d.engine.state.harmony >= 0f)
    }
}
