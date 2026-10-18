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
import android.view.inputmethod.EditorInfo
import android.provider.Settings
import android.app.AlertDialog
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
    private lateinit var sevenStatus: TextView
    private lateinit var sevenList: LinearLayout
    private val batchStore by lazy { ChipKSevenPageStore(this) }
    private val prefs by lazy { getSharedPreferences("chipk-gemini", Context.MODE_PRIVATE) }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = validCode() ?: return@registerForActivityResult
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            statusView.text = "使用者已取消螢幕擷取授權；沒有擷取任何畫面。"
            return@registerForActivityResult
        }
        val batch = prefs.getString("capture_mode", "single") == "batch"
        if (!batch) prefs.edit().putString("stock", code).remove("capture").remove("capture_stock").apply()
        val command = Intent(this, ChipKCaptureService::class.java)
            .putExtra(ChipKCaptureService.EXTRA_CONSENT_CODE, result.resultCode)
            .putExtra(ChipKCaptureService.EXTRA_CONSENT_DATA, data)
            .putExtra(ChipKCaptureService.EXTRA_STOCK_CODE, code)
            .putExtra(ChipKCaptureService.EXTRA_BATCH, batch)
        val started = runCatching { ContextCompat.startForegroundService(this, command) }.isSuccess
        if (!started) {
            statusView.text = "未能啟動擷取服務，請檢查系統授權與背景限制。"
            return@registerForActivityResult
        }
        statusView.text = if (batch)
            "已授權 7 頁擷取：即將開啟籌碼K線。請保持手機亮屏，完成後點擊通知返回本頁。"
        else "已授權：正在切換籌碼K線，將在畫面就緒後自動截圖。完成後點擊系統通知預覽。"
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
            prefs.edit().putString("capture", target.name)
                .putString("capture_stock", stockInput.text.toString().trim())
                .putString("stock", stockInput.text.toString().trim()).apply()
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
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { startBatchConsent(); true } else false
            }
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
        root.addView(button("② 自動巡覽 7 頁並截圖（推薦）") { startBatchConsent() })
        root.addView(button("備用：只截一張圖片") {
            if (validCode() == null) return@button
            prefs.edit().putString("capture_mode", "single").apply()
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            capturePermission.launch(mgr.createScreenCaptureIntent())
        })
        root.addView(button("或者：選取已保存的籌碼K線截圖") {
            if (validCode() != null) imagePicker.launch("image/*")
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
        sevenStatus = label("七頁擷取尚未開始", 14f, Color.rgb(109,218,244))
        root.addView(sevenStatus)
        sevenList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sevenList)
        root.addView(button("更新 7 頁完成狀態／查看截圖") { showBatch() })
        root.addView(button("③ 分享已完成的多張截圖（手動選 Gemini）") { shareBatch() })
        root.addView(button("停止七頁擷取並保留已完成圖片") {
            if(batchStore.snapshot()?.active==true)
                startService(Intent(this, ChipKCaptureService::class.java).setAction(ChipKCaptureService.ACTION_CANCEL))
            showBatch()
        })
        root.addView(button("單張備用：分享截圖給 Gemini") { shareImage() })
        root.addView(button("複製專業分析指令") { copyPrompt() })
        root.addView(button("清除本機截圖") { clearLatest() })
        root.addView(label("說明：直接分享給 Gemini 仍需要你選擇 Gemini 並送出。要在本 App 內完全自動批次分析，須另接合法資料 API 與受保護的 Gemini API。", 12f, Color.rgb(163, 180, 200)))
        showLatest()
        showBatch()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) showLatest()
        if (::sevenStatus.isInitialized) showBatch()
    }


    private fun startBatchConsent() {
        val code = validCode() ?: return
        if (!ChipKSearchRequest.enabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("啟用籌碼K線搜尋輔助")
                .setMessage("自動切換 7 個頁面需要 Android 無障礙服務。只會讀取籌碼K線的股票搜尋、頁籤和畫面狀態，不會操作買賣。請先啟用「籌碼K線搜尋輔助」，返回後重新啟動。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往設定") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
            return
        }
        val existing = batchStore.snapshot()
        if (existing?.active == true) {
            Toast.makeText(this,"上一輪七頁擷取仍在執行；請先停止或等待完成",Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("stock",code).putString("capture_mode","batch").apply()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun showBatch() {
        if (!::sevenStatus.isInitialized || !::sevenList.isInitialized) return
        val current = batchStore.snapshot()
        sevenList.removeAllViews()
        if (current == null) {
            sevenStatus.text = "尚無七頁擷取紀錄"
            return
        }
        sevenStatus.text = "股票 ${current.code} · ${current.count}/7 已截圖 · ${current.failed} 頁失敗 · " +
            if (current.active) "執行中：第 ${current.index+1}/7 頁" else "狀態：${current.state}"
        current.pages.forEach { p ->
            val result = when(p.state) {
                "captured" -> "✓ 已截圖"
                "failed" -> "✕ 失敗"
                "ready" -> "◌ 畫面已定位"
                "navigating" -> "… 正在切換"
                else -> "待處理"
            }
            val accuracy = if(p.verification=="changed_unverified") "（畫面變動，需人工核對）" else ""
            val row = TextView(this).apply {
                text = "${p.page.number}. ${p.page.title}　$result$accuracy" +
                    if (p.error.isBlank()) "" else "\n　原因：${p.error}"
                setTextColor(if(p.state=="captured") Color.rgb(129,233,184)
                    else if(p.state=="failed") Color.rgb(255,162,145) else Color.rgb(194,209,225))
                textSize = 13f
                setPadding(4,12,4,12)
                if(p.state=="captured") {
                    setOnClickListener {
                        val f = File(batchStore.dirFor(current),p.file)
                        showPreviewFile(f, "${p.page.title}　${p.verification}")
                    }
                }
            }
            sevenList.addView(row)
        }
        if(!current.active && current.count > 0) {
            current.pages.firstOrNull { it.state=="captured" }?.let { first ->
                if(first.file.isNotBlank()) showPreviewFile(File(batchStore.dirFor(current), first.file),
                    "七頁截圖已保存；請逐張檢查內容")
            }
        }
    }

    private fun showPreviewFile(file: File, caption: String) {
        if (!file.isFile || !::preview.isInitialized)return
        val probe=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,probe)
        val opts=BitmapFactory.Options().apply {
            inSampleSize=maxOf(1,maxOf(probe.outWidth,probe.outHeight)/1200)
        }
        val image=BitmapFactory.decodeFile(file.path,opts)
        if(image!=null) {
            preview.setImageBitmap(image)
            statusView.text = caption
        }
    }

    private fun shareBatch() {
        val current = batchStore.snapshot()
        val files = current?.let { batchStore.capturedFiles(it) }.orEmpty()
        if (current == null || files.isEmpty()) {
            Toast.makeText(this,"還沒有可分享的七頁截圖",Toast.LENGTH_LONG).show()
            return
        }
        if(current.active) {
            Toast.makeText(this,"請等待七頁擷取完成，或先停止作業",Toast.LENGTH_LONG).show()
            return
        }
        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(this,packageName + ".posters",file)
        })
        val prompt = ChipKGeminiPrompt.forBatchStock(current.code, current.pages
            .map { it.page.title + "：" + it.state + " / " + it.verification }.joinToString("\n"))
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Gemini 七頁分析指令",prompt))
        val clip = ClipData.newUri(contentResolver,"籌碼K線多頁截圖",uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val send=Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type="image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT,prompt)
            clipData=clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        statusView.text = "已準備 ${files.size} 張圖片並複製分析指令。Gemini 不一定支援 Android 一次接收多張，請在接收端確認圖片與文字後送出。"
        startActivity(Intent.createChooser(send,"選擇多張截圖的接收 App"))
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
            val savedCode = prefs.getString("capture_stock", "").orEmpty()
            statusView.text = "截圖已保存於本機：" + file.name + "（標記股號：" + savedCode + "）。請確認畫面與輸入股號一致。"
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
        val capturedCode = prefs.getString("capture_stock", "").orEmpty()
        val requestedCode = stockInput.text.toString().trim()
        if (capturedCode != requestedCode) {
            Toast.makeText(this, "截圖標記股號 $capturedCode 與輸入股號 $requestedCode 不同，請重新擷取或選圖", Toast.LENGTH_LONG).show()
            return
        }
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
        prefs.edit().remove("capture").remove("capture_stock").apply()
        preview.setImageDrawable(null)
        statusView.text = "本機截圖已清除。"
    }
}
