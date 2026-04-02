package com.movile2.bot

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
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null

    private var captureView: View? = null
    private var captureTimeoutRunnable: Runnable? = null
    private var slotsAddedDuringCapture = 0
    private val MAX_POT_SLOTS = 3
    private val MAX_SKILL_SLOTS = 5

    private var tvStatus: TextView? = null
    private var btnAttack: TextView? = null
    private var btnSetAtt: TextView? = null
    private var btnPot: TextView? = null
    private var btnLoot: TextView? = null
    private var btnSetPoz: TextView? = null
    private var btnSkill: TextView? = null
    private var btnSetSkill: TextView? = null
    private var btnSetJoy: TextView? = null
    private var contentLayout: LinearLayout? = null
    private var btnToggle: TextView? = null
    private var panelCollapsed = false

    private val handler = Handler(Looper.getMainLooper())
    private var potInterval: Long = 3000L

    private enum class CaptureMode { NONE, ATTACK, POTION, SKILL, JOYSTICK }
    private var captureMode = CaptureMode.NONE

    // ── Joystick detection via ACTION_OUTSIDE ──────────────────────────────────
    private var joystickResumeRunnable: Runnable? = null
    private val JOY_RESUME_DELAY_MS = 1500L

    private fun onOutsideTouch(rx: Float, ry: Float) {
        val center = BotState.joystickPos ?: return
        val halfZone = (140f * resources.displayMetrics.density)
        val dx = rx - center.first; val dy = ry - center.second
        if (abs(dx) < halfZone && abs(dy) < halfZone) {
            BotState.joystickActive = true
            joystickResumeRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                BotAccessibilityService.instance?.resumeAfterJoystick()
                    ?: run { BotState.joystickActive = false }
            }
            joystickResumeRunnable = r
            handler.postDelayed(r, JOY_RESUME_DELAY_MS)
        }
    }

    // ── Ticker ────────────────────────────────────────────────────────────────
    private val ticker = object : Runnable {
        override fun run() {
            val attOn   = BotState.attackRunning
            val potOn   = BotState.potionRunning
            val skillOn = BotState.skillsRunning
            val lootOn  = BotState.lootRunning
            val found   = BotState.lootItemsFound
            val potSlots   = BotState.potionSlots.size
            val skillSlots = BotState.skillSlots.size
            val hasAtt  = BotState.attackPos != null
            val hasJoy  = BotState.joystickPos != null
            val mobNear = BotState.mobNearby

            val parts = mutableListOf<String>()
            if (BotState.joystickActive) parts.add("🕹️ PAUSA")
            else {
                if (attOn)   parts.add(if (mobNear) "⚔️ ATT🔴" else "⚔️ ATT…")
                if (potOn)   parts.add("💊 POZ")
                if (skillOn) parts.add("✨ SKILL")
                if (lootOn)  parts.add("🎒 LOOT($found)")
            }
            tvStatus?.text = if (parts.isEmpty()) "● INATTIVO" else parts.joinToString(" + ")
            tvStatus?.setTextColor(
                if (BotState.joystickActive) Color.YELLOW
                else if (parts.isEmpty()) Color.LTGRAY else Color.GREEN)

            btnSetAtt?.text = if (hasAtt) "🎯 ATT ✓" else "🎯 IMPOSTA ATT"
            btnAttack?.text = if (attOn) "⚔️ ATT: ON" else "⚔️ ATT: OFF"
            btnAttack?.setBackgroundColor(if (attOn) Color.argb(230,180,50,0) else Color.argb(220,50,50,80))

            val psl = if (potSlots > 0) " ($potSlots)" else ""
            btnSetPoz?.text = "🎯 IMPOSTA POZ$psl"
            btnPot?.text  = if (potOn)  "💊 POZ: ON"  else "💊 POZ: OFF"
            btnPot?.setBackgroundColor(if (potOn) Color.argb(230,0,130,160) else Color.argb(220,50,50,80))

            val ssl = if (skillSlots > 0) " ($skillSlots)" else ""
            btnSetSkill?.text = "🎯 IMPOSTA SKILL$ssl"
            btnSkill?.text  = if (skillOn) "✨ SKILL: ON" else "✨ SKILL: OFF"
            btnSkill?.setBackgroundColor(if (skillOn) Color.argb(230,120,0,180) else Color.argb(220,50,50,80))

            btnLoot?.text = if (lootOn) "🎒 LOOT: ON" else "🎒 LOOT: OFF"
            btnLoot?.setBackgroundColor(if (lootOn) Color.argb(230,20,150,50) else Color.argb(220,50,50,80))

            btnSetJoy?.text = if (hasJoy) "🕹️ JOYSTICK ✓" else "🕹️ IMPOSTA JOYSTICK"
            btnSetJoy?.setBackgroundColor(if (hasJoy) Color.argb(220,30,80,30) else Color.argb(220,60,40,10))

            handler.postDelayed(this, 500L)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        // Carica intervalli salvati
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        potInterval = prefs.getLong("pot_interval_ms", 3000L)
        for (idx in 0 until 5) {
            val ms = prefs.getLong("skill${idx + 1}_interval_ms", 5000L)
            if (idx < BotState.skillIntervals.size) BotState.skillIntervals[idx] = ms
        }
        startForeground()
        buildPanel()
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        BotAccessibilityService.instance?.stopAttack()
        BotAccessibilityService.instance?.stopPotion()
        BotAccessibilityService.instance?.stopSkills()
        BotAccessibilityService.instance?.stopLoot()
        BotState.joystickActive = false
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

        // Barra superiore: drag + hide
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val drag = makeText("☰ BOT", 11f, Color.argb(180, 150, 200, 255))
        drag.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btnToggle = makeText("▼", 13f, Color.argb(200, 150, 200, 255))
        btnToggle!!.setPadding(12, 4, 4, 4)
        topBar.addView(drag); topBar.addView(btnToggle)

        tvStatus = makeText("● INATTIVO", 13f, Color.LTGRAY)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── Attacco ──────────────────────────────────────────────────────────
        btnSetAtt = makeButton("🎯 IMPOSTA ATT", Color.argb(220, 100, 40, 0))
        btnSetAtt!!.setOnClickListener { startPickAttack() }

        btnAttack = makeButton("⚔️ ATT: OFF", Color.argb(220, 50, 50, 80))
        btnAttack!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run { showWarn("Abilita Accessibilità!"); return@setOnClickListener }
            if (BotState.attackRunning) bot.stopAttack()
            else {
                if (BotState.attackPos == null) { showWarn("Prima imposta ATT!"); return@setOnClickListener }
                bot.startAttack()
            }
        }

        // ── Pozione ──────────────────────────────────────────────────────────
        btnSetPoz = makeButton("🎯 IMPOSTA POZ", Color.argb(220, 130, 70, 0))
        btnSetPoz!!.setOnClickListener { startPickPotion() }

        btnPot = makeButton("💊 POZ: OFF", Color.argb(220, 50, 50, 80))
        btnPot!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run { showWarn("Abilita Accessibilità!"); return@setOnClickListener }
            if (BotState.potionRunning) bot.stopPotion()
            else {
                if (BotState.potionSlots.isEmpty()) { showWarn("Prima imposta POZ!"); return@setOnClickListener }
                bot.startPotion(potInterval)
            }
        }

        // ── Abilità ──────────────────────────────────────────────────────────
        btnSetSkill = makeButton("🎯 IMPOSTA SKILL", Color.argb(220, 80, 0, 120))
        btnSetSkill!!.setOnClickListener { startPickSkill() }

        btnSkill = makeButton("✨ SKILL: OFF", Color.argb(220, 50, 50, 80))
        btnSkill!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run { showWarn("Abilita Accessibilità!"); return@setOnClickListener }
            if (BotState.skillsRunning) bot.stopSkills()
            else {
                if (BotState.skillSlots.isEmpty()) { showWarn("Prima imposta SKILL!"); return@setOnClickListener }
                bot.startSkills()
            }
        }

        // ── Loot ─────────────────────────────────────────────────────────────
        btnLoot = makeButton("🎒 LOOT: OFF", Color.argb(220, 50, 50, 80))
        btnLoot!!.setOnClickListener {
            val bot = BotAccessibilityService.instance ?: run { showWarn("Abilita Accessibilità!"); return@setOnClickListener }
            if (BotState.lootRunning) bot.stopLoot() else bot.startLoot()
        }

        // ── Joystick ─────────────────────────────────────────────────────────
        btnSetJoy = makeButton("🕹️ IMPOSTA JOYSTICK", Color.argb(220, 60, 40, 10))
        btnSetJoy!!.setOnClickListener { startPickJoystick() }

        content.addView(space(6))
        content.addView(btnSetAtt);   content.addView(space(4))
        content.addView(btnAttack);   content.addView(space(8))
        content.addView(btnSetPoz);   content.addView(space(4))
        content.addView(btnPot);      content.addView(space(8))
        content.addView(btnSetSkill); content.addView(space(4))
        content.addView(btnSkill);    content.addView(space(8))
        content.addView(btnLoot);     content.addView(space(8))
        content.addView(btnSetJoy)
        contentLayout = content

        root.addView(topBar); root.addView(tvStatus); root.addView(content)
        panel = root

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 180 }
        panelLp = lp

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) onOutsideTouch(e.rawX, e.rawY)
            false
        }

        // Drag
        var dX = 0f; var dY = 0f; var sX = 0; var sY = 0
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = e.rawX; dY = e.rawY; sX = lp.x; sY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (sX + (e.rawX - dX)).toInt(); lp.y = (sY + (e.rawY - dY)).toInt()
                    wm.updateViewLayout(root, lp); true
                }
                else -> false
            }
        }

        // Hide/show
        btnToggle!!.setOnClickListener {
            panelCollapsed = !panelCollapsed
            if (panelCollapsed) {
                contentLayout?.visibility = View.GONE; tvStatus?.visibility = View.GONE; btnToggle?.text = "▶"
            } else {
                contentLayout?.visibility = View.VISIBLE; tvStatus?.visibility = View.VISIBLE; btnToggle?.text = "▼"
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
        showStatus("Tocca il tasto ATTACCO... (5s)", Color.YELLOW)
        val cv = makeCaptureOverlay()
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN && captureMode == CaptureMode.ATTACK) {
                BotState.attackPos = e.rawX to e.rawY
                finishCapture("⚔️ Attacco impostato!")
            }
            true
        }
        wm.addView(cv, captureOverlayParams()); captureView = cv
        scheduleCaptureTimeout()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATTURA SLOT POZIONE
    // ═══════════════════════════════════════════════════════════════════════════
    private fun startPickPotion() {
        if (captureView != null) return
        captureMode = CaptureMode.POTION
        BotState.potionSlots.clear(); BotState.potionRunning = false
        BotAccessibilityService.instance?.stopPotion()
        slotsAddedDuringCapture = 0
        showStatus("Tocca slot 1/$MAX_POT_SLOTS... (5s)", Color.YELLOW)
        val cv = makeCaptureOverlay()
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN && captureMode == CaptureMode.POTION) {
                slotsAddedDuringCapture++
                BotState.potionSlots.add(e.rawX to e.rawY)
                if (slotsAddedDuringCapture >= MAX_POT_SLOTS) {
                    finishPotionCapture()
                } else {
                    showStatus("✓ Slot $slotsAddedDuringCapture → Tocca ${slotsAddedDuringCapture + 1}/$MAX_POT_SLOTS o aspetta", Color.YELLOW)
                }
            }
            true
        }
        wm.addView(cv, captureOverlayParams()); captureView = cv
        scheduleCaptureTimeout()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATTURA SLOT ABILITÀ
    // ═══════════════════════════════════════════════════════════════════════════
    private fun startPickSkill() {
        if (captureView != null) return
        captureMode = CaptureMode.SKILL
        BotState.skillSlots.clear(); BotState.skillsRunning = false
        BotAccessibilityService.instance?.stopSkills()
        slotsAddedDuringCapture = 0
        showStatus("Tocca slot abilità 1/$MAX_SKILL_SLOTS... (8s)", Color.YELLOW)
        val cv = makeCaptureOverlay()
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN && captureMode == CaptureMode.SKILL) {
                slotsAddedDuringCapture++
                BotState.skillSlots.add(e.rawX to e.rawY)
                if (slotsAddedDuringCapture >= MAX_SKILL_SLOTS) {
                    finishSkillCapture()
                } else {
                    showStatus("✓ Skill $slotsAddedDuringCapture → Tocca ${slotsAddedDuringCapture + 1}/$MAX_SKILL_SLOTS o aspetta", Color.YELLOW)
                }
            }
            true
        }
        wm.addView(cv, captureOverlayParams()); captureView = cv
        // Timeout più lungo per le abilità (fino a 5 slot)
        val timeout = Runnable { if (captureView != null) finishSkillCapture() }
        captureTimeoutRunnable = timeout
        handler.postDelayed(timeout, 8000L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATTURA POSIZIONE JOYSTICK
    // ═══════════════════════════════════════════════════════════════════════════
    private fun startPickJoystick() {
        if (captureView != null) return
        captureMode = CaptureMode.JOYSTICK
        showStatus("Tocca il CENTRO del joystick... (5s)", Color.YELLOW)
        val cv = makeCaptureOverlay()
        cv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN && captureMode == CaptureMode.JOYSTICK) {
                BotState.joystickPos = e.rawX to e.rawY
                finishCapture("🕹️ Zona joystick impostata!")
            }
            true
        }
        wm.addView(cv, captureOverlayParams()); captureView = cv
        scheduleCaptureTimeout()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS CATTURA
    // ═══════════════════════════════════════════════════════════════════════════
    private fun finishCapture(msg: String?) {
        captureTimeoutRunnable?.let { handler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        removeCaptureView()
        captureMode = CaptureMode.NONE
        if (msg != null) showStatus(msg, Color.GREEN)
        if (panelCollapsed) handler.postDelayed({ if (panelCollapsed) tvStatus?.visibility = View.GONE }, 2500L)
    }

    private fun finishPotionCapture() {
        captureTimeoutRunnable?.let { handler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        removeCaptureView()
        captureMode = CaptureMode.NONE
        val slots = BotState.potionSlots.size
        if (slots == 0) { showStatus("Nessuno slot impostato", Color.LTGRAY); return }
        val bot = BotAccessibilityService.instance
        if (bot != null) { bot.startPotion(potInterval); showStatus("💊 POZ: $slots slot attivi!", Color.GREEN) }
        else showStatus("$slots slot salvati. Abilita Accessibilità!", Color.YELLOW)
        if (panelCollapsed) handler.postDelayed({ if (panelCollapsed) tvStatus?.visibility = View.GONE }, 2500L)
    }

    private fun finishSkillCapture() {
        captureTimeoutRunnable?.let { handler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        removeCaptureView()
        captureMode = CaptureMode.NONE
        val slots = BotState.skillSlots.size
        if (slots == 0) { showStatus("Nessuno slot abilità impostato", Color.LTGRAY); return }
        showStatus("✨ SKILL: $slots slot salvati!", Color.GREEN)
        if (panelCollapsed) handler.postDelayed({ if (panelCollapsed) tvStatus?.visibility = View.GONE }, 2500L)
    }

    private fun scheduleCaptureTimeout() {
        val timeout = Runnable { if (captureView != null) finishCapture(null) }
        captureTimeoutRunnable = timeout
        handler.postDelayed(timeout, 5000L)
    }

    private fun makeCaptureOverlay() = View(this).apply { setBackgroundColor(Color.argb(1, 0, 0, 0)) }

    private fun captureOverlayParams() = overlayParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    )

    private fun removeCaptureView() {
        captureView?.let { runCatching { wm.removeView(it) }; captureView = null }
    }

    private fun showStatus(msg: String, color: Int) {
        tvStatus?.text = msg; tvStatus?.setTextColor(color)
        if (panelCollapsed) tvStatus?.visibility = View.VISIBLE
    }

    private fun showWarn(msg: String) {
        showStatus(msg, Color.YELLOW)
        if (panelCollapsed) handler.postDelayed({ if (panelCollapsed) tvStatus?.visibility = View.GONE }, 2500L)
    }

    // ── Helpers UI ────────────────────────────────────────────────────────────
    private fun makeText(txt: String, size: Float, color: Int) = TextView(this).apply {
        text = txt; textSize = size; setTextColor(color); setPadding(0, 0, 0, 0)
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
                .createNotificationChannel(NotificationChannel(ch, "Bot", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Movile2 Bot").setContentText("Pannello attivo").build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(1002, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1002, notif)
    }
}
