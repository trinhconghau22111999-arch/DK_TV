package Com.hau.name

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tay cam dieu khien app TV (thay cho giao dien chup anh/gui tep cu - cai cu van con trong
 * code nhung khong con duoc mo).
 *
 * Moi lenh la 1 tin JSON {"type":"remote","action":...} gui qua DataChannel truyen tep co san
 * (cung ma co dinh 123456, cung ket noi WebRTC/Firebase) toi app TV:
 *   - key    : key = up | down | left | right | ok
 *   - move   : dx, dy (chuot cam ung, di chuyen tuong doi)
 *   - click  : cham 1 lan tren touchpad
 *   - scroll : dy (2 ngon tay truot)
 *   - screen : on = false (tat man hinh TV) / true (bat lai)
 *   - voice  : q = cau noi da nhan dang tren dien thoai (TV mo YouTube tim va mo video)
 */
class RemoteActivity : AppCompatActivity(), CameraStreamService.ConnectionStatusListener {

    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnScreen: TextView

    private var attachedService: CameraStreamService? = null
    private var connected = false
    private var tvScreenOn = true
    private var lastWarnAt = 0L

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val q = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!q.isNullOrBlank()) send(JSONObject().put("action", "voice").put("q", q.trim()))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int = 0, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        // Back chi dua app xuong nen (dich vu ket noi van chay), khong thoat han.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        setConnected(false)
    }

    // ---------------------------------------------------------------- UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        // Thanh trang thai nho: cham xanh/do + chu
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).also { it.rightMargin = dp(8) })
        statusText = TextView(this).apply { setTextColor(0xFFA6A6AD.toInt()); textSize = 13f }
        statusRow.addView(statusText)
        root.addView(statusRow, LinearLayout.LayoutParams(-1, -2))

        // Hang tren: nut tat man hinh + mic
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnScreen = circleButton("⏻", 0xFF3A1414.toInt(), 26f).also {
            it.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tvScreenOn = !tvScreenOn
                send(JSONObject().put("action", "screen").put("on", tvScreenOn))
                refreshScreenButton()
            }
        }
        val btnMic = circleButton("🎤", 0xFF14253A.toInt(), 26f).also {
            it.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                startVoice()
            }
        }
        top.addView(btnScreen, LinearLayout.LayoutParams(dp(72), dp(72)).also { it.rightMargin = dp(40) })
        top.addView(btnMic, LinearLayout.LayoutParams(dp(72), dp(72)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(14) })
        refreshScreenButton()

        // Chuot cam ung (chiem phan giua)
        val pad = FrameLayout(this).apply { background = round(0xFF17171A.toInt(), 20) }
        pad.addView(TextView(this).apply {
            text = "Chuột cảm ứng\nTrượt: di chuyển · Chạm: nhấp · 2 ngón: cuộn"
            setTextColor(0xFF6E6E75.toInt()); textSize = 13f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        attachTouchpad(pad)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(14); it.bottomMargin = dp(14) })

        // Cum 5 nut: tren / duoi / trai / phai / OK o giua
        val size = dp(76)
        fun cell(v: View?) = FrameLayout(this).also { f ->
            v?.let { f.addView(it, FrameLayout.LayoutParams(size, size, Gravity.CENTER)) }
        }
        fun row(a: View?, b: View?, c: View?) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cell(a), LinearLayout.LayoutParams(0, size, 1f))
            addView(cell(b), LinearLayout.LayoutParams(0, size, 1f))
            addView(cell(c), LinearLayout.LayoutParams(0, size, 1f))
        }
        val up = keyButton("▲", "up", true)
        val down = keyButton("▼", "down", true)
        val left = keyButton("◀", "left", true)
        val right = keyButton("▶", "right", true)
        val ok = keyButton("OK", "ok", false).also {
            it.background = round(0xFFFF6B00.toInt(), 0, oval = true)
            it.setTextColor(Color.BLACK)
        }
        val dpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(null, up, null), LinearLayout.LayoutParams(-1, size))
            addView(row(left, ok, right), LinearLayout.LayoutParams(-1, size))
            addView(row(null, down, null), LinearLayout.LayoutParams(-1, size))
        }
        root.addView(dpad, LinearLayout.LayoutParams(-1, -2).also { it.leftMargin = dp(24); it.rightMargin = dp(24) })
        return root
    }

    private fun circleButton(label: String, bg: Int, sp: Float) = TextView(this).apply {
        text = label; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = round(bg, 0, oval = true)
        isClickable = true
    }

    private fun refreshScreenButton() {
        btnScreen.background = round(if (tvScreenOn) 0xFF3A1414.toInt() else 0xFFB3261E.toInt(), 0, oval = true)
        btnScreen.alpha = if (tvScreenOn) 1f else 0.95f
    }

    /** Nut mui ten/OK. [repeat]=true: giu de lap lai (cho 4 mui ten). */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyButton(label: String, key: String, repeat: Boolean) = TextView(this).apply {
        text = label; textSize = if (key == "ok") 20f else 22f
        setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        background = round(0xFF26262B.toInt(), 0, oval = true)
        val fire = { send(JSONObject().put("action", "key").put("key", key)) }
        var repeater: Runnable? = null
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.alpha = 0.6f
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    fire()
                    if (repeat) {
                        val r = object : Runnable { override fun run() { fire(); ui.postDelayed(this, 110) } }
                        repeater = r
                        ui.postDelayed(r, 380)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.alpha = 1f
                    repeater?.let { ui.removeCallbacks(it) }
                    repeater = null
                    true
                }
                else -> true
            }
        }
    }

    // ---------------------------------------------------------------- Touchpad

    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downAt = 0L
    private var moved = false
    private var multi = false
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendScroll = 0f
    private var flushScheduled = false

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchpad(pad: View) {
        pad.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; startX = e.x; startY = e.y
                    downAt = e.eventTime; moved = false; multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    lastY = avgY(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val y = avgY(e)
                        pendScroll += (y - lastY) * SCROLL_GAIN
                        lastY = y
                        scheduleFlush()
                    } else if (!multi) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        lastX = e.x; lastY = e.y
                        if (!moved && (abs(e.x - startX) > dp(6) || abs(e.y - startY) > dp(6))) moved = true
                        pendDx += dx * MOVE_GAIN
                        pendDy += dy * MOVE_GAIN
                        scheduleFlush()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Con 1 ngon sau khi nha ngon thu 2: khong gay nhay con tro
                    val idx = if (e.actionIndex == 0) 1 else 0
                    if (idx < e.pointerCount) { lastX = e.getX(idx); lastY = e.getY(idx) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !multi && e.eventTime - downAt < TAP_MS) {
                        pad.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        send(JSONObject().put("action", "click"))
                    }
                }
            }
            true
        }
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    /** Gop cac cu truot nho thanh toi da ~60 tin/giay de khong nghen kenh truyen. */
    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        ui.postDelayed({
            flushScheduled = false
            val mx = pendDx.roundToInt(); val my = pendDy.roundToInt()
            val sc = pendScroll.roundToInt()
            if (mx != 0 || my != 0) {
                pendDx -= mx; pendDy -= my
                send(JSONObject().put("action", "move").put("dx", mx).put("dy", my), warn = false)
            }
            if (sc != 0) {
                pendScroll -= sc
                send(JSONObject().put("action", "scroll").put("dy", sc), warn = false)
            }
        }, 16)
    }

    // ---------------------------------------------------------------- Mic

    private fun startVoice() {
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên video muốn mở trên TV")
        }
        try { voiceLauncher.launch(it) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Điện thoại chưa có ứng dụng nhận dạng giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- Gui lenh

    private fun send(cmd: JSONObject, warn: Boolean = true) {
        val json = cmd.put("type", "remote").toString()
        sender.execute {
            val n = CameraStreamService.instance?.sendRemoteCommand(json) ?: 0
            if (n == 0 && warn) {
                val now = System.currentTimeMillis()
                if (now - lastWarnAt > 3000) {
                    lastWarnAt = now
                    ui.post { Toast.makeText(this, "Chưa kết nối với TV", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Trang thai ket noi

    override fun onConnectionStatusChanged(connectedCount: Int, maxViewers: Int) {
        setConnected(connectedCount > 0)
    }

    private fun setConnected(on: Boolean) {
        connected = on
        statusDot.background = round(if (on) 0xFF3DDC84.toInt() else 0xFFFF5A5A.toInt(), 0, oval = true)
        statusText.text = if (on) "Đã kết nối TV · Mã ${CameraActivity.FIXED_CODE}"
        else "Chưa kết nối TV · Mã ${CameraActivity.FIXED_CODE}"
    }

    /** Service co the chua kip khoi dong luc mo man hinh: thu gan lai moi giay cho toi khi co. */
    private val attachLoop = object : Runnable {
        override fun run() {
            val svc = CameraStreamService.instance
            if (svc !== attachedService) {
                attachedService?.removeConnectionStatusListener(this@RemoteActivity)
                attachedService = svc
                svc?.addConnectionStatusListener(this@RemoteActivity)
                if (svc == null) setConnected(false)
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(attachLoop)
    }

    override fun onPause() {
        ui.removeCallbacks(attachLoop)
        attachedService?.removeConnectionStatusListener(this)
        attachedService = null
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        sender.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MOVE_GAIN = 1.8f
        private const val SCROLL_GAIN = 1.0f
        private const val TAP_MS = 250L
    }
}
