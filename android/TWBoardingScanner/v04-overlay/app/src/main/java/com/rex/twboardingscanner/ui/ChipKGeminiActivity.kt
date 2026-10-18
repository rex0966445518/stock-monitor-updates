package com.rex.twboardingscanner.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * User-initiated capture workflow for their own ChipK session:
 * 1) authorize capture, 2) open the stock in ChipK, 3) preview the captured image,
 * 4) explicitly share with Gemini. No invisible screen upload or order trading.
 */
class ChipKGeminiActivity : AppCompatActivity() {
    private lateinit var stockInput: EditText
    private lateinit var statusView: TextView
    private lateinit var preview: ImageView
    private val prefs by lazy { getSharedPreferences("chipk-gemini", Context.MODE_PRIVATE) }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = validCode() ?: return@registerForActivityResult
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            statusView.text = "使用者已取消螢幕擷取授權；沒有擷取任何畫面。"
            return@registerForActivityResult
        }
        prefs.edit().putString("stock", code).apply()
        val command = Intent(this, ChipKCaptureService::class.java)
            .putExtra(ChipKCaptureService.EXTRA_CONSENT_CODE, result.resultCode)
            .putExtra(ChipKCaptureService.EXTRA_CONSENT_DATA, data)
            .putExtra(ChipKCaptureService.EXTRA_STOCK_CODE, code)
        val started = runCatching { ContextCompat.startForegroundService(this, command) }.isSuccess
        if (!started) {
            statusView.text = "未能啟動擷取服務，請檢查系統授權與背景限制。"
            return@registerForActivityResult
        }
        statusView.text = "已授權：正在切換籌碼K線，將在畫面就緒後自動截圖。完成後點擊系統通知預覽。"
        ChipKLauncher.open(this, code, "")
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val directory = File(cacheDir, "chipk").apply { mkdirs() }
            val target = File(directory, "imported-" + System.currentTimeMillis() + ".jpg")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "無法讀取圖片" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            // Imported file may be PNG/WebP; sniffed by Android when displaying; sharing uses image/*.
            prefs.edit().putString("capture", target.name).putString("stock", stockInput.text.toString()).apply()
            showLatest()
        }.onFailure {
            statusView.text = "讀取圖片失敗：" + (it.message ?: "未知錯誤")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Gemini 籌碼 AI 分析"
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(12, 18, 30))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        fun label(s: String, size: Float = 14f, color: Int = Color.rgb(212, 226, 241)): TextView =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                setPadding(0, pad / 3, 0, pad / 3)
            }
        fun button(s: String, click: () -> Unit): MaterialButton =
            MaterialButton(this).apply {
                text = s
                isAllCaps = false
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        root.addView(label("CHIPK × GOOGLE GEMINI", 21f, Color.rgb(109, 218, 244)))
        root.addView(label("先由你授權擷取籌碼K線畫面，確認圖片後再分享給 Gemini。這個版本不會在背景偷偷上傳截圖，也不會操作下單。", 13f))
        stockInput = EditText(this).apply {
            hint = "輸入股號，例如 2327"
            setText(prefs.getString("stock", ""))
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(stockInput)
        root.addView(button("① 開啟籌碼K線並搜尋股號") {
            validCode()?.let { code ->
                prefs.edit().putString("stock", code).apply()
                ChipKLauncher.open(this, code, "")
            }
        })
        root.addView(button("② 授權自動截圖（一次一檔）") {
            if (validCode() == null) return@button
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            capturePermission.launch(mgr.createScreenCaptureIntent())
        })
        root.addView(button("或者：選取已保存的籌碼K線截圖") {
            imagePicker.launch("image/*")
        })
        statusView = label("等待截圖。Android 會要求你同意擷取；截圖完成後通知列會顯示結果。", 13f, Color.rgb(158, 181, 203))
        root.addView(statusView)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 300).toInt())
            setBackgroundColor(Color.rgb(22, 32, 48))
        }
        root.addView(preview)
        root.addView(button("③ 分享截圖給 Google Gemini") { shareImage() })
        root.addView(button("複製專業分析指令") { copyPrompt() })
        root.addView(button("清除本機截圖") { clearLatest() })
        root.addView(label("說明：直接分享給 Gemini 仍需要你選擇 Gemini 並送出。要在本 App 內完全自動批次分析，須另接合法資料 API 與受保護的 Gemini API。", 12f, Color.rgb(163, 180, 200)))
        showLatest()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) showLatest()
    }

    private fun validCode(): String? {
        val code = stockInput.text.toString().trim()
        if (code.matches(Regex("[0-9]{4,6}"))) return code
        Toast.makeText(this, "請輸入 4～6 位股票代號", Toast.LENGTH_LONG).show()
        return null
    }

    private fun selectedFile(): File? {
        val name = prefs.getString("capture", "") ?: ""
        if (!name.matches(Regex("[a-zA-Z0-9_.-]+"))) return null
        return File(cacheDir, "chipk/$name").takeIf { it.isFile }
    }

    private fun showLatest() {
        val file = selectedFile()
        if (file == null) {
            preview.setImageDrawable(null)
            return
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val sample = maxOf(1, maxOf(options.outWidth, options.outHeight) / 1100)
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val bitmap = BitmapFactory.decodeFile(file.path, options)
        if (bitmap == null) {
            preview.setImageDrawable(null)
            statusView.text = "圖片無法解碼。請改選其他截圖。"
        } else {
            preview.setImageBitmap(bitmap)
            statusView.text = "截圖已保存於本機：" + file.name + "。請先檢查是否為正確股票畫面，再選擇分享。"
        }
    }

    private fun analysisText(): String? {
        val code = validCode() ?: return null
        return ChipKGeminiPrompt.forStock(code)
    }

    private fun copyPrompt() {
        val instruction = analysisText() ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("籌碼K線分析指令", instruction))
        Toast.makeText(this, "已複製 Gemini 分析指令", Toast.LENGTH_SHORT).show()
    }

    private fun shareImage() {
        val file = selectedFile()
        if (file == null) {
            Toast.makeText(this, "尚未有截圖，請先授權擷取或選取圖片", Toast.LENGTH_LONG).show()
            return
        }
        val text = analysisText() ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("籌碼K線分析指令", text))
        val uri = FileProvider.getUriForFile(this, packageName + ".posters", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newUri(contentResolver, "籌碼K線截圖", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "選擇 Google Gemini 分析（指令已複製）"))
    }

    private fun clearLatest() {
        selectedFile()?.delete()
        prefs.edit().remove("capture").apply()
        preview.setImageDrawable(null)
        statusView.text = "本機截圖已清除。"
    }
}
