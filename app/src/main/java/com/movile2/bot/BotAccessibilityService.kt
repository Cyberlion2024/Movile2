package com.movile2.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // ── Timing ────────────────────────────────────────────────────────────────
    private val TAP_MS           = 60L
    private val GAP_MS           = 90L
    private val JOYSTICK_MS      = 350L
    private val CAMERA_MS        = 250L
    private val POST_MOVE_MS     = 100L
    private val NEXT_CYCLE_EXTRA = 150L
    private val SCAN_DELAY_MS    = 1000L   // screenshot ogni 1 secondo
    private val CAMERA_EVERY     = 5

    // ── Cooldown abilità ──────────────────────────────────────────────────────
    private var lastSkill1Ms = 0L
    private var lastSkill2Ms = 0L
    private var lastSkill3Ms = 0L
    private var lastSkill4Ms = 0L
    private var lastSkill5Ms = 0L

    // ── Pattugliamento ────────────────────────────────────────────────────────
    private var patrolDir   = 0
    private var patrolSteps = 0
    private var cameraDir   = 1

    // ── Stato ciclo ───────────────────────────────────────────────────────────
    private var huntCycles      = 0
    private var potionUses      = 0
    private var inCombatCycles  = 0
    private var defendAngleIdx  = 0

    private val DEFEND_ANGLES = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

    // ── Pixel detection ───────────────────────────────────────────────────────
    @Volatile private var targetX         = 0f
    @Volatile private var targetY         = 0f
    @Volatile private var prevTargetFound = false
    @Volatile private var scannedHpRatio  = 1.0f
    @Volatile private var hpBarConfigured = false

    // Auto HP bar detection (trovata automaticamente dal primo screenshot)
    @Volatile private var autoBarX     = -1
    @Volatile private var autoBarY     = -1
    @Volatile private var autoBarFullW = 0

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS  = arrayOf(
        floatArrayOf(0f, -1f), floatArrayOf(1f, 0f),
        floatArrayOf(0f, 1f),  floatArrayOf(-1f, 0f),
    )

    private enum class Phase { HUNT, DEFEND, POTION, REFILL }
    private var phase = Phase.HUNT

    // ═══════════════════════════════════════════════════════════════════════════
    // Loop principale
    // ═══════════════════════════════════════════════════════════════════════════
    private val loop = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            val cfg = BotConfig.load(this@BotAccessibilityService)
            if (cfg.maxKills > 0 && BotState.killCount >= cfg.maxKills) { stopBot(); return }
            if (cfg.sessionMinutes > 0 &&
                System.currentTimeMillis() - BotState.sessionStartMs >= cfg.sessionMinutes * 60_000L) {
                stopBot(); return
            }
            when (phase) {
                Phase.HUNT   -> doHunt(cfg)
                Phase.DEFEND -> doDefend(cfg)
                Phase.POTION -> doPotion(cfg)
                Phase.REFILL -> doRefill(cfg)
            }
        }
    }

    // ── Scanner periodico ─────────────────────────────────────────────────────
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScreenScan()
            handler.postDelayed(this, SCAN_DELAY_MS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScreenScan() {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw  = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    bmp?.let { analyzeFrame(it); it.recycle() }
                }
                override fun onFailure(e: Int) {
                    targetX = 0f; targetY = 0f
                }
            })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Analisi frame
    // ═══════════════════════════════════════════════════════════════════════════
    private fun analyzeFrame(bmp: Bitmap) {
        val cfg = BotConfig.load(this)
        val w = bmp.width; val h = bmp.height

        // ── 1. Rilevamento nomi mostri (rosso brillante) ──────────────────────
        // Zona ampliata: 7%-85% verticale per coprire anche mostri vicinissimi
        val yStart = (h * 0.07f).toInt()
        val yEnd   = (h * 0.85f).toInt()
        val xStart = 80  // skip overlay

        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yStart until yEnd step 4) {
            for (x in xStart until w step 4) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                // Rosso vivo dei nomi nemici (es. "Cane Selvaggio")
                if (r > 160 && g < 130 && b < 120 && r > g + 40 && r > b + 40) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count >= 10) {
            targetX = (sumX / count).toFloat(); targetY = (sumY / count).toFloat()
        } else {
            targetX = 0f; targetY = 0f
        }

        // ── 2. Barra HP: auto-rilevamento + lettura ratio ─────────────────────
        // Strategia: se configurata manualmente usa quelle; altrimenti auto-trova
        val barX: Int; val barY: Int; val barFullW: Int

        if (cfg.hpBarX > 0f) {
            // Configurazione manuale dell'utente
            barX = cfg.hpBarX.toInt(); barY = cfg.hpBarY.toInt()
            barFullW = cfg.hpBarFullWidth
            hpBarConfigured = true
        } else {
            // Auto-rilevamento: cerca la striscia rossa orizzontale in top-left
            if (autoBarX < 0) autoDetectHpBar(bmp, w, h)
            barX = autoBarX; barY = autoBarY; barFullW = autoBarFullW
        }

        if (barX >= 0 && barFullW > 0) {
            hpBarConfigured = true
            // Scansiona un rettangolo di 7px di altezza centrato sulla barra
            // Conta pixel rossi (HP rimasto) su tutta la larghezza della barra piena
            var redPx = 0; var totPx = 0
            val scanEnd = (barX + (barFullW * 1.1f).toInt()).coerceAtMost(w)
            for (dy in -3..3) {
                val sy = (barY + dy).coerceIn(0, h - 1)
                for (x in barX until scanEnd) {
                    val p = bmp.getPixel(x, sy)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    if (r > 120 && g < 110 && b < 110 && r > g + 25 && r > b + 25) redPx++
                    totPx++
                }
            }
            val newRatio = if (totPx > 0) redPx.toFloat() / totPx else 1.0f
            BotState.hpDisplayPct = (newRatio * 100).toInt()

            // ── Rilevamento perdita vita ───────────────────────────────────────
            val drop = BotState.lastHpRatio - newRatio
            if (drop > 0.012f) {        // drop >1.2% → vita calata
                BotState.hpDropCycles++
                BotState.hpStableCycles = 0
            } else {
                BotState.hpStableCycles++
                BotState.hpDropCycles = 0
            }
            BotState.lastHpRatio = newRatio
            scannedHpRatio = newRatio

            // Entra in difesa dopo 1 drop confermato
            if (!BotState.underAttack && BotState.hpDropCycles >= 1) BotState.underAttack = true
            // Esce da difesa dopo 4 cicli stabili
            if (BotState.underAttack && BotState.hpStableCycles >= 4) {
                BotState.underAttack = false; BotState.hpDropCycles = 0
            }
        }
    }

    // ── Auto-rilevamento barra HP ─────────────────────────────────────────────
    // Cerca la striscia orizzontale rossa nel top-left dello schermo.
    // La barra HP in Mobile2 è nel pannello in alto a sinistra, sotto il ritratto.
    // Dall'analisi degli screenshot: occupa il 5-7% dall'alto, il 25% da sinistra.
    private fun autoDetectHpBar(bmp: Bitmap, w: Int, h: Int) {
        val maxY = (h * 0.08f).toInt()   // cerca solo nel top 8%
        val maxX = (w * 0.28f).toInt()   // cerca solo nel left 28%

        for (y in 4 until maxY) {
            var firstRed = -1; var lastRed = -1; var redCount = 0

            for (x in 18 until maxX) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                // Pixel tipico della barra HP: rosso scuro/medio, G e B bassi
                if (r > 140 && g < 75 && b < 75) {
                    if (firstRed < 0) firstRed = x
                    lastRed = x; redCount++
                }
            }

            val stripeW = if (firstRed >= 0) lastRed - firstRed + 1 else 0
            val density = if (stripeW > 0) redCount.toFloat() / stripeW else 0f

            // Barra valida: larga almeno 40px con densità rossa ≥ 65%
            if (stripeW >= 40 && density >= 0.65f) {
                autoBarX = firstRed
                autoBarY = y
                // Stima larghezza piena: continua a scorrere per trovare la fine
                // dell'area della barra (pixel scuri = HP vuoto)
                var extX = lastRed + 1
                while (extX < maxX) {
                    val p = bmp.getPixel(extX, y)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    // Sfondo della barra vuota: molto scuro
                    if (r < 90 && g < 90 && b < 90) extX++
                    else break
                }
                autoBarFullW = (extX - firstRed).coerceAtLeast(stripeW + 10)
                break
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() { super.onDestroy(); stopBot(); if (instance === this) instance = null }

    fun startBot() {
        if (BotState.isRunning) return
        handler.removeCallbacksAndMessages(null)
        BotState.isRunning      = true
        BotState.sessionStartMs = System.currentTimeMillis()
        BotState.lastHpRatio    = 1.0f
        BotState.hpDropCycles   = 0; BotState.hpStableCycles = 0
        BotState.underAttack    = false; BotState.hpDisplayPct = -1
        huntCycles   = 0; potionUses = 0; inCombatCycles = 0; defendAngleIdx = 0
        lastSkill1Ms = 0L; lastSkill2Ms = 0L; lastSkill3Ms = 0L
        lastSkill4Ms = 0L; lastSkill5Ms = 0L
        phase = Phase.HUNT
        patrolDir = 0; patrolSteps = 0; cameraDir = 1
        targetX = 0f; targetY = 0f; prevTargetFound = false
        scannedHpRatio = 1.0f; hpBarConfigured = false
        autoBarX = -1; autoBarY = -1; autoBarFullW = 0
        handler.post(loop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, SCAN_DELAY_MS)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
        targetX = 0f; targetY = 0f
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HUNT
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doHunt(cfg: BotConfig) {
        val now       = System.currentTimeMillis()
        val hasTarget = targetX > 0f && targetY > 0f

        // Kill detection: target scomparso = mostro eliminato
        if (prevTargetFound && !hasTarget) BotState.killCount++
        prevTargetFound = hasTarget

        // ── Trigger difesa ────────────────────────────────────────────────────
        if (hasTarget) {
            inCombatCycles++
            // Fallback senza HP bar: se in combattimento da 5+ cicli → difesa
            if (!hpBarConfigured && inCombatCycles >= 5) BotState.underAttack = true
        } else {
            inCombatCycles = 0
            if (!hpBarConfigured) BotState.underAttack = false
        }

        if (BotState.underAttack) { phase = Phase.DEFEND; handler.post(loop); return }

        // ── Pozione ───────────────────────────────────────────────────────────
        val potionSet   = cfg.potionX > 0f
        val hpLow       = hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
        // Timer pozione di backup: ogni 12 cicli se non c'è HP bar
        val timerPotion = potionSet && !hpBarConfigured && huntCycles > 0 && huntCycles % 12 == 0
        if (potionSet && (hpLow || timerPotion)) { phase = Phase.POTION; handler.post(loop); return }

        // ── Movimento ─────────────────────────────────────────────────────────
        val useJoystick = cfg.joystickX > 0f && cfg.joystickY > 0f
        val useCamera   = cfg.cameraAreaX > 0f
        val doCamera    = useCamera && !hasTarget && huntCycles > 0 && huntCycles % CAMERA_EVERY == 0

        var t = 0L

        if (doCamera) {
            val cx = cfg.cameraAreaX; val cy = cfg.cameraAreaY; val r = cfg.cameraSwipeRange
            swipe(cx - cameraDir * r, cy, cx + cameraDir * r, cy, CAMERA_MS)
            cameraDir = -cameraDir
            t = CAMERA_MS + POST_MOVE_MS
        } else if (useJoystick) {
            val dir = PATROL_DIRS[patrolDir]
            joystickPush(
                cfg.joystickX, cfg.joystickY,
                cfg.joystickX + dir[0] * cfg.joystickRadius,
                cfg.joystickY + dir[1] * cfg.joystickRadius,
                JOYSTICK_MS
            )
            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) { patrolSteps = 0; patrolDir = (patrolDir + 1) % 4 }
            t = JOYSTICK_MS + POST_MOVE_MS
        }

        // ── Attacco ───────────────────────────────────────────────────────────
        val tx = targetX; val ty = targetY
        if (hasTarget) {
            handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, t)
            t += TAP_MS + GAP_MS
        }
        handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, t)
        t += TAP_MS + GAP_MS

        t = scheduleSkills(cfg, now, t)
        huntCycles++
        handler.postDelayed(loop, t + NEXT_CYCLE_EXTRA)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEFEND — attacca tutto intorno al personaggio
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doDefend(cfg: BotConfig) {
        val now = System.currentTimeMillis()

        if (!BotState.underAttack) {
            inCombatCycles = 0; phase = Phase.HUNT
            handler.postDelayed(loop, 100L); return
        }

        // Pozione se HP basso
        if (cfg.potionX > 0f && hpBarConfigured &&
            scannedHpRatio in 0.01f..cfg.hpPotionThreshold) {
            phase = Phase.POTION; handler.post(loop); return
        }

        var t = 0L

        // Tap circolare intorno al personaggio (8 angoli, uno per ciclo)
        if (cfg.playerX > 0f && cfg.playerY > 0f && cfg.defenseRadiusPx > 0) {
            val angle = Math.toRadians(DEFEND_ANGLES[defendAngleIdx].toDouble())
            val tapX  = cfg.playerX + (cos(angle) * cfg.defenseRadiusPx).toFloat()
            val tapY  = cfg.playerY + (sin(angle) * cfg.defenseRadiusPx).toFloat()
            tap(tapX, tapY)
            t += TAP_MS + GAP_MS
            defendAngleIdx = (defendAngleIdx + 1) % DEFEND_ANGLES.size
        }

        // Attacco principale
        handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, t)
        t += TAP_MS + GAP_MS

        // Anche se c'è un target visibile, selezionalo
        val tx = targetX; val ty = targetY
        if (tx > 0f && ty > 0f) {
            handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, t)
            t += TAP_MS + GAP_MS
            // Attacca di nuovo dopo la selezione
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, t)
            t += TAP_MS + GAP_MS
        }

        t = scheduleSkills(cfg, now, t)
        handler.postDelayed(loop, t + NEXT_CYCLE_EXTRA)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POTION
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doPotion(cfg: BotConfig) {
        tap(cfg.potionX, cfg.potionY)
        potionUses++
        phase = when {
            potionUses >= cfg.maxPotionsInSlot && cfg.backupPotionX > 0f -> Phase.REFILL
            BotState.underAttack -> Phase.DEFEND
            else -> Phase.HUNT
        }
        handler.postDelayed(loop, TAP_MS + 300L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REFILL
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doRefill(cfg: BotConfig) {
        swipe(cfg.backupPotionX, cfg.backupPotionY, cfg.potionX, cfg.potionY, 400L)
        potionUses = 0
        phase = if (BotState.underAttack) Phase.DEFEND else Phase.HUNT
        handler.postDelayed(loop, 700L)
    }

    // ── Abilità sequenziali (condivise da HUNT e DEFEND) ─────────────────────
    private fun scheduleSkills(cfg: BotConfig, now: Long, startT: Long): Long {
        var t = startT
        if (cfg.skill1X > 0f && now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            lastSkill1Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill1X, cfg.skill1Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill2X > 0f && now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            lastSkill2Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill2X, cfg.skill2Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill3X > 0f && cfg.skill3Y > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) {
            lastSkill3Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill3X, cfg.skill3Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill4X > 0f && cfg.skill4Y > 0f && now - lastSkill4Ms >= cfg.skill4CooldownMs) {
            lastSkill4Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill4X, cfg.skill4Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill5X > 0f && cfg.skill5Y > 0f && now - lastSkill5Ms >= cfg.skill5CooldownMs) {
            lastSkill5Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill5X, cfg.skill5Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        return t
    }

    // ── Gesti ─────────────────────────────────────────────────────────────────
    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build(),
            null, null
        )
    }
    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, dur: Long) {
        val path = Path().apply { moveTo(cx, cy); lineTo(tx, ty) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
            null, null
        )
    }
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
            null, null
        )
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
