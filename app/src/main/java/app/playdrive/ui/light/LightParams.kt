package app.playdrive.ui.light

import app.playdrive.album.AlbumNaming
import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.CoverSpec
import app.playdrive.ui.MoodFrame
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.sin

/**
 * 셰이더 uniform 값(순수 Kotlin). MoodFrame / CoverSpec → 이름별 FloatArray.
 * 같은 입력이면 같은 값(시계·난수 없음) → "주행 중 프레임 불변"이 렌더러까지 이어진다.
 *
 * 좌표: p = 픽셀 / 높이 → y 0~1(위→아래), x 0~가로세로비.
 */
object LightParams {
    const val MAX_LIGHTS = 8
    const val MAX_SCARS = 4
    const val MAX_GLASS = 2

    /** 시간대 색온도(선형에 가까운 sRGB 0~1): 위, 아래, 바닥 반사 띠 근처 빛. */
    data class Palette(val top: FloatArray, val bottom: FloatArray, val horizon: FloatArray)

    fun palette(p: PartOfDay): Palette = when (p) {
        PartOfDay.DAWN -> Palette(f(0.07f, 0.075f, 0.15f), f(0.02f, 0.025f, 0.05f), f(0.30f, 0.22f, 0.42f))
        PartOfDay.MORNING -> Palette(f(0.30f, 0.35f, 0.40f), f(0.11f, 0.14f, 0.17f), f(0.55f, 0.60f, 0.64f))   // 옅은 청회색
        PartOfDay.DAY -> Palette(f(0.06f, 0.26f, 0.30f), f(0.02f, 0.10f, 0.13f), f(0.30f, 0.62f, 0.62f))       // 맑은 청록
        PartOfDay.EVENING -> Palette(f(0.17f, 0.075f, 0.23f), f(0.27f, 0.12f, 0.07f), f(0.85f, 0.44f, 0.20f))   // 앰버-자주
        PartOfDay.NIGHT -> Palette(f(0.025f, 0.04f, 0.09f), f(0.004f, 0.007f, 0.018f), f(0.12f, 0.16f, 0.32f)) // 깊은 남색-검정
    }

    fun partOf(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): PartOfDay =
        AlbumNaming.partOfDay(Calendar.getInstance(tz).apply { timeInMillis = epochMs }.get(Calendar.HOUR_OF_DAY))

    private fun f(vararg v: Float) = v
    private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(a.size) { a[it] + (b[it] - a[it]) * k }
    private fun rgb(c: Int) = f(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    private fun easeOut(k: Float) = 1f - (1f - k) * (1f - k) * (1f - k)
    private fun tau(t: Float, period: Float) = sin(2 * PI * t / period).toFloat()

    /** 유리 패널(픽셀 좌표 l,t,r,b + 모서리 반지름). */
    data class Glass(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float)

    /** 무드 화면. w,h = 그릴 영역 픽셀. */
    fun mood(fr: MoodFrame, w: Float, h: Float, glass: List<Glass> = emptyList(), scale: Float = 1f): Map<String, FloatArray> {
        val aspect = w / h
        val t = fr.timeSec
        val pal = mix3(palette(fr.dayFrom), palette(fr.dayTo), fr.dayMix)
        val s = fr.mood
        // 메인 빛: 화면 중앙보다 약간 위. 10초·12초 주기의 아주 느린 흔들림과 크기 숨쉬기.
        val tone = AlbumNaming.lerpColor(0xFFE59A5C.toInt(), 0xFF6CC3B5.toInt(), s.calm)
        val mainColor = mix(f(1f, 0.87f, 0.72f), rgb(tone), 0.55f)
        val bloomIn = 0.55f + 0.45f * fr.master
        val mainR = 0.14f * (1f + 0.45f * s.energy) * (1f + 0.025f * tau(t, 12f)) * bloomIn
        val mainI = (0.55f + 0.25f * s.brightness + 0.2f * s.energy) * fr.pulse * fr.moodAlpha.coerceAtLeast(0.55f)
        val main = f(aspect / 2 + 0.012f * tau(t, 10f), 0.40f + 0.008f * tau(t + 2.5f, 12f), mainR, mainI)

        // 보조 빛: 깊은 곳의 은은한 빛 2개 + 오늘 커버의 겹(최근 6개, 오래된 겹일수록 깊이)
        val lights = FloatArray(MAX_LIGHTS * 4); val cols = FloatArray(MAX_LIGHTS * 4)
        var n = 0
        fun add(x: Float, y: Float, r: Float, depth: Float, c: FloatArray, a: Float) {
            if (n >= MAX_LIGHTS) return
            lights[n * 4] = x; lights[n * 4 + 1] = y; lights[n * 4 + 2] = r; lights[n * 4 + 3] = depth
            cols[n * 4] = c[0]; cols[n * 4 + 1] = c[1]; cols[n * 4 + 2] = c[2]; cols[n * 4 + 3] = a; n++
        }
        val gain = 0.55f + 0.45f * fr.bright
        add(aspect * 0.20f + 0.010f * tau(t, 12f), 0.28f, 0.11f * bloomIn, 0.9f, pal.horizon, 0.30f * gain)
        add(aspect * 0.82f + 0.008f * tau(t + 4f, 10f), 0.22f, 0.08f * bloomIn, 0.7f, mix(pal.horizon, mainColor, 0.5f), 0.26f * gain)
        val ring = FloatArray(4)
        val scars = FloatArray(MAX_SCARS * 4); var ns = 0
        val c = fr.cover
        if (c != null) {
            val side = 1.08f * minOf(aspect, 1f)
            val ox = aspect / 2 - side / 2; val oy = 0.46f - side / 2
            val from = (c.layers.size - 6).coerceAtLeast(0)
            for (i in from until c.layers.size) {
                val l = c.layers[i]
                val k = if (i >= fr.revealFrom) fr.reveal else 1f
                if (k <= 0f) continue
                val age = (c.layers.size - 1 - i).toFloat() / 6f
                val depth = (0.15f + 0.6f * age).coerceAtMost(0.8f)
                add(ox + l.x * side, oy + l.y * side, l.r * side * 0.75f * easeOut(k) * bloomIn, depth, rgb(l.color), 0.5f * gain * k)
                if (i >= fr.revealFrom && k < 1f) { ring[0] = ox + l.x * side; ring[1] = oy + l.y * side; ring[2] = l.r * side * (0.8f + 0.5f * k); ring[3] = (1f - k) * 0.9f }
                if (l.scars > 0 && ns < MAX_SCARS) { scars[ns * 4] = ox + l.x * side; scars[ns * 4 + 1] = (l.scars / 2f).coerceAtMost(1f) * k; ns++ }
            }
        }
        if (n < 3) add(aspect * 0.66f + 0.008f * tau(t + 7f, 10f), 0.60f, 0.05f * bloomIn, 0.35f, mainColor, 0.22f * gain)

        val g = FloatArray(MAX_GLASS * 4); val gr = FloatArray(4)
        glass.take(MAX_GLASS).forEachIndexed { i, q ->
            g[i * 4] = q.l * scale; g[i * 4 + 1] = q.t * scale; g[i * 4 + 2] = q.r * scale; g[i * 4 + 3] = q.b * scale; gr[i] = q.radius * scale
        }
        return linkedMapOf(
            "iResolution" to f(w, h),
            "iTime" to f(t),
            "coverMode" to f(0f),
            "bgTop" to pal.top, "bgBottom" to pal.bottom, "horizon" to pal.horizon,
            "mainLight" to main, "mainColor" to mainColor,
            "calm" to f(s.calm),
            "lights" to lights, "lightCol" to cols, "lightCount" to f(n.toFloat()),
            "ring" to ring,
            "scars" to scars, "scarCount" to f(ns.toFloat()),
            "bandY" to f(0.80f), "bandWave" to f((0.004f + 0.012f * (1f - s.calm)) * (0.6f + 0.6f * s.energy)),
            "rain" to f(if (fr.rain) 0.85f else 0f), "rainSeed" to f(3f),
            "glass" to g, "glassRad" to gr, "glassCount" to f(glass.size.coerceAtMost(MAX_GLASS).toFloat()),
            "master" to f(fr.master),
        )
    }

    /** 마음 앨범 커버(정사각형). 부드러움 = 빛의 크기·선명도, 비 = 물방울, 테이프스톱 = 빛이 흐트러진 자국, 시간대 = 색온도. */
    fun cover(c: CoverSpec, side: Float, tz: TimeZone = TimeZone.getDefault()): Map<String, FloatArray> {
        val pal = palette(partOf(c.id, tz))
        val lights = FloatArray(MAX_LIGHTS * 4); val cols = FloatArray(MAX_LIGHTS * 4)
        val scars = FloatArray(MAX_SCARS * 4); var ns = 0
        val from = (c.layers.size - MAX_LIGHTS).coerceAtLeast(0)
        var n = 0
        for (i in from until c.layers.size) {
            val l = c.layers[i]
            val sharp = ((l.r - 0.06f) / 0.16f).coerceIn(0f, 1f)
            lights[n * 4] = l.x; lights[n * 4 + 1] = l.y; lights[n * 4 + 2] = l.r * 1.05f; lights[n * 4 + 3] = (1f - sharp) * 0.7f
            val col = rgb(l.color)
            cols[n * 4] = col[0]; cols[n * 4 + 1] = col[1]; cols[n * 4 + 2] = col[2]; cols[n * 4 + 3] = 0.55f + 0.35f * sharp
            if (l.scars > 0 && ns < MAX_SCARS) { scars[ns * 4] = l.x; scars[ns * 4 + 1] = (l.scars / 2f).coerceAtMost(1f); ns++ }
            n++
        }
        val calm = (1f - c.wave).coerceIn(0f, 1f)
        return linkedMapOf(
            "iResolution" to f(side, side),
            "iTime" to f(0f),
            "coverMode" to f(1f),
            "bgTop" to pal.top, "bgBottom" to pal.bottom, "horizon" to pal.horizon,
            "mainLight" to FloatArray(4), "mainColor" to FloatArray(3),
            "calm" to f(calm),
            "lights" to lights, "lightCol" to cols, "lightCount" to f(n.toFloat()),
            "ring" to FloatArray(4),
            "scars" to scars, "scarCount" to f(ns.toFloat()),
            "bandY" to f(0.82f), "bandWave" to f(0.004f + 0.014f * c.wave),
            "rain" to f((c.rainLines / 20f).coerceIn(0f, 1f)), "rainSeed" to f((c.id % 997).toFloat()),
            "glass" to FloatArray(MAX_GLASS * 4), "glassRad" to FloatArray(4), "glassCount" to f(0f),
            "master" to f(1f),
        )
    }

    private fun mix3(a: Palette, b: Palette, k: Float) = Palette(mix(a.top, b.top, k), mix(a.bottom, b.bottom, k), mix(a.horizon, b.horizon, k))
}
