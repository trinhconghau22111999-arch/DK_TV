package Com.hau.name

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Nhap chu sang may tinh ngay tren man hinh chup (khung nam duoi khung chup) - logic cu cua
 * TextInputActivity duoc chuyen vao day.
 *
 * Co che dong bo (dam bao DU, khong sot chu):
 *  - [sentText] = noi dung may tinh dang co tu phien nhap nay. Moi lan o nhap doi, so sanh voi
 *    [sentText]: giu phan dau chung, XOA phan duoi cu (del) roi CHEN phan moi (ins).
 *  - Chi cap nhat [sentText] khi gui THANH CONG. Chua ket noi/rot mang thi TU GUI LAI (1.5s/lan).
 *  - Moi thao tac chay tuan tu tren 1 luong (executor) -> dung thu tu.
 */
class TextSyncController(
    private val activity: AppCompatActivity,
    private val editInput: EditText,
    private val textStatus: TextView,
    private val btnPaste: Button,
    private val btnClearBox: Button
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Chi doc/ghi tren [executor]. */
    private var sentText = ""
    @Volatile private var latestText = ""
    private var ignoreChanges = false
    private var retryScheduled = false
    @Volatile private var released = false

    init {
        editInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreChanges) return
                latestText = s?.toString() ?: ""
                submitSync(latestText)
            }
        })
        btnPaste.setOnClickListener { pasteFromClipboard() }
        btnClearBox.setOnClickListener { clearBoxOnly() }
        setConnected(false)
    }

    /** Chi cho GO / DAN / XOA O khi da ket noi may tinh thanh cong; chua ket noi thi khoa o nhap
     *  (chu da go truoc do van duoc giu va tu gui lai khi ket noi). */
    fun setConnected(connected: Boolean) {
        editInput.isEnabled = connected
        btnPaste.isEnabled = connected
        btnClearBox.isEnabled = connected
        val a = if (connected) 1f else 0.4f
        editInput.alpha = a
        btnPaste.alpha = a
        btnClearBox.alpha = a
        editInput.hint = if (connected) "Gõ ở đây…" else "Chưa kết nối - chưa gõ được"
        if (connected) {
            setStatus("Sẵn sàng")
        } else {
            editInput.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(editInput.windowToken, 0)
            setStatus("Kết nối máy tính thành công mới gõ được")
        }
    }

    private fun pasteFromClipboard() {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
        } catch (e: Exception) { null }
        if (text.isNullOrEmpty()) {
            Toast.makeText(activity, "Bộ nhớ tạm đang trống", Toast.LENGTH_SHORT).show()
            return
        }
        val editable = editInput.text
        val a = editInput.selectionStart.coerceAtLeast(0)
        val b = editInput.selectionEnd.coerceAtLeast(0)
        // Chen vao o nhap -> TextWatcher tu gui phan chen (nguyen van, giu xuong dong).
        editable.replace(minOf(a, b), maxOf(a, b), text)
        editInput.setSelection(editInput.text.length.coerceAtMost(minOf(a, b) + text.length))
        Toast.makeText(activity, "Đã dán ${text.length} ký tự", Toast.LENGTH_SHORT).show()
    }

    /** Lam trong o nhap tren dien thoai, KHONG gui thao tac xoa nao sang may tinh. */
    private fun clearBoxOnly() {
        ignoreChanges = true
        editInput.setText("")
        ignoreChanges = false
        latestText = ""
        executor.execute { sentText = "" }
        setStatus("Đã làm trống ô (chữ trên web giữ nguyên)")
    }

    private fun submitSync(target: String) {
        executor.execute { syncOnce(target) }
    }

    private fun syncOnce(target: String) {
        if (target == sentText) {
            setStatus("✓ Đã gửi")
            return
        }
        val old = sentText
        var p = 0
        val max = minOf(old.length, target.length)
        while (p < max && old[p] == target[p]) p++
        // Khong cat giua cap ky tu thay the (emoji)
        if (p > 0 && p < old.length && Character.isHighSurrogate(old[p - 1])) p--
        if (p > 0 && p < target.length && Character.isHighSurrogate(target[p - 1])) p--
        val del = old.codePointCount(p, old.length)
        val ins = target.substring(p)

        val sent = CameraStreamService.instance?.sendTextEditToAllViewers(del, ins) ?: 0
        if (sent > 0) {
            sentText = target
            setStatus("✓ Đã gửi (${target.length} ký tự)")
        } else {
            setStatus("⏳ Chưa kết nối máy tính - sẽ tự gửi lại khi kết nối")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        uiHandler.post {
            if (retryScheduled || released) return@post
            retryScheduled = true
            uiHandler.postDelayed({
                retryScheduled = false
                if (!released && !activity.isFinishing) submitSync(latestText)
            }, 1500L)
        }
    }

    private fun setStatus(msg: String) {
        activity.runOnUiThread { if (!released && !activity.isDestroyed) textStatus.text = msg }
    }

    fun release() {
        released = true
        uiHandler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }
}
