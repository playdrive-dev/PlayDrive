package app.playdrive.ui

import java.io.InputStream

/**
 * 물결용 '악보': 앱이 재생하는 그 루프(SONG 세트 8마디)를 1/32박(1000프레임 ≈ 20.8ms) 칸으로 분석한 값.
 * 만든 곳: tools/make_wave_score.py → res/raw/wave_score.txt. 화면은 (들리는 격자 위치 − 곡 시작) mod 루프 → 칸 번호로 읽는다.
 * 모든 값은 0~1(음 높이는 MIDI, 0 = 음 없음).
 */
class WaveScore(private val rows: Map<String, FloatArray>) {
    val steps: Int = rows.values.firstOrNull()?.size ?: 0

    private fun row(name: String) = rows[name] ?: FloatArray(steps)
    val pad = row("pad"); val keys = row("keys"); val bass = row("bass"); val perc = row("perc"); val guitar = row("guitar")
    val kick = row("kick"); val snare = row("snare"); val hat = row("hat"); val bassOn = row("bass_on")
    /** MIDI(0 = 음 없음). */
    val bassPitch = row("bass_pitch"); val guitarPitch = row("guitar_pitch")

    fun at(r: FloatArray, step: Int) = if (steps == 0) 0f else r[Math.floorMod(step, steps)]

    /** (from, to] 칸 사이의 가장 큰 값(온셋 행: 그 사이에 친 타격). 한 바퀴를 넘으면 to 근처 한 바퀴만. */
    fun maxBetween(r: FloatArray, from: Int, to: Int): Float {
        if (steps == 0 || to <= from) return 0f
        var m = 0f
        for (k in maxOf(from + 1, to - steps + 1)..to) m = maxOf(m, r[Math.floorMod(k, steps)])
        return m
    }

    companion object {
        /** 칸 하나 = 1000 격자 프레임(48kHz에서 1/32박, 90 BPM). */
        const val HOP = 1000
        /** 이 악보가 맞는 루프 길이(SONG 세트 8마디). 다른 세트면 쓰지 않는다. */
        const val LOOP_FRAMES = 1_024_000
        const val STEPS = LOOP_FRAMES / HOP
        val EMPTY = WaveScore(emptyMap())

        fun parse(input: InputStream): WaveScore = input.bufferedReader().useLines { lines ->
            val m = HashMap<String, FloatArray>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.trim().split(' ')
                val name = parts[0]
                val pitch = name.endsWith("_pitch")
                m[name] = FloatArray(parts.size - 1) { val v = parts[it + 1].toInt(); if (pitch) v / 2f else v / 255f }
            }
            WaveScore(m)
        }
    }
}
