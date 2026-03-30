package com.movile2.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastSkill1Ms = 0L
    private var lastSkill2Ms = 0L
    private var potionUsesInSlot = 0
    private var attackCycles = 0
    private var monsterFoundViaTree = false

    private enum class Phase { TARGET_MONSTER, WAIT_WALK, ATTACK, POTION, REFILL }
    private var phase = Phase.TARGET_MONSTER

    // Loop principale
    private val loop = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            val cfg = BotConfig.load(this@BotAccessibilityService)

            if (cfg.maxKills > 0 && BotState.killCount >= cfg.maxKills) {
                stopBot(); return
            }

            when (phase) {
                Phase.TARGET_MONSTER -> doTargetMonster(cfg)
                Phase.WAIT_WALK      -> doWaitWalk(cfg)
                Phase.ATTACK         -> doAttack(cfg)
                Phase.POTION         -> doPotion(cfg)
                Phase.REFILL         -> doRefill(cfg)
            }
        }
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Cerca il mostro per nome nell'albero UI (funziona solo se il gioco usa viste Android)
        if (!BotState.isRunning) return
        val cfg = BotConfig.load(this)
        if (cfg.monsterName.isNotBlank()) {
            monsterFoundViaTree = false
            scanForMonster(event?.source, cfg.monsterName)
        }
    }

    override fun onInterrupt() { stopBot() }

    override fun onDestroy() {
        super.onDestroy()
        stopBot()
        if (instance === this) instance = null
    }

    fun startBot() {
        if (BotState.isRunning) return
        BotState.isRunning = true
        attackCycles = 0
        potionUsesInSlot = 0
        lastSkill1Ms = 0L
        lastSkill2Ms = 0L
        monsterFoundViaTree = false
        phase = Phase.TARGET_MONSTER
        handler.post(loop)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    // ── FASE 1: Tocca dove si trovano i mostri per selezionarli ──────────────
    private fun doTargetMonster(cfg: BotConfig) {
        if (!monsterFoundViaTree) {
            // I mostri appaiono nella parte ALTA dell'area configurata.
            // Tocchiamo casualmente lì: il personaggio li selezionerà e ci camminerà sopra.
            val x = Random.nextFloat() * (cfg.searchRight - cfg.searchLeft) + cfg.searchLeft
            // Solo la metà superiore: dove i mostri sono visibili sullo schermo
            val monsterZoneBottom = cfg.searchTop + (cfg.searchBottom - cfg.searchTop) * 0.55f
            val y = Random.nextFloat() * (monsterZoneBottom - cfg.searchTop) + cfg.searchTop
            tap(x, y)
        }
        // Aspetta che il personaggio inizi a camminare verso il mostro
        phase = Phase.WAIT_WALK
        next(1200L)
    }

    // ── FASE 2: Attendi avvicinamento, poi inizia attacco ────────────────────
    private fun doWaitWalk(cfg: BotConfig) {
        // Premi già un'abilità per iniziare il danno appena il pg è in range
        tap(cfg.skill1X, cfg.skill1Y)
        lastSkill1Ms = System.currentTimeMillis()
        phase = Phase.ATTACK
        next(500L)
    }

    // ── FASE 3: Loop attacco + abilità ───────────────────────────────────────
    private fun doAttack(cfg: BotConfig) {
        val now = System.currentTimeMillis()

        // Bottone attacco base (o skill principale)
        tap(cfg.attackX, cfg.attackY)

        // Abilità 1 quando è disponibile
        if (now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill1X, cfg.skill1Y)
                    lastSkill1Ms = System.currentTimeMillis()
                }
            }, 150L)
        }

        // Abilità 2 quando è disponibile
        if (now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill2X, cfg.skill2Y)
                    lastSkill2Ms = System.currentTimeMillis()
                }
            }, 300L)
        }

        attackCycles++

        // Ogni 6 cicli di attacco = 1 kill stimato
        if (attackCycles % 6 == 0) {
            BotState.killCount++
        }

        // Usa pozione ogni 25 cicli (se configurata)
        if (cfg.potionX > 0 && attackCycles % 25 == 0) {
            phase = Phase.POTION
            next(300L)
            return
        }

        // Ogni 15 cicli ritorna a cercare un nuovo mostro
        if (attackCycles % 15 == 0) {
            phase = Phase.TARGET_MONSTER
            next(400L)
            return
        }

        next(cfg.attackDelayMs + Random.nextLong(-20L, 40L))
    }

    // ── FASE 4: Usa pozione ──────────────────────────────────────────────────
    private fun doPotion(cfg: BotConfig) {
        tap(cfg.potionX, cfg.potionY)
        potionUsesInSlot++
        phase = if (potionUsesInSlot >= cfg.maxPotionsInSlot && cfg.backupPotionX > 0)
            Phase.REFILL else Phase.ATTACK
        next(400L)
    }

    // ── FASE 5: Ricarica pozione dall'inventario ─────────────────────────────
    private fun doRefill(cfg: BotConfig) {
        // Swipe dalla pozione di riserva (inventario) allo slot attivo
        swipe(cfg.backupPotionX, cfg.backupPotionY, cfg.potionX, cfg.potionY)
        potionUsesInSlot = 0
        phase = Phase.ATTACK
        next(1000L)
    }

    // ── Cerca mostro nell'albero di accessibilità (giochi con viste Android) ─
    private fun scanForMonster(node: AccessibilityNodeInfo?, name: String) {
        node ?: return
        if (node.text?.toString()?.contains(name, ignoreCase = true) == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                tap(rect.exactCenterX(), rect.exactCenterY())
                monsterFoundViaTree = true
            }
        }
        for (i in 0 until node.childCount) scanForMonster(node.getChild(i), name)
    }

    private fun next(ms: Long) = handler.postDelayed(loop, ms)

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(), null, null
        )
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
                .build(), null, null
        )
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
