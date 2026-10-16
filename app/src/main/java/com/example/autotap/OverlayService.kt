package com.example.autotap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import java.io.File
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var button: Button? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    @Volatile private var running = false

    private val SCALE = 4            // downscale factor for matching speed
    private val THRESHOLD = 14       // mean abs gray difference; lower = stricter
    private val INTERVAL_MS = 700L
    private val COOLDOWN_MS = 2000L  // wait after a tap before looking again

    private var tw = 0; private var th = 0
    private var tpl = IntArray(0)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        loadTemplate()
        setupCapture()
        thread = HandlerThread("matcher").also { it.start() }
        handler = Handler(thread.looper)
        addButton()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("autotap", "AutoTap", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "autotap")
            .setContentTitle("AutoTap overlay running")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun loadTemplate() {
        val bmp = BitmapFactory.decodeFile(File(filesDir, "target.png").path) ?: return
        tw = maxOf(1, bmp.width / SCALE); th = maxOf(1, bmp.height / SCALE)
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, tw, th, true)
        val px = IntArray(tw * th); small.getPixels(px, 0, tw, 0, 0, tw, th)
        tpl = IntArray(tw * th) { gray(px[it]) }
    }

    private fun gray(c: Int) = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m); m.widthPixels to m.heightPixels
        }
    }

    private fun setupCapture() {
        val (w, h) = screenSize()
        val dpi = resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay("autotap", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private fun addButton() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        val b = Button(this).apply { text = "OFF"; setBackgroundColor(Color.parseColor("#CC444444")); setTextColor(Color.WHITE) }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > 12 || abs(e.rawY - sy) > 12) moved = true
                    if (moved) { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(b, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle(b)
            }
            true
        }
        wm.addView(b, lp); button = b
    }

    private fun toggle(b: Button) {
        running = !running
        b.text = if (running) "ON" else "OFF"
        b.setBackgroundColor(Color.parseColor(if (running) "#CC1B8F3A" else "#CC444444"))
        if (running) handler.post(loop) else handler.removeCallbacks(loop)
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            var delay = INTERVAL_MS
            try { if (scanAndTap()) delay = COOLDOWN_MS } catch (_: Exception) {}
            handler.postDelayed(this, delay)
        }
    }

    private fun scanAndTap(): Boolean {
        if (tpl.isEmpty()) return false
        val img = reader?.acquireLatestImage() ?: return false
        val fw: Int; val fh: Int; val frame: IntArray
        try {
            val p = img.planes[0]; val buf = p.buffer
            val rowStride = p.rowStride; val pix = p.pixelStride
            fw = img.width / SCALE; fh = img.height / SCALE
            frame = IntArray(fw * fh)
            for (y in 0 until fh) for (x in 0 until fw) {
                val o = (y * SCALE) * rowStride + (x * SCALE) * pix
                val r = buf.get(o).toInt() and 255; val g = buf.get(o + 1).toInt() and 255; val bl = buf.get(o + 2).toInt() and 255
                frame[y * fw + x] = (r * 30 + g * 59 + bl * 11) / 100
            }
        } finally { img.close() }
        if (fw < tw || fh < th) return false

        var best = Int.MAX_VALUE; var bx = -1; var by = -1
        val samples = ((tw + 1) / 2) * ((th + 1) / 2)
        val limit = THRESHOLD * samples
        var y = 0
        while (y <= fh - th) {
            var x = 0
            while (x <= fw - tw) {
                var sum = 0; var ty = 0
                loop@ while (ty < th) {
                    var tx = 0
                    while (tx < tw) {
                        sum += abs(frame[(y + ty) * fw + x + tx] - tpl[ty * tw + tx])
                        tx += 2
                    }
                    if (sum > minOf(best, limit)) break@loop
                    ty += 2
                }
                if (sum < best) { best = sum; bx = x; by = y }
                x += 2
            }
            y += 2
        }
        if (bx >= 0 && best <= limit) {
            val cx = (bx + tw / 2f) * SCALE; val cy = (by + th / 2f) * SCALE
            ClickService.instance?.tap(cx, cy)
            return true
        }
        return false
    }

    override fun onDestroy() {
        running = false
        try { handler.removeCallbacks(loop); thread.quitSafely() } catch (_: Exception) {}
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
