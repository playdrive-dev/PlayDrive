package app.playdrive.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import app.playdrive.album.CoverSpec
import app.playdrive.album.MoodState
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * (API 33 미만 대체) 무드(주행) 화면: 셰이더와 같은 물결을 Canvas 경로로 단순하게 그린다. 글자·숫자 없음.
 * 상태는 [MoodScene]·[WaveMotion](순수, 단위 테스트).
 */
class CanvasMoodLayer(context: Context, private val host: MoodView) : View(context) {
    override fun onDraw(c: Canvas) {
        val f = host.currentFrame()
        val pal = app.playdrive.ui.light.LightParams.palette(if (f.dayMix >= 0.5f) f.dayTo else f.dayFrom)
        WavePainter.draw(c, 0f, 0f, width.toFloat(), height.toFloat(), f.waves, pal, f.master)
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** Canvas 물결(셰이더가 없는 기기): 바탕 그라데이션 + 악기별 물결 면·가장자리 + 멜로디 선 + 바닥 앰비언트 선. */
object WavePainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private fun col(c: FloatArray, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), (c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt())

    fun draw(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, ws: WaveState, pal: app.playdrive.ui.light.LightParams.Palette, master: Float) {
        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, y0, 0f, y0 + h, col(pal.top, 1f), col(pal.bottom, 1f), Shader.TileMode.CLAMP)
        c.drawRect(x0, y0, x0 + w, y0 + h, p); p.shader = null
        val aspect = w / h
        ws.lines.forEachIndexed { i, l ->
            if (i >= 5 || l.edge + l.glow <= 0.002f) return@forEachIndexed
            path.reset(); path.moveTo(x0, y0 + h)
            var x = 0f
            while (x <= aspect + 0.01f) { path.lineTo(x0 + x * h, y0 + WaveGeom.height(ws, i, x, aspect) * h); x += 0.01f }
            val y = l.y
            path.lineTo(x0 + w, y0 + h); path.close()
            val lc = if (i == 1 || i == 4) FloatArray(3) { pal.wave[it] + (pal.accent[it] - pal.wave[it]) * 0.4f } else pal.wave
            p.style = Paint.Style.FILL
            p.shader = android.graphics.LinearGradient(0f, y0 + y * h, 0f, y0 + h, col(lc, 0.45f * l.glow * WaveState.WEIGHT[i] * 4f), col(lc, 0f), Shader.TileMode.CLAMP)
            c.drawPath(path, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = h * 0.004f
            p.color = col(pal.accent, 0.55f * l.edge * (1f - 0.75f * l.fold))
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, y0 + h * 0.93f, 0f, y0 + h, col(pal.accent, 0f), col(pal.accent, 0.5f), Shader.TileMode.CLAMP)
        c.drawRect(x0, y0 + h * 0.93f, x0 + w, y0 + h, p); p.shader = null
        val dark = 1f - master * (1f - ws.dim)
        if (dark > 0f) { p.color = Color.argb((dark * 255).toInt().coerceIn(0, 255), 0, 0, 0); c.drawRect(x0, y0, x0 + w, y0 + h, p) }
    }
}

/**
 * 유리 질감 바탕: 1px 하이라이트 테두리(위쪽이 더 밝음). 흐림은 뒤의 무드 셰이더가 그 자리에서 그린다(glassBehind = true).
 * 셰이더가 없는 기기(API 33 미만)에서는 옅은 반투명 면을 직접 깐다.
 */
object Glass {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(34, 255, 255, 255) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, glassBehind: Boolean) {
        if (!glassBehind) c.drawRoundRect(l, t, r, b, radius, radius, fill)
        rim.shader = android.graphics.LinearGradient(0f, t, 0f, b, Color.argb(150, 255, 255, 255), Color.argb(26, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f, radius, radius, rim)
    }
}

/** 유리 카드 배경(주차 카드 등): 버튼과 같은 질감. 흐림은 뒤의 셰이더가 같은 자리에 그린다. */
class GlassDrawable(private val radius: Float, private val glassBehind: Boolean) : android.graphics.drawable.Drawable() {
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 8, 12, 18) }
    override fun draw(c: Canvas) {
        val b = bounds
        // 흐린 장면 위에 아주 옅게 어둡게 → 긴 글도 읽힌다(셰이더 없는 기기는 면을 더 진하게)
        dim.alpha = if (glassBehind) 70 else 200
        c.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius, radius, dim)
        Glass.draw(c, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius, glassBehind)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** 재생/일시정지(글자 없음): 유리 원 + 얇은 선 아이콘. */
class PlayPauseButton(context: Context) : View(context) {
    var playing = false
        set(v) { field = v; contentDescription = if (v) "일시정지" else "재생"; invalidate() }
    var glassBehind = false
        set(v) { field = v; invalidate() }
    private val navyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 14, 26, 58) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    init { isClickable = true; isFocusable = true; contentDescription = "재생" }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, s / 2, navyFill)   // 남색 반투명(뒤 빛 색이 그대로 비치지 않게)
        Glass.draw(c, cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, s / 2, glassBehind)
        p.color = Color.argb(235, 255, 255, 255); p.strokeWidth = (s * 0.022f).coerceAtLeast(1.5f)
        val u = s * 0.15f
        if (playing) {
            c.drawLine(cx - u * 0.45f, cy - u, cx - u * 0.45f, cy + u, p)
            c.drawLine(cx + u * 0.45f, cy - u, cx + u * 0.45f, cy + u, p)
        } else {
            val t = Path().apply { moveTo(cx - u * 0.55f, cy - u); lineTo(cx + u * 0.95f, cy); lineTo(cx - u * 0.55f, cy + u); close() }
            c.drawPath(t, p)
        }
    }
}

/**
 * 원형 아이콘 버튼(얇은 선). glyph: 'A' = 드라이브 앨범(재킷에서 반쯤 나온 음반), 'D' = 디버그(점 3개), 'X' = 닫기, '<' = 뒤로.
 * bare = 바탕 없이 아이콘만(라벨이 붙은 알약 버튼 안에서 쓸 때).
 */
class GlyphButton(context: Context, private val glyph: Char, desc: String, private val bare: Boolean = false) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    init { if (!bare) { isClickable = true; isFocusable = true }; contentDescription = desc }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        if (!bare) Glass.draw(c, cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, s / 2, false)
        p.color = Color.argb(235, 255, 255, 255); p.style = Paint.Style.STROKE
        p.strokeWidth = (s * 0.028f).coerceAtLeast(1.5f)
        val u = s * (if (bare) 0.34f else 0.18f)
        when (glyph) {
            'A' -> {
                // 음반(오른쪽)이 재킷(왼쪽 사각형)에서 반쯤 나온 모양 — 선만
                c.drawArc(cx + u * 0.35f - u * 0.85f, cy - u * 0.85f, cx + u * 0.35f + u * 0.85f, cy + u * 0.85f, -70f, 140f, false, p)
                c.drawCircle(cx + u * 0.35f, cy, u * 0.16f, p)
                c.drawRoundRect(cx - u * 1.05f, cy - u * 0.95f, cx + u * 0.15f, cy + u * 0.95f, u * 0.08f, u * 0.08f, p)
            }
            'D' -> { p.style = Paint.Style.FILL; for (k in -1..1) c.drawCircle(cx + k * u, cy, s * 0.03f, p) }
            'X' -> { c.drawLine(cx - u, cy - u, cx + u, cy + u, p); c.drawLine(cx + u, cy - u, cx - u, cy + u, p) }
            '<' -> { c.drawLine(cx + u * 0.4f, cy - u, cx - u * 0.6f, cy, p); c.drawLine(cx - u * 0.6f, cy, cx + u * 0.4f, cy + u, p) }
            'S' -> { // 설정: 가로 슬라이더 세 줄 + 손잡이
                for ((k, knob) in listOf(-1 to -0.4f, 0 to 0.45f, 1 to -0.05f)) {
                    val y = cy + k * u * 0.75f
                    c.drawLine(cx - u, y, cx + u, y, p)
                    p.style = Paint.Style.FILL; c.drawCircle(cx + knob * u, y, s * 0.045f, p); p.style = Paint.Style.STROKE
                }
            }
        }
    }
}

/** 앨범 커버(격자·상세·결과 화면): 무드 화면과 같은 물결 셰이더(coverMode), API 33 미만은 Canvas 물결. */
class CoverView(context: Context) : View(context) {
    var spec: CoverSpec? = null
        set(v) { field = v; invalidate() }
    private val shaderPaint: Paint? = if (android.os.Build.VERSION.SDK_INT >= 33) Paint().apply { shader = app.playdrive.ui.light.LightShader.create() } else null
    override fun onDraw(c: Canvas) {
        val s = spec ?: return
        val side = maxOf(width, height).toFloat()
        val sp = shaderPaint
        if (sp != null && android.os.Build.VERSION.SDK_INT >= 33) {
            val sh = sp.shader as android.graphics.RuntimeShader
            app.playdrive.ui.light.LightShader.apply(sh, app.playdrive.ui.light.LightParams.cover(s, side))
            c.save(); c.translate((width - side) / 2, (height - side) / 2)
            c.drawRect(0f, 0f, side, side, sp)
            c.restore()
        } else {
            val (ws, pal) = app.playdrive.ui.light.LightParams.coverWaves(s)
            WavePainter.draw(c, (width - side) / 2, (height - side) / 2, side, side, ws, pal, 1f)
        }
    }
}
