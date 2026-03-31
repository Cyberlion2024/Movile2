package com.movile2.bot

// ═══════════════════════════════════════════════════════════════════════════════
// Analisi libUE4.so (Mobile2 Global 2.23 — ARM64 stripped, 182 MB)
//
// Costanti trovate nel codice nativo del gioco:
//   ATTACK_RANGE, ATTACK_SPEED, AttackTimeMsec  → timing 60ms confermato corretto
//   AGGRESSIVE_HP_PCT, AGGRESSIVE_SIGHT         → mob aggro attivo; patrol raggio contenuto
//   MOB_COLOR (nome mob = rosso vivace)          → R>170, G<110, B<110 confermato
//   SKILL_VNUM0..4, SKILL_LEVEL0..4             → 5 slot abilità confermati
//   dropLocs, DROP_ITEM, dropedAt               → drop con posizione precisa; centroide OK
//   yang, buyuAttack, buyuDef, coinBonusYuzde   → gioco derivato da Metin2 (turco)
//
// IMPORTANTE — Emulator detection integrata nel gioco:
//   com.bluestacks.*, com.bignox.*, com.microvirt.*, com.nox.mopen.app,
//   com.vphone.*, org.chromium.arc
//   → usare SOLO dispositivi fisici reali, non emulatori noti.
// ═══════════════════════════════════════════════════════════════════════════════

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
    // TAP_MS = 60ms confermato da AttackTimeMsec nel codice nativo del gioco
    private val TAP_MS           = 60L
    private val GAP_MS           = 90L
    private val JOYSTICK_MS      = 350L
    private val CAMERA_MS        = 250L
    private val POST_MOVE_MS     = 100L
    private val NEXT_CYCLE_EXTRA = 150L
    private val SCAN_DELAY_MS    = 1000L
    private val CAMERA_EVERY     = 5        // ruota telecamera ogni 5 cicli HUNT senza bersaglio
    private val COMBAT_GRACE_MS  = 2500L
    private val TARGET_GRACE_MS  = 1800L
    private val LOOT_TAP_CD_MS   = 1200L   // dropedAt: i drop hanno lifetime limitato
    private val POTION_CD_MS     = 2500L   // minimo tra una pozione e la successiva

    // ── Soglie colore pixel (da analisi MOB_COLOR in libUE4.so) ───────────────
    // Nomi mostri nemici: rosso vivace tipico Metin2 (R alto, G/B bassi)
    private val MOB_R_MIN  = 170;  private val MOB_G_MAX  = 110;  private val MOB_B_MAX  = 110
    private val MOB_R_DIFF = 45    // R deve superare G e B di almeno 45
    // Barra HP (striscia rossa in top-left): rosso meno saturo del nome mob
    private val HP_R_MIN   = 120;  private val HP_G_MAX   = 110;  private val HP_B_MAX   = 110
    private val HP_R_DIFF  = 25

    // ── Coordinate automatiche (calcolate da risoluzione schermo) ─────────────
    // Layout Mobile2 Global derivato dall'analisi degli screenshot.
    // Valori proporzionali → moltiplicati per sw/sh a runtime.
    // L'utente può sovrascrivere ognuno dall'app (se > 0 nel cfg la preferenza è manuale).
    private var aAttackX       = 0f; private var aAttackY       = 0f
    private var aSkill1X       = 0f; private var aSkill1Y       = 0f
    private var aSkill2X       = 0f; private var aSkill2Y       = 0f
    private var aSkill3X       = 0f; private var aSkill3Y       = 0f
    private var aSkill4X       = 0f; private var aSkill4Y       = 0f
    private var aSkill5X       = 0f; private var aSkill5Y       = 0f
    private var aPotionX       = 0f; private var aPotionY       = 0f
    private var aBackupPotX    = 0f; private var aBackupPotY    = 0f
    private var aJoystickX     = 0f; private var aJoystickY     = 0f; private var aJoystickR  = 0f
    private var aCameraX       = 0f; private var aCameraY       = 0f; private var aCameraRange = 0f
    private var aPlayerX       = 0f; private var aPlayerY       = 0f; private var aDefenseR   = 0

    // Resolve: usa la coordinata manuale se > 0, altrimenti quella automatica
    private fun r(manual: Float, auto: Float) = if (manual > 0f) manual else auto
    private fun ri(manual: Int, auto: Int)    = if (manual > 0)  manual else auto

    // ── Cooldown abilità ──────────────────────────────────────────────────────
    private var lastSkill1Ms = 0L; private var lastSkill2Ms = 0L
    private var lastSkill3Ms = 0L; private var lastSkill4Ms = 0L; private var lastSkill5Ms = 0L

    // ── Timestamp eventi ──────────────────────────────────────────────────────
    private var lastDamageMs     = 0L
    private var lastTargetSeenMs = 0L
    private var lastLootTapMs    = 0L
    private var lastPotionTapMs  = 0L
    private var lastTargetX      = 0f
    private var lastTargetY      = 0f

    // ── Pattugliamento ────────────────────────────────────────────────────────
    private var patrolDir = 0; private var patrolSteps = 0; private var cameraDir = 1

    // ── Stato ciclo ───────────────────────────────────────────────────────────
    private var huntCycles = 0; private var potionUses = 0
    private var inCombatCycles = 0; private var defendAngleIdx = 0

    private val DEFEND_ANGLES = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

    // ── Pixel detection ───────────────────────────────────────────────────────
    @Volatile private var targetX         = 0f
    @Volatile private var targetY         = 0f
    @Volatile private var prevTargetFound = false
    @Volatile private var scannedHpRatio  = 1.0f
    @Volatile private var hpBarConfigured = false
    @Volatile private var lootX = 0f
    @Volatile private var lootY = 0f

    // Auto HP bar detection dal pixel scanning
    @Volatile private var autoBarX     = -1
    @Volatile private var autoBarY     = -1
    @Volatile private var autoBarFullW = 0
    @Volatile private var autoPlayerTrackX = 0f
    @Volatile private var autoPlayerTrackY = 0f
    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS  = arrayOf(
        floatArrayOf(0f, -1f), floatArrayOf(1f, 0f),
        floatArrayOf(0f, 1f),  floatArrayOf(-1f, 0f),
    )

    private enum class Phase { HUNT, DEFEND, POTION, REFILL }
    private var phase = Phase.HUNT

    // ═══════════════════════════════════════════════════════════════════════════
    // Calcolo coordinate automatiche dalla risoluzione dello schermo
    // Proporzioni derivate dall'analisi degli screenshot di Mobile2 Global:
    //   - Pannello tasti in basso a destra: y≈80-85%, x≈77-90%
    //   - Joystick in basso a sinistra: x≈8%, y≈84%
    //   - Personaggio al centro: x≈46%, y≈39%
    // ═══════════════════════════════════════════════════════════════════════════
    private fun initAutoCoords() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()

        // Righe del pannello skill (in basso a destra)
        val topRowY = sh * 0.800f   // riga superiore: pozioni/oggetti
        val botRowY = sh * 0.849f   // riga inferiore: skill + attacco

        // Colonne del pannello (4 slot + attacco)
        val col1 = sw * 0.771f      // primo slot (sinistra)
        val col2 = sw * 0.819f      // secondo slot
        val col3 = sw * 0.866f      // terzo slot
        val col4 = sw * 0.904f      // tasto attacco (destra)

        aAttackX    = col4;  aAttackY    = botRowY
        aSkill1X    = col1;  aSkill1Y    = botRowY
        aSkill2X    = col2;  aSkill2Y    = botRowY
        aSkill3X    = col4;  aSkill3Y    = topRowY
        aSkill4X    = col3;  aSkill4Y    = botRowY
        aSkill5X    = col3;  aSkill5Y    = topRowY
        aPotionX    = col1;  aPotionY    = topRowY   // prima pozione (slot sinistra)
        aBackupPotX = col2;  aBackupPotY = topRowY   // seconda pozione (slot a destra)

        // Joystick (cerchio grande in basso a sinistra)
        aJoystickX  = sw * 0.083f
        aJoystickY  = sh * 0.844f
        aJoystickR  = sw * 0.065f

        // Area telecamera (trascina centro schermo per ruotare)
        aCameraX     = sw * 0.500f
        aCameraY     = sh * 0.375f
        aCameraRange = sw * 0.140f

        // Centro personaggio (bashy: circa centro-sinistra del game world)
        aPlayerX  = sw * 0.455f
        aPlayerY  = sh * 0.395f
        aDefenseR = (sw * 0.165f).toInt()   // raggio difesa ~165px su 1080px
    }

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

        // ── 0. Tracking giocatore (nome verde -> centro corpo) ───────────────
        val playerScanX0 = (w * 0.20f).toInt()
        val playerScanX1 = (w * 0.80f).toInt()
        val playerScanY0 = (h * 0.18f).toInt()
        val playerScanY1 = (h * 0.70f).toInt()
        var pgX = 0L; var pgY = 0L; var pgCount = 0
        for (y in playerScanY0 until playerScanY1 step 4) {
            for (x in playerScanX0 until playerScanX1 step 4) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (g > 145 && g > r + 28 && g > b + 20) {
                    pgX += x; pgY += y; pgCount++
                }
            }
        }
        if (pgCount >= 8) {
            val nameX = (pgX / pgCount).toFloat()
            val nameY = (pgY / pgCount).toFloat()
            autoPlayerTrackX = nameX
            autoPlayerTrackY = (nameY + h * 0.05f).coerceAtMost(h - 1f)
        }

        // ── 1. Rilevamento nomi mostri ────────────────────────────────────────
        // Rosso vivo = nome nemico (es. "Cane Selvaggio")
        // Zona: 7%-85% verticale, skip 80px a sinistra (overlay bot)
        val yStart = (h * 0.07f).toInt(); val yEnd = (h * 0.85f).toInt()
        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yStart until yEnd step 4) {
            for (x in 80 until w step 4) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > MOB_R_MIN && g < MOB_G_MAX && b < MOB_B_MAX && r > g + MOB_R_DIFF && r > b + MOB_R_DIFF) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count >= 10) {
            targetX = (sumX / count).toFloat(); targetY = (sumY / count).toFloat()
            lastTargetSeenMs = System.currentTimeMillis()
            lastTargetX = targetX; lastTargetY = targetY
        } else {
            targetX = 0f; targetY = 0f
        }

        // ── 2. Barra HP ───────────────────────────────────────────────────────
        val barX: Int; val barY: Int; val barFullW: Int
        if (cfg.hpBarX > 0f) {
            barX = cfg.hpBarX.toInt(); barY = cfg.hpBarY.toInt()
            barFullW = if (cfg.hpBarFullWidth > 0) cfg.hpBarFullWidth else 160
            hpBarConfigured = true
        } else {
            if (autoBarX < 0) autoDetectHpBar(bmp, w, h)
            barX = autoBarX; barY = autoBarY; barFullW = autoBarFullW
        }

        if (barX >= 0 && barFullW > 0) {
            hpBarConfigured = true
            var redPx = 0; var totPx = 0
            val scanEnd = (barX + (barFullW * 1.1f).toInt()).coerceAtMost(w)
            for (dy in -3..3) {
                val sy = (barY + dy).coerceIn(0, h - 1)
                for (x in barX until scanEnd) {
                    val p = bmp.getPixel(x, sy)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    if (r > HP_R_MIN && g < HP_G_MAX && b < HP_B_MAX && r > g + HP_R_DIFF && r > b + HP_R_DIFF) redPx++
                    totPx++
                }
            }
            val newRatio = if (totPx > 0) redPx.toFloat() / totPx else 1.0f
            BotState.hpDisplayPct = (newRatio * 100).toInt()

            val drop = BotState.lastHpRatio - newRatio
            if (drop > 0.012f) {
                BotState.hpDropCycles++; BotState.hpStableCycles = 0
                lastDamageMs = System.currentTimeMillis()
            } else {
                BotState.hpStableCycles++; BotState.hpDropCycles = 0
            }
            BotState.lastHpRatio = newRatio
            scannedHpRatio = newRatio

            if (!BotState.underAttack && BotState.hpDropCycles >= 1)  BotState.underAttack = true
            if ( BotState.underAttack && BotState.hpStableCycles >= 4) {
                BotState.underAttack = false; BotState.hpDropCycles = 0
            }
        }

        // ── 3. Loot a terra (Yang + drop col nome in verde) ─────────────────
        detectLoot(bmp, w, h)
    }

    private fun detectLoot(bmp: Bitmap, w: Int, h: Int) {
        val x0 = (w * 0.12f).toInt()
        val x1 = (w * 0.88f).toInt()
        val y0 = (h * 0.16f).toInt()
        val y1 = (h * 0.86f).toInt()
        var sx = 0L; var sy = 0L; var cnt = 0
        for (y in y0 until y1 step 5) {
            for (x in x0 until x1 step 5) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val whiteText = r > 175 && g > 175 && b > 175 && kotlin.math.abs(r - g) < 24 && kotlin.math.abs(r - b) < 24
                val greenText = g > 155 && g > r + 25 && g > b + 18
                if (whiteText || greenText) {
                    sx += x; sy += y; cnt++
                }
            }
        }
        if (cnt >= 7) {
            lootX = (sx / cnt).toFloat()
            lootY = (sy / cnt).toFloat() + (h * 0.02f)
        } else {
            lootX = 0f; lootY = 0f
        }
    }

    // Cerca la striscia rossa orizzontale (barra HP) nel top-left dello schermo
    private fun autoDetectHpBar(bmp: Bitmap, w: Int, h: Int) {
        val maxY = (h * 0.22f).toInt(); val maxX = (w * 0.35f).toInt()
        for (y in 4 until maxY) {
            var firstRed = -1; var lastRed = -1; var redCount = 0
            for (x in 14 until maxX) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > HP_R_MIN && r > g + HP_R_DIFF && r > b + HP_R_DIFF) {
                    if (firstRed < 0) firstRed = x; lastRed = x; redCount++
                }
            }
            val stripeW = if (firstRed >= 0) lastRed - firstRed + 1 else 0
            if (stripeW in 50..320 && redCount.toFloat() / stripeW >= 0.45f) {
                autoBarX = firstRed; autoBarY = y
                var extX = lastRed + 1
                while (extX < maxX) {
                    val p = bmp.getPixel(extX, y)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    if (r < 90 && g < 90 && b < 90) extX++ else break
                }
                autoBarFullW = (extX - firstRed).coerceAtLeast(stripeW + 16)
                break
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        instance = this
        initAutoCoords()
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() { super.onDestroy(); stopBot(); if (instance === this) instance = null }

    fun startBot() {
        if (BotState.isRunning) return
        handler.removeCallbacksAndMessages(null)
        initAutoCoords()   // ricalcola sempre a ogni avvio
        BotState.isRunning      = true
        BotState.sessionStartMs = System.currentTimeMillis()
        BotState.lastHpRatio    = 1.0f
        BotState.hpDropCycles   = 0; BotState.hpStableCycles = 0
        BotState.underAttack    = false; BotState.hpDisplayPct = -1
        huntCycles = 0; potionUses = 0; inCombatCycles = 0; defendAngleIdx = 0
        lastSkill1Ms = 0L; lastSkill2Ms = 0L; lastSkill3Ms = 0L
        lastSkill4Ms = 0L; lastSkill5Ms = 0L
        phase = Phase.HUNT; patrolDir = 0; patrolSteps = 0; cameraDir = 1
        targetX = 0f; targetY = 0f; prevTargetFound = false
        scannedHpRatio = 1.0f; hpBarConfigured = false
        lootX = 0f; lootY = 0f
        lastDamageMs = 0L; lastTargetSeenMs = 0L
        lastLootTapMs = 0L; lastTargetX = 0f; lastTargetY = 0f
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
        val now = System.currentTimeMillis()
        val hasTarget = targetX > 0f && targetY > 0f
        val recentCombat = (now - lastDamageMs) < COMBAT_GRACE_MS || (now - lastTargetSeenMs) < TARGET_GRACE_MS

        if (prevTargetFound && !hasTarget) BotState.killCount++
        prevTargetFound = hasTarget

        if (hasTarget) {
            inCombatCycles++
            if (!hpBarConfigured && inCombatCycles >= 5) BotState.underAttack = true
        } else {
            inCombatCycles = 0
        }
        if (BotState.underAttack || recentCombat) { phase = Phase.DEFEND; handler.post(loop); return }

        // Coordinate effettive (manuale se impostato, altrimenti automatico)
        val potX = r(cfg.potionX, aPotionX); val potY = r(cfg.potionY, aPotionY)
        val potionActive = potX > 0f
        val hpLow       = hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
        val timerPotion = potionActive && (!hpBarConfigured || recentCombat) && huntCycles > 0 && huntCycles % 4 == 0
        if (potionActive && (hpLow || timerPotion) && now - lastPotionTapMs >= POTION_CD_MS) { phase = Phase.POTION; handler.post(loop); return }

        val jx = r(cfg.joystickX, aJoystickX); val jy = r(cfg.joystickY, aJoystickY)
        val jr = r(cfg.joystickRadius, aJoystickR)
        val cx = r(cfg.cameraAreaX, aCameraX); val cy = r(cfg.cameraAreaY, aCameraY)
        val cr = r(cfg.cameraSwipeRange, aCameraRange)

        val useJoystick = jx > 0f && jy > 0f
        val useCamera   = cx > 0f
        val doCamera    = useCamera && !hasTarget && !recentCombat && huntCycles > 0 && huntCycles % CAMERA_EVERY == 0
        val ax = r(cfg.attackX, aAttackX); val ay = r(cfg.attackY, aAttackY)

        var t = 0L

        if (doCamera) {
            swipe(cx - cameraDir * cr, cy, cx + cameraDir * cr, cy, CAMERA_MS)
            cameraDir = -cameraDir; t = CAMERA_MS + POST_MOVE_MS
        } else if (useJoystick && !BotState.underAttack && !recentCombat) {
            val dir = PATROL_DIRS[patrolDir]
            joystickPush(jx, jy, jx + dir[0] * jr, jy + dir[1] * jr, JOYSTICK_MS)
            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) { patrolSteps = 0; patrolDir = (patrolDir + 1) % 4 }
            t = JOYSTICK_MS + POST_MOVE_MS
        }

        val tx = if (hasTarget) targetX else lastTargetX
        val ty = if (hasTarget) targetY else lastTargetY
        if (tx > 0f && ty > 0f && (hasTarget || recentCombat)) {
            handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, t); t += TAP_MS + GAP_MS
        }
        repeat(ATTACK_SPAM_COUNT) {
            handler.postDelayed({ if (BotState.isRunning) tap(ax, ay) }, t)
            t += TAP_MS + GAP_MS
        }

        // Raccolta loot quando non siamo più in combattimento
        if (!hasTarget && !recentCombat && lootX > 0f && now - lastLootTapMs >= LOOT_TAP_CD_MS) {
            val lx = lootX; val ly = lootY
            handler.postDelayed({ if (BotState.isRunning) tap(lx, ly) }, t)
            lastLootTapMs = now
            t += TAP_MS + GAP_MS
        }
        // fallback raccolta: tap attorno al player quando il combat è finito da poco
        if (!hasTarget && !recentCombat && now - lastCombatSeenMs < 9000L && now - lastLootTapMs >= LOOT_TAP_CD_MS) {
            val px = if (cfg.playerX > 0f) cfg.playerX else if (autoPlayerTrackX > 0f) autoPlayerTrackX else aPlayerX
            val py = if (cfg.playerY > 0f) cfg.playerY else if (autoPlayerTrackY > 0f) autoPlayerTrackY else aPlayerY
            val r = ri(cfg.defenseRadiusPx, aDefenseR).coerceAtLeast(120)
            val lx = px + r * 0.35f
            val ly = py + r * 0.15f
            handler.postDelayed({ if (BotState.isRunning) tap(lx, ly) }, t)
            lastLootTapMs = now
            t += TAP_MS + GAP_MS
        }

        // Raccolta loot quando non siamo più in combattimento
        if (!hasTarget && !recentCombat && lootX > 0f && now - lastLootTapMs >= LOOT_TAP_CD_MS) {
            val lx = lootX; val ly = lootY
            handler.postDelayed({ if (BotState.isRunning) tap(lx, ly) }, t)
            lastLootTapMs = now
            t += TAP_MS + GAP_MS
        }

        t = scheduleSkills(cfg, now, t)
        huntCycles++
        handler.postDelayed(loop, t + NEXT_CYCLE_EXTRA)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEFEND
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doDefend(cfg: BotConfig) {
        val now = System.currentTimeMillis()
        val recentCombat = (now - lastDamageMs) < COMBAT_GRACE_MS || (now - lastTargetSeenMs) < TARGET_GRACE_MS
        if (!BotState.underAttack && !recentCombat) {
            inCombatCycles = 0; phase = Phase.HUNT
            handler.postDelayed(loop, 100L); return
        }

        val potX = r(cfg.potionX, aPotionX); val potY = r(cfg.potionY, aPotionY)
        val potionByHp = potX > 0f && hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
        val potionByTimer = potX > 0f && !hpBarConfigured && now % 3200L < 250L
        if ((potionByHp || potionByTimer) && now - lastPotionTapMs >= POTION_CD_MS) {
            phase = Phase.POTION; handler.post(loop); return
        }

        val ax = r(cfg.attackX, aAttackX); val ay = r(cfg.attackY, aAttackY)
        val px = if (cfg.playerX > 0f) cfg.playerX else if (autoPlayerTrackX > 0f) autoPlayerTrackX else aPlayerX
        val py = if (cfg.playerY > 0f) cfg.playerY else if (autoPlayerTrackY > 0f) autoPlayerTrackY else aPlayerY
        val dr = ri(cfg.defenseRadiusPx, aDefenseR)

        var t = 0L

        // Tap circolare intorno al personaggio
        if (px > 0f && py > 0f && dr > 0) {
            val angle = Math.toRadians(DEFEND_ANGLES[defendAngleIdx].toDouble())
            val tapX  = px + (cos(angle) * dr).toFloat()
            val tapY  = py + (sin(angle) * dr).toFloat()
            tap(tapX, tapY); t += TAP_MS + GAP_MS
            defendAngleIdx = (defendAngleIdx + 1) % DEFEND_ANGLES.size
        }

        repeat(ATTACK_SPAM_COUNT) {
            handler.postDelayed({ if (BotState.isRunning) tap(ax, ay) }, t)
            t += TAP_MS + GAP_MS
        }

        val tx = if (targetX > 0f) targetX else lastTargetX
        val ty = if (targetY > 0f) targetY else lastTargetY
        if (tx > 0f && ty > 0f) {
            handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, t); t += TAP_MS + GAP_MS
            repeat(2) {
                handler.postDelayed({ if (BotState.isRunning) tap(ax, ay) }, t)
                t += TAP_MS + GAP_MS
            }
        }

        t = scheduleSkills(cfg, now, t)
        handler.postDelayed(loop, t + NEXT_CYCLE_EXTRA)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POTION
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doPotion(cfg: BotConfig) {
        val potX = r(cfg.potionX, aPotionX); val potY = r(cfg.potionY, aPotionY)
        tap(potX, potY)
        lastPotionTapMs = System.currentTimeMillis()
        potionUses++
        val backX = r(cfg.backupPotionX, aBackupPotX)
        val now = System.currentTimeMillis()
        val recentCombat = (now - lastDamageMs) < COMBAT_GRACE_MS || (now - lastTargetSeenMs) < TARGET_GRACE_MS
        phase = when {
            potionUses >= cfg.maxPotionsInSlot && backX > 0f -> Phase.REFILL
            BotState.underAttack || recentCombat -> Phase.DEFEND
            else -> Phase.HUNT
        }
        handler.postDelayed(loop, TAP_MS + 300L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REFILL
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doRefill(cfg: BotConfig) {
        val potX = r(cfg.potionX, aPotionX);    val potY = r(cfg.potionY, aPotionY)
        val bkX  = r(cfg.backupPotionX, aBackupPotX); val bkY = r(cfg.backupPotionY, aBackupPotY)
        swipe(bkX, bkY, potX, potY, 400L)
        potionUses = 0
        phase = if (BotState.underAttack) Phase.DEFEND else Phase.HUNT
        handler.postDelayed(loop, 700L)
    }

    // ── Abilità ───────────────────────────────────────────────────────────────
    private fun scheduleSkills(cfg: BotConfig, now: Long, startT: Long): Long {
        var t = startT
        val s1x = r(cfg.skill1X, aSkill1X); val s1y = r(cfg.skill1Y, aSkill1Y)
        val s2x = r(cfg.skill2X, aSkill2X); val s2y = r(cfg.skill2Y, aSkill2Y)
        val s3x = r(cfg.skill3X, aSkill3X); val s3y = r(cfg.skill3Y, aSkill3Y)
        val s4x = r(cfg.skill4X, aSkill4X); val s4y = r(cfg.skill4Y, aSkill4Y)
        val s5x = r(cfg.skill5X, aSkill5X); val s5y = r(cfg.skill5Y, aSkill5Y)

        if (s1x > 0f && now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            lastSkill1Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(s1x, s1y) }, ft); t += TAP_MS + GAP_MS
        }
        if (s2x > 0f && now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            lastSkill2Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(s2x, s2y) }, ft); t += TAP_MS + GAP_MS
        }
        if (s3x > 0f && s3y > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) {
            lastSkill3Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(s3x, s3y) }, ft); t += TAP_MS + GAP_MS
        }
        if (s4x > 0f && s4y > 0f && now - lastSkill4Ms >= cfg.skill4CooldownMs) {
            lastSkill4Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(s4x, s4y) }, ft); t += TAP_MS + GAP_MS
        }
        if (s5x > 0f && s5y > 0f && now - lastSkill5Ms >= cfg.skill5CooldownMs) {
            lastSkill5Ms = now; val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(s5x, s5y) }, ft); t += TAP_MS + GAP_MS
        }
        return t
    }

    // ── Gesti ─────────────────────────────────────────────────────────────────
    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build(),
            null, null)
    }
    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, dur: Long) {
        val path = Path().apply { moveTo(cx, cy); lineTo(tx, ty) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
            null, null)
    }
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
            null, null)
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
