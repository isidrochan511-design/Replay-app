package com.replayapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val reqCapture = 1
    private val reqNotif = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val info = TextView(this).apply {
            text = "Replay: guarda los últimos 30 segundos de tu pantalla"
            textSize = 18f
        }
        val start = Button(this).apply {
            text = "Iniciar grabación"
            setOnClickListener { startCapture() }
        }
        val save = Button(this).apply {
            text = "Guardar últimos 30 s"
            setOnClickListener { sendAction(RecorderService.ACTION_SAVE) }
        }
        val stop = Button(this).apply {
            text = "Detener"
            setOnClickListener { sendAction(RecorderService.ACTION_STOP) }
        }
        layout.addView(info)
        layout.addView(start)
        layout.addView(save)
        layout.addView(stop)
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
        }
    }

    private fun startCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    private fun sendAction(action: String) {
        startService(Intent(this, RecorderService::class.java).setAction(action))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_START
                putExtra("code", resultCode)
                putExtra("data", data)
            }
            startForegroundService(i)
        }
    }
}
