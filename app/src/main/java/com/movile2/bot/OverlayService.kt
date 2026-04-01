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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var captureView: View? = null
    private var tvStatus: TextView? = null
    private var btnPot: TextView? = null
    private var btnLoot: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    // ── Intervallo pozione (ms) configurabile nel pannello ────────────────────
    private var potInterval: Long = 3000L

    // ── Ticker — aggiorna testo ogni 500ms ────────────────────────────────────
    private val ticker = object : Runnable {
        override fun run() {
            val potOn  = BotState.potionRunning
            val lootOn = BotState.lootRunning
            val found  = BotState.lootItemsFound

            when {
                potOn && lootOn -> {
                    tvStatus?.text = "💊 POZ + 🎒 LOOT ($found)"
                    tvStatus?.setTextColor(Color.GREEN)
                }
                potOn -> {
                    tvStatus?.text = "💊 POZ attivo"
                    tvStatus?.setTextColor(Color.CYAN)
                }
                lootOn -> {
                    tvStatus?.text = "🎒 LOOT: $found oggetti"
                    tvStatus?.setTextColor(Color.GREEN)
                }
                else -> {
                    tvStatus?.text = "● INATTIVO"
                    tvStatus?.setTextColor(Color.LTGRAY)
                }
            }

            btnPot?.text  = if (potOn)  "💊 POZ: ON"  else "💊 POZ: OFF"
            btnPot?.setBackgroundColor(
                if (potOn) Color.argb(230, 0, 130, 160) else Color.argb(220, 50, 50, 80))

            btnLoot?.text = if (lootOn) "🎒 LOOT: ON" else "🎒 LOOT: OFF"
            btnLoot?.setBackgroundColor(
                if (lootOn) Color.argb(230, 20, 150, 50) else Color.argb(220, 50, 50, 80))

            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        buildPanel()
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        BotAccessibilityService.instance?.stopPotion()
        BotAccessibilityService.instance?.stopLoot()
        panel?.let { runCatching { wm.removeView(it) } }
        captureView?.let { runCatching { wm.removeView(it) } }
        panel = null; captureView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // PANNELLO FLOTTANTE
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildPanel() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 8, 8, 28))
            setPadding(16, 12, 16, 12)
        }

        // ── Handle drag ───────────────────────────────────────────────────────
        val drag = makeText("☰ BOT", 11f, Color.argb(180, 150, 200, 255))

        // ── Status ────────────────────────────────────────────────────────────
        tvStatus = makeText("● INATTIVO", 13f, Color.LTGRAY)

        // ── Bottone: imposta posizione pozione ────────────────────────────────
        // Quando premuto: schermo diventa toccabile per 5 secondi.
        // Il primo tocco dell'utente sul gioco viene registrato come
        // posizione della pozione.
        val btnSetPoz = makeButton("🎯 IMPOSTA POZ", Color.argb(220, 130, 70, 0))
        btnSetPoz.setOnClickListener { startPickPotion() }

        // ── Bottone: pozione ON/OFF ───────────────────────────────────────────
        btnPot = makeButton("💊 POZ: OFF", Color.argb(220, 50, 50, 80))
        btnPot!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run {
                tvStatus?.text = "Abilita Accessibilità!"
                tvStatus?.setTextColor(Color.YELLOW)
                return@setOnClickListener
            }
            if (BotState.potionRunning) {
                bot.stopPotion()
            } else {
                if (BotState.potionX <= 0f) {
                    tvStatus?.text = "Prima imposta la POZ!"
                    tvStatus?.setTextColor(Color.YELLOW)
                    return@setOnClickListener
                }
                bot.startPotion(BotState.potionX, BotState.potionY, potInterval)
            }
        }

        // ── Bottone: loot ON/OFF ──────────────────────────────────────────────
        btnLoot = makeButton("🎒 LOOT: OFF", Color.argb(220, 50, 50, 80))
        btnLoot!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run {
                tvStatus?.text = "Abilita Accessibilità!"
                tvStatus?.setTextColor(Color.YELLOW)
                return@setOnClickListener
            }
            if (BotState.lootRunning) bot.stopLoot() else bot.startLoot()
        }

        root.addView(drag)
        root.addView(tvStatus)
        root.addView(space(8))
        root.addView(btnSetPoz)
        root.addView(space(4))
        root.addView(btnPot)
        root.addView(space(4))
        root.addView(btnLoot)
        panel = root

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 180 }

        // Drag
        var dX = 0f; var dY = 0f; var sX = 0; var sY = 0
        drag.setOnTouchListener { _, e ->
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

    // ═══════════════════════════════════════════════════════════════════════════
    // CATTURA TOCCO PER IMPOSTARE POZIONE
    //
    // Aggiunge un View trasparente che copre tutto lo schermo per 5 secondi.
    // Il primo tocco dell'utente viene salvato come posizione della pozione.
    // Poi il View viene rimosso e il pannello torna normale.
    // ═══════════════════════════════════════════════════════════════════════════
    private fun startPickPotion() {
        if (captureView != null) return
        tvStatus?.text = "Tocca la pozione... (5s)"
        tvStatus?.setTextColor(Color.YELLOW)

        val cv = View(this).apply {
            setBackgroundColor(Color.argb(1, 0, 0, 0)) // quasi trasparente
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val x = e.rawX; val y = e.rawY
                BotState.potionX = x; BotState.potionY = y
                removeCaptureView()
                tvStatus?.text = "Pozione: ${x.toInt()},${y.toInt()}"
                tvStatus?.setTextColor(Color.GREEN)
            }
            true
        }

        wm.addView(cv, lp)
        captureView = cv

        // Timeout automatico dopo 5 secondi
        handler.postDelayed({
            if (captureView != null) {
                removeCaptureView()
                if (BotState.potionX <= 0f) {
                    tvStatus?.text = "Annullato"
                    tvStatus?.setTextColor(Color.LTGRAY)
                }
            }
        }, 5000L)
    }

    private fun removeCaptureView() {
        captureView?.let { runCatching { wm.removeView(it) }; captureView = null }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun makeText(txt: String, size: Float, color: Int) = TextView(this).apply {
        text = txt; textSize = size; setTextColor(color)
        setPadding(0, 0, 0, 0)
    }

    private fun makeButton(txt: String, bg: Int) = TextView(this).apply {
        text = txt; textSize = 14f; setTextColor(Color.WHITE)
        setBackgroundColor(bg); setPadding(18, 10, 18, 10)
    }

    private fun space(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * resources.displayMetrics.density).toInt()
        )
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        flags, PixelFormat.TRANSLUCENT
    )

    // ── Foreground notification ───────────────────────────────────────────────
    private fun startForeground() {
        val ch = "bot_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(ch, "Bot", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Movile2 Bot")
            .setContentText("Pannello attivo")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1002, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, notif)
        }
    }
}
