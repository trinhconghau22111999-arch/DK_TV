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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) Haptics.tick(this)
        return super.onTouchEvent(e)
    }

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
        } else if (kind == REW || kind == FWD) {
            // Tua 10 giay: 2 tam giac cung huong + so "10" ben duoi
            fill.color = line.color
            val dir = if (kind == FWD) 1f else -1f
            val t = r * 0.22f
            for (k in 0..1) {
                val x = cx + dir * (k - 0.5f) * t * 1.7f
                val tri = Path().apply {
                    moveTo(x + dir * t, cy - r * 0.16f)
                    lineTo(x - dir * t * 0.8f, cy - r * 0.16f - t)
                    lineTo(x - dir * t * 0.8f, cy - r * 0.16f + t)
                    close()
                }
                c.drawPath(tri, fill)
            }
            fill.textSize = r * 0.40f; fill.textAlign = Paint.Align.CENTER
            fill.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            c.drawText("10", cx, cy + r * 0.62f, fill)
        } else if (kind == PLAY) {
            // Phat / dung trong 1 nut: tam giac + 2 vach
            fill.color = line.color
            val t = r * 0.30f
            val tx = cx - r * 0.24f
            c.drawPath(Path().apply {
                moveTo(tx + t, cy); lineTo(tx - t * 0.8f, cy - t * 1.1f); lineTo(tx - t * 0.8f, cy + t * 1.1f); close()
            }, fill)
            val bw = r * 0.10f
            c.drawRect(cx + r * 0.22f, cy - t * 1.0f, cx + r * 0.22f + bw, cy + t * 1.0f, fill)
            c.drawRect(cx + r * 0.46f, cy - t * 1.0f, cx + r * 0.46f + bw, cy + t * 1.0f, fill)
        } else if (kind == NEXT) {
            // Bai/video ke tiep: tam giac + vach dung
            fill.color = line.color
            val t = r * 0.30f
            val tx = cx - r * 0.08f
            c.drawPath(Path().apply {
                moveTo(tx + t, cy); lineTo(tx - t * 0.9f, cy - t * 1.1f); lineTo(tx - t * 0.9f, cy + t * 1.1f); close()
            }, fill)
            c.drawRect(cx + r * 0.34f, cy - t * 1.1f, cx + r * 0.34f + r * 0.11f, cy + t * 1.1f, fill)
        } else if (kind == FULL) {
            // Phong to: 4 goc khung huong ra ngoai
            val o = r * 0.50f; val l = r * 0.24f
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                val px = cx + sx * o; val py = cy + sy * o
                c.drawLine(px, py, px - sx * l, py, line)
                c.drawLine(px, py, px, py - sy * l, line)
            }
        } else if (kind == TOUCH) {
            // Cham: cham tron + 2 vong lan toa; dang khoa (inverted) them net gach cheo
            fill.color = line.color
            c.drawCircle(cx, cy, r * 0.10f, fill)
            line.strokeWidth = 2.6f * d
            c.drawCircle(cx, cy, r * 0.30f, line)
            c.drawCircle(cx, cy, r * 0.52f, line)
            if (inverted) {
                line.strokeWidth = 3.4f * d
                c.drawLine(cx - r * 0.58f, cy + r * 0.58f, cx + r * 0.58f, cy - r * 0.58f, line)
            }
        } else {
            val cap = RectF(cx - r * 0.14f, cy - r * 0.50f, cx + r * 0.14f, cy + r * 0.06f)
            c.drawRoundRect(cap, r * 0.14f, r * 0.14f, line)
            c.drawArc(RectF(cx - r * 0.30f, cy - r * 0.34f, cx + r * 0.30f, cy + r * 0.26f), 0f, 180f, false, line)
            c.drawLine(cx, cy + r * 0.26f, cx, cy + r * 0.46f, line)
            c.drawLine(cx - r * 0.16f, cy + r * 0.46f, cx + r * 0.16f, cy + r * 0.46f, line)
        }
    }

    companion object { const val POWER = 0; const val MIC = 1; const val TOUCH = 2; const val FULL = 3; const val REW = 4; const val FWD = 5; const val PLAY = 6; const val NEXT = 7 }
}
