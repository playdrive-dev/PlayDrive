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
import app.playdrive.album.BreathCoach
import app.playdrive.album.MoodState
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * (API 33 미만 대체) 운전 중 무드 화면: 단색 배경 + 추상 그래픽(겹친 원과 물결). 글자·숫자 없음.
 * 상태·시간 규칙은 [MoodScene](순수, 단위 테스트):
 * - restricted(주행 중, SA-1): 완전히 멈춤. 다시 그리지도 않는다(invalidate 없음 → CPU도 0에 가깝다).
 * - 정차 중: 원이 12초 주기로 아주 천천히 숨 쉬듯 움직이고, 주행 중 쌓인 음악 상태로 3초에 걸쳐 바뀐다.
 */
class CanvasMoodLayer(context: Context, private val host: MoodView) : View(context) {
    private fun now() = SystemClock.uptimeMillis()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val path = Path()
    private val scene get() = host.scene

    override fun onDraw(c: Canvas) {
        val f = scene.frame(now())
        c.drawColor(f.background)
        val w = width.toFloat(); val h = height.toFloat()
        // 오늘의 커버: 무드 원과 같은 중심에 화면 높이만 한 정사각형으로 은은하게(가장자리는 배경색으로 스며들게)
        f.cover?.let { cv ->
            val side = min(w, h) * 1.08f
            CoverPainter.draw(c, cv, (w - side) / 2, h * 0.46f - side / 2, side, f.coverAlpha, f.revealFrom, f.reveal, f.background)
        }
        val cx = w / 2; val cy = h * 0.46f
        val unit = min(w, h)
        val ma = f.moodAlpha
        // 겹친 원 3개: 레이어가 많을수록(에너지) 크고 넓게. 가장자리는 투명으로 번진다.
        for ((rr, col) in f.circles) {
            val r = rr * unit
            val a = (Color.alpha(col) * ma).toInt()
            paint.shader = RadialGradient(cx, cy, r, (a shl 24) or (col and 0xFFFFFF), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
        }
        paint.shader = null
        // 물결: 부드러울수록 잔잔
        wavePaint.color = ((Color.alpha(f.waveColor) * ma).toInt() shl 24) or (f.waveColor and 0xFFFFFF)
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
        if (f.master < 1f) c.drawColor(Color.argb(((1f - f.master) * 255).toInt(), 0, 0, 0))
        if (scene.animating && isAttachedToWindow) postInvalidateOnAnimation()
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
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    init { isClickable = true; isFocusable = true; contentDescription = "재생" }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
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
 * 원형 아이콘 버튼(얇은 선). glyph: 'A' = 마음 앨범(재킷에서 반쯤 나온 음반), 'D' = 디버그(점 3개), 'X' = 닫기, '<' = 뒤로.
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

/** 앨범 커버(격자·상세·호흡 화면). */
/** 앨범 커버: API 33+ 는 무드 화면과 같은 빛 셰이더(coverMode), 그 아래는 Canvas. */
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
        } else CoverPainter.draw(c, s, (width - side) / 2, (height - side) / 2, side, 1f, s.layers.size, 1f, null)
    }
}

/**
 * 커버 그리기(무드 배경·앨범 공용).
 * 겹마다: 둥근 원(부드러운 구간) + 빗줄기(비 온 구간) + 물결이 흐트러진 자국(깨진 순간).
 * revealFrom 이후 겹은 reveal(0~1)만큼만 그린다: 원이 자라고, 빗줄기가 내려오고, 자국이 번진다. 새 겹 둘레엔 잠깐 빛 고리.
 * fadeTo != null 이면 가장자리를 그 색으로 스며들게(무드 배경용).
 */
object CoverPainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun a(color: Int, alpha: Float) = ((Color.alpha(color) * alpha).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
    private fun easeOut(k: Float) = 1f - (1f - k) * (1f - k) * (1f - k)

    fun draw(c: Canvas, s: CoverSpec, x0: Float, y0: Float, side: Float, alpha: Float, revealFrom: Int, reveal: Float, fadeTo: Int?) {
        fun X(v: Float) = x0 + v * side
        fun Y(v: Float) = y0 + v * side
        p.style = Paint.Style.FILL; p.shader = null
        if (fadeTo == null) {
            p.color = a(s.background or 0xFF000000.toInt(), alpha)
            c.drawRect(x0, y0, x0 + side, y0 + side, p)
        } else { // 무드 배경: 네모 테두리 없이 둥글게 번지는 바탕색
            p.shader = RadialGradient(X(0.5f), Y(0.5f), side * 0.5f, intArrayOf(a(s.background or 0xFF000000.toInt(), alpha * 0.4f), a(s.background or 0xFF000000.toInt(), alpha * 0.25f), 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(x0, y0, x0 + side, y0 + side, p); p.shader = null
        }
        p.shader = RadialGradient(X(0.5f), Y(0.5f), side * 0.7f, a(s.glow, alpha), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(x0, y0, x0 + side, y0 + side, p); p.shader = null
        val lum = (Color.red(s.background) * 0.299 + Color.green(s.background) * 0.587 + Color.blue(s.background) * 0.114) / 255
        val rainColor = if (lum > 0.5) 0x993A5A7A.toInt() else 0x88BFD9FF.toInt()
        // 바탕 물결(잔잔/출렁임)
        p.style = Paint.Style.STROKE; p.strokeWidth = side * 0.008f; p.strokeCap = Paint.Cap.ROUND
        p.color = a(0x66FFFFFF, alpha)
        path.reset(); path.moveTo(X(0f), Y(0.8f))
        var xx = 0f
        while (xx <= 1f) { path.lineTo(X(xx), Y(0.8f + 0.05f * s.wave * sin(2 * PI * xx * 3).toFloat())); xx += 1f / 48 }
        c.drawPath(path, p)
        s.layers.forEachIndexed { i, l ->
            val k = if (i < revealFrom) 1f else reveal
            if (k <= 0f) return@forEachIndexed
            val grow = easeOut(k)
            // 둥근 원
            p.style = Paint.Style.FILL
            p.color = a((l.color and 0xFFFFFF) or 0x88000000.toInt(), alpha * k)
            c.drawCircle(X(l.x), Y(l.y), l.r * side * grow, p)
            if (i >= revealFrom && k < 1f) { // 새 겹 둘레 빛 고리
                p.style = Paint.Style.STROKE; p.strokeWidth = side * 0.006f
                p.color = a(0xCCFFFFFF.toInt(), alpha * (1f - k))
                c.drawCircle(X(l.x), Y(l.y), l.r * side * (1f + 0.35f * k), p)
            }
            // 빗줄기: 원 둘레에서 위→아래로 내려온다
            if (l.rain > 0) {
                p.style = Paint.Style.STROKE; p.strokeWidth = side * 0.005f
                p.color = a(rainColor, alpha * k)
                val rnd = java.util.Random(l.seed.toLong())
                repeat(l.rain) {
                    val rx = l.x + (rnd.nextFloat() - 0.5f) * 0.5f
                    val ry = l.y + (rnd.nextFloat() - 0.6f) * 0.5f
                    val len = 0.07f * grow
                    c.drawLine(X(rx), Y(ry), X(rx - 0.025f * grow), Y(ry + len), p)
                }
            }
            // 깨진 순간(앨범 커버만): 바탕 물결이 그 자리에서 한 번 부드럽게 출렁이며 흩어진 자국
            if (l.scars > 0 && fadeTo == null) {
                p.style = Paint.Style.STROKE; p.strokeWidth = side * (0.006f + 0.004f * l.scars)
                p.color = a(0x55F2E6D8, alpha * k)
                val half = 0.12f * grow
                path.reset(); path.moveTo(X(l.x - half), Y(0.8f))
                var q = -half
                while (q <= half) {
                    val e = kotlin.math.exp(-(q / (half * 0.5f)) * (q / (half * 0.5f)))
                    path.lineTo(X(l.x + q), Y(0.8f + 0.03f * e * sin(q / half * PI * 1.3).toFloat())); q += half / 24
                }
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
        // 무드 배경: 가장자리를 배경색으로 스며들게
        if (fadeTo != null) {
            p.shader = RadialGradient(X(0.5f), Y(0.5f), side * 0.7f, intArrayOf(fadeTo and 0x00FFFFFF, fadeTo and 0x00FFFFFF, fadeTo or 0xFF000000.toInt()), floatArrayOf(0f, 0.45f, 0.71f), Shader.TileMode.CLAMP)
            c.drawRect(x0, y0, x0 + side, y0 + side, p); p.shader = null
        }
    }
}
