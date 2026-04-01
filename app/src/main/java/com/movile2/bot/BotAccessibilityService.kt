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
import kotlin.math.abs

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // ── Timing ───────────────────────────────────────────────────────────────
    private val TAP_MS          = 60L
    private val JOYSTICK_MS     = 300L
    private val CAMERA_MS       = 200L
    private val COMBAT_CYCLE_MS = 280L
    private val PATROL_CYCLE_MS = 550L
    private val SCAN_DELAY_MS   = 500L
    private val CAMERA_EVERY    = 5
    private val TARGET_GRACE_MS = 3000L
    private val COMBAT_GRACE_MS = 3000L
    private val LOOT_GRACE_MS   = 7000L
    private val LOOT_CD_MS      = 800L
    private val POTION_CD_MS    = 2000L
    private val POTION_CD_EMERG = 900L
    private val MAX_TAPS_PER_SLOT = 12

    // ── Soglie pixel mob ──────────────────────────────────────────────────────
    private val MOB_R_MIN = 158; private val MOB_G_MAX = 120; private val MOB_B_MAX = 120
    private val MOB_DIFF  = 36
    private val HP_R_MIN  = 100; private val HP_G_MAX  = 115; private val HP_B_MAX  = 115
    private val HP_DIFF   = 18

    // ── Coordinate automatiche ────────────────────────────────────────────────
    private var aAttackX = 0f; private var aAttackY = 0f
    private var aS1X = 0f; private var aS1Y = 0f
    private var aS2X = 0f; private var aS2Y = 0f
    private var aS3X = 0f; private var aS3Y = 0f
    private var aS4X = 0f; private var aS4Y = 0f
    private var aS5X = 0f; private var aS5Y = 0f
    private var aPot1X = 0f; private var aPot1Y = 0f
    private var aJoyX = 0f; private var aJoyY = 0f; private var aJoyR = 0f
    private var aCamX = 0f; private var aCamY = 0f; private var aCamR = 0f
    private var aPlyX = 0f; private var aPlyY = 0f; private var aDefR = 0

    private fun r(m: Float, a: Float) = if (m > 0f) m else a
    private fun ri(m: Int, a: Int) = if (m > 0) m else a

    private fun initAutoCoords() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat(); val sh = dm.heightPixels.toFloat()
        val botY = sh * 0.872f; val topY = sh * 0.793f
        val c1 = sw * 0.771f; val c2 = sw * 0.819f
        val c3 = sw * 0.866f; val c4 = sw * 0.950f
        aAttackX = c4; aAttackY = botY
        aS1X = c1; aS1Y = botY; aS2X = c2; aS2Y = botY
        aS3X = c3; aS3Y = botY; aS4X = c3; aS4Y = topY; aS5X = c2; aS5Y = topY
        aPot1X = sw * 0.882f; aPot1Y = sh * 0.310f
        aJoyX = sw * 0.067f; aJoyY = sh * 0.815f; aJoyR = sw * 0.072f
        aCamX = sw * 0.530f; aCamY = sh * 0.430f; aCamR = sw * 0.165f
        aPlyX = sw * 0.500f; aPlyY = sh * 0.510f
        aDefR = (sw * 0.145f).toInt()
    }

    // ── Cooldown abilità ──────────────────────────────────────────────────────
    private var tSk1 = 0L; private var tSk2 = 0L; private var tSk3 = 0L
    private var tSk4 = 0L; private var tSk5 = 0L

    // ── Timestamp/stato bot principale ────────────────────────────────────────
    private var tDamage = 0L; private var tLastTgt = 0L; private var tLastCombat = 0L
    private var tLoot = 0L; private var tPotion = 0L
    private var lastTX = 0f; private var lastTY = 0f
    private var huntCycles = 0
    private var patrolDir = 0; private var patrolStep = 0; private var camDir = 1
    private var prevHadTarget = false

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS = arrayOf(
        floatArrayOf(0f, -1f), floatArrayOf(1f, 0f),
        floatArrayOf(0f, 1f), floatArrayOf(-1f, 0f),
    )

    // ── Sistema pozioni ───────────────────────────────────────────────────────
    private var slotIdx = 0
    private var slotTaps = IntArray(7) { 0 }
    private var isRefilling = false

    // ── Pixel detection bot principale ────────────────────────────────────────
    @Volatile private var targetX = 0f; @Volatile private var targetY = 0f
    @Volatile private var hpRatio = 1.0f
    @Volatile private var hpFound = false
    @Volatile private var lootX = 0f; @Volatile private var lootY = 0f
    @Volatile private var barX = -1; @Volatile private var barY = -1
    @Volatile private var barW = 0
    @Volatile private var trackX = 0f; @Volatile private var trackY = 0f
    @Volatile private var slotLooksEmpty = false
    private var scanCount = 0
    @Volatile private var potAutoX = 0f; @Volatile private var potAutoY = 0f

    // ── Raccolta terra ────────────────────────────────────────────────────────
    // Lista degli oggetti trovati nello screenshot (yang + nomi item)
    // Ogni elemento = centroide di un cluster separato da tappare
    @Volatile private var groundItems: List<Pair<Float, Float>> = emptyList()

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER — screenshot ogni 500ms
    // Attivo quando: bot principale running, potionOnlyMode, oppure lootOnlyMode
    // ═══════════════════════════════════════════════════════════════════════════
    private val scanner = object : Runnable {
        override fun run() {
            val active = BotState.isRunning || BotState.potionOnlyMode || BotState.lootOnlyMode
            if (!active) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScan()
            handler.postDelayed(this, SCAN_DELAY_MS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA TERRA (standalone, indipendente dal bot principale)
    //
    // Ogni ciclo:
    //   1. Legge la lista groundItems trovata dall'ultimo scan
    //   2. Tappa su ogni oggetto: tap + 200ms + tap (doppio tap per raccogliere)
    //   3. 500ms di pausa tra un oggetto e il successivo
    //   4. Riparte dopo 800ms dall'ultimo oggetto
    //
    // NON attacca, NON si muove, NON usa skill.
    // ═══════════════════════════════════════════════════════════════════════════
    private val lootOnlyLoop = object : Runnable {
        override fun run() {
            if (!BotState.lootOnlyMode) return

            val items = groundItems
            BotState.lootItemsFound = items.size

            if (items.isEmpty()) {
                // Nessun oggetto trovato → riprova tra 600ms
                handler.postDelayed(this, 600L)
                return
            }

            // Tappa su ogni oggetto in sequenza
            var delay = 0L
            for ((ix, iy) in items) {
                handler.postDelayed({
                    if (!BotState.lootOnlyMode) return@postDelayed
                    // Primo tap: seleziona/avvicina
                    tap(ix, iy)
                    // Secondo tap dopo 220ms: raccoglie
                    handler.postDelayed({ if (BotState.lootOnlyMode) tap(ix, iy) }, 220L)
                }, delay)
                delay += 520L  // 220ms (attesa 2° tap) + 300ms (pausa tra oggetti)
            }

            // Prossimo ciclo dopo aver processato tutti gli oggetti
            handler.postDelayed(this, delay + 400L)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP SOLO POZIONI — indipendente dal bot principale
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionOnlyLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionOnlyMode) return
            val cfg = BotConfig.load(this@BotAccessibilityService)
            val slots = buildSlotList(cfg)
            if (slots.isNotEmpty()) {
                slots.forEachIndexed { i, (sx, sy) ->
                    handler.postDelayed({
                        if (BotState.potionOnlyMode) {
                            tap(sx, sy)
                            BotState.potTapX = sx; BotState.potTapY = sy
                            BotState.potSource = "POZ"
                        }
                    }, i * 300L)
                }
            }
            val cd = cfg.potionOnlyCdMs.coerceAtLeast(500L)
            handler.postDelayed(this, cd + slots.size * 300L)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP PRINCIPALE (combat + patrol) — invariato
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

    private fun doLoop(cfg: BotConfig) {
        val now = System.currentTimeMillis()
        val hasTgt = targetX > 0f && targetY > 0f
        val recentDmg = now - tDamage < COMBAT_GRACE_MS
        val recentTgt = now - tLastTgt < TARGET_GRACE_MS
        val inCombat = hasTgt || recentDmg || recentTgt

        if (prevHadTarget && !hasTgt) { BotState.killCount++; tLastCombat = now }
        prevHadTarget = hasTgt
        BotState.underAttack = recentDmg || (hpFound && BotState.hpDropCycles >= 1)

        val ax = r(cfg.attackX, aAttackX); val ay = r(cfg.attackY, aAttackY)
        val px = when { cfg.playerX > 0f -> cfg.playerX; trackX > 0f -> trackX; else -> aPlyX }
        val py = when { cfg.playerY > 0f -> cfg.playerY; trackY > 0f -> trackY; else -> aPlyY }

        if (!BotState.potionOnlyMode) {
            val hpEmergency = hpFound && hpRatio in 0.01f..0.20f
            val hpLow = hpFound && hpRatio in 0.01f..cfg.hpPotionThreshold
            val blindTimer = !hpFound && inCombat && huntCycles > 0 && huntCycles % 5 == 0
            val potionCd = if (hpEmergency) POTION_CD_EMERG else POTION_CD_MS
            val potionReady = now - tPotion >= potionCd
            if ((hpEmergency || !isRefilling) && potionReady && (hpLow || hpEmergency || blindTimer)) {
                firePotion(cfg, now)
            }
        }

        if (inCombat) {
            val tx = if (hasTgt) targetX else lastTX
            val ty = if (hasTgt) targetY else lastTY
            val pts = mutableListOf<Pair<Float, Float>>()
            if (ax > 0f && ay > 0f) pts.add(ax to ay)
            if (hasTgt && tx > 0f && ty > 0f) pts.add(tx to ty)
            val s1x = r(cfg.skill1X, aS1X); val s1y = r(cfg.skill1Y, aS1Y)
            val s2x = r(cfg.skill2X, aS2X); val s2y = r(cfg.skill2Y, aS2Y)
            val s3x = r(cfg.skill3X, aS3X); val s3y = r(cfg.skill3Y, aS3Y)
            val s4x = r(cfg.skill4X, aS4X); val s4y = r(cfg.skill4Y, aS4Y)
            val s5x = r(cfg.skill5X, aS5X); val s5y = r(cfg.skill5Y, aS5Y)
            if (s1x > 0f && now - tSk1 >= cfg.skill1CooldownMs) { pts.add(s1x to s1y); tSk1 = now }
            if (s2x > 0f && now - tSk2 >= cfg.skill2CooldownMs) { pts.add(s2x to s2y); tSk2 = now }
            if (s3x > 0f && now - tSk3 >= cfg.skill3CooldownMs) { pts.add(s3x to s3y); tSk3 = now }
            if (s4x > 0f && now - tSk4 >= cfg.skill4CooldownMs) { pts.add(s4x to s4y); tSk4 = now }
            if (s5x > 0f && now - tSk5 >= cfg.skill5CooldownMs) { pts.add(s5x to s5y); tSk5 = now }
            if (pts.isNotEmpty()) multiTap(pts)
            if (lootX > 0f && lootY > 0f && now - tLoot >= LOOT_CD_MS * 2) {
                val lx = lootX; val ly = lootY
                handler.postDelayed({ if (BotState.isRunning) tap(lx, ly) }, 100L)
                tLoot = now
            }
            huntCycles++
            handler.postDelayed(loop, COMBAT_CYCLE_MS)
            return
        }

        val justKilled = now - tLastCombat < LOOT_GRACE_MS
        if (now - tLoot >= LOOT_CD_MS) {
            if (lootX > 0f && lootY > 0f) { tap(lootX, lootY); tLoot = now }
            else if (justKilled && px > 0f && py > 0f) {
                val dr = ri(cfg.defenseRadiusPx, aDefR).coerceAtLeast(90).toFloat()
                multiTap(listOf(px + dr * 0.4f to py, px - dr * 0.4f to py,
                                px to py + dr * 0.3f, px to py - dr * 0.3f))
                tLoot = now
            }
        }
        val s1x = r(cfg.skill1X, aS1X); val s1y = r(cfg.skill1Y, aS1Y)
        val s2x = r(cfg.skill2X, aS2X); val s2y = r(cfg.skill2Y, aS2Y)
        val s3x = r(cfg.skill3X, aS3X); val s3y = r(cfg.skill3Y, aS3Y)
        val ps = mutableListOf<Pair<Float, Float>>()
        if (s1x > 0f && now - tSk1 >= cfg.skill1CooldownMs) { ps.add(s1x to s1y); tSk1 = now }
        if (s2x > 0f && now - tSk2 >= cfg.skill2CooldownMs) { ps.add(s2x to s2y); tSk2 = now }
        if (s3x > 0f && now - tSk3 >= cfg.skill3CooldownMs) { ps.add(s3x to s3y); tSk3 = now }
        if (ps.isNotEmpty()) multiTap(ps)
        val hpCritical = hpFound && hpRatio < 0.30f
        if (!hpCritical) {
            val cx = r(cfg.cameraAreaX, aCamX); val cy = r(cfg.cameraAreaY, aCamY)
            val cr = r(cfg.cameraSwipeRange, aCamR)
            var delay = 0L
            if (cx > 0f && huntCycles > 0 && huntCycles % CAMERA_EVERY == 0) {
                val d = camDir; camDir = -camDir
                handler.postDelayed({ if (BotState.isRunning) swipe(cx - d * cr, cy, cx + d * cr, cy, CAMERA_MS) }, delay)
                delay += CAMERA_MS + 60L
            }
            val jx = r(cfg.joystickX, aJoyX); val jy = r(cfg.joystickY, aJoyY)
            val jr = r(cfg.joystickRadius, aJoyR)
            if (jx > 0f && jy > 0f && jr > 0f) {
                val dir = PATROL_DIRS[patrolDir]
                val jtx = jx + dir[0] * jr; val jty = jy + dir[1] * jr
                handler.postDelayed({ if (BotState.isRunning) joystickPush(jx, jy, jtx, jty, JOYSTICK_MS) }, delay)
                patrolStep++
                if (patrolStep >= PATROL_STEPS[patrolDir]) { patrolStep = 0; patrolDir = (patrolDir + 1) % 4 }
            }
        }
        huntCycles++
        handler.postDelayed(loop, PATROL_CYCLE_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SISTEMA POZIONI
    // ═══════════════════════════════════════════════════════════════════════════
    private fun firePotion(cfg: BotConfig, now: Long) {
        val slots = buildSlotList(cfg)
        if (slots.isEmpty()) return
        val idx = slotIdx.coerceIn(0, slots.size - 1)
        val (px, py) = slots[idx]
        tap(px, py); tPotion = now; slotTaps[idx]++
        BotState.potTapX = px; BotState.potTapY = py
        BotState.potSource = if (cfg.potionSlots().isNotEmpty()) "MAN" else if (potAutoX > 0f) "AUTO" else "EST"
        if (slotTaps[idx] >= MAX_TAPS_PER_SLOT || (slotLooksEmpty && slotTaps[idx] >= 2)) advanceSlot(cfg, slots)
    }

    private fun advanceSlot(cfg: BotConfig, slots: List<Pair<Float, Float>>) {
        val next = slotIdx + 1
        if (next < slots.size) { slotIdx = next }
        else {
            val invX = cfg.inventoryPotionX; val invY = cfg.inventoryPotionY
            if (invX > 0f && invY > 0f && slots.isNotEmpty()) {
                val (s0x, s0y) = slots[0]
                isRefilling = true; swipe(invX, invY, s0x, s0y, 400L)
                handler.postDelayed({ slotIdx = 0; slotTaps.fill(0); slotLooksEmpty = false; isRefilling = false }, 1500L)
            } else { slotIdx = 0; slotTaps.fill(0) }
        }
    }

    private fun buildSlotList(cfg: BotConfig): List<Pair<Float, Float>> {
        val manual = cfg.potionSlots()
        if (manual.isNotEmpty()) return manual
        if (potAutoX > 0f && potAutoY > 0f) return listOf(potAutoX to potAutoY)
        return listOf(aPot1X to aPot1Y)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER — screenshot + analisi pixel
    // ═══════════════════════════════════════════════════════════════════════════
    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScan() {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
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

        // Sempre aggiorna groundItems — usato sia in lootOnlyMode che nel bot
        groundItems = findGroundItems(bmp, w, h)
        BotState.lootItemsFound = groundItems.size

        // Se solo raccolta terra → non fare il resto
        if (BotState.lootOnlyMode && !BotState.isRunning) return

        // ── Player tracking (verde) ────────────────────────────────────────────
        var pgX = 0L; var pgY = 0L; var pgN = 0
        for (y in (h * 0.18f).toInt() until (h * 0.70f).toInt() step 4) {
            for (x in (w * 0.20f).toInt() until (w * 0.80f).toInt() step 4) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (gv > 145 && gv > rv + 28 && gv > bv + 20) { pgX += x; pgY += y; pgN++ }
            }
        }
        if (pgN >= 6) { trackX = (pgX / pgN).toFloat(); trackY = (pgY / pgN + h * 0.05f).toFloat() }

        // ── Mostri (rosso vivo) ────────────────────────────────────────────────
        var sumX = 0L; var sumY = 0L; var cnt = 0
        for (y in (h * 0.07f).toInt() until (h * 0.85f).toInt() step 3) {
            for (x in 80 until (w * 0.88f).toInt() step 3) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (rv > MOB_R_MIN && gv < MOB_G_MAX && bv < MOB_B_MAX &&
                    rv - gv > MOB_DIFF && rv - bv > MOB_DIFF) { sumX += x; sumY += y; cnt++ }
            }
        }
        if (cnt >= 8) {
            targetX = (sumX / cnt).toFloat(); targetY = (sumY / cnt).toFloat()
            tLastTgt = System.currentTimeMillis(); lastTX = targetX; lastTY = targetY
        } else { targetX = 0f; targetY = 0f }

        // ── Barra HP ───────────────────────────────────────────────────────────
        val bxConf = cfg.hpBarX.toInt(); val byConf = cfg.hpBarY.toInt()
        if (bxConf > 0 && byConf > 0) {
            hpFound = true
            measureHpRatio(bmp, bxConf, byConf, if (cfg.hpBarFullWidth > 0) cfg.hpBarFullWidth else 200, w, h)
        } else {
            if (barX < 0 || scanCount % 25 == 0) autoFindHpBar(bmp, w, h)
            if (barX >= 0) { hpFound = true; measureHpRatio(bmp, barX, barY, barW, w, h) }
        }

        // ── Auto-rilevamento slot pozione ──────────────────────────────────────
        if (cfg.potionSlots().isEmpty() && (potAutoX <= 0f || scanCount % 35 == 0))
            autoFindPotionSlot(bmp, w, h)

        // ── Pixel check slot corrente ──────────────────────────────────────────
        val slots = buildSlotList(cfg)
        if (slots.isNotEmpty() && !isRefilling) {
            val idx = slotIdx.coerceIn(0, slots.size - 1)
            val (sx, sy) = slots[idx]
            if (sx > 0f && sy > 0f) slotLooksEmpty = !pixelHasPotion(bmp, sx.toInt(), sy.toInt(), w, h)
        }

        // ── Loot classico (centroide unico) per bot principale ─────────────────
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

    // ═══════════════════════════════════════════════════════════════════════════
    // RILEVAMENTO OGGETTI A TERRA
    //
    // Cerca cluster di pixel corrispondenti a testo di oggetti/yang in Mobile2.
    // Ogni cluster separato = un oggetto diverso da tappare.
    //
    // Colori rilevati:
    //   YANG   → testo giallo-oro: R>195, G>155, B<75, R-B>130
    //   ITEM   → testo bianco brillante: R>195, G>195, B>195, diff<25
    //   ITEM+  → testo verde (comune magico): G>180, G>R+35, G>B+35
    //   ITEM++ → testo azzurro (raro): B>160, B>R+30, B>G+10 (opzionale)
    //
    // Area scansionata: zona centrale del gioco, evitando UI ai bordi.
    //   Top 13%: HP bar, minimappa
    //   Bottom 22%: skill buttons, joystick
    //   Left 8%: pannello overlay
    //   Right 15%: colonna skill destra
    //
    // Algoritmo:
    //   Divide l'area in celle 38×38 px, step 3px per la scansione interna.
    //   Ogni cella con ≥3 pixel validi = un oggetto.
    //   Ritorna fino a 10 oggetti ordinati per posizione Y crescente (più vicini = più in basso).
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findGroundItems(bmp: Bitmap, w: Int, h: Int): List<Pair<Float, Float>> {
        val y0 = (h * 0.13f).toInt()
        val y1 = (h * 0.78f).toInt()
        val x0 = (w * 0.08f).toInt()
        val x1 = (w * 0.85f).toInt()
        val cell = 38

        val result = mutableListOf<Pair<Float, Float>>()

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                var hits = 0
                for (dy in 0 until cell step 3) {
                    for (dx in 0 until cell step 3) {
                        val p = bmp.getPixel(cx + dx, cy + dy)
                        val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                        // Yang: testo giallo-oro brillante
                        val yang = rv > 195 && gv > 155 && bv < 75 && rv - bv > 130
                        // Oggetto: testo bianco brillante
                        val white = rv > 195 && gv > 195 && bv > 195 &&
                                    abs(rv - gv) < 25 && abs(rv - bv) < 25 && abs(gv - bv) < 25
                        // Oggetto magico: testo verde chiaro
                        val grn = gv > 180 && gv > rv + 35 && gv > bv + 35
                        if (yang || white || grn) hits++
                    }
                }
                // Soglia: almeno 3 pixel trovati nella cella
                if (hits >= 3) {
                    result.add((cx + cell / 2).toFloat() to (cy + cell / 2).toFloat())
                    if (result.size >= 10) return result
                }
                cx += cell
            }
            cy += cell
        }

        // Ordina per Y decrescente = prima gli oggetti più in basso (più vicini al personaggio)
        return result.sortedByDescending { it.second }
    }

    // ── Misura rapporto HP ────────────────────────────────────────────────────
    private fun measureHpRatio(bmp: Bitmap, bx: Int, by: Int, bw2: Int, w: Int, h: Int) {
        var red = 0; var tot = 0
        val endX = (bx + (bw2 * 1.1f).toInt()).coerceAtMost(w)
        for (dy in -4..4) {
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
        BotState.lastHpRatio = ratio; hpRatio = ratio
    }

    private fun autoFindHpBar(bmp: Bitmap, w: Int, h: Int) {
        val maxY = (h * 0.25f).toInt(); val maxX = (w * 0.50f).toInt()
        for (y in 3 until maxY) {
            var first = -1; var last = -1; var rc = 0
            for (x in 5 until maxX) {
                val p = bmp.getPixel(x, y)
                val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                if (rv > HP_R_MIN && rv - gv > HP_DIFF && rv - bv > HP_DIFF) {
                    if (first < 0) first = x; last = x; rc++
                }
            }
            val sw = if (first >= 0) last - first + 1 else 0
            if (sw >= 30 && rc.toFloat() / sw >= 0.38f) {
                barX = first; barY = y
                var ex = last + 1
                while (ex < maxX) {
                    val p = bmp.getPixel(ex, y)
                    val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                    if (rv < 90 && gv < 90 && bv < 90) ex++ else break
                }
                barW = (ex - first).coerceAtLeast(sw + 10); break
            }
        }
    }

    private fun autoFindPotionSlot(bmp: Bitmap, w: Int, h: Int) {
        val x0 = (w * 0.70f).toInt(); val y0 = (h * 0.18f).toInt(); val y1 = (h * 0.72f).toInt()
        val cell = 26; var bestDensity = 0.27f; var bestX = -1f; var bestY = -1f
        var cy = y0
        while (cy + cell < y1) {
            var cx = x0
            while (cx + cell < w) {
                var red = 0; var tot = 0
                for (dy in 0 until cell step 2) for (dx in 0 until cell step 2) {
                    val p = bmp.getPixel(cx + dx, cy + dy)
                    val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
                    if (rv > 140 && gv < 100 && bv < 100 && rv - gv > 50) red++
                    tot++
                }
                val density = if (tot > 0) red.toFloat() / tot else 0f
                if (density > bestDensity) { bestDensity = density; bestX = (cx + cell / 2).toFloat(); bestY = (cy + cell / 2).toFloat() }
                cx += cell
            }
            cy += cell
        }
        if (bestX > 0f) { potAutoX = bestX; potAutoY = bestY }
    }

    private fun pixelHasPotion(bmp: Bitmap, cx: Int, cy: Int, w: Int, h: Int): Boolean {
        val x0 = (cx - 15).coerceAtLeast(0); val x1 = (cx + 15).coerceAtMost(w - 1)
        val y0 = (cy - 15).coerceAtLeast(0); val y1 = (cy + 15).coerceAtMost(h - 1)
        var red = 0; var tot = 0
        for (y in y0..y1 step 3) for (x in x0..x1 step 3) {
            val p = bmp.getPixel(x, y)
            val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
            if (rv > 138 && gv < 105 && bv < 105 && rv - gv > 45) red++
            tot++
        }
        return if (tot > 0) red.toFloat() / tot >= 0.08f else true
    }

    // ═══════════════════════════════════════════════════════════════════════════
    override fun onServiceConnected() { instance = this; initAutoCoords() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() {
        super.onDestroy()
        stopBot(); stopPotionOnly(); stopLootOnly()
        if (instance === this) instance = null
    }

    fun startBot() {
        if (BotState.isRunning) return
        handler.removeCallbacks(loop); handler.removeCallbacks(scanner)
        initAutoCoords()
        BotState.isRunning = true; BotState.sessionStartMs = System.currentTimeMillis()
        BotState.lastHpRatio = 1.0f; BotState.hpDropCycles = 0; BotState.hpStableCycles = 0
        BotState.underAttack = false; BotState.hpDisplayPct = -1
        BotState.potTapX = 0f; BotState.potTapY = 0f; BotState.potSource = ""
        huntCycles = 0; patrolDir = 0; patrolStep = 0; camDir = 1; prevHadTarget = false
        targetX = 0f; targetY = 0f; hpRatio = 1.0f; hpFound = false
        lootX = 0f; lootY = 0f; trackX = 0f; trackY = 0f
        tDamage = 0L; tLastTgt = 0L; tLastCombat = 0L; tLoot = 0L; tPotion = 0L
        lastTX = 0f; lastTY = 0f
        tSk1 = 0L; tSk2 = 0L; tSk3 = 0L; tSk4 = 0L; tSk5 = 0L
        barX = -1; barY = -1; barW = 0; scanCount = 0
        potAutoX = 0f; potAutoY = 0f
        slotIdx = 0; slotTaps.fill(0); slotLooksEmpty = false; isRefilling = false
        handler.post(loop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, SCAN_DELAY_MS)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacks(loop)
        if (!BotState.potionOnlyMode && !BotState.lootOnlyMode)
            handler.removeCallbacks(scanner)
        targetX = 0f; targetY = 0f; isRefilling = false
    }

    fun startPotionOnly() {
        if (BotState.potionOnlyMode) return
        BotState.potionOnlyMode = true
        BotState.potTapX = 0f; BotState.potTapY = 0f; BotState.potSource = ""
        handler.post(potionOnlyLoop)
        if (!BotState.isRunning && !BotState.lootOnlyMode &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, SCAN_DELAY_MS)
    }

    fun stopPotionOnly() {
        BotState.potionOnlyMode = false
        handler.removeCallbacks(potionOnlyLoop)
        if (!BotState.isRunning && !BotState.lootOnlyMode)
            handler.removeCallbacks(scanner)
    }

    // ── Modalità Raccolta Terra (standalone) ──────────────────────────────────
    fun startLootOnly() {
        if (BotState.lootOnlyMode) return
        BotState.lootOnlyMode = true
        BotState.lootItemsFound = 0
        groundItems = emptyList()
        handler.post(lootOnlyLoop)
        if (!BotState.isRunning && !BotState.potionOnlyMode &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, SCAN_DELAY_MS)
    }

    fun stopLootOnly() {
        BotState.lootOnlyMode = false
        BotState.lootItemsFound = 0
        handler.removeCallbacks(lootOnlyLoop)
        if (!BotState.isRunning && !BotState.potionOnlyMode)
            handler.removeCallbacks(scanner)
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
