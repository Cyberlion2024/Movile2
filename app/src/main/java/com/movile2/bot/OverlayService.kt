package com.movile2.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlayView: LinearLayout? = null
    private var tvStatus: TextView? = null
    private var tvKills: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val updateKills = object : Runnable {
        override fun run() {
            tvKills?.text = "Kills: ${BotState.killCount}"
            tvStatus?.text = if (BotState.isRunning) "● RUNNING" else "● STOP"
            tvStatus?.setTextColor(if (BotState.isRunning) Color.GREEN else Color.RED)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        showOverlay()
        handler.post(updateKills)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        BotAccessibilityService.instance?.stopBot()
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 10, 10, 40))
            setPadding(16, 12, 16, 12)
        }

        tvStatus = TextView(this).apply {
            text = "● STOP"
            setTextColor(Color.RED)
            textSize = 13f
        }

        tvKills = TextView(this).apply {
            text = "Kills: 0"
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        val btnToggle = TextView(this).apply {
            text = "▶ START"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(200, 30, 80, 200))
            setPadding(20, 10, 20, 10)
        }

        val btnStop = TextView(this).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(200, 150, 30, 30))
            setPadding(20, 10, 20, 10)
        }

        btnToggle.setOnClickListener {
            val bot = BotAccessibilityService.instance
            if (bot == null) {
                tvStatus?.text = "Abilita Accessibilità!"
                tvStatus?.setTextColor(Color.YELLOW)
                return@setOnClickListener
            }
            if (BotState.isRunning) {
                bot.stopBot()
                btnToggle.text = "▶ START"
            } else {
                BotState.killCount = 0
                bot.startBot()
                btnToggle.text = "⏸ PAUSA"
            }
        }

        btnStop.setOnClickListener {
            BotAccessibilityService.instance?.stopBot()
            btnToggle.text = "▶ START"
        }

        layout.addView(tvStatus)
        layout.addView(tvKills)
        layout.addView(btnToggle)
        layout.addView(btnStop)
        overlayView = layout

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 200
        }

        makeDraggable(layout, lp)
        wm.addView(layout, lp)
    }

    private fun makeDraggable(v: android.view.View, lp: WindowManager.LayoutParams) {
        var dX = 0f; var dY = 0f; var startX = 0; var startY = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = e.rawX; dY = e.rawY; startX = lp.x; startY = lp.y; false }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - dX)).toInt()
                    lp.y = (startY + (e.rawY - dY)).toInt()
                    wm.updateViewLayout(v, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "bot_overlay_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Bot Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Movile2 Bot attivo")
            .setContentText("Usa il pannello flottante per controllare il bot")
            .build()
    }

    companion object { private const val NOTIF_ID = 1002 }
}
