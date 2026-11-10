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
import app.playdrive.album.AlbumNaming
import app.playdrive.album.BreathCoach
import app.playdrive.album.MoodState
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * 운전 중 무드 화면: 단색 배경 + 추상 그래픽(겹친 원과 물결). 글자·숫자 없음.
 * - restricted(주행 중): 연속 움직임 없음. 새 상태가 오면 3초 동안 천천히 전환만 한다(전환 간격은 MoodThrottle이 10초 이상으로 제한).
 * - 정차 중: 원이 아주 천천히 숨 쉬듯 움직인다.
 */
class MoodView(context: Context) : View(context) {
    private var from = MoodState(0.2f, 0.5f, 0f, false)
    private var to = from
    private var t = 1f
    var restricted = false
        set(v) { field = v; if (v) idle.cancel() else if (!idle.isStarted) idle.start(); invalidate() }

    private val fade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3_000; addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private var breathe = 0f
    private val idle = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 12_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { breathe = it.animatedValue as Float; invalidate() }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val path = Path()

    fun show(s: MoodState) {
        from = current(); to = s; t = 0f
        fade.cancel(); fade.start()
    }

    private fun current() = MoodState(lerp(from.energy, to.energy, t), lerp(from.calm, to.calm, t), lerp(from.brightness, to.brightness, t), if (t > 0.5f) to.rain else from.rain)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!restricted) idle.start() }
    override fun onDetachedFromWindow() { idle.cancel(); fade.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = current()
        // 단색 배경: 밝기(속도)와 비에 따라 아주 미세하게
        val bg = AlbumNaming.lerpColor(0xFF0E1420.toInt(), 0xFF1A2B3A.toInt(), s.brightness * 0.6f + if (s.rain) 0.2f else 0f)
        c.drawColor(bg)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h * 0.46f
        val base = min(w, h) * 0.18f
        val tone = AlbumNaming.lerpColor(0xFFE59A5C.toInt(), 0xFF6CC3B5.toInt(), s.calm)
        val motion = if (restricted) 0f else sin(2 * PI * breathe).toFloat() * 0.04f
        // 겹친 원 3개: 레이어가 많을수록(에너지) 크고 넓게
        for (i in 2 downTo 0) {
            val r = base * (1f + i * (0.35f + 0.35f * s.energy)) * (1f + motion * (i + 1))
            val alpha = (90 - i * 25).coerceAtLeast(20)
            paint.shader = RadialGradient(cx, cy, r, Color.argb(alpha, Color.red(tone), Color.green(tone), Color.blue(tone)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
        }
        paint.shader = null
        // 물결: 부드러울수록 잔잔
        wavePaint.color = Color.argb(70, Color.red(tone), Color.green(tone), Color.blue(tone))
        val amp = h * (0.01f + 0.04f * (1f - s.calm)) * (0.5f + s.energy)
        for (k in 0 until 2) {
            path.reset()
            val y0 = h * (0.78f + k * 0.05f)
            var x = 0f
            path.moveTo(0f, y0)
            while (x <= w) {
                path.lineTo(x, y0 + amp * sin(2 * PI * (x / w * 1.5 + k * 0.3 + breathe * if (restricted) 0 else 1)).toFloat())
                x += 8f
            }
            c.drawPath(path, wavePaint)
        }
        if (s.rain) { // 비: 가는 사선 몇 개(정적)
            wavePaint.color = Color.argb(40, 200, 220, 255)
            for (i in 0 until 14) { val x = w * (i + 0.5f) / 14; c.drawLine(x, h * 0.1f, x - 20, h * 0.1f + 60, wavePaint) }
        }
    }
}

/** 재생/일시정지 아이콘 버튼(글자 없음). */
class PlayPauseButton(context: Context) : View(context) {
    var playing = false
        set(v) { field = v; contentDescription = if (v) "일시정지" else "재생"; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; isFocusable = true; contentDescription = "재생" }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        p.color = Color.argb(46, 255, 255, 255); c.drawCircle(cx, cy, s / 2, p)
        p.color = Color.argb(230, 255, 255, 255)
        val u = s * 0.18f
        if (playing) {
            c.drawRoundRect(RectF(cx - u * 0.9f, cy - u, cx - u * 0.25f, cy + u), 6f, 6f, p)
            c.drawRoundRect(RectF(cx + u * 0.25f, cy - u, cx + u * 0.9f, cy + u), 6f, 6f, p)
        } else {
            val t = Path().apply { moveTo(cx - u * 0.6f, cy - u); lineTo(cx + u, cy); lineTo(cx - u * 0.6f, cy + u); close() }
            c.drawPath(t, p)
        }
    }
}

/** 작은 원형 아이콘 버튼(앨범/설정 등). glyph: 'A' = 앨범(겹친 사각), 'D' = 디버그(점 3개), 'X' = 닫기. */
class GlyphButton(context: Context, private val glyph: Char, desc: String) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    init { isClickable = true; isFocusable = true; contentDescription = desc }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.FILL; p.color = Color.argb(40, 255, 255, 255); c.drawCircle(cx, cy, s / 2, p)
        p.color = Color.argb(220, 255, 255, 255); p.style = Paint.Style.STROKE
        val u = s * 0.2f
        when (glyph) {
            'A' -> { c.drawRect(cx - u, cy - u * 0.6f, cx + u * 0.6f, cy + u, p); c.drawRect(cx - u * 0.6f, cy - u, cx + u, cy + u * 0.6f, p) }
            'D' -> { p.style = Paint.Style.FILL; for (k in -1..1) c.drawCircle(cx + k * u, cy, 4f, p) }
            'X' -> { c.drawLine(cx - u, cy - u, cx + u, cy + u, p); c.drawLine(cx + u, cy - u, cx - u, cy + u, p) }
        }
    }
}

/** 1분 호흡: 들숨 4초에 커지고 날숨 6초에 작아지는 원(정차 중에만 쓰는 화면). */
class BreathView(context: Context, private val onPhase: (inhale: Boolean) -> Unit, private val onDone: () -> Unit) : View(context) {
    private var start = 0L
    private var lastInhale: Boolean? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = BreathCoach.DURATION_MS; interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) onDone() }
        })
    }
    private var cancelled = false
    /** 스크린샷·테스트용: 특정 시점에 멈춘 모습. */
    var frozenAtMs: Long? = null

    fun begin() { start = SystemClock.elapsedRealtime(); cancelled = false; anim.start() }
    fun cancel() { cancelled = true; anim.cancel() }

    override fun onDraw(c: Canvas) {
        val el = frozenAtMs ?: (SystemClock.elapsedRealtime() - start)
        val (inhale, k) = BreathCoach.phase(el)
        if (inhale != lastInhale) { lastInhale = inhale; onPhase(inhale) }
        val e = (1 - kotlin.math.cos(PI * k).toFloat()) / 2 // 부드러운 가감속
        c.drawColor(0xFF0F1A24.toInt())
        val cx = width / 2f; val cy = height * 0.40f
        val r = min(width, height) * (0.12f + 0.12f * e)
        p.shader = RadialGradient(cx, cy, r * 1.6f, 0x556CC3B5, 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.6f, p)
        p.shader = null; p.color = 0xCC6CC3B5.toInt()
        c.drawCircle(cx, cy, r, p)
    }
}

/** 앨범 커버: AlbumNaming.CoverSpec을 그린다. */
class CoverView(context: Context) : View(context) {
    var spec: AlbumNaming.CoverSpec? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = spec ?: return
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(s.background)
        p.shader = RadialGradient(w * 0.5f, h * 0.5f, w * 0.7f, s.glow, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        for ((x, y, r) in s.circles) {
            p.color = (s.circleColor and 0x00FFFFFF) or 0x88000000.toInt()
            c.drawCircle(x * w, y * h, r * w, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.01f; p.color = 0x66FFFFFF
        val path = Path(); path.moveTo(0f, h * 0.8f)
        var x = 0f
        while (x <= w) { path.lineTo(x, h * 0.8f + h * 0.05f * s.wave * sin(2 * PI * x / w * 3).toFloat()); x += w / 40 }
        c.drawPath(path, p)
        // 비: 밝은 배경에선 진한 남색, 어두운 배경에선 옅은 하늘색
        val lum = (Color.red(s.background) * 0.299 + Color.green(s.background) * 0.587 + Color.blue(s.background) * 0.114) / 255
        p.color = if (lum > 0.5) 0x993A5A7A.toInt() else 0x66BFD9FF
        for (i in 0 until s.rainLines) { val xx = w * ((i * 37) % 100) / 100f; val yy = h * ((i * 53) % 100) / 100f; c.drawLine(xx, yy, xx - w * 0.03f, yy + h * 0.08f, p) }
        p.style = Paint.Style.FILL
    }
}
