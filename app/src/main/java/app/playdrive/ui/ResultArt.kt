package app.playdrive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/** 결과·기록 화면 그림(정차 중에만). 색: 남색 #0E1A3A·#1F2F5C, 포인트 청록 #3FD0C0, 실패 #E88A8A. */
object ResultColors {
    const val NAVY = 0xFF0E1A3A.toInt()
    const val CARD = 0xFF1F2F5C.toInt()
    const val CARD_DEEP = 0xFF16264E.toInt()
    const val TEAL = 0xFF3FD0C0.toInt()
    const val TEAL_DARK = 0xFF04342C.toInt()
    const val RED = 0xFFE88A8A.toInt()
    const val SUB = 0xFF9FB0C8.toInt()
    const val TEXT = 0xFFE8EEF6.toInt()
}

/** 메달: 받은 배지 = 청록으로 채운 원 + 고리 + 가운데 글자, 아직 = 회색 실루엣. */
class MedalView(context: Context, private val glyph: String, private val earned: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f; val r = s / 2f
        p.style = Paint.Style.FILL
        p.shader = if (earned) RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.3f, 0xFF7FE6DA.toInt(), 0xFF167A72.toInt(), Shader.TileMode.CLAMP) else null
        if (!earned) p.color = 0xFF263355.toInt()
        c.drawCircle(cx, cy, r * 0.96f, p); p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.07f; p.color = if (earned) 0xFFBDF4EC.toInt() else 0xFF3A4870.toInt()
        c.drawCircle(cx, cy, r * 0.93f, p)
        p.strokeWidth = r * 0.025f; p.color = if (earned) 0x88FFFFFF.toInt() else 0xFF33405F.toInt()
        c.drawCircle(cx, cy, r * 0.74f, p)
        tp.color = if (earned) ResultColors.TEXT else 0xFF55638A.toInt()
        tp.textSize = r * (if (glyph.length >= 3) 0.52f else if (glyph.length == 2) 0.62f else 0.8f)
        c.drawText(glyph, cx, cy - (tp.ascent() + tp.descent()) / 2, tp)
    }
}

/** 자물쇠(잠긴 장르) / 열린 자물쇠. */
class LockView(context: Context, private val open: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val x0 = (width - s) / 2; val y0 = (height - s) / 2
        val col = if (open) ResultColors.TEAL else ResultColors.SUB
        p.color = col; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f; p.strokeCap = Paint.Cap.ROUND
        val arc = RectF(x0 + s * 0.27f, y0 + s * 0.08f, x0 + s * 0.73f, y0 + s * 0.6f)
        if (open) c.drawArc(RectF(arc.left - s * 0.22f, arc.top, arc.right - s * 0.22f, arc.bottom), 180f, 180f, false, p) else c.drawArc(arc, 180f, 180f, false, p)
        if (!open) { c.drawLine(arc.left, arc.centerY(), arc.left, y0 + s * 0.45f, p); c.drawLine(arc.right, arc.centerY(), arc.right, y0 + s * 0.45f, p) }
        else c.drawLine(arc.left - s * 0.22f, arc.centerY(), arc.left - s * 0.22f, y0 + s * 0.45f, p)
        p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(x0 + s * 0.18f, y0 + s * 0.42f, x0 + s * 0.82f, y0 + s * 0.94f), s * 0.1f, s * 0.1f, p)
        p.color = ResultColors.CARD
        c.drawCircle(x0 + s * 0.5f, y0 + s * 0.64f, s * 0.07f, p)
    }
}
