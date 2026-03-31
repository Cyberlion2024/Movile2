package com.movile2.bot

// ═══════════════════════════════════════════════════════════════════════════
// Analisi libUE4.so (Mobile2 Global 2.23 — ARM64 stripped, 182 MB)
//   ATTACK_RANGE/SPEED, AttackTimeMsec → 60ms TAP confermato
//   MOB_COLOR → rosso vivace R>160, G<118, B<118
//   SKILL_VNUM0..4 → 5 slot abilità
//   dropLocs/dropedAt → centroide drop corretto
//   Emulator detection: BlueStacks, NoxPlayer, MEmu → solo dispositivo fisico
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
    private enum class HudProfile { COMPACT, WIDE }

    // ── Timing ───────────────────────────────────────────────────────────────
    private val TAP_MS          = 60L
    private val JOYSTICK_MS     = 300L
    private val CAMERA_MS       = 200L
    private val COMBAT_CYCLE_MS = 280L
    private val PATROL_CYCLE_MS = 550L
    private val SCAN_DELAY_MS   = 500L
    private val CAMERA_EVERY    = 5
    private val TARGET_GRACE_MS = 3000L  // 3s grace: meno pause tra un mostro e l'altro
    private val COMBAT_GRACE_MS = 3000L
    private val LOOT_GRACE_MS   = 7000L
    private val LOOT_CD_MS      = 800L
    private val POTION_CD_MS    = 2000L  // 2s tra pozioni normali
    private val POTION_CD_EMERG = 1000L  // 1s se HP < 20% (emergenza)
    private val MAX_TAPS_PER_SLOT = 10

    // ── Soglie pixel ──────────────────────────────────────────────────────────
    private val MOB_R_MIN = 158; private val MOB_G_MAX = 120; private val MOB_B_MAX = 120
    private val MOB_DIFF  = 36
    private val HP_R_MIN  = 100; private val HP_G_MAX  = 115; private val HP_B_MAX  = 115
    private val HP_DIFF   = 18

    // ── Coordinate automatiche ────────────────────────────────────────────────
    private var aAttackX = 0f; private var aAttackY = 0f
    private var aS1X = 0f;    private var aS1Y = 0f
    private var aS2X = 0f;    private var aS2Y = 0f
    private var aS3X = 0f;    private var aS3Y = 0f
    private var aS4X = 0f;    private var aS4Y = 0f
    private var aS5X = 0f;    private var aS5Y = 0f
    private var aPot1X = 0f;  private var aPot1Y = 0f
    private var aJoyX = 0f;   private var aJoyY = 0f; private var aJoyR = 0f
    private var aCamX = 0f;   private var aCamY = 0f; private var aCamR = 0f
    private var aPlyX = 0f;   private var aPlyY = 0f; private var aDefR = 0

    private fun r(m: Float, a: Float) = if (m > 0f) m else a
    private fun ri(m: Int,  a: Int)  = if (m > 0)  m else a

    private fun initAutoCoords() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()

        // ── Skill/Attack bar — fila in basso a destra ─────────────────────────
        // Riga inferiore (botY ≈ 87%) e superiore (topY ≈ 79%)
        val botY = sh * 0.870f; val topY = sh * 0.790f
        val c1   = sw * 0.771f; val c2  = sw * 0.819f
        val c3   = sw * 0.866f; val c4  = sw * 0.930f  // attack: più a destra

        aAttackX = c4; aAttackY = botY
        aS1X = c1; aS1Y = botY; aS2X = c2; aS2Y = botY
        aS3X = c3; aS3Y = botY; aS4X = c3; aS4Y = topY; aS5X = c2; aS5Y = topY

        // ── Pozioni vita — colonna destra a metà schermo ──────────────────────
        // Dal screenshot: 4 slot verticali a destra (~88% X), partendo da ~31% Y
        // Slot 1 (HP rosso) ≈ 88% x, 31% y
        aPot1X = sw * 0.882f; aPot1Y = sh * 0.310f

        // ── Joystick — fondo sinistra ─────────────────────────────────────────
        aJoyX = sw * 0.067f; aJoyY = sh * 0.815f; aJoyR = sw * 0.072f

        // ── Camera e player ───────────────────────────────────────────────────
        aCamX = sw * 0.530f; aCamY = sh * 0.430f; aCamR = sw * 0.165f
        aPlyX = sw * 0.500f; aPlyY = sh * 0.510f
        aDefR = (sw * 0.145f).toInt()
    }

    // ── Cooldown abilità ──────────────────────────────────────────────────────
    private var tSk1 = 0L; private var tSk2 = 0L; private var tSk3 = 0L
    private var tSk4 = 0L; private var tSk5 = 0L

    // ── Timestamp/stato ───────────────────────────────────────────────────────
    private var tDamage = 0L; private var tLastTgt = 0L; private var tLastCombat = 0L
    private var tLoot   = 0L; private var tPotion  = 0L
    private var lastTX  = 0f; private var lastTY   = 0f
    private var huntCycles   = 0
    private var patrolDir    = 0; private var patrolStep = 0; private var camDir = 1
    private var prevHadTarget = false

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS  = arrayOf(
        floatArrayOf(0f, -1f), floatArrayOf(1f, 0f),
        floatArrayOf(0f, 1f),  floatArrayOf(-1f, 0f),
    )

    // ── Sistema pozioni multi-slot ────────────────────────────────────────────
    // LOGICA CORRETTA:
    //   1. Tappa SEMPRE lo slot corrente quando HP è basso (nessun blocco da pixel check)
    //   2. Dopo MAX_TAPS_PER_SLOT tap → avanza al prossimo slot
    //   3. Pixel check (opzionale): se lo slot appare vuoto dopo il tap → avanza subito
    //   4. Tutti gli slot esauriti → refill dall'inventario
    private var slotIdx     = 0          // slot corrente (0-based)
    private var slotTaps    = IntArray(7) { 0 }  // tap per ogni slot
    private var isRefilling = false

    // ── Pixel detection (volatile, aggiornata dallo scanner) ─────────────────
    @Volatile private var targetX = 0f; @Volatile private var targetY = 0f
    @Volatile private var hpRatio = 1.0f
    @Volatile private var hpFound = false   // barra HP trovata/configurata
    @Volatile private var lootX   = 0f; @Volatile private var lootY = 0f
    @Volatile private var barX    = -1;  @Volatile private var barY = -1
    @Volatile private var barW    = 0
    @Volatile private var trackX  = 0f; @Volatile private var trackY = 0f
    // Pixel check slot corrente: true = slot sembra vuoto (icona rossa assente)
    // Usato solo DOPO il tap, non per bloccarlo
    @Volatile private var slotLooksEmpty = false
    private var scanCount = 0

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScan()
            handler.postDelayed(this, SCAN_DELAY_MS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP UNIFICATO
    // ═══════════════════════════════════════════════════════════════════════════
    private fun doLoop(cfg: BotConfig) {
        val now      = System.currentTimeMillis()
        val hasTgt   = targetX > 0f && targetY > 0f
        val recentDmg = now - tDamage   < COMBAT_GRACE_MS
        val recentTgt = now - tLastTgt  < TARGET_GRACE_MS
        val inCombat  = hasTgt || recentDmg || recentTgt

        // Kill counter
        if (prevHadTarget && !hasTgt) {
            BotState.killCount++
            tLastCombat = now
        }
        prevHadTarget = hasTgt
        BotState.underAttack = recentDmg || (hpFound && BotState.hpDropCycles >= 1)

        // Coordinate effettive
        val ax = r(cfg.attackX, aAttackX); val ay = r(cfg.attackY, aAttackY)
        val px = when { cfg.playerX > 0f -> cfg.playerX; trackX > 0f -> trackX; else -> aPlyX }
        val py = when { cfg.playerY > 0f -> cfg.playerY; trackY > 0f -> trackY; else -> aPlyY }

        // ─────────────────────────────────────────────────────────────────────
        // POZIONE — priorità massima
        //
        // Modalità EMERGENZA (HP < 20%):
        //   - CD ridotto a 1s
        //   - Ignora isRefilling (non si muore aspettando il refill)
        //   - Si attiva anche se HP bar non trovata (ogni 3 cicli)
        //
        // Modalità NORMALE (HP < soglia configurata):
        //   - CD normale 2s
        //   - Rispetta isRefilling
        //
        // Fallback cieco (nessuna HP bar):
        //   - Ogni 5 cicli in combattimento
        // ─────────────────────────────────────────────────────────────────────
        val hpEmergency = hpFound && hpRatio > 0.01f && hpRatio < 0.20f
        val hpLow       = hpFound && hpRatio > 0.01f && hpRatio < cfg.hpPotionThreshold
        val blindTimer  = !hpFound && inCombat && huntCycles > 0 && huntCycles % 5 == 0
        val blindEmerg  = !hpFound && inCombat && huntCycles > 0 && huntCycles % 3 == 0

        val potionCd     = if (hpEmergency || blindEmerg) POTION_CD_EMERG else POTION_CD_MS
        val potionReady  = now - tPotion >= potionCd
        val skipRefill   = hpEmergency || blindEmerg   // emergenza: ignora isRefilling

        if ((skipRefill || !isRefilling) && potionReady && (hpLow || hpEmergency || blindTimer || blindEmerg)) {
            firePotion(cfg, now)
        }

        // ─────────────────────────────────────────────────────────────────────
        // COMBATTIMENTO
        // ─────────────────────────────────────────────────────────────────────
        if (inCombat) {
            val tx = if (hasTgt) targetX else lastTX
            val ty = if (hasTgt) targetY else lastTY

            // Multi-touch: attack + target (se visibile) + skill pronte
            val pts = mutableListOf<Pair<Float, Float>>()

            // Attack SEMPRE incluso
            if (ax > 0f && ay > 0f) pts.add(ax to ay)

            // Tap target per selezionarlo
            if (hasTgt && tx > 0f && ty > 0f) pts.add(tx to ty)

            // Skill 1-5 se pronte
            val s1x = r(cfg.skill1X, aS1X); val s1y = r(cfg.skill1Y, aS1Y)
            val s2x = r(cfg.skill2X, aS2X); val s2y = r(cfg.skill2Y, aS2Y)
            val s3x = r(cfg.skill3X, aS3X); val s3y = r(cfg.skill3Y, aS3Y)
            val s4x = r(cfg.skill4X, aS4X); val s4y = r(cfg.skill4Y, aS4Y)
            val s5x = r(cfg.skill5X, aS5X); val s5y = r(cfg.skill5Y, aS5Y)
            if (s1x > 0f && s1y > 0f && now - tSk1 >= cfg.skill1CooldownMs) { pts.add(s1x to s1y); tSk1 = now }
            if (s2x > 0f && s2y > 0f && now - tSk2 >= cfg.skill2CooldownMs) { pts.add(s2x to s2y); tSk2 = now }
            if (s3x > 0f && s3y > 0f && now - tSk3 >= cfg.skill3CooldownMs) { pts.add(s3x to s3y); tSk3 = now }
            if (s4x > 0f && s4y > 0f && now - tSk4 >= cfg.skill4CooldownMs) { pts.add(s4x to s4y); tSk4 = now }
            if (s5x > 0f && s5y > 0f && now - tSk5 >= cfg.skill5CooldownMs) { pts.add(s5x to s5y); tSk5 = now }

            if (pts.isNotEmpty()) multiTap(pts)

            // Un secondo attack tap metà ciclo dopo (colma il gap senza accumulare postDelayed)
            if (ax > 0f && ay > 0f) {
                val mid = COMBAT_CYCLE_MS / 2
                handler.postDelayed({ if (BotState.isRunning) tap(ax, ay) }, mid)
            }

            // Loot in combattimento
            if (lootX > 0f && lootY > 0f && now - tLoot >= LOOT_CD_MS * 2) {
                val lx = lootX; val ly = lootY
                handler.postDelayed({ if (BotState.isRunning) tap(lx, ly) }, TAP_MS + 80L)
                tLoot = now
            }

            huntCycles++
            handler.postDelayed(loop, COMBAT_CYCLE_MS)
            return
        }

        // ─────────────────────────────────────────────────────────────────────
        // PATTUGLIA
        // ─────────────────────────────────────────────────────────────────────
        val justKilled = now - tLastCombat < LOOT_GRACE_MS

        // Loot
        if (now - tLoot >= LOOT_CD_MS) {
            if (lootX > 0f && lootY > 0f) {
                tap(lootX, lootY); tLoot = now
            } else if (justKilled && px > 0f && py > 0f) {
                val dr = ri(cfg.defenseRadiusPx, aDefR).coerceAtLeast(90).toFloat()
                multiTap(listOf(px + dr * 0.4f to py, px - dr * 0.4f to py,
                                px to py + dr * 0.3f, px to py - dr * 0.3f))
                tLoot = now
            }
        }

        // Skill durante pattuglia
        val s1x = r(cfg.skill1X, aS1X); val s1y = r(cfg.skill1Y, aS1Y)
        val s2x = r(cfg.skill2X, aS2X); val s2y = r(cfg.skill2Y, aS2Y)
        val s3x = r(cfg.skill3X, aS3X); val s3y = r(cfg.skill3Y, aS3Y)
        val ps = mutableListOf<Pair<Float, Float>>()
        if (s1x > 0f && s1y > 0f && now - tSk1 >= cfg.skill1CooldownMs) { ps.add(s1x to s1y); tSk1 = now }
        if (s2x > 0f && s2y > 0f && now - tSk2 >= cfg.skill2CooldownMs) { ps.add(s2x to s2y); tSk2 = now }
        if (s3x > 0f && s3y > 0f && now - tSk3 >= cfg.skill3CooldownMs) { ps.add(s3x to s3y); tSk3 = now }
        if (ps.isNotEmpty()) multiTap(ps)

        // Se HP è critico NON muovere — resta fermo e aspetta la pozione
        val hpCritical = hpFound && hpRatio < 0.30f
        if (!hpCritical) {
            // Camera
            val cx = r(cfg.cameraAreaX, aCamX); val cy = r(cfg.cameraAreaY, aCamY)
            val cr = r(cfg.cameraSwipeRange, aCamR)
            var moveDelay = 0L
            if (cx > 0f && huntCycles > 0 && huntCycles % CAMERA_EVERY == 0) {
                val d = camDir; camDir = -camDir
                handler.postDelayed({ if (BotState.isRunning) swipe(cx - d * cr, cy, cx + d * cr, cy, CAMERA_MS) }, moveDelay)
                moveDelay += CAMERA_MS + 60L
            }

            // Joystick
            val jx = r(cfg.joystickX, aJoyX); val jy = r(cfg.joystickY, aJoyY)
            val jr = r(cfg.joystickRadius, aJoyR)
            if (jx > 0f && jy > 0f && jr > 0f) {
                val dir = PATROL_DIRS[patrolDir]
                val jtx = jx + dir[0] * jr; val jty = jy + dir[1] * jr
                handler.postDelayed({ if (BotState.isRunning) joystickPush(jx, jy, jtx, jty, JOYSTICK_MS) }, moveDelay)
                patrolStep++
                if (patrolStep >= PATROL_STEPS[patrolDir]) { patrolStep = 0; patrolDir = (patrolDir + 1) % 4 }
            }
        }

        huntCycles++
        handler.postDelayed(loop, PATROL_CYCLE_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SISTEMA POZIONI — LOGICA CORRETTA (v3)
    //
    // SEMPRE tappa lo slot corrente → nessun blocco da pixel check.
    // Avanza slot quando:
    //   - Dopo MAX_TAPS_PER_SLOT tap su questo slot (contatore)
    //   - OPPURE: pixel check dice vuoto E almeno 1 tap già fatto su questo slot
    // Quando tutti gli slot sono esauriti → drag dall'inventario allo slot 1
    // ═══════════════════════════════════════════════════════════════════════════
    private fun firePotion(cfg: BotConfig, now: Long) {
        val slots = buildSlotList(cfg)
        if (slots.isEmpty()) return

        val idx = slotIdx.coerceIn(0, slots.size - 1)
        val (px, py) = slots[idx]

        // TAP — sempre, incondizionatamente
        tap(px, py)
        tPotion = now
        slotTaps[idx]++

        // Avanza al prossimo slot se:
        //   contatore raggiunto OPPURE pixel check dice vuoto (con almeno 1 uso)
        val counterFull  = slotTaps[idx] >= MAX_TAPS_PER_SLOT
        val pixelEmpty   = slotLooksEmpty && slotTaps[idx] >= 1
        if (counterFull || pixelEmpty) {
            advanceSlot(cfg, slots)
        }
    }

    private fun advanceSlot(cfg: BotConfig, slots: List<Pair<Float, Float>>) {
        val next = slotIdx + 1
        if (next < slots.size) {
            // Slot successivo disponibile
            slotIdx = next
        } else {
            // Tutti gli slot esauriti → refill dall'inventario
            val invX = cfg.inventoryPotionX
            val invY = cfg.inventoryPotionY
            if (invX > 0f && invY > 0f && slots.isNotEmpty()) {
                val (s0x, s0y) = slots[0]
                isRefilling = true
                swipe(invX, invY, s0x, s0y, 400L)
                handler.postDelayed({
                    slotIdx = 0
                    slotTaps.fill(0)
                    slotLooksEmpty = false
                    isRefilling = false
                }, 1200L)
            } else {
                // Nessun inventario configurato → ricomincia dal primo slot
                slotIdx = 0
                slotTaps.fill(0)
            }
        }
    }

    private fun buildSlotList(cfg: BotConfig): List<Pair<Float, Float>> {
        val manual = cfg.potionSlots()
        return if (manual.isNotEmpty()) manual else listOf(aPot1X to aPot1Y)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER — screenshot + analisi pixel
    // ═══════════════════════════════════════════════════════════════════════════
    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScan() {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw  = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    bmp?.let { analyzeFrame(it); it.recycle() }
                }
                override fun onFailure(e: Int) { targetX = 0f; targetY = 0f }
            })
    }

    private fun analyzeFrame(bmp: Bitmap) {
        val cfg = BotConfig.load(this)
        val w = bmp.width; val h = bmp.height
        scanCount++

        // ── 0. Player tracking (verde) ────────────────────────────────────────
        var pgX = 0L; var pgY = 0L; var pgN = 0
        for (y in (h * 0.18f).toInt() until (h * 0.70f).toInt() step 4) {
            for (x in (w * 0.20f).toInt() until (w * 0.80f).toInt() step 4) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (gv > 145 && gv > rv + 28 && gv > bv + 20) { pgX += x; pgY += y; pgN++ }
            }
        }
        if (pgN >= 6) { trackX = (pgX / pgN).toFloat(); trackY = (pgY / pgN + h * 0.05f).toFloat() }

        // ── 1. Mostri (rosso vivo) ────────────────────────────────────────────
        var sumX = 0L; var sumY = 0L; var cnt = 0
        for (y in (h * 0.07f).toInt() until (h * 0.85f).toInt() step 3) {
            for (x in 80 until w step 3) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (rv > MOB_R_MIN && gv < MOB_G_MAX && bv < MOB_B_MAX &&
                    rv - gv > MOB_DIFF && rv - bv > MOB_DIFF) { sumX += x; sumY += y; cnt++ }
            }
        }
        if (cnt >= 8) {
            targetX = (sumX / cnt).toFloat(); targetY = (sumY / cnt).toFloat()
            val now = System.currentTimeMillis()
            tLastTgt = now; tLastCombat = now; lastTX = targetX; lastTY = targetY
        } else { targetX = 0f; targetY = 0f }

        // ── 2. Barra HP ───────────────────────────────────────────────────────
        // Ritenta la ricerca automatica OGNI scan finché non trovata.
        // Se configurata manualmente usa quella.
        val bx: Int; val by: Int; val bw2: Int
        if (cfg.hpBarX > 0f) {
            bx = cfg.hpBarX.toInt(); by = cfg.hpBarY.toInt()
            bw2 = if (cfg.hpBarFullWidth > 0) cfg.hpBarFullWidth else 180
            hpFound = true
        } else {
            // Ritenta ogni scan (non solo al primo tentativo)
            if (barX < 0 || scanCount % 20 == 0) autoFindHpBar(bmp, w, h)
            bx = barX; by = barY; bw2 = barW
        }

        if (bx >= 0 && bw2 > 0) {
            hpFound = true
            var red = 0; var tot = 0
            val endX = (bx + (bw2 * 1.1f).toInt()).coerceAtMost(w)
            for (dy in -5..5) {
                val sy = (by + dy).coerceIn(0, h - 1)
                for (x in bx until endX) {
                    val p = bmp.getPixel(x, sy)
                    val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                    if (rv > HP_R_MIN && gv < HP_G_MAX && bv < HP_B_MAX &&
                        rv - gv > HP_DIFF && rv - bv > HP_DIFF) red++
                    tot++
                }
            }
            val ratio = if (tot > 0) red.toFloat() / tot else 1.0f
            BotState.hpDisplayPct = (ratio * 100).toInt()
            val drop = BotState.lastHpRatio - ratio
            if (drop > 0.010f) {
                BotState.hpDropCycles++; BotState.hpStableCycles = 0
                tDamage = System.currentTimeMillis()
            } else {
                BotState.hpStableCycles++
                if (BotState.hpStableCycles >= 4) BotState.hpDropCycles = 0
            }
            BotState.lastHpRatio = ratio
            hpRatio = ratio
        }

        // ── 3. Pixel check slot pozione corrente ──────────────────────────────
        // Controlla se lo slot ha ancora icona rossa (pozione presente).
        // Usato DOPO il tap per decidere se avanzare — NON blocca il tap.
        val slots = buildSlotList(cfg)
        if (slots.isNotEmpty() && !isRefilling) {
            val idx = slotIdx.coerceIn(0, slots.size - 1)
            val (sx, sy) = slots[idx]
            if (sx > 0f && sy > 0f) slotLooksEmpty = !pixelHasPotion(bmp, sx.toInt(), sy.toInt(), w, h)
        }

        // ── 4. Loot (bianco/verde a terra) ────────────────────────────────────
        var lx = 0L; var ly = 0L; var ln = 0
        for (y in (h * 0.15f).toInt() until (h * 0.87f).toInt() step 4) {
            for (x in (w * 0.10f).toInt() until (w * 0.90f).toInt() step 4) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                val white = rv > 178 && gv > 178 && bv > 178 && abs(rv - gv) < 22 && abs(rv - bv) < 22
                val green = gv > 158 && gv > rv + 28 && gv > bv + 20
                if (white || green) { lx += x; ly += y; ln++ }
            }
        }
        if (ln >= 6) { lootX = (lx / ln).toFloat(); lootY = (ly / ln).toFloat() + h * 0.02f }
        else { lootX = 0f; lootY = 0f }
    }

    // ── Ricerca automatica barra HP ───────────────────────────────────────────
    // Cerca una striscia rossa orizzontale continua nel quarto superiore sinistro.
    private fun autoFindHpBar(bmp: Bitmap, w: Int, h: Int) {
        val maxY = (h * 0.25f).toInt(); val maxX = (w * 0.45f).toInt()
        for (y in 3 until maxY) {
            var first = -1; var last = -1; var rc = 0
            for (x in 10 until maxX) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (rv > HP_R_MIN && rv - gv > HP_DIFF && rv - bv > HP_DIFF) {
                    if (first < 0) first = x; last = x; rc++
                }
            }
            val sw = if (first >= 0) last - first + 1 else 0
            // Striscia di almeno 30px con densità ≥40% di pixel rossi
            if (sw >= 30 && rc.toFloat() / sw >= 0.40f) {
                barX = first; barY = y
                // Stima larghezza totale: continua oltre lastRed cercando pixel scuri (barra vuota)
                var ex = last + 1
                while (ex < maxX) {
                    val p = bmp.getPixel(ex, y)
                    val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                    if (rv < 100 && gv < 100 && bv < 100) ex++ else break
                }
                barW = (ex - first).coerceAtLeast(sw + 10)
                break
            }
        }
    }

    // Campiona pixel attorno a (cx, cy) per verificare presenza icona rossa pozione
    private fun pixelHasPotion(bmp: Bitmap, cx: Int, cy: Int, w: Int, h: Int): Boolean {
        val x0 = (cx - 16).coerceAtLeast(0); val x1 = (cx + 16).coerceAtMost(w - 1)
        val y0 = (cy - 16).coerceAtLeast(0); val y1 = (cy + 16).coerceAtMost(h - 1)
        var red = 0; var tot = 0
        for (y in y0..y1 step 4) {
            for (x in x0..x1 step 4) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (rv > 140 && gv < 110 && bv < 110 && rv - gv > 45) red++
                tot++
            }
        }
        return if (tot > 0) red.toFloat() / tot >= 0.07f else true // dubbio → assume pieno
    }

    // ═══════════════════════════════════════════════════════════════════════════
    override fun onServiceConnected() { instance = this; initAutoCoords() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() { super.onDestroy(); stopBot(); if (instance === this) instance = null }

    fun startBot() {
        if (BotState.isRunning) return
        handler.removeCallbacksAndMessages(null)
        initAutoCoords()
        BotState.isRunning      = true
        BotState.sessionStartMs = System.currentTimeMillis()
        BotState.lastHpRatio    = 1.0f
        BotState.hpDropCycles   = 0; BotState.hpStableCycles = 0
        BotState.underAttack    = false; BotState.hpDisplayPct = -1
        huntCycles = 0; patrolDir = 0; patrolStep = 0; camDir = 1
        prevHadTarget = false
        targetX = 0f; targetY = 0f; hpRatio = 1.0f; hpFound = false
        lootX = 0f; lootY = 0f; trackX = 0f; trackY = 0f
        tDamage = 0L; tLastTgt = 0L; tLastCombat = 0L; tLoot = 0L; tPotion = 0L
        lastTX = 0f; lastTY = 0f
        tSk1 = 0L; tSk2 = 0L; tSk3 = 0L; tSk4 = 0L; tSk5 = 0L
        barX = -1; barY = -1; barW = 0; scanCount = 0
        slotIdx = 0; slotTaps.fill(0); slotLooksEmpty = false; isRefilling = false
        handler.post(loop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, SCAN_DELAY_MS)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
        targetX = 0f; targetY = 0f; isRefilling = false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GESTI
    // ═══════════════════════════════════════════════════════════════════════════
    private fun multiTap(pts: List<Pair<Float, Float>>) {
        if (pts.isEmpty()) return
        val b = GestureDescription.Builder()
        for ((x, y) in pts.take(10)) {
            try { b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0L, TAP_MS)) }
            catch (_: Exception) {}
        }
        dispatchGesture(b.build(), null, null)
    }

    private fun tap(x: Float, y: Float) {
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0L, TAP_MS))
            .build(), null, null)
    }

    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, dur: Long) {
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(cx, cy); lineTo(tx, ty) }, 0L, dur))
            .build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0L, dur))
            .build(), null, null)
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
