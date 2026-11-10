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

/**
 * 운전 중 무드 화면: 단색 배경 + 추상 그래픽(겹친 원과 물결). 글자·숫자 없음.
 * 상태·시간 규칙은 [MoodScene](순수, 단위 테스트):
 * - restricted(주행 중, SA-1): 완전히 멈춤. 다시 그리지도 않는다(invalidate 없음 → CPU도 0에 가깝다).
 * - 정차 중: 원이 12초 주기로 아주 천천히 숨 쉬듯 움직이고, 주행 중 쌓인 음악 상태로 3초에 걸쳐 바뀐다.
 */
class MoodView(context: Context) : View(context) {
    private fun now() = SystemClock.uptimeMillis()
    val scene = MoodScene(now())
    var restricted: Boolean
        get() = scene.restricted
        set(v) { if (v != scene.restricted) { scene.setRestricted(v, now()); invalidate() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val path = Path()

    fun show(s: MoodState, immediate: Boolean = false) {
        scene.show(s, now(), immediate)
        if (!scene.restricted) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val f = scene.frame(now())
        c.drawColor(f.background)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h * 0.46f
        val unit = min(w, h)
        // 겹친 원 3개: 레이어가 많을수록(에너지) 크고 넓게. 가장자리는 투명으로 번진다.
        for ((rr, col) in f.circles) {
            val r = rr * unit
            paint.shader = RadialGradient(cx, cy, r, col, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
        }
        paint.shader = null
        // 물결: 부드러울수록 잔잔
        wavePaint.color = f.waveColor
        val amp = h * f.waveAmp
        for (k in 0 until 2) {
            path.reset()
            val y0 = h * (0.78f + k * 0.05f)
            var x = 0f
            path.moveTo(0f, y0)
            while (x <= w) {
                path.lineTo(x, y0 + amp * sin(2 * PI * (x / w * 1.5 + k * 0.3 + f.wavePhase)).toFloat())
                x += 8f
            }
            c.drawPath(path, wavePaint)
        }
        if (f.rain) { // 비: 가는 사선 몇 개(정적)
            wavePaint.color = Color.argb(40, 200, 220, 255)
            for (i in 0 until 14) { val x = w * (i + 0.5f) / 14; c.drawLine(x, h * 0.1f, x - 20, h * 0.1f + 60, wavePaint) }
        }
        if (scene.animating && isAttachedToWindow) postInvalidateOnAnimation()
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

/**
 * 원형 아이콘 버튼. glyph: 'A' = 마음 앨범(재킷에서 반쯤 나온 음반), 'D' = 디버그(점 3개), 'X' = 닫기, '<' = 뒤로.
 * bare = 배경 원 없이 아이콘만(라벨이 붙은 알약 버튼 안에서 쓸 때).
 */
class GlyphButton(context: Context, private val glyph: Char, desc: String, private val bare: Boolean = false) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
    init { if (!bare) { isClickable = true; isFocusable = true }; contentDescription = desc }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        if (!bare) { p.style = Paint.Style.FILL; p.color = Color.argb(40, 255, 255, 255); c.drawCircle(cx, cy, s / 2, p) }
        p.color = Color.argb(230, 255, 255, 255); p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.05f
        val u = s * (if (bare) 0.34f else 0.2f)
        when (glyph) {
            'A' -> {
                // 음반(오른쪽)이 재킷(왼쪽 사각형)에서 반쯤 나온 모양
                c.drawCircle(cx + u * 0.35f, cy, u * 0.85f, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx + u * 0.35f, cy, u * 0.18f, p)
                p.color = Color.argb(255, 0x1B, 0x26, 0x33); c.drawRect(cx - u * 1.05f, cy - u * 0.95f, cx + u * 0.15f, cy + u * 0.95f, p)
                p.color = Color.argb(230, 255, 255, 255); p.style = Paint.Style.STROKE
                c.drawRect(cx - u * 1.05f, cy - u * 0.95f, cx + u * 0.15f, cy + u * 0.95f, p)
            }
            'D' -> { p.style = Paint.Style.FILL; for (k in -1..1) c.drawCircle(cx + k * u, cy, s * 0.04f, p) }
            'X' -> { c.drawLine(cx - u, cy - u, cx + u, cy + u, p); c.drawLine(cx + u, cy - u, cx - u, cy + u, p) }
            '<' -> { c.drawLine(cx + u * 0.4f, cy - u, cx - u * 0.6f, cy, p); c.drawLine(cx - u * 0.6f, cy, cx + u * 0.4f, cy + u, p) }
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
        // 무드 화면 원과 같은 톤(청록 0x6CC3B5): 바깥 번짐 + 가장자리로 갈수록 투명해지는 몸통
        p.shader = RadialGradient(cx, cy, r * 1.6f, 0x556CC3B5, 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.6f, p)
        p.shader = RadialGradient(cx, cy, r, intArrayOf(0xCC6CC3B5.toInt(), 0xB86CC3B5.toInt(), 0x006CC3B5), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
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
