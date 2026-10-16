package com.example.autotap

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private val REQ_PICK = 1
    private val REQ_CAPTURE = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        fun add(text: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; setOnClickListener { onClick() } })
        }
        root.addView(TextView(this).apply { text = "AutoTap Overlay"; textSize = 24f })

        add("1. Allow draw over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        add("2. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        add("3. Pick button image (screenshot crop)") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_PICK)
        }
        add("4. Start overlay") {
            when {
                !Settings.canDrawOverlays(this) -> toast("Do step 1 first")
                ClickService.instance == null -> toast("Do step 2 first")
                !File(filesDir, "target.png").exists() -> toast("Do step 3 first")
                else -> {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
                }
            }
        }
        add("Stop overlay") { stopService(Intent(this, OverlayService::class.java)) }
        setContentView(root)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == REQ_PICK) {
            val uri = data.data ?: return
            contentResolver.openInputStream(uri)?.use { input ->
                File(filesDir, "target.png").outputStream().use { input.copyTo(it) }
            }
            toast("Button image saved")
        } else if (req == REQ_CAPTURE) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", res).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
