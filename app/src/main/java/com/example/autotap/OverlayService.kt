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
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import java.io.File
import kotlin.math.abs

class OverlayService : Service() {
    // ---- tuning ----
    private val THRESHOLD = 22      // accept if mean gray difference <= this (0 = identical). Raise if it never matches.
    private val COARSE_LIMIT = 50   // loose pre-filter, leave alone
    private val INTERVAL_MS = 600L  // time between scans
    private val COOLDOWN_MS = 2500L // pause after a tap
    private val DS = 4              // coarse downscale
    private val TAG = "AutoTap"

    private lateinit var wm: WindowManager
    private val overlayViews = mutableListOf<Button>()
    private var statusBtn: Button? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var autoScroll = false
    private var misses = 0

    private var sw = 0; private var sh = 0
    private var frame: IntArray? = null     // full-res grayscale of latest screen
    private var haveFrame = false
    private var tw0 = 0; private var th0 = 0
    private var tg = IntArray(0)            // template gray, full-res
    private var tDs = IntArray(0); private var tdw = 0; private var tdh = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        val okTpl = loadTemplate()
        setupCapture()
        thread = HandlerThread("matcher").also { it.start() }
        handler = Handler(thread!!.looper)
        addButtons(if (okTpl) "OFF" else "NO IMG")
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

    private fun gray(c: Int) = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100

    /** Box-average downscale by DS. */
    private fun ds(src: IntArray, w: Int, h: Int): Triple<IntArray, Int, Int> {
        val nw = w / DS; val nh = h / DS
        val out = IntArray(maxOf(0, nw * nh))
        val div = DS * DS
        for (y in 0 until nh) for (x in 0 until nw) {
            var s = 0
            val base = (y * DS) * w + x * DS
            for (dy in 0 until DS) { val o = base + dy * w; for (dx in 0 until DS) s += src[o + dx] }
            out[y * nw + x] = s / div
        }
        return Triple(out, nw, nh)
    }

    private fun loadTemplate(): Boolean {
        val bmp = BitmapFactory.decodeFile(File(filesDir, "target.png").path) ?: return false
        tw0 = bmp.width; th0 = bmp.height
        val px = IntArray(tw0 * th0); bmp.getPixels(px, 0, tw0, 0, 0, tw0, th0)
        tg = IntArray(px.size) { gray(px[it]) }
        val t = ds(tg, tw0, th0)
        tDs = t.first; tdw = t.second; tdh = t.third
        return tdw >= 2 && tdh >= 2
    }

    private fun screenSize(): Pair<Int, Int> {
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

    // ---------- overlay buttons ----------
    private fun makeButton(label: String, startY: Int, onClick: (Button) -> Unit): Button {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = startY }
        val b = Button(this).apply {
            text = label; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC444444"))
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > 12 || abs(e.rawY - sy) > 12) moved = true
                    if (moved) { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(b, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onClick(b)
            }
            true
        }
        wm.addView(b, lp); overlayViews.add(b)
        return b
    }

    private fun addButtons(initial: String) {
        statusBtn = makeButton(initial, 300) { b ->
            running = !running
            if (running) {
                misses = 0; b.text = "ON"; b.setBackgroundColor(Color.parseColor("#CC1B8F3A"))
                handler?.removeCallbacks(loop); handler?.post(loop)
            } else {
                b.text = "OFF"; b.setBackgroundColor(Color.parseColor("#CC444444"))
                handler?.removeCallbacks(loop)
            }
        }
        makeButton("SCR OFF", 420) { b ->
            autoScroll = !autoScroll
            b.text = if (autoScroll) "SCR ON" else "SCR OFF"
            b.setBackgroundColor(Color.parseColor(if (autoScroll) "#CC1B6BC4" else "#CC444444"))
        }
    }

    private fun status(t: String) { ui.post { if (running) statusBtn?.text = t } }

    // ---------- scanning ----------
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            var delay = INTERVAL_MS
            try {
                if (!updateFrame()) {
                    status("no frame")
                } else {
                    val r = match()
                    if (r == null) status("bad img")
                    else if (r.first <= THRESHOLD) {
                        status("TAP ${r.first}")
                        val x = r.second.toFloat(); val y = r.third.toFloat()
                        ui.post {
                            val svc = ClickService.instance
                            if (svc == null) status("NO A11Y") else svc.tap(x, y) { ok -> if (!ok) status("TAP FAIL") }
                        }
                        misses = 0; delay = COOLDOWN_MS
                    } else {
                        status(if (isBlack()) "BLACK" else "ON ${r.first}")
                        misses++
                        if (autoScroll && misses >= 2) {
                            misses = 0; delay = 1200L
                            val sx = sw / 2f
                            ui.post { ClickService.instance?.swipe(sx, sh * 0.75f, sx, sh * 0.35f, 400) }
                        }
                    }
                }
            } catch (e: Throwable) { Log.e(TAG, "scan failed", e); status("ERR") }
            handler?.postDelayed(this, delay)
        }
    }

    /** Pull newest screen frame into [frame]. A static screen delivers no new image, so keep the old one. */
    private fun updateFrame(): Boolean {
        val img = reader?.acquireLatestImage()
        if (img != null) {
            try {
                val p = img.planes[0]; val buf = p.buffer
                val rs = p.rowStride; val ps = p.pixelStride
                val w = img.width; val h = img.height
                if (frame == null || sw != w || sh != h) { sw = w; sh = h; frame = IntArray(w * h) }
                val f = frame!!
                val row = ByteArray(rs)
                for (y in 0 until h) {
                    buf.position(y * rs)
                    buf.get(row, 0, minOf(rs, buf.remaining()))
                    var o = 0; val base = y * w
                    for (x in 0 until w) {
                        val r = row[o].toInt() and 255; val g = row[o + 1].toInt() and 255; val b = row[o + 2].toInt() and 255
                        f[base + x] = (r * 30 + g * 59 + b * 11) / 100
                        o += ps
                    }
                }
                haveFrame = true
            } finally { img.close() }
        }
        return haveFrame
    }

    private fun isBlack(): Boolean {
        val f = frame ?: return true
        var mx = 0; var i = 0
        val step = maxOf(1, f.size / 500)
        while (i < f.size) { if (f[i] > mx) mx = f[i]; i += step }
        return mx <= 2
    }

    /** Returns (score, centerX, centerY). Lower score = better. null if template unusable. */
    private fun match(): Triple<Int, Int, Int>? {
        val f = frame ?: return null
        if (tw0 > sw || th0 > sh || tdw < 2 || tdh < 2) return null
        val (fd, fdw, fdh) = ds(f, sw, sh)
        if (tdw > fdw || tdh > fdh) return null

        // 1) coarse search on box-averaged images, keep best 6 spots
        val cands = ArrayList<IntArray>()
        val baseLimit = COARSE_LIMIT * tdw * tdh
        for (y in 0..fdh - tdh) {
            for (x in 0..fdw - tdw) {
                var limit = baseLimit
                if (cands.size >= 6) limit = minOf(limit, cands[cands.size - 1][0])
                var sum = 0; var ty = 0; var dead = false
                while (ty < tdh) {
                    val fo = (y + ty) * fdw + x; val to = ty * tdw
                    for (tx in 0 until tdw) sum += abs(fd[fo + tx] - tDs[to + tx])
                    if (sum > limit) { dead = true; break }
                    ty++
                }
                if (dead) continue
                var merged = false
                for (c in cands) {
                    if (abs(c[1] - x) <= tdw / 2 && abs(c[2] - y) <= tdh / 2) {
                        if (sum < c[0]) { c[0] = sum; c[1] = x; c[2] = y }
                        merged = true; break
                    }
                }
                if (!merged) cands.add(intArrayOf(sum, x, y))
                cands.sortBy { it[0] }
                while (cands.size > 6) cands.removeAt(cands.size - 1)
            }
        }
        if (cands.isEmpty()) return Triple(255, -1, -1)

        // 2) refine at full resolution around each candidate (+-6 px)
        val samples = ((tw0 + 1) / 2) * ((th0 + 1) / 2)
        var bestSum = Int.MAX_VALUE; var bx = -1; var by = -1
        for (c in cands) {
            val cx = c[1] * DS; val cy = c[2] * DS
            for (dy in -6..6) for (dx in -6..6) {
                val x = cx + dx; val y = cy + dy
                if (x < 0 || y < 0 || x + tw0 > sw || y + th0 > sh) continue
                var sum = 0; var ty = 0
                while (ty < th0 && sum < bestSum) {
                    val fo = (y + ty) * sw + x; val to = ty * tw0
                    var tx = 0
                    while (tx < tw0) { sum += abs(f[fo + tx] - tg[to + tx]); tx += 2 }
                    ty += 2
                }
                if (sum < bestSum) { bestSum = sum; bx = x; by = y }
            }
        }
        if (bx < 0) return Triple(255, -1, -1)
        return Triple(bestSum / samples, bx + tw0 / 2, by + th0 / 2)
    }

    override fun onDestroy() {
        running = false
        try { handler?.removeCallbacks(loop); thread?.quitSafely() } catch (_: Exception) {}
        for (v in overlayViews) try { wm.removeView(v) } catch (_: Exception) {}
        overlayViews.clear()
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
