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
    private var btnSetPoz: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    // ── Intervallo pozione (ms) configurabile nel pannello ────────────────────
    private var potInterval: Long = 3000L

    // ── Ticker — aggiorna testo ogni 500ms ────────────────────────────────────
    private val ticker = object : Runnable {
        override fun run() {
            val potOn  = BotState.potionRunning
            val lootOn = BotState.lootRunning
            val found  = BotState.lootItemsFound
            val slots  = BotState.potionSlots.size

            when {
                potOn && lootOn -> {
                    tvStatus?.text = "💊 POZ + 🎒 LOOT ($found)"
                    tvStatus?.setTextColor(Color.GREEN)
                }
                potOn -> {
                    tvStatus?.text = "💊 POZ attivo ($slots slot)"
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

            val slotLabel = if (slots > 0) " ($slots)" else ""
            btnSetPoz?.text = "🎯 IMPOSTA POZ$slotLabel"

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

        // ── Bottone: imposta slot pozione ─────────────────────────────────────
        // Prima pressione: azzera slot precedenti e avvia cattura.
        // Ogni tocco sullo schermo aggiunge uno slot (max 3).
        // Dopo il 3° tocco o 5s di timeout, la pozione parte automaticamente.
        btnSetPoz = makeButton("🎯 IMPOSTA POZ", Color.argb(220, 130, 70, 0))
        btnSetPoz!!.setOnClickListener { startPickPotion() }

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
                if (BotState.potionSlots.isEmpty()) {
                    tvStatus?.text = "Prima imposta la POZ!"
                    tvStatus?.setTextColor(Color.YELLOW)
                    return@setOnClickListener
                }
                bot.startPotion(potInterval)
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
    // CATTURA TOCCHI PER IMPOSTARE SLOT POZIONE (max 3)
    //
    // Quando premuto IMPOSTA POZ:
    //   1. Azzera gli slot precedenti.
    //   2. Overlay trasparente: ogni tocco aggiunge uno slot pozione.
    //   3. Dopo il 3° tocco OR 5s di timeout:
    //      - rimuove overlay
    //      - avvia automaticamente la pozione
    // ═══════════════════════════════════════════════════════════════════════════
    private val MAX_SLOTS = 3
    private var slotsAddedDuringCapture = 0
    private var captureTimeoutRunnable: Runnable? = null

    private fun startPickPotion() {
        if (captureView != null) return

        // Azzera slot precedenti e ferma pozione attiva
        BotState.potionSlots.clear()
        BotState.potionRunning = false
        BotAccessibilityService.instance?.stopPotion()
        slotsAddedDuringCapture = 0

        tvStatus?.text = "Tocca slot 1/3... (5s)"
        tvStatus?.setTextColor(Color.YELLOW)

        val cv = View(this).apply {
            setBackgroundColor(Color.argb(1, 0, 0, 0))
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val x = e.rawX; val y = e.rawY
                slotsAddedDuringCapture++
                BotState.potionSlots.add(x to y)

                if (slotsAddedDuringCapture >= MAX_SLOTS) {
                    finishPickPotion()
                } else {
                    val next = slotsAddedDuringCapture + 1
                    tvStatus?.text = "✓ Slot $slotsAddedDuringCapture  →  Tocca slot $next/3 o aspetta"
                    tvStatus?.setTextColor(Color.YELLOW)
                }
            }
            true
        }

        wm.addView(cv, lp)
        captureView = cv

        // Timeout automatico dopo 5 secondi
        val timeout = Runnable {
            if (captureView != null) finishPickPotion()
        }
        captureTimeoutRunnable = timeout
        handler.postDelayed(timeout, 5000L)
    }

    private fun finishPickPotion() {
        captureTimeoutRunnable?.let { handler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        removeCaptureView()

        val slots = BotState.potionSlots.size
        if (slots == 0) {
            tvStatus?.text = "Nessuno slot impostato"
            tvStatus?.setTextColor(Color.LTGRAY)
            return
        }

        // Avvia automaticamente la pozione
        val bot = BotAccessibilityService.instance
        if (bot != null) {
            bot.startPotion(potInterval)
            tvStatus?.text = "💊 POZ: $slots slot attivi!"
            tvStatus?.setTextColor(Color.GREEN)
        } else {
            tvStatus?.text = "$slots slot salvati. Abilita Accessibilità!"
            tvStatus?.setTextColor(Color.YELLOW)
        }
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
