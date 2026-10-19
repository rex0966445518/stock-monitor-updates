package com.rex.twboardingscanner.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import java.io.File

/**
 * One explicit MediaProjection consent/virtual display, up to seven successive page frames.
 * Only screenshots in the user's own ChipK session are captured. No silent cloud upload.
 */
class ChipKCaptureService : Service() {
    companion object {
        const val EXTRA_CONSENT_CODE = "capture_result_code"
        const val EXTRA_CONSENT_DATA = "capture_result_intent"
        const val EXTRA_STOCK_CODE = "capture_stock_code"
        const val EXTRA_BATCH = "capture_seven_pages"
        const val ACTION_CANCEL = "com.rex.twboardingscanner.STOP_CHIPK_CAPTURE"
        private const val CHANNEL = "chipk_capture"
        private const val RUNNING_ID = 4507
        private const val RESULT_ID = 4508
    }

    private val handler = Handler(Looper.getMainLooper())
    private val batchStore by lazy { ChipKSevenPageStore(this) }
    private var mediaProjection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var images: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var begunAt = 0L
    private var pageStartedAt = 0L
    private var observedIndex = -1
    private var lastPulseAt = 0L
    private var pageCaptureAttempts = 0
    private var finished = false
    private var batch = false
    private var stockCode = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(
                CHANNEL, "籌碼K線截圖", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (batch) batchStore.stop("使用者從通知列停止作業")
            finishCapture(false, "已停止擷取，已完成的圖片仍保存在手機")
            return START_NOT_STICKY
        }
        if (mediaProjection != null || finished) return START_NOT_STICKY
        stockCode = intent?.getStringExtra(EXTRA_STOCK_CODE).orEmpty()
        if (!stockCode.matches(Regex("[0-9]{4,6}"))) {
            stopSelf()
            return START_NOT_STICKY
        }
        batch = intent?.getBooleanExtra(EXTRA_BATCH, false) ?: false
        val resultCode = intent?.getIntExtra(EXTRA_CONSENT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val token = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_CONSENT_DATA, Intent::class.java)
        } else intent?.getParcelableExtra<Intent>(EXTRA_CONSENT_DATA)

        try {
            val note = notification("等待籌碼K線畫面，請保持手機亮屏", true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(RUNNING_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(RUNNING_ID, note)
            requireNotNull(token) { "沒有畫面擷取權杖" }
            require(resultCode == android.app.Activity.RESULT_OK) { "未授權畫面擷取" }
            if (batch) batchStore.start(stockCode)
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, token)
            val projection = requireNotNull(mediaProjection)
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!finished) finishCapture(false, "螢幕擷取已停止，未完成頁面不會假裝成功")
                }
            }
            projection.registerCallback(requireNotNull(projectionCallback), handler)

            val dimensions = if (Build.VERSION.SDK_INT >= 30) {
                val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val metrics = resources.displayMetrics
                Pair(metrics.widthPixels, metrics.heightPixels)
            }
            val width = dimensions.first.coerceAtLeast(1)
            val height = dimensions.second.coerceAtLeast(1)
            images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            // Android 14+ permits exactly ONE virtual display per consent token.
            display = projection.createVirtualDisplay(
                "ChipKUserConsentedPages", width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                requireNotNull(images).surface, null, handler
            )
            begunAt = System.currentTimeMillis()
            pageStartedAt = begunAt
            handler.postDelayed(tick, 650)
        } catch (e: Exception) {
            if(batch) batchStore.stop("擷取啟動失敗：" + (e.message ?: "系統限制"))
            finishCapture(false, "截圖無法啟動：" + (e.message ?: "系統限制"))
        }
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            if (batch) captureBatch() else captureSingle()
            if (!finished) handler.postDelayed(this, if(batch) 450 else 1000)
        }
    }

    private fun captureSingle() {
        val elapsed = System.currentTimeMillis() - begunAt
        val stillSearching = ChipKSearchRequest.get(this) != null
        if (elapsed < 12_000 || (stillSearching && elapsed < 45_000)) return
        val photo = runCatching { captureBitmap() }.getOrNull()
        if (photo == null) {
            if (elapsed >= 50_000) finishCapture(false, "尚未取得可用畫面")
            return
        }
        try {
            val dir = File(cacheDir, "chipk").apply { mkdirs() }
            val file = File(dir, "chipk-$stockCode-" + System.currentTimeMillis() + ".jpg")
            file.outputStream().use { output ->
                check(photo.bitmap.compress(Bitmap.CompressFormat.JPEG,92,output))
            }
            dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?.drop(20)?.forEach { it.delete() }
            getSharedPreferences("chipk-gemini", 0).edit()
                .putString("capture",file.name)
                .putString("capture_stock",stockCode).putString("stock",stockCode).commit()
            finishCapture(true, "已擷取 $stockCode 一張截圖：點此檢查圖片")
        } catch (e: Exception) {
            finishCapture(false,"圖片儲存失敗：" + (e.message ?: "未知錯誤"))
        } finally {
            photo.recycle()
        }
    }

    private fun captureBatch() {
        val session = batchStore.snapshot()
        if (session == null) {
            finishCapture(false,"找不到七頁擷取任務")
            return
        }
        if (!session.active) {
            finishCapture(true, "七頁任務完成：成功 ${session.count}/7、失敗 ${session.failed}，點此查看")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPulseAt >= 5_000) { batchStore.pulse(); lastPulseAt = now }
        val index = session.index
        val page = ChipKPage.at(index) ?: return
        if (index != observedIndex) {
            observedIndex = index
            pageCaptureAttempts = 0
            pageStartedAt = now
            updateForeground("第 ${index+1}/7 頁：正在定位「${page.title}」；已存 ${session.count} 張")
        }
        // Never hang if ChipK is logged out, hidden, accessibility disabled or OEM suspends it.
        if(now - pageStartedAt > 36_000) {
            batchStore.skip(index, "36 秒無法完成截圖，可能需要重新定位頁籤或重新授權")
            return
        }
        // Accessibility must have acknowledged THIS page and still be observing ChipK.
        if (session.readyIndex != index || session.readyAt == 0L ||
            now - session.readyAt < 1250 || now - session.seenAt > 1700) return

        val photo = runCatching { captureBitmap() }.getOrNull()
        if (photo == null) {
            pageCaptureAttempts++
            if (pageCaptureAttempts >= 7)
                batchStore.skip(index, "畫面不可讀或受 Android 螢幕保護限制")
            return
        }
        try {
            if (photo.isProbablyBlank()) {
                batchStore.skip(index, "截圖為空白畫面，可能受保護或畫面尚未載入")
                return
            }
            val file = batchStore.fileFor(session,page)
            file.outputStream().use { out ->
                check(photo.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
            }
            batchStore.complete(index,file,session.pages[index].verification)
            val progress=batchStore.snapshot()
            updateForeground("已保存「${page.title}」；${progress?.count ?: 0}/7 張")
        } catch (e:Exception) {
            batchStore.skip(index,"無法儲存「${page.title}」：" + (e.message ?: "未知錯誤"))
        } finally {
            photo.recycle()
        }
    }

    private data class ImageFrame(val bitmap: Bitmap) {
        fun isProbablyBlank(): Boolean {
            var min=255
            var max=0
            var samples=0
            for(y in 0 until bitmap.height step maxOf(1,bitmap.height/23)) {
                for(x in 0 until bitmap.width step maxOf(1,bitmap.width/23)) {
                    val px=bitmap.getPixel(x,y)
                    val brightness=(android.graphics.Color.red(px)+android.graphics.Color.green(px)+
                        android.graphics.Color.blue(px))/3
                    min=minOf(min,brightness)
                    max=maxOf(max,brightness)
                    samples++
                }
            }
            return samples>0 && (max-min)<10 && max<45
        }
        fun recycle() { bitmap.recycle() }
    }

    private fun captureBitmap(): ImageFrame? {
        val image = images?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            require(plane.pixelStride >= 4 && paddedWidth >= image.width)
            val padded = Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded,0,0,image.width,image.height)
            padded.recycle()
            ImageFrame(cropped)
        } finally {
            image.close()
        }
    }

    private fun updateForeground(message:String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RUNNING_ID, notification(message,true))
    }

    private fun notification(message: String, running: Boolean): Notification {
        val open = Intent(this, ChipKGeminiActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 4508, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if(running) "籌碼K線 AI・七頁擷取中" else "籌碼K線 AI・擷取結果")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setOngoing(running)
            .setAutoCancel(!running)
        if(running && batch) {
            val stop = Intent(this,ChipKCaptureService::class.java).setAction(ACTION_CANCEL)
            val cancel = PendingIntent.getService(this,4509,stop,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_delete,"停止並保存進度",cancel)
        }
        return builder.build()
    }

    private fun finishCapture(ok: Boolean, message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        if(batch && batchStore.snapshot()?.active==true)batchStore.stop(message)
        display?.release()
        display = null
        images?.close()
        images = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RESULT_ID,notification(message,false))
        stopSelf()
    }

    override fun onDestroy() {
        if(!finished) {
            finished=true
            handler.removeCallbacksAndMessages(null)
            if(batch) batchStore.stop("Android 服務被停止，已完成截圖保留")
            display?.release()
            images?.close()
            mediaProjection?.stop()
        }
        super.onDestroy()
    }
}
