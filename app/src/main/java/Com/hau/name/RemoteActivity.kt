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
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var btnScreen: View

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

    /** Khung chu nhat bo goc: nen [fill], vien [strokeDp] mau den. */
    private fun box(fill: Int, radiusDp: Int, strokeDp: Float) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat()
        setStroke((strokeDp * resources.displayMetrics.density).toInt(), Color.BLACK)
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t; textSize = 10.5f; setTextColor(Color.BLACK); letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
    }

    private fun labeled(btn: View, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(btn, LinearLayout.LayoutParams(dp(78), dp(78)))
        addView(caption(label), LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(8) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        // Thanh he thong sang de hop nen trang
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(18), dp(20), dp(22))
        }

        // Dau trang: ten (chu hoa, gian chu) + trang thai ket noi
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "DK TV"; textSize = 15f; setTextColor(Color.BLACK); letterSpacing = 0.2f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        statusText = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 11.5f; letterSpacing = 0.04f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusDot = View(this)
        head.addView(statusDot, LinearLayout.LayoutParams(dp(11), dp(11)).also { it.rightMargin = dp(8) })
        head.addView(statusText)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(-1, dp(2)).also { it.topMargin = dp(12) })

        // Hang tren: tat man hinh + giong noi
        btnScreen = IconButton(this, IconButton.POWER).also {
            it.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tvScreenOn = !tvScreenOn
                send(JSONObject().put("action", "screen").put("on", tvScreenOn))
                refreshScreenButton()
            }
        }
        val btnMic = IconButton(this, IconButton.MIC).also {
            it.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                startVoice()
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        top.addView(labeled(btnScreen, "TẮT MÀN HÌNH"), LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(36) })
        top.addView(labeled(btnMic, "GIỌNG NÓI"), LinearLayout.LayoutParams(-2, -2))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(18) })
        refreshScreenButton()

        // Chuot cam ung: khung trang vien den
        val pad = FrameLayout(this).apply { background = box(Color.WHITE, 24, 2.2f) }
        pad.addView(TextView(this).apply {
            text = "CHUỘT CẢM ỨNG\nTrượt: di chuyển  ·  Chạm: nhấp  ·  2 ngón: cuộn"
            setTextColor(0xFF6B6B6B.toInt()); textSize = 12f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
        }, FrameLayout.LayoutParams(-1, -1))
        attachTouchpad(pad)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(18); it.bottomMargin = dp(18) })

        // Vong dieu huong 5 nut
        val dpad = DPadView(this) { k -> send(JSONObject().put("action", "key").put("key", k)) }
        root.addView(dpad, LinearLayout.LayoutParams(dp(250), dp(250)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        return root
    }

    /** Nut tat man hinh dao mau (den, icon trang) khi TV dang tat. */
    private fun refreshScreenButton() { (btnScreen as IconButton).inverted = !tvScreenOn }

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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên video YouTube hoặc tên kênh TV")
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
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF2BB673.toInt() else 0xFFE5322D.toInt())
            setStroke(dp(1), Color.BLACK)
        }
        statusText.text = if (on) "ĐÃ KẾT NỐI"
        else "CHƯA KẾT NỐI"
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
