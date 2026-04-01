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
    private var btnToggle: TextView? = null
    private var btnPotionOnly: TextView? = null
    private var btnLootOnly: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val running   = BotState.isRunning
            val defend    = BotState.underAttack
            val hpPct     = BotState.hpDisplayPct
            val potOnly   = BotState.potionOnlyMode
            val lootOnly  = BotState.lootOnlyMode
            val lootFound = BotState.lootItemsFound

            val hpStr  = if (hpPct >= 0) " HP:${hpPct}%" else ""
            val potStr = if (BotState.potSource.isNotEmpty())
                " POZ[${BotState.potSource}]:${BotState.potTapX.toInt()},${BotState.potTapY.toInt()}"
            else ""
            tvKills?.text = "Kills: ${BotState.killCount}$hpStr$potStr"

            when {
                lootOnly -> {
                    tvStatus?.text = "🎒 LOOT: $lootFound oggetti"
                    tvStatus?.setTextColor(Color.GREEN)
                }
                potOnly && !running -> {
                    tvStatus?.text = "💊 SOLO POZ"
                    tvStatus?.setTextColor(Color.CYAN)
                }
                !running -> {
                    tvStatus?.text = "● STOP"
                    tvStatus?.setTextColor(Color.RED)
                }
                defend -> {
                    tvStatus?.text = "🛡 DIFESA"
                    tvStatus?.setTextColor(Color.YELLOW)
                }
                else -> {
                    tvStatus?.text = "● RUNNING"
                    tvStatus?.setTextColor(Color.GREEN)
                }
            }

            btnToggle?.text = if (running) "⏸ PAUSA" else "▶ START BOT"

            btnPotionOnly?.text = if (potOnly) "💊 POZ: ON" else "💊 POZ: OFF"
            btnPotionOnly?.setBackgroundColor(
                if (potOnly) Color.argb(230, 0, 140, 160) else Color.argb(220, 50, 50, 80)
            )

            btnLootOnly?.text = if (lootOnly) "🎒 LOOT: ON" else "🎒 LOOT: OFF"
            btnLootOnly?.setBackgroundColor(
                if (lootOnly) Color.argb(230, 20, 160, 60) else Color.argb(220, 50, 50, 80)
            )

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
        buildOverlay()
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        BotAccessibilityService.instance?.stopBot()
        BotAccessibilityService.instance?.stopPotionOnly()
        BotAccessibilityService.instance?.stopLootOnly()
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 8, 8, 30))
            setPadding(14, 10, 14, 10)
        }

        val dragHandle = TextView(this).apply {
            text = "☰ BOT"
            setTextColor(Color.argb(200, 150, 200, 255))
            textSize = 11f
            setPadding(0, 0, 0, 6)
        }

        tvStatus = TextView(this).apply {
            text = "● STOP"
            setTextColor(Color.RED)
            textSize = 13f
            setPadding(0, 0, 0, 2)
        }

        tvKills = TextView(this).apply {
            text = "Kills: 0"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }

        // ── START / PAUSA bot principale ─────────────────────────────────────
        btnToggle = TextView(this).apply {
            text = "▶ START BOT"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(220, 20, 90, 200))
            setPadding(18, 10, 18, 10)
        }
        btnToggle!!.setOnClickListener {
            val bot = BotAccessibilityService.instance
            if (bot == null) {
                tvStatus?.text = "Abilita Accessibilità!"
                tvStatus?.setTextColor(Color.YELLOW)
                return@setOnClickListener
            }
            if (BotState.isRunning) bot.stopBot()
            else { BotState.killCount = 0; bot.startBot() }
        }

        // ── STOP definitivo ───────────────────────────────────────────────────
        val btnStop = TextView(this).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(220, 160, 20, 20))
            setPadding(18, 10, 18, 10)
        }
        btnStop.setOnClickListener {
            BotAccessibilityService.instance?.stopBot()
            BotState.killCount = 0
        }

        // ── RACCOLTA TERRA — tap su yang + oggetti ────────────────────────────
        // Il tasto più importante: scansiona e raccoglie automaticamente
        // yang e oggetti da terra, senza attaccare né muoversi.
        btnLootOnly = TextView(this).apply {
            text = "🎒 LOOT: OFF"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(220, 50, 50, 80))
            setPadding(18, 12, 18, 12)
        }
        btnLootOnly!!.setOnClickListener {
            val bot = BotAccessibilityService.instance
            if (bot == null) {
                tvStatus?.text = "Abilita Accessibilità!"
                tvStatus?.setTextColor(Color.YELLOW)
                return@setOnClickListener
            }
            if (BotState.lootOnlyMode) bot.stopLootOnly()
            else bot.startLootOnly()
        }

        // ── SOLO POZIONI ──────────────────────────────────────────────────────
        btnPotionOnly = TextView(this).apply {
            text = "💊 POZ: OFF"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.argb(220, 50, 50, 80))
            setPadding(18, 10, 18, 10)
        }
        btnPotionOnly!!.setOnClickListener {
            val bot = BotAccessibilityService.instance
            if (bot == null) {
                tvStatus?.text = "Abilita Accessibilità!"
                tvStatus?.setTextColor(Color.YELLOW)
                return@setOnClickListener
            }
            if (BotState.potionOnlyMode) bot.stopPotionOnly()
            else bot.startPotionOnly()
        }

        root.addView(dragHandle)
        root.addView(tvStatus)
        root.addView(tvKills)
        root.addView(btnLootOnly)   // Raccolta terra — in cima perché è la funzione principale
        root.addView(btnToggle)
        root.addView(btnStop)
        root.addView(btnPotionOnly)
        overlayView = root

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 180 }

        var dX = 0f; var dY = 0f; var sX = 0; var sY = 0
        dragHandle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = e.rawX; dY = e.rawY; sX = lp.x; sY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (sX + (e.rawX - dX)).toInt()
                    lp.y = (sY + (e.rawY - dY)).toInt()
                    wm.updateViewLayout(root, lp)
                    true
                }
                else -> false
            }
        }

        wm.addView(root, lp)
    }

    private fun buildNotification(): Notification {
        val ch = "bot_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Bot", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Movile2 Bot attivo")
            .setContentText("Usa il pannello flottante")
            .build()
    }

    companion object { private const val NOTIF_ID = 1002 }
}
