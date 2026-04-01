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
    private var btnAttack: TextView? = null
    private var btnSetAtt: TextView? = null
    private var btnPot: TextView? = null
    private var btnLoot: TextView? = null
    private var btnSetPoz: TextView? = null
    private var contentLayout: LinearLayout? = null
    private var btnToggle: TextView? = null
    private var panelCollapsed = false
    private val handler = Handler(Looper.getMainLooper())

    private var potInterval: Long = 3000L

    // ── Modalità cattura corrente ─────────────────────────────────────────────
    private enum class CaptureMode { NONE, ATTACK, POTION }
    private var captureMode = CaptureMode.NONE

    // ── Ticker — aggiorna testo ogni 500ms ────────────────────────────────────
    private val ticker = object : Runnable {
        override fun run() {
            val attOn  = BotState.attackRunning
            val potOn  = BotState.potionRunning
            val lootOn = BotState.lootRunning
            val found  = BotState.lootItemsFound
            val slots  = BotState.potionSlots.size
            val hasAtt = BotState.attackPos != null

            val parts = mutableListOf<String>()
            if (attOn)  parts.add("⚔️ ATT")
            if (potOn)  parts.add("💊 POZ")
            if (lootOn) parts.add("🎒 LOOT($found)")
            tvStatus?.text = if (parts.isEmpty()) "● INATTIVO" else parts.joinToString(" + ")
            tvStatus?.setTextColor(if (parts.isEmpty()) Color.LTGRAY else Color.GREEN)

            btnSetAtt?.text = if (hasAtt) "🎯 ATT ✓" else "🎯 IMPOSTA ATT"

            btnAttack?.text = if (attOn) "⚔️ ATT: ON" else "⚔️ ATT: OFF"
            btnAttack?.setBackgroundColor(
                if (attOn) Color.argb(230, 180, 50, 0) else Color.argb(220, 50, 50, 80))

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
        BotAccessibilityService.instance?.stopAttack()
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

        // ── Barra superiore: drag + pulsante hide ─────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val drag = makeText("☰ BOT", 11f, Color.argb(180, 150, 200, 255))
        drag.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        btnToggle = makeText("▼", 13f, Color.argb(200, 150, 200, 255))
        btnToggle!!.setPadding(12, 4, 4, 4)

        topBar.addView(drag)
        topBar.addView(btnToggle)

        tvStatus = makeText("● INATTIVO", 13f, Color.LTGRAY)

        // ── Contenuto collassabile ─────────────────────────────────────────────
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Attacco
        btnSetAtt = makeButton("🎯 IMPOSTA ATT", Color.argb(220, 100, 40, 0))
        btnSetAtt!!.setOnClickListener { startPickAttack() }

        btnAttack = makeButton("⚔️ ATT: OFF", Color.argb(220, 50, 50, 80))
        btnAttack!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run {
                showWarn("Abilita Accessibilità!")
                return@setOnClickListener
            }
            if (BotState.attackRunning) {
                bot.stopAttack()
            } else {
                if (BotState.attackPos == null) {
                    showWarn("Prima imposta ATT!")
                    return@setOnClickListener
                }
                bot.startAttack()
            }
        }

        // Pozione
        btnSetPoz = makeButton("🎯 IMPOSTA POZ", Color.argb(220, 130, 70, 0))
        btnSetPoz!!.setOnClickListener { startPickPotion() }

        btnPot = makeButton("💊 POZ: OFF", Color.argb(220, 50, 50, 80))
        btnPot!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run {
                showWarn("Abilita Accessibilità!")
                return@setOnClickListener
            }
            if (BotState.potionRunning) {
                bot.stopPotion()
            } else {
                if (BotState.potionSlots.isEmpty()) {
                    showWarn("Prima imposta la POZ!")
                    return@setOnClickListener
                }
                bot.startPotion(potInterval)
            }
        }

        // Loot
        btnLoot = makeButton("🎒 LOOT: OFF", Color.argb(220, 50, 50, 80))
        btnLoot!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run {
                showWarn("Abilita Accessibilità!")
                return@setOnClickListener
            }
            if (BotState.lootRunning) bot.stopLoot() else bot.startLoot()
        }

        content.addView(space(6))
        content.addView(btnSetAtt)
        content.addView(space(4))
        content.addView(btnAttack)
        content.addView(space(8))
        content.addView(btnSetPoz)
        content.addView(space(4))
        content.addView(btnPot)
        content.addView(space(4))
        content.addView(btnLoot)

        contentLayout = content

        root.addView(topBar)
        root.addView(tvStatus)
        root.addView(content)
        panel = root

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 180 }

        // ── Drag sulla barra superiore ─────────────────────────────────────────
        var dX = 0f; var dY = 0f; var sX = 0; var sY = 0; var moved = false
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = e.rawX; dY = e.rawY; sX = lp.x; sY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (sX + (e.rawX - dX)).toInt()
                    val ny = (sY + (e.rawY - dY)).toInt()
                    if (Math.abs(nx - sX) > 4 || Math.abs(ny - sY) > 4) moved = true
                    lp.x = nx; lp.y = ny
                    wm.updateViewLayout(root, lp)
                    true
                }
                else -> false
            }
        }

        // ── Pulsante hide/show ─────────────────────────────────────────────────
        btnToggle!!.setOnClickListener {
            panelCollapsed = !panelCollapsed
            if (panelCollapsed) {
                contentLayout?.visibility = View.GONE
                tvStatus?.visibility = View.GONE
                btnToggle?.text = "▶"
            } else {
                contentLayout?.visibility = View.VISIBLE
                tvStatus?.visibility = View.VISIBLE
                btnToggle?.text = "▼"
            }
            wm.updateViewLayout(root, lp)
        }

        wm.addView(root, lp)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATTURA POSIZIONE ATTACCO
    // ═══════════════════════════════════════════════════════════════════════════
    private fun startPickAttack() {
        if (captureView != null) return
        captureMode = CaptureMode.ATTACK

        tvStatus?.text = "Tocca il tasto ATTACCO... (5s)"
        tvStatus?.setTextColor(Color.YELLOW)
        if (panelCollapsed) {
            tvStatus?.visibility = View.VISIBLE
        }

        val cv = makeCaptureOverlay()
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN && captureMode == CaptureMode.ATTACK) {
                BotState.attackPos = e.rawX to e.rawY
                finishCapture("⚔️ Attacco impostato!")
            }
            true
        }

        wm.addView(cv, captureOverlayParams())
        captureView = cv
        scheduleCaptureTimeout()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATTURA SLOT POZIONE
    // ═══════════════════════════════════════════════════════════════════════════
    private val MAX_SLOTS = 3
    private var slotsAddedDuringCapture = 0
    private var captureTimeoutRunnable: Runnable? = null

    private fun startPickPotion() {
        if (captureView != null) return
        captureMode = CaptureMode.POTION

        BotState.potionSlots.clear()
        BotState.potionRunning = false
        BotAccessibilityService.instance?.stopPotion()
        slotsAddedDuringCapture = 0

        tvStatus?.text = "Tocca slot 1/3... (5s)"
        tvStatus?.setTextColor(Color.YELLOW)
        if (panelCollapsed) {
            tvStatus?.visibility = View.VISIBLE
        }

        val cv = makeCaptureOverlay()
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN && captureMode == CaptureMode.POTION) {
                slotsAddedDuringCapture++
                BotState.potionSlots.add(e.rawX to e.rawY)
                if (slotsAddedDuringCapture >= MAX_SLOTS) {
                    finishCapture(null)
                } else {
                    val next = slotsAddedDuringCapture + 1
                    tvStatus?.text = "✓ Slot $slotsAddedDuringCapture  →  Tocca slot $next/3 o aspetta"
                    tvStatus?.setTextColor(Color.YELLOW)
                }
            }
            true
        }

        wm.addView(cv, captureOverlayParams())
        captureView = cv
        scheduleCaptureTimeout()
    }

    private fun finishCapture(msg: String?) {
        captureTimeoutRunnable?.let { handler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        removeCaptureView()
        captureMode = CaptureMode.NONE

        if (msg != null) {
            tvStatus?.text = msg
            tvStatus?.setTextColor(Color.GREEN)
        } else {
            val slots = BotState.potionSlots.size
            if (slots == 0) {
                tvStatus?.text = "Nessuno slot impostato"
                tvStatus?.setTextColor(Color.LTGRAY)
                return
            }
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

        // Ripristina visibilità status se il pannello è collassato
        if (panelCollapsed) {
            handler.postDelayed({ if (panelCollapsed) tvStatus?.visibility = View.GONE }, 2500L)
        }
    }

    private fun scheduleCaptureTimeout() {
        val timeout = Runnable { if (captureView != null) finishCapture(null) }
        captureTimeoutRunnable = timeout
        handler.postDelayed(timeout, 5000L)
    }

    private fun makeCaptureOverlay() = View(this).apply {
        setBackgroundColor(Color.argb(1, 0, 0, 0))
    }

    private fun captureOverlayParams() = overlayParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    )

    private fun removeCaptureView() {
        captureView?.let { runCatching { wm.removeView(it) }; captureView = null }
    }

    private fun showWarn(msg: String) {
        tvStatus?.text = msg
        tvStatus?.setTextColor(Color.YELLOW)
        if (panelCollapsed) {
            tvStatus?.visibility = View.VISIBLE
            handler.postDelayed({ if (panelCollapsed) tvStatus?.visibility = View.GONE }, 2500L)
        }
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
