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
    private val TAP_MS           = 60L   // durata singolo gesto tap
    private val GAP_MS           = 90L   // pausa tra tap consecutivi (buffer anticonflitto)
    private val JOYSTICK_MS      = 350L
    private val CAMERA_MS        = 250L
    private val POST_MOVE_MS     = 100L  // pausa dopo il gesto di movimento
    private val NEXT_CYCLE_EXTRA = 150L  // pausa extra dopo tutti i tap del ciclo
    private val SCAN_DELAY_MS    = 1200L
    private val CAMERA_EVERY     = 5     // ruota visuale ogni N cicli, SOLO senza target

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
    private var huntCycles       = 0
    private var potionUses       = 0
    private var inCombatCycles   = 0  // cicli consecutivi con target rilevato
    private var defendAngleIdx   = 0  // indice corrente per la tappatura circolare

    // 8 direzioni del cerchio di difesa (gradi → radianti calcolati al volo)
    private val DEFEND_ANGLES = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

    // ── Pixel detection (scritto dallo scanner, letto dal loop) ───────────────
    @Volatile private var targetX          = 0f
    @Volatile private var targetY          = 0f
    @Volatile private var prevTargetFound  = false
    @Volatile private var scannedHpRatio   = 1.0f
    @Volatile private var hpBarConfigured  = false

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS  = arrayOf(
        floatArrayOf(0f, -1f), floatArrayOf(1f, 0f),
        floatArrayOf(0f, 1f),  floatArrayOf(-1f, 0f),
    )

    private enum class Phase { HUNT, DEFEND, POTION, REFILL }
    private var phase = Phase.HUNT

    // ═══════════════════════════════════════════════════════════════════════════
    // Loop principale — ogni fase schedula il prossimo run DOPO le proprie azioni
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

    // ── Scanner schermo periodico (Android 11+) ───────────────────────────────
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
                    // Screenshot non disponibile (FLAG_SECURE): bot lavora senza visione
                    targetX = 0f; targetY = 0f
                }
            })
    }

    // ── Analisi frame ─────────────────────────────────────────────────────────
    private fun analyzeFrame(bmp: Bitmap) {
        val cfg = BotConfig.load(this)
        val w = bmp.width; val h = bmp.height

        // ── 1. Rilevamento nomi mostri ─────────────────────────────────────────
        // Zona ampliata: 8%-80% verticale, skip solo 80px a sinistra per l'overlay
        val yStart = (h * 0.08f).toInt()
        val yEnd   = (h * 0.80f).toInt()
        val xStart = 80

        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yStart until yEnd step 4) {
            for (x in xStart until w step 4) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                // Rosso vivace tipico dei nomi nemici
                if (r > 170 && g < 120 && b < 110) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count >= 12) {
            targetX = (sumX / count).toFloat(); targetY = (sumY / count).toFloat()
        } else {
            targetX = 0f; targetY = 0f
        }

        // ── 2. Lettura barra HP (scansione rettangolo 5px di altezza) ──────────
        if (cfg.hpBarX > 0f && cfg.hpBarY > 0f && cfg.hpBarFullWidth > 0) {
            hpBarConfigured = true
            val bx  = cfg.hpBarX.toInt()
            val by  = cfg.hpBarY.toInt()
            val bw  = cfg.hpBarFullWidth.coerceAtMost(w - bx)

            var redPx = 0; var totalPx = 0
            // Scansiona un rettangolo alto 5px centrato su hpBarY
            for (dy in -2..2) {
                val scanY = (by + dy).coerceIn(0, h - 1)
                for (x in bx until (bx + bw).coerceAtMost(w)) {
                    val p = bmp.getPixel(x, scanY)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    // Soglie più permissive: cattura rosso, arancio-rosso, bordeaux
                    if (r > 110 && g < 110 && b < 110 && r > g + 30 && r > b + 30) redPx++
                    totalPx++
                }
            }
            val newRatio = if (totalPx > 0) redPx.toFloat() / totalPx else 1.0f
            BotState.hpDisplayPct = (newRatio * 100).toInt()

            val drop = BotState.lastHpRatio - newRatio
            if (drop > 0.015f) {            // soglia abbassata a 1.5% (era 2.5%)
                BotState.hpDropCycles++
                BotState.hpStableCycles = 0
            } else {
                BotState.hpStableCycles++
                BotState.hpDropCycles = 0
            }
            BotState.lastHpRatio = newRatio
            scannedHpRatio = newRatio

            // 1 solo drop → difesa immediata (era 2)
            if (!BotState.underAttack && BotState.hpDropCycles >= 1) BotState.underAttack = true
            if (BotState.underAttack && BotState.hpStableCycles >= 3) {
                BotState.underAttack = false; BotState.hpDropCycles = 0
            }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() { super.onDestroy(); stopBot(); if (instance === this) instance = null }

    // ── Start / Stop ──────────────────────────────────────────────────────────
    fun startBot() {
        if (BotState.isRunning) return
        handler.removeCallbacksAndMessages(null)
        BotState.isRunning       = true
        BotState.sessionStartMs  = System.currentTimeMillis()
        BotState.lastHpRatio     = 1.0f
        BotState.hpDropCycles    = 0; BotState.hpStableCycles = 0
        BotState.underAttack     = false
        BotState.hpDisplayPct    = -1
        huntCycles    = 0; potionUses = 0; inCombatCycles = 0; defendAngleIdx = 0
        lastSkill1Ms  = 0L; lastSkill2Ms = 0L; lastSkill3Ms = 0L
        lastSkill4Ms  = 0L; lastSkill5Ms = 0L
        phase         = Phase.HUNT
        patrolDir     = 0; patrolSteps = 0; cameraDir = 1
        targetX       = 0f; targetY = 0f; prevTargetFound = false
        scannedHpRatio = 1.0f; hpBarConfigured = false
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
    // HUNT — caccia normale
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doHunt(cfg: BotConfig) {
        val now      = System.currentTimeMillis()
        val hasTarget = targetX > 0f && targetY > 0f

        // ── Kill detection ────────────────────────────────────────────────────
        if (prevTargetFound && !hasTarget) BotState.killCount++
        prevTargetFound = hasTarget

        // ── Trigger difesa ────────────────────────────────────────────────────
        // Metodo 1: HP bar (se configurata)
        // Metodo 2: fallback — se siamo in combattimento da troppi cicli senza HP bar
        if (hasTarget) {
            inCombatCycles++
            if (!hpBarConfigured && inCombatCycles >= 6) BotState.underAttack = true
        } else {
            inCombatCycles = 0
            if (!hpBarConfigured) BotState.underAttack = false
        }

        if (BotState.underAttack) { phase = Phase.DEFEND; handler.post(loop); return }

        // ── Pozione ───────────────────────────────────────────────────────────
        val potionSet   = cfg.potionX > 0f
        val hpLow       = hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
        val timerPotion = potionSet && !hpBarConfigured && huntCycles > 0 && huntCycles % 15 == 0
        if (potionSet && (hpLow || timerPotion)) { phase = Phase.POTION; handler.post(loop); return }

        // ── Movimento ─────────────────────────────────────────────────────────
        val useJoystick = cfg.joystickX > 0f && cfg.joystickY > 0f
        val useCamera   = cfg.cameraAreaX > 0f
        val doCamera    = useCamera && !hasTarget && huntCycles > 0 && (huntCycles % CAMERA_EVERY == 0)

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
            // Seleziona il bersaglio toccando il suo nome
            handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, t)
            t += TAP_MS + GAP_MS
        }

        // Preme il tasto attacco
        handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, t)
        t += TAP_MS + GAP_MS

        // ── Abilità (aggiorna cooldown subito per evitare doppio fire) ─────────
        t = scheduleSkills(cfg, now, t)

        huntCycles++
        // Il prossimo ciclo parte solo dopo che TUTTE le azioni sono terminate
        handler.postDelayed(loop, t + NEXT_CYCLE_EXTRA)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEFEND — spam attacco + cerchio intorno al personaggio
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doDefend(cfg: BotConfig) {
        val now = System.currentTimeMillis()

        // Rientro in caccia se HP stabile e niente più attacco
        if (!BotState.underAttack) {
            inCombatCycles = 0
            phase = Phase.HUNT
            handler.postDelayed(loop, 100L)
            return
        }

        // Pozione se HP basso
        if (cfg.potionX > 0f) {
            val hpLow = hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
            if (hpLow) { phase = Phase.POTION; handler.post(loop); return }
        }

        var t = 0L

        // ── Tap nel cerchio di difesa intorno al personaggio ──────────────────
        // Ogni ciclo tappa UN punto del cerchio (avanza di angolo ad ogni ciclo)
        if (cfg.playerX > 0f && cfg.playerY > 0f && cfg.defenseRadiusPx > 0) {
            val angleDeg = DEFEND_ANGLES[defendAngleIdx]
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val tapX = cfg.playerX + (cos(angleRad) * cfg.defenseRadiusPx).toFloat()
            val tapY = cfg.playerY + (sin(angleRad) * cfg.defenseRadiusPx).toFloat()
            tap(tapX, tapY)
            t += TAP_MS + GAP_MS
            defendAngleIdx = (defendAngleIdx + 1) % DEFEND_ANGLES.size
        }

        // ── Attacco principale ────────────────────────────────────────────────
        handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, t)
        t += TAP_MS + GAP_MS

        // ── Abilità (tutte, se disponibili) ───────────────────────────────────
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

    // ── Abilità 1-5 sequenziali (usato da HUNT e DEFEND) ─────────────────────
    // Ritorna il nuovo valore di t (offset temporale dopo l'ultima abilità)
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

    // ── Primitive gesti ───────────────────────────────────────────────────────
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
