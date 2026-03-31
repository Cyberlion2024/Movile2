package com.movile2.bot

// ═══════════════════════════════════════════════════════════════════════════
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
// ═══════════════════════════════════════════════════════════════════════════

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
import kotlin.math.abs

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // ── Timing (AttackTimeMsec = 60ms confermato da libUE4.so) ───────────────
    private val TAP_MS            = 60L
    private val GAP_MS            = 75L
    private val JOYSTICK_MS       = 280L
    private val CAMERA_MS         = 200L
    private val POST_MOVE_MS      = 60L
    private val COMBAT_CYCLE_MS   = 320L
    private val PATROL_CYCLE_MS   = 550L
    private val SCAN_DELAY_MS     = 600L
    private val CAMERA_EVERY      = 4
    private val COMBAT_GRACE_MS   = 1800L
    private val TARGET_GRACE_MS   = 1200L
    private val LOOT_TAP_CD_MS    = 700L
    private val POTION_CD_MS      = 3000L   // 3s minimo tra pozioni per non spam
    private val LOOT_GRACE_MS     = 7000L
    private val REFILL_DELAY_MS   = 600L    // attesa dopo drag inventario→slot

    // ── Soglie pixel ──────────────────────────────────────────────────────────
    private val MOB_R_MIN  = 160;  private val MOB_G_MAX  = 118;  private val MOB_B_MAX  = 118
    private val MOB_R_DIFF = 38
    private val HP_R_MIN   = 110;  private val HP_G_MAX   = 115;  private val HP_B_MAX   = 115
    private val HP_R_DIFF  = 20
    // Soglia per considerare uno slot pozione "vuoto" (no pixel rossi = nessuna icona pozione)
    private val SLOT_RED_THRESHOLD = 0.08f  // meno del 8% pixel rossi = slot vuoto

    // ── Coordinate automatiche ────────────────────────────────────────────────
    private var aAttackX = 0f;    private var aAttackY = 0f
    private var aSkill1X = 0f;    private var aSkill1Y = 0f
    private var aSkill2X = 0f;    private var aSkill2Y = 0f
    private var aSkill3X = 0f;    private var aSkill3Y = 0f
    private var aSkill4X = 0f;    private var aSkill4Y = 0f
    private var aSkill5X = 0f;    private var aSkill5Y = 0f
    private var aPotion1X = 0f;   private var aPotion1Y = 0f
    private var aJoystickX = 0f;  private var aJoystickY = 0f; private var aJoystickR = 0f
    private var aCameraX = 0f;    private var aCameraY = 0f;   private var aCameraRange = 0f
    private var aPlayerX = 0f;    private var aPlayerY = 0f;   private var aDefenseR = 0

    private fun r(manual: Float, auto: Float)  = if (manual > 0f) manual else auto
    private fun ri(manual: Int,   auto: Int)   = if (manual > 0)  manual else auto

    // ── Cooldown abilità ──────────────────────────────────────────────────────
    private var lastSkill1Ms = 0L; private var lastSkill2Ms = 0L; private var lastSkill3Ms = 0L
    private var lastSkill4Ms = 0L; private var lastSkill5Ms = 0L

    // ── Timestamp ─────────────────────────────────────────────────────────────
    private var lastDamageMs     = 0L
    private var lastTargetSeenMs = 0L
    private var lastCombatSeenMs = 0L
    private var lastLootTapMs    = 0L
    private var lastPotionTapMs  = 0L
    private var lastTargetX      = 0f
    private var lastTargetY      = 0f

    // ── Stato pattuglia ───────────────────────────────────────────────────────
    private var patrolDir   = 0
    private var patrolSteps = 0
    private var cameraDir   = 1
    private var huntCycles  = 0
    private var prevTargetFound = false

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS  = arrayOf(
        floatArrayOf( 0f, -1f),
        floatArrayOf( 1f,  0f),
        floatArrayOf( 0f,  1f),
        floatArrayOf(-1f,  0f),
    )

    // ── Sistema multi-slot pozioni ────────────────────────────────────────────
    // currentSlotIdx: indice dello slot attivo (0-based su lista slots configurati)
    // slotEmptyFlags: true se il pixel check ha rilevato slot vuoto
    // isRefilling:    true mentre si esegue il drag inventario→slot
    private var currentSlotIdx  = 0
    private var slotEmptyFlags  = BooleanArray(7) { false }
    private var isRefilling     = false
    private var lastSlotCheckBmp: Bitmap? = null

    // ── Pixel detection (thread-safe, tutto su main looper) ───────────────────
    @Volatile private var targetX           = 0f
    @Volatile private var targetY           = 0f
    @Volatile private var scannedHpRatio    = 1.0f
    @Volatile private var hpBarConfigured   = false
    @Volatile private var lootX             = 0f
    @Volatile private var lootY             = 0f
    @Volatile private var autoBarX          = -1
    @Volatile private var autoBarY          = -1
    @Volatile private var autoBarFullW      = 0
    @Volatile private var autoPlayerTrackX  = 0f
    @Volatile private var autoPlayerTrackY  = 0f

    // Risultato pixel check slot (aggiornato da analyzeFrame, letto da doLoop)
    @Volatile private var currentSlotIsEmpty = false

    // ═══════════════════════════════════════════════════════════════════════════
    // Coordinate automatiche — layout Mobile2 Global
    // ═══════════════════════════════════════════════════════════════════════════
    private fun initAutoCoords() {
        val dm  = resources.displayMetrics
        val sw  = dm.widthPixels.toFloat()
        val sh  = dm.heightPixels.toFloat()

        val topRowY = sh * 0.800f
        val botRowY = sh * 0.849f
        val col1    = sw * 0.771f
        val col2    = sw * 0.819f
        val col3    = sw * 0.866f
        val col4    = sw * 0.904f

        aAttackX = col4;      aAttackY = botRowY
        aSkill1X = col1;      aSkill1Y = botRowY
        aSkill2X = col2;      aSkill2Y = botRowY
        aSkill3X = col3;      aSkill3Y = botRowY
        aSkill4X = col4;      aSkill4Y = topRowY
        aSkill5X = col3;      aSkill5Y = topRowY
        aPotion1X = col1;     aPotion1Y = topRowY
        aJoystickX  = sw * 0.067f
        aJoystickY  = sh * 0.815f
        aJoystickR  = sw * 0.072f
        aCameraX    = sw * 0.530f
        aCameraY    = sh * 0.430f
        aCameraRange = sw * 0.165f
        aPlayerX    = sw * 0.500f
        aPlayerY    = sh * 0.510f
        aDefenseR   = (sw * 0.145f).toInt()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Restituisce la lista degli slot pozione attivi (configurati o auto)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun activePotionSlots(cfg: BotConfig): List<Pair<Float, Float>> {
        val manual = cfg.potionSlots()
        return if (manual.isNotEmpty()) manual
               else listOf(aPotion1X to aPotion1Y)  // fallback auto: 1 slot
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Controlla se uno slot pozione ha l'icona rossa (pozione presente)
    // Campiona una griglia di pixel attorno al centro dello slot.
    // Slot vuoto = nessun pixel rosso-vivo (sfondo grigio/scuro del gioco).
    // ═══════════════════════════════════════════════════════════════════════════
    private fun isSlotHasPotion(bmp: Bitmap, sx: Float, sy: Float): Boolean {
        val bw = bmp.width; val bh = bmp.height
        val cx = sx.toInt().coerceIn(16, bw - 17)
        val cy = sy.toInt().coerceIn(16, bh - 17)
        var redCount = 0; var total = 0
        for (dy in -14..14 step 4) {
            for (dx in -14..14 step 4) {
                val x = (cx + dx).coerceIn(0, bw - 1)
                val y = (cy + dy).coerceIn(0, bh - 1)
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                // Pixel rosso vivo = icona pozione vita HP
                if (rv > 140 && gv < 110 && bv < 110 && rv - gv > 50) redCount++
                total++
            }
        }
        val ratio = if (total > 0) redCount.toFloat() / total else 0f
        return ratio >= SLOT_RED_THRESHOLD
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP PRINCIPALE
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
            doLoop(cfg)
        }
    }

    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScreenScan()
            handler.postDelayed(this, SCAN_DELAY_MS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP UNIFICATO
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doLoop(cfg: BotConfig) {
        val now         = System.currentTimeMillis()
        val hasTarget   = targetX > 0f && targetY > 0f
        val recentDmg   = (now - lastDamageMs)     < COMBAT_GRACE_MS
        val recentTgt   = (now - lastTargetSeenMs) < TARGET_GRACE_MS
        val inCombat    = hasTarget || recentDmg || recentTgt

        // ── Kill counter ──────────────────────────────────────────────────
        if (prevTargetFound && !hasTarget) {
            BotState.killCount++
            lastCombatSeenMs = now
        }
        prevTargetFound = hasTarget
        BotState.underAttack = recentDmg || (hpBarConfigured && BotState.hpDropCycles >= 1)

        // ── Coordinate effettive ──────────────────────────────────────────
        val ax = r(cfg.attackX, aAttackX)
        val ay = r(cfg.attackY, aAttackY)
        val px = if (cfg.playerX > 0f) cfg.playerX
                 else if (autoPlayerTrackX > 0f) autoPlayerTrackX else aPlayerX
        val py = if (cfg.playerY > 0f) cfg.playerY
                 else if (autoPlayerTrackY > 0f) autoPlayerTrackY else aPlayerY

        // ── POZIONE MULTI-SLOT ────────────────────────────────────────────
        // hpLow: HP monitorato e sotto soglia
        // timerPotion: fallback se HP non rilevato (usa pozione ogni N cicli in combattimento)
        val hpLow = hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
        val timerPotion = !hpBarConfigured && inCombat && huntCycles > 0 && huntCycles % 8 == 0

        if (!isRefilling && (hpLow || timerPotion) && now - lastPotionTapMs >= POTION_CD_MS) {
            usePotion(cfg, now)
        }

        // ── COMBATTIMENTO ─────────────────────────────────────────────────
        if (inCombat) {
            val tx = if (hasTarget) targetX else lastTargetX
            val ty = if (hasTarget) targetY else lastTargetY

            // Multi-touch simultaneo: attack SEMPRE incluso + target + skill pronte
            val wave1 = mutableListOf<Pair<Float, Float>>()

            // Attacco — sempre presente se configurato/auto
            if (ax > 0f && ay > 0f) wave1.add(ax to ay)

            // Target tap (seleziona il mob)
            if (tx > 0f && ty > 0f && hasTarget) wave1.add(tx to ty)

            // Skills — tutte e 5 controllate, aggiunte se pronte
            val s1x = r(cfg.skill1X, aSkill1X); val s1y = r(cfg.skill1Y, aSkill1Y)
            val s2x = r(cfg.skill2X, aSkill2X); val s2y = r(cfg.skill2Y, aSkill2Y)
            val s3x = r(cfg.skill3X, aSkill3X); val s3y = r(cfg.skill3Y, aSkill3Y)
            val s4x = r(cfg.skill4X, aSkill4X); val s4y = r(cfg.skill4Y, aSkill4Y)
            val s5x = r(cfg.skill5X, aSkill5X); val s5y = r(cfg.skill5Y, aSkill5Y)

            if (s1x > 0f && s1y > 0f && now - lastSkill1Ms >= cfg.skill1CooldownMs) {
                wave1.add(s1x to s1y); lastSkill1Ms = now
            }
            if (s2x > 0f && s2y > 0f && now - lastSkill2Ms >= cfg.skill2CooldownMs) {
                wave1.add(s2x to s2y); lastSkill2Ms = now
            }
            if (s3x > 0f && s3y > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) {
                wave1.add(s3x to s3y); lastSkill3Ms = now
            }
            if (s4x > 0f && s4y > 0f && now - lastSkill4Ms >= cfg.skill4CooldownMs) {
                wave1.add(s4x to s4y); lastSkill4Ms = now
            }
            if (s5x > 0f && s5y > 0f && now - lastSkill5Ms >= cfg.skill5CooldownMs) {
                wave1.add(s5x to s5y); lastSkill5Ms = now
            }

            if (wave1.isNotEmpty()) multiTap(wave1)

            // Spam attack aggiuntivi tra i cicli (non bloccano il loop)
            if (ax > 0f && ay > 0f) {
                val d1 = TAP_MS + GAP_MS
                val d2 = d1 * 2
                handler.postDelayed({ if (BotState.isRunning) tap(ax, ay) }, d1)
                handler.postDelayed({ if (BotState.isRunning) tap(ax, ay) }, d2)
                if (tx > 0f && ty > 0f && hasTarget) {
                    handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, d2 + GAP_MS)
                }
            }

            // Loot anche in combattimento
            if (lootX > 0f && lootY > 0f && now - lastLootTapMs >= LOOT_TAP_CD_MS * 2) {
                val lx = lootX; val ly = lootY
                handler.postDelayed({ if (BotState.isRunning) tap(lx, ly) }, TAP_MS + GAP_MS * 3)
                lastLootTapMs = now
            }

            huntCycles++
            handler.postDelayed(loop, COMBAT_CYCLE_MS)
            return
        }

        // ── PATTUGLIA + LOOT ──────────────────────────────────────────────
        val justKilled  = now - lastCombatSeenMs < LOOT_GRACE_MS
        val lootVisible = lootX > 0f && lootY > 0f
        val lootReady   = now - lastLootTapMs >= LOOT_TAP_CD_MS

        if (lootReady) {
            if (lootVisible) {
                tap(lootX, lootY)
                lastLootTapMs = now
            } else if (justKilled && px > 0f && py > 0f) {
                val dr = ri(cfg.defenseRadiusPx, aDefenseR).coerceAtLeast(90).toFloat()
                multiTap(listOf(
                    px + dr * 0.40f to py,
                    px - dr * 0.40f to py,
                    px to py + dr * 0.30f,
                    px to py - dr * 0.30f,
                ))
                lastLootTapMs = now
            }
        }

        // Skills durante pattuglia
        val s1x = r(cfg.skill1X, aSkill1X); val s1y = r(cfg.skill1Y, aSkill1Y)
        val s2x = r(cfg.skill2X, aSkill2X); val s2y = r(cfg.skill2Y, aSkill2Y)
        val s3x = r(cfg.skill3X, aSkill3X); val s3y = r(cfg.skill3Y, aSkill3Y)
        val patrolSkills = mutableListOf<Pair<Float, Float>>()
        if (s1x > 0f && s1y > 0f && now - lastSkill1Ms >= cfg.skill1CooldownMs) { patrolSkills.add(s1x to s1y); lastSkill1Ms = now }
        if (s2x > 0f && s2y > 0f && now - lastSkill2Ms >= cfg.skill2CooldownMs) { patrolSkills.add(s2x to s2y); lastSkill2Ms = now }
        if (s3x > 0f && s3y > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) { patrolSkills.add(s3x to s3y); lastSkill3Ms = now }
        if (patrolSkills.isNotEmpty()) multiTap(patrolSkills)

        // Rotazione camera
        val cx = r(cfg.cameraAreaX, aCameraX)
        val cy = r(cfg.cameraAreaY, aCameraY)
        val cr = r(cfg.cameraSwipeRange, aCameraRange)
        var moveDelay = 0L
        if (cx > 0f && huntCycles > 0 && huntCycles % CAMERA_EVERY == 0) {
            handler.postDelayed({ if (BotState.isRunning)
                swipe(cx - cameraDir * cr, cy, cx + cameraDir * cr, cy, CAMERA_MS) }, moveDelay)
            cameraDir = -cameraDir
            moveDelay += CAMERA_MS + POST_MOVE_MS
        }

        // Joystick patrol
        val jx = r(cfg.joystickX, aJoystickX)
        val jy = r(cfg.joystickY, aJoystickY)
        val jr = r(cfg.joystickRadius, aJoystickR)
        if (jx > 0f && jy > 0f && jr > 0f) {
            val dir = PATROL_DIRS[patrolDir]
            val jtx = jx + dir[0] * jr
            val jty = jy + dir[1] * jr
            handler.postDelayed({ if (BotState.isRunning) joystickPush(jx, jy, jtx, jty, JOYSTICK_MS) }, moveDelay)
            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) { patrolSteps = 0; patrolDir = (patrolDir + 1) % 4 }
        }

        huntCycles++
        handler.postDelayed(loop, PATROL_CYCLE_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGICA POZIONE MULTI-SLOT
    //
    // 1. Prendi lo slot corrente dalla lista configurata
    // 2. Se il pixel check dice che è vuoto → avanza al prossimo slot
    // 3. Se tutti i slot sono vuoti → refill dall'inventario (drag)
    // 4. Dopo il refill → reset flags e riparti dallo slot 0
    // ═══════════════════════════════════════════════════════════════════════════
    private fun usePotion(cfg: BotConfig, now: Long) {
        val slots = activePotionSlots(cfg)
        if (slots.isEmpty()) return

        // Se il pixel check indica slot vuoto, cerca il prossimo slot con pozione
        if (currentSlotIsEmpty) {
            slotEmptyFlags[currentSlotIdx % slotEmptyFlags.size] = true
            // Avanza al prossimo slot non vuoto
            var found = false
            for (i in 1..slots.size) {
                val nextIdx = (currentSlotIdx + i) % slots.size
                if (!slotEmptyFlags[nextIdx % slotEmptyFlags.size]) {
                    currentSlotIdx = nextIdx
                    found = true
                    break
                }
            }
            if (!found) {
                // Tutti gli slot sono vuoti → refill dall'inventario
                val invX = cfg.inventoryPotionX
                val invY = cfg.inventoryPotionY
                if (invX > 0f && invY > 0f && slots.isNotEmpty()) {
                    val (s0x, s0y) = slots[0]
                    isRefilling = true
                    // Drag dalla posizione inventario al primo slot
                    swipe(invX, invY, s0x, s0y, 350L)
                    handler.postDelayed({
                        // Reset dopo il refill
                        currentSlotIdx = 0
                        slotEmptyFlags.fill(false)
                        currentSlotIsEmpty = false
                        isRefilling = false
                    }, REFILL_DELAY_MS + 400L)
                }
                return
            }
        }

        // Usa lo slot corrente
        val (potX, potY) = slots[currentSlotIdx % slots.size]
        tap(potX, potY)
        lastPotionTapMs = now
        // Dopo il tap, il prossimo analyzeFrame aggiornerà currentSlotIsEmpty
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Analisi screenshot
    // ═══════════════════════════════════════════════════════════════════════════
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

    private fun analyzeFrame(bmp: Bitmap) {
        val cfg = BotConfig.load(this)
        val w = bmp.width; val h = bmp.height

        // ── 0. Tracking giocatore ─────────────────────────────────────────────
        val pgX0 = (w * 0.20f).toInt(); val pgX1 = (w * 0.80f).toInt()
        val pgY0 = (h * 0.18f).toInt(); val pgY1 = (h * 0.70f).toInt()
        var pgSX = 0L; var pgSY = 0L; var pgCnt = 0
        for (y in pgY0 until pgY1 step 4) {
            for (x in pgX0 until pgX1 step 4) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (gv > 145 && gv > rv + 28 && gv > bv + 20) { pgSX += x; pgSY += y; pgCnt++ }
            }
        }
        if (pgCnt >= 6) {
            autoPlayerTrackX = (pgSX / pgCnt).toFloat()
            autoPlayerTrackY = ((pgSY / pgCnt).toFloat() + h * 0.05f).coerceAtMost(h - 1f)
        }

        // ── 1. Rilevamento nomi mostri ────────────────────────────────────────
        val yStart = (h * 0.07f).toInt(); val yEnd = (h * 0.85f).toInt()
        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yStart until yEnd step 3) {
            for (x in 80 until w step 3) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (rv > MOB_R_MIN && gv < MOB_G_MAX && bv < MOB_B_MAX &&
                    rv - gv > MOB_R_DIFF && rv - bv > MOB_R_DIFF) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count >= 8) {
            targetX = (sumX / count).toFloat()
            targetY = (sumY / count).toFloat()
            val now = System.currentTimeMillis()
            lastTargetSeenMs = now
            lastCombatSeenMs = now
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
            for (dy in -4..4) {
                val sy = (barY + dy).coerceIn(0, h - 1)
                for (x in barX until scanEnd) {
                    val p = bmp.getPixel(x, sy)
                    val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                    if (rv > HP_R_MIN && gv < HP_G_MAX && bv < HP_B_MAX &&
                        rv - gv > HP_R_DIFF && rv - bv > HP_R_DIFF) redPx++
                    totPx++
                }
            }
            val newRatio = if (totPx > 0) redPx.toFloat() / totPx else 1.0f
            BotState.hpDisplayPct = (newRatio * 100).toInt()
            val drop = BotState.lastHpRatio - newRatio
            if (drop > 0.010f) {
                BotState.hpDropCycles++; BotState.hpStableCycles = 0
                lastDamageMs = System.currentTimeMillis()
            } else {
                BotState.hpStableCycles++
                if (BotState.hpStableCycles >= 4) BotState.hpDropCycles = 0
            }
            BotState.lastHpRatio = newRatio
            scannedHpRatio = newRatio
        }

        // ── 3. Pixel check slot pozione corrente ──────────────────────────────
        // Controlla se lo slot attivo ha ancora l'icona rossa (pozione presente)
        val slots = activePotionSlots(cfg)
        if (slots.isNotEmpty() && !isRefilling) {
            val idx = currentSlotIdx % slots.size
            val (sx, sy) = slots[idx]
            if (sx > 0f && sy > 0f) {
                currentSlotIsEmpty = !isSlotHasPotion(bmp, sx, sy)
            }
        }

        // ── 4. Loot a terra ───────────────────────────────────────────────────
        detectLoot(bmp, w, h)
    }

    private fun detectLoot(bmp: Bitmap, w: Int, h: Int) {
        val x0 = (w * 0.10f).toInt(); val x1 = (w * 0.90f).toInt()
        val y0 = (h * 0.15f).toInt(); val y1 = (h * 0.87f).toInt()
        var sx = 0L; var sy = 0L; var cnt = 0
        for (y in y0 until y1 step 4) {
            for (x in x0 until x1 step 4) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                val white = rv > 178 && gv > 178 && bv > 178 && abs(rv - gv) < 22 && abs(rv - bv) < 22
                val green = gv > 158 && gv > rv + 28 && gv > bv + 20
                if (white || green) { sx += x; sy += y; cnt++ }
            }
        }
        if (cnt >= 6) {
            lootX = (sx / cnt).toFloat()
            lootY = (sy / cnt).toFloat() + (bmp.height * 0.02f)
        } else {
            lootX = 0f; lootY = 0f
        }
    }

    private fun autoDetectHpBar(bmp: Bitmap, w: Int, h: Int) {
        val maxY = (h * 0.22f).toInt(); val maxX = (w * 0.38f).toInt()
        for (y in 4 until maxY) {
            var firstRed = -1; var lastRed = -1; var redCount = 0
            for (x in 14 until maxX) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (rv > HP_R_MIN && rv - gv > HP_R_DIFF && rv - bv > HP_R_DIFF) {
                    if (firstRed < 0) firstRed = x; lastRed = x; redCount++
                }
            }
            val stripeW = if (firstRed >= 0) lastRed - firstRed + 1 else 0
            if (stripeW in 40..340 && redCount.toFloat() / stripeW >= 0.42f) {
                autoBarX = firstRed; autoBarY = y
                var extX = lastRed + 1
                while (extX < maxX) {
                    val p = bmp.getPixel(extX, y)
                    val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                    if (rv < 90 && gv < 90 && bv < 90) extX++ else break
                }
                autoBarFullW = (extX - firstRed).coerceAtLeast(stripeW + 12)
                break
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onServiceConnected()                { instance = this; initAutoCoords() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt()                       { stopBot() }
    override fun onDestroy()                         { super.onDestroy(); stopBot(); if (instance === this) instance = null }

    fun startBot() {
        if (BotState.isRunning) return
        handler.removeCallbacksAndMessages(null)
        initAutoCoords()
        BotState.isRunning      = true
        BotState.sessionStartMs = System.currentTimeMillis()
        BotState.lastHpRatio    = 1.0f
        BotState.hpDropCycles   = 0;  BotState.hpStableCycles = 0
        BotState.underAttack    = false; BotState.hpDisplayPct  = -1
        huntCycles = 0
        patrolDir  = 0; patrolSteps = 0; cameraDir = 1
        prevTargetFound = false
        targetX = 0f; targetY = 0f
        scannedHpRatio = 1.0f; hpBarConfigured = false
        lootX = 0f; lootY = 0f
        lastDamageMs = 0L; lastTargetSeenMs = 0L; lastCombatSeenMs = 0L
        lastLootTapMs = 0L; lastPotionTapMs = 0L
        lastTargetX = 0f; lastTargetY = 0f
        lastSkill1Ms = 0L; lastSkill2Ms = 0L; lastSkill3Ms = 0L
        lastSkill4Ms = 0L; lastSkill5Ms = 0L
        autoBarX = -1; autoBarY = -1; autoBarFullW = 0
        // Reset sistema pozioni
        currentSlotIdx = 0
        slotEmptyFlags.fill(false)
        currentSlotIsEmpty = false
        isRefilling = false
        handler.post(loop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, SCAN_DELAY_MS)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
        targetX = 0f; targetY = 0f
        isRefilling = false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GESTI
    // ═══════════════════════════════════════════════════════════════════════════

    private fun multiTap(points: List<Pair<Float, Float>>) {
        if (points.isEmpty()) return
        val builder = GestureDescription.Builder()
        for ((x, y) in points.take(10)) {
            val path = Path().apply { moveTo(x, y) }
            try { builder.addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS)) }
            catch (e: Exception) { /* stroke ignorato se troppi pointer */ }
        }
        dispatchGesture(builder.build(), null, null)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build(), null, null)
    }

    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, dur: Long) {
        val path = Path().apply { moveTo(cx, cy); lineTo(tx, ty) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build(), null, null)
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
