package Com.hau.name

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Phong cach Sony: nen trang, vien den day, net ve phang, nhan cam (#FF6B00). */
private const val INK = Color.BLACK
private const val PAPER = Color.WHITE
private const val PRESSED = 0xFFE4E4E4.toInt()
private const val ORANGE = 0xFFFF6B00.toInt()
private const val ORANGE_DARK = 0xFFE05A00.toInt()

/** Nut tron vien den, icon tu ve (khong phu thuoc font nen khong bi o vuong loi). */
class IconButton(ctx: Context, private val kind: Int) : View(ctx) {
    var inverted = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    init { isClickable = true }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 2f * d
        fill.color = if (inverted) INK else if (isPressed) PRESSED else PAPER
        c.drawCircle(cx, cy, r, fill)
        line.color = INK; line.strokeWidth = 2.2f * d
        c.drawCircle(cx, cy, r, line)

        line.color = if (inverted) PAPER else INK
        line.strokeWidth = 3f * d
        if (kind == POWER) {
            val ri = r * 0.38f
            c.drawArc(RectF(cx - ri, cy - ri, cx + ri, cy + ri), -60f, 300f, false, line)
            c.drawLine(cx, cy - r * 0.46f, cx, cy - r * 0.04f, line)
        } else {
            val cap = RectF(cx - r * 0.14f, cy - r * 0.50f, cx + r * 0.14f, cy + r * 0.06f)
            c.drawRoundRect(cap, r * 0.14f, r * 0.14f, line)
            c.drawArc(RectF(cx - r * 0.30f, cy - r * 0.34f, cx + r * 0.30f, cy + r * 0.26f), 0f, 180f, false, line)
            c.drawLine(cx, cy + r * 0.26f, cx, cy + r * 0.46f, line)
            c.drawLine(cx - r * 0.16f, cy + r * 0.46f, cx + r * 0.16f, cy + r * 0.46f, line)
        }
    }

    companion object { const val POWER = 0; const val MIC = 1 }
}

/**
 * Vong dieu huong kieu remote Sony: vong tron vien den chia 4 huong + nut OK cam o giua,
 * khong con nut dinh/chong nhau. Giu mui ten de lap lai.
 */
class DPadView(ctx: Context, private val onKey: (String) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var down: String? = null
    private var repeater: Runnable? = null

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    private fun keyAt(x: Float, y: Float): String? {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f
        val dx = x - cx; val dy = y - cy
        val dist = hypot(dx, dy)
        if (dist > r) return null
        if (dist < r * 0.36f) return "ok"
        return if (abs(dx) > abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = keyAt(e.x, e.y) ?: return false
                down = k; invalidate()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onKey(k)
                if (k != "ok") {
                    val r = object : Runnable { override fun run() { onKey(k); ui.postDelayed(this, 110) } }
                    repeater = r
                    ui.postDelayed(r, 380)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeater?.let { ui.removeCallbacks(it) }
                repeater = null
                down = null; invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        repeater?.let { ui.removeCallbacks(it) }
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val R = min(width, height) / 2f - 2f * d
        val rOk = R * 0.36f
        val outer = RectF(cx - R, cy - R, cx + R, cy + R)

        p.style = Paint.Style.FILL; p.color = PAPER
        c.drawCircle(cx, cy, R, p)

        // Huong dang nhan: to xam nhat
        val start = when (down) { "up" -> -135f; "right" -> -45f; "down" -> 45f; "left" -> 135f; else -> null }
        if (start != null) { p.color = PRESSED; c.drawArc(outer, start, 90f, true, p) }

        // Vien ngoai + 4 duong cheo chia huong
        p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 2.2f * d
        c.drawCircle(cx, cy, R, p)
        p.strokeWidth = 1.5f * d
        val k = 0.7071f
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            c.drawLine(cx + sx * k * rOk, cy + sy * k * rOk, cx + sx * k * R, cy + sy * k * R, p)
        }

        // Nut OK o giua (nhan cam)
        p.style = Paint.Style.FILL; p.color = if (down == "ok") ORANGE_DARK else ORANGE
        c.drawCircle(cx, cy, rOk, p)
        p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 2.2f * d
        c.drawCircle(cx, cy, rOk, p)
        p.style = Paint.Style.FILL; p.color = INK
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = rOk * 0.62f; p.textAlign = Paint.Align.CENTER
        c.drawText("OK", cx, cy + p.textSize * 0.35f, p)

        // 4 mui ten tam giac
        val dist = R * 0.68f; val s = R * 0.11f
        fun tri(ax: Float, ay: Float, dx: Float, dy: Float) {
            // (dx,dy) la huong mui ten; (-dy,dx) la phuong ngang cua day tam giac
            val path = Path().apply {
                moveTo(ax + dx * s, ay + dy * s)
                lineTo(ax - dx * s * 0.6f - dy * s, ay - dy * s * 0.6f + dx * s)
                lineTo(ax - dx * s * 0.6f + dy * s, ay - dy * s * 0.6f - dx * s)
                close()
            }
            p.style = Paint.Style.FILL; p.color = INK
            c.drawPath(path, p)
        }
        tri(cx, cy - dist, 0f, -1f); tri(cx, cy + dist, 0f, 1f)
        tri(cx - dist, cy, -1f, 0f); tri(cx + dist, cy, 1f, 0f)
    }
}
