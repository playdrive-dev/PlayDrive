package app.playdrive.ui.light

import app.playdrive.album.AlbumNaming
import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.CoverArt
import app.playdrive.album.CoverSpec
import app.playdrive.ui.Inst
import app.playdrive.ui.MoodFrame
import app.playdrive.ui.WaveMotion
import app.playdrive.ui.WaveState
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 셰이더 uniform 값(순수 Kotlin). MoodFrame / CoverSpec → 이름별 FloatArray. 같은 입력이면 같은 값.
 * 좌표: p = 픽셀 / 높이 → y 0~1(위→아래), x 0~가로세로비.
 */
object LightParams {
    const val MAX_SCARS = 4
    const val MAX_GLASS = 4

    /**
     * 시간대 색(sRGB 0~1), 채도 낮게 2~3색만: 바탕 위·아래, 물결, 포인트(물결 가장자리·멜로디·바닥 앰비언트 선).
     * band = 노을의 지평선 쪽 물결 끝(맨 뒤 물결 가장자리)에만 얹는 얇은 색(r, g, b, 세기). 세기 0이면 없음.
     */
    data class Palette(val top: FloatArray, val bottom: FloatArray, val wave: FloatArray, val accent: FloatArray, val band: FloatArray = FloatArray(4),
                       /** 바닥 앰비언트 선(차량 대시보드 아래 청록 라인과 이어지게) — 모든 시간대 #3FD0C0 계열. */
                       val ambient: FloatArray = rgb(0x3FD0C0))

    fun palette(p: PartOfDay): Palette = when (p) {
        PartOfDay.DAWN -> Palette(rgb(0x060A18), rgb(0x1A2850), rgb(0x3A508C), rgb(0xA4B4EC), ambient = rgb(0x46C8C0))
        // 아침: 짙은 청회색 → 맑은 하늘빛 청색, 포인트 연한 금빛
        PartOfDay.MORNING -> Palette(rgb(0x1E3046), rgb(0x5E9CCB), rgb(0x9CCBEC), rgb(0xF2DFAA), ambient = rgb(0x5ED6CA))
        // 한낮: 짙은 청록 → 맑은 청록
        PartOfDay.DAY -> Palette(rgb(0x022126), rgb(0x16807B), rgb(0x45BDB2), rgb(0xA8EDE2))
        // 노을: 남색 #0E1A3A → 밝은 남색, 지평선 쪽 물결 끝에만 #C9805A
        PartOfDay.EVENING -> Palette(rgb(0x0E1A3A), rgb(0x2C437F), rgb(0x5670B8), rgb(0xA6B6EE), band = rgb(0xC9805A).let { f(it[0], it[1], it[2], 0.85f) })
        // 밤: 검은 남색 → 남색, 포인트 청록 #3FD0C0(앰비언트 라인 색)
        PartOfDay.NIGHT -> Palette(rgb(0x02040A), rgb(0x0F1F4E), rgb(0x24418C), rgb(0x3FD0C0))
    }

    // ---- OKLab / OKLCh (그라데이션 보간용) ----
    private fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun gam(c: Float) = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f

    /** sRGB → OKLCh (L, C, h 라디안). */
    fun oklch(c: FloatArray): FloatArray {
        val r = lin(c[0]); val g = lin(c[1]); val b = lin(c[2])
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        val L = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        val A = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        val B = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        return f(L, sqrt(A * A + B * B), atan2(B, A))
    }

    /** OKLCh → sRGB(0~1로 자름). 셰이더의 fromLch와 같은 식. */
    fun srgb(lch: FloatArray): FloatArray {
        val A = lch[1] * cos(lch[2]); val B = lch[1] * sin(lch[2])
        val l = (lch[0] + 0.3963377774f * A + 0.2158037573f * B).let { it * it * it }
        val m = (lch[0] - 0.1055613458f * A - 0.0638541728f * B).let { it * it * it }
        val s = (lch[0] - 0.0894841775f * A - 1.2914855480f * B).let { it * it * it }
        return f(
            gam(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s).coerceIn(0f, 1f),
            gam(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s).coerceIn(0f, 1f),
            gam(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s).coerceIn(0f, 1f),
        )
    }

    /** 위·아래 색을 OKLCh로: 색상각은 짧은 쪽으로 돌도록 아래 쪽을 펼친다(셰이더는 그냥 선형 보간). */
    fun gradientLch(top: FloatArray, bottom: FloatArray): Pair<FloatArray, FloatArray> {
        val a = oklch(top); val b = oklch(bottom)
        if (a[1] < 0.02f) a[2] = b[2]
        if (b[1] < 0.02f) b[2] = a[2]
        var d = b[2] - a[2]
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        b[2] = a[2] + d
        return a to b
    }

    /** 그라데이션 t(0 위 ~ 1 아래) 지점의 색(테스트·Canvas용). */
    fun gradientAt(top: FloatArray, bottom: FloatArray, t: Float): FloatArray {
        val (a, b) = gradientLch(top, bottom)
        return srgb(FloatArray(3) { a[it] + (b[it] - a[it]) * t })
    }

    /** 촬영용(debug daypart:…): 커버 색도 이 시간대로. null = 주행 시각대로. */
    @Volatile var coverDayOverride: PartOfDay? = null

    fun partOf(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): PartOfDay = coverDayOverride ?:
        AlbumNaming.partOfDay(Calendar.getInstance(tz).apply { timeInMillis = epochMs }.get(Calendar.HOUR_OF_DAY))

    private fun f(vararg v: Float) = v
    private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(a.size) { a[it] + (b[it] - a[it]) * k }
    fun rgb(c: Int) = f(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    /** 유리 패널(픽셀 좌표 l,t,r,b + 모서리 반지름). */
    data class Glass(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float)

    /** 무드(주행) 화면. w,h = 그릴 영역 픽셀. */
    fun mood(fr: MoodFrame, w: Float, h: Float, glass: List<Glass> = emptyList(), scale: Float = 1f): Map<String, FloatArray> {
        val pal = mixPalette(palette(fr.dayFrom), palette(fr.dayTo), fr.dayMix)
        val g = FloatArray(MAX_GLASS * 4); val gr = FloatArray(4)
        glass.take(MAX_GLASS).forEachIndexed { i, q ->
            g[i * 4] = q.l * scale; g[i * 4 + 1] = q.t * scale; g[i * 4 + 2] = q.r * scale; g[i * 4 + 3] = q.b * scale; gr[i] = q.radius * scale
        }
        return waves(fr.waves, pal, w, h, fr.timeSec, fr.master, cover = false) + mapOf(
            "glass" to g, "glassRad" to gr, "glassCount" to f(glass.size.coerceAtMost(MAX_GLASS).toFloat()),
        )
    }

    /** 물결 상태 → uniform. 멜로디 기준 높이·폭은 곡 진행만큼 함께 오른다. */
    fun waves(ws: WaveState, pal: Palette, w: Float, h: Float, timeSec: Float, master: Float, cover: Boolean): Map<String, FloatArray> {
        val L = FloatArray(24); val M = FloatArray(24); val X = FloatArray(24)
        ws.lines.take(6).forEachIndexed { i, l ->
            L[i * 4] = l.y; L[i * 4 + 1] = l.amp; L[i * 4 + 2] = l.phase; L[i * 4 + 3] = l.freq
            M[i * 4] = l.edge; M[i * 4 + 1] = l.glow; M[i * 4 + 2] = l.swell; M[i * 4 + 3] = l.fold
            X[i * 4] = l.tint; X[i * 4 + 1] = l.ripple; X[i * 4 + 2] = l.shape
        }
        val mel = FloatArray(32) { ws.melY.getOrElse(it) { 0.5f } }
        val melA = FloatArray(32) { ws.melA.getOrElse(it) { 0f } }
        val lift = 0.05f * ws.rise + 0.03f * ws.climax
        val P = FloatArray(WaveState.MAX_PULSES * 4); ws.pulses.takeLast(WaveState.MAX_PULSES).forEachIndexed { i, q -> P[i * 4] = q.t; P[i * 4 + 1] = q.amp; P[i * 4 + 2] = q.cx; P[i * 4 + 3] = q.kind.toFloat() }
        val (top, bottom) = gradientLch(pal.top, pal.bottom)
        return linkedMapOf(
            "iResolution" to f(w, h), "iTime" to f(timeSec), "coverMode" to f(if (cover) 1f else 0f),
            "bgTop" to top, "bgBottom" to bottom, "waveCol" to deepen(pal.wave, ws.rise), "accent" to pal.accent, "band" to pal.band,
            "amb" to f(pal.ambient[0], pal.ambient[1], pal.ambient[2], ws.ambKick),
            "L" to L, "M" to M, "X" to X,
            "mel" to mel, "melA" to melA, "melBase" to f(WaveMotion.BASE_Y[Inst.MELODY.ordinal] - lift, 0.11f, ws.melShift),
            "P" to P,
            "G" to f(ws.flat, ws.dim, ws.sag, ws.fade), "G2" to f(ws.rise, ws.rain, ws.edgeL, ws.edgeR),
            "scars" to FloatArray(MAX_SCARS * 4), "scarCount" to f(0f),
            "master" to f(master),
            "glass" to FloatArray(MAX_GLASS * 4), "glassRad" to FloatArray(4), "glassCount" to f(0f),
        )
    }

    /** 곡이 진행될수록 색이 조금 깊어짐(채도 +20%까지). */
    private fun deepen(c: FloatArray, k: Float): FloatArray {
        if (k <= 0f) return c
        val l = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
        val s = 1f + 0.2f * k
        return FloatArray(3) { (l + (c[it] - l) * s).coerceIn(0f, 1f) }
    }

    /**
     * 드라이브 앨범·결과 커버(정사각형): 같은 물결 스타일. 구간(겹) 하나 = 물결 한 줄(최대 5줄).
     * 부드러운 구간 = 잔잔하고 또렷한 물결, 분주한 구간 = 높게 출렁임, 흔들림(테이프스톱) = 그 자리 물결이 접힌 자국, 비 = 잔물결 결, 시간대 = 색.
     */
    fun cover(c: CoverSpec, side: Float, tz: TimeZone = TimeZone.getDefault()): Map<String, FloatArray> {
        val (ws, pal) = coverWaves(c, tz)
        val u = waves(ws, pal, side, side, 0f, 1f, cover = true).toMutableMap()
        val scars = FloatArray(MAX_SCARS * 4); var ns = 0
        for (l in c.layers.takeLast(5)) if (l.scars > 0 && ns < MAX_SCARS) { scars[ns * 4] = l.x; scars[ns * 4 + 1] = (0.45f + 0.25f * l.scars).coerceAtMost(1f); ns++ }
        u["scars"] = scars; u["scarCount"] = f(ns.toFloat())
        return u
    }

    /** 커버의 물결 상태와 팔레트(셰이더·Canvas 공용). */
    fun coverWaves(c: CoverSpec, tz: TimeZone = TimeZone.getDefault()): Pair<WaveState, Palette> {
        val pal = palette(partOf(c.id, tz))
        val rnd = Random(c.id)
        val lines = Inst.entries.map { app.playdrive.ui.WaveLine(WaveMotion.BASE_Y[it.ordinal], 0f, 0f, WaveMotion.FREQ[it.ordinal], 0f, 0f) }.toMutableList()
        // 맨 뒤 패드 = 바탕 물결(늘), 구간마다 물결 한 줄(코드 → 베이스 → 드럼 → 하이햇 자리)
        val smoothAll = if (c.layers.isEmpty()) 0.6f else c.layers.map { CoverArt.sharpness(it) }.average().toFloat()
        lines[Inst.PAD.ordinal] = app.playdrive.ui.WaveLine(WaveMotion.BASE_Y[Inst.PAD.ordinal] - 0.06f, 0.040f, rnd.nextFloat() * 6.283f, WaveMotion.FREQ[Inst.PAD.ordinal] * 1.3f, 1f, 0.85f)
        val slots = listOf(Inst.CHORDS, Inst.BASS, Inst.DRUMS, Inst.HIHAT)
        c.layers.takeLast(slots.size).forEachIndexed { i, l ->
            val x = slots[i]
            val sharp = CoverArt.sharpness(l)
            lines[x.ordinal] = app.playdrive.ui.WaveLine(
                y = WaveMotion.BASE_Y[x.ordinal] - 0.04f, amp = WaveMotion.BASE_AMP[x.ordinal].coerceAtLeast(0.014f) * (0.8f + 1.6f * (1f - sharp)),
                phase = rnd.nextFloat() * 6.283f, freq = WaveMotion.FREQ[x.ordinal] * 1.4f,
                edge = 1f, glow = 0.7f + 0.3f * sharp, shape = rnd.nextFloat() * 6.283f,
                ripple = if (l.rain > 0) 0.6f else 0f,
            )
        }
        // 멜로디 리본(포인트 색): 부드러운 주행일수록 또렷하게, 모양은 주행마다 다르게
        val p0 = rnd.nextFloat() * 6.283f
        val melY = List(WaveState.MEL_POINTS) { j -> (0.5f + 0.35f * kotlin.math.sin(j * 0.32f + p0) * (0.6f + 0.4f * (1f - smoothAll))).coerceIn(0f, 1f) }
        val melA = List(WaveState.MEL_POINTS) { (0.45f + 0.5f * smoothAll) }
        return WaveState(lines = lines, melY = melY, melA = melA, rain = (c.rainLines / 20f).coerceIn(0f, 1f), rise = 0.45f) to pal
    }

    private fun mixPalette(a: Palette, b: Palette, k: Float) =
        Palette(mix(a.top, b.top, k), mix(a.bottom, b.bottom, k), mix(a.wave, b.wave, k), mix(a.accent, b.accent, k), ambient = mix(a.ambient, b.ambient, k), band =
            f(a.band[0] + (b.band[0] - a.band[0]) * k, a.band[1] + (b.band[1] - a.band[1]) * k, a.band[2] + (b.band[2] - a.band[2]) * k, a.band[3] + (b.band[3] - a.band[3]) * k)
                .also { if (a.band[3] == 0f) { it[0] = b.band[0]; it[1] = b.band[1]; it[2] = b.band[2] } else if (b.band[3] == 0f) { it[0] = a.band[0]; it[1] = a.band[1]; it[2] = a.band[2] } })
}
