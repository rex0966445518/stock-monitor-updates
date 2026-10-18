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
 * Short, user-consented capture session. Never records unattended or uploads to a server.
 * On Android 14+ this creates exactly one virtual display per permission token.
 */
class ChipKCaptureService : Service() {
    companion object {
        const val EXTRA_CONSENT_CODE = "capture_result_code"
        const val EXTRA_CONSENT_DATA = "capture_result_intent"
        const val EXTRA_STOCK_CODE = "capture_stock_code"
        private const val CHANNEL = "chipk_capture"
        private const val RUNNING_ID = 4507
        private const val RESULT_ID = 4508
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var images: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var begunAt = 0L
    private var finished = false
    private var stockCode = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "籌碼K線截圖", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaProjection != null || finished) return START_NOT_STICKY
        stockCode = intent?.getStringExtra(EXTRA_STOCK_CODE).orEmpty()
        if (!stockCode.matches(Regex("[0-9]{4,6}"))) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_CONSENT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val token = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_CONSENT_DATA, Intent::class.java)
        } else intent?.getParcelableExtra<Intent>(EXTRA_CONSENT_DATA)

        try {
            val note = notification("正在擷取 $stockCode：等待籌碼K線畫面就緒", true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(RUNNING_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(RUNNING_ID, note)

            requireNotNull(token) { "沒有畫面擷取權杖" }
            require(resultCode == android.app.Activity.RESULT_OK) { "未授權畫面擷取" }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, token)
            val projection = requireNotNull(mediaProjection)
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!finished) finishCapture(false, "畫面擷取已由 Android 或使用者停止，未保存截圖")
                }
            }
            projection.registerCallback(requireNotNull(projectionCallback), handler)

            val dimensions = if (Build.VERSION.SDK_INT >= 30) {
                val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val dm = resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
            val width = dimensions.first.coerceAtLeast(1)
            val height = dimensions.second.coerceAtLeast(1)
            images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            display = projection.createVirtualDisplay(
                "ChipKAuthorizedCapture", width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                requireNotNull(images).surface, null, handler
            )
            begunAt = System.currentTimeMillis()
            handler.postDelayed(captureTick, 1000)
        } catch (e: Exception) {
            finishCapture(false, "截圖無法啟動：" + (e.message ?: "系統限制"))
        }
        return START_NOT_STICKY
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (finished) return
            val elapsed = System.currentTimeMillis() - begunAt
            // Existing opt-in ChipK accessibility helper clears this request on navigation complete.
            // Wait at least 12s to give the user time to select the right screenshot content.
            val stillSearching = ChipKSearchRequest.get(this@ChipKCaptureService) != null
            if (elapsed < 12_000 || (stillSearching && elapsed < 45_000)) {
                handler.postDelayed(this, 1000)
                return
            }
            val image = images?.acquireLatestImage()
            if (image == null) {
                if (elapsed < 50_000) handler.postDelayed(this, 1000)
                else finishCapture(false, "尚未取得畫面，請確認系統螢幕分享權限")
                return
            }
            try {
                val width = image.width
                val height = image.height
                val plane = image.planes[0]
                val bytesPerPixel = plane.pixelStride
                val paddedWidth = plane.rowStride / bytesPerPixel
                require(bytesPerPixel >= 4 && paddedWidth >= width) { "不支援的畫面像素格式" }
                val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                plane.buffer.rewind()
                paddedBitmap.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
                paddedBitmap.recycle()
                val dir = File(cacheDir, "chipk").apply { mkdirs() }
                val file = File(dir, "chipk-$stockCode-" + System.currentTimeMillis() + ".jpg")
                file.outputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "無法寫入圖片" }
                }
                bitmap.recycle()
                // Keep only a limited number of local screenshots to avoid filling storage.
                dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    ?.drop(20)?.forEach { it.delete() }
                getSharedPreferences("chipk-gemini", 0).edit()
                    .putString("capture", file.name).putString("capture_stock", stockCode)
                    .putString("stock", stockCode).commit()
                finishCapture(true, "已擷取 $stockCode：點擊檢查圖片並分享到 Gemini")
            } catch (e: Exception) {
                finishCapture(false, "畫面擷取失敗：" + (e.message ?: "未知錯誤"))
            } finally {
                image.close()
            }
        }
    }

    private fun notification(message: String, running: Boolean): Notification {
        val open = Intent(this, ChipKGeminiActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 4508, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (running) "Gemini 籌碼分析・擷取中" else "Gemini 籌碼分析・擷取結果")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setOngoing(running)
            .setAutoCancel(!running)
            .build()
    }

    private fun finishCapture(ok: Boolean, message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        display?.release()
        display = null
        images?.close()
        images = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RESULT_ID, notification(message, false))
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) {
            finished = true
            handler.removeCallbacksAndMessages(null)
            display?.release()
            images?.close()
            mediaProjection?.stop()
        }
        super.onDestroy()
    }
}
