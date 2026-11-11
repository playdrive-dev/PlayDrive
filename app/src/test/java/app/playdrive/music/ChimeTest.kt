package app.playdrive.music

import app.playdrive.music.synth.Chime
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChimeTest {
    @Test fun `웰컴 차임 - 곡의 두 코드에 모두 있는 음만, 첫 다운비트부터 소리, 클리핑 없음`() {
        for (m in Chime.MIDI) {
            assertTrue("$m", m % 12 in ChordTimeline.AM7.tones && m % 12 in ChordTimeline.FMAJ7.tones)
        }
        val timing = MusicTiming()
        val mixer = LoopMixer(timing, emptyMap(), mapOf(Cue.CHIME to Chime.render(timing.sampleRate)))
        mixer.submit(MusicCommand.PlayCue(Cue.CHIME))
        val out = FloatArray(2 * 4_800)
        mixer.render(out, 4_800)                          // 처음 0.1초
        assertTrue(out.take(2 * 480).maxOf { abs(it) } > 0.01f)   // 10ms 안에 울림(박 머리)
        assertTrue(out.maxOf { abs(it) } <= 1f)
    }
}
