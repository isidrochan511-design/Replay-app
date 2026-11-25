package com.replayapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

class MainActivity : Activity() {
    private val reqCapture = 1
    private val reqNotif = 2

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, color: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor(color))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(14) }
            setOnClickListener { action() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val title = TextView(this).apply {
            text = "Replay"
            textSize = 38f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Guarda los últimos 30 segundos de tu juego"
            textSize = 15f
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(24))
        }
        val steps = TextView(this).apply {
            text = "1. Toca Iniciar y acepta los permisos\n" +
                "2. Abre tu juego\n" +
                "3. Toca el botón rojo flotante para guardar"
            textSize = 14f
            setTextColor(Color.parseColor("#8A8A8A"))
            setPadding(0, 0, 0, dp(16))
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(steps)
        layout.addView(makeButton("Iniciar grabación", "#2E7D32") { startCapture() })
        layout.addView(makeButton("Guardar últimos 30 s", "#1565C0") {
            sendAction(RecorderService.ACTION_SAVE)
        })
        layout.addView(makeButton("Detener", "#C62828") {
            sendAction(RecorderService.ACTION_STOP)
        })
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
        }
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Activa 'Mostrar sobre otras apps' para Replay y vuelve",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            )
            return
        }
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
            moveTaskToBack(true)
        }
    }
}
