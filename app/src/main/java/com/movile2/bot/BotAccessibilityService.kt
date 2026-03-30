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
    private var huntCycles = 0
    private var monsterFoundViaTree = false

    // Patrol direction index (0=N, 1=E, 2=S, 3=W) and steps in current direction
    private var patrolDir = 0
    private var patrolSteps = 0

    // Patrol pattern: how many steps per direction (N, E, S, W) to make a rectangle
    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)

    // Direction vectors (dx, dy) for joystick push: N=up, E=right, S=down, W=left
    private val PATROL_DIRS = arrayOf(
        floatArrayOf(0f, -1f),   // N
        floatArrayOf(1f,  0f),   // E
        floatArrayOf(0f,  1f),   // S
        floatArrayOf(-1f, 0f),   // W
    )

    private enum class Phase { HUNT, POTION, REFILL }
    private var phase = Phase.HUNT

    private val loop = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            val cfg = BotConfig.load(this@BotAccessibilityService)

            if (cfg.maxKills > 0 && BotState.killCount >= cfg.maxKills) {
                stopBot(); return
            }

            when (phase) {
                Phase.HUNT   -> doHunt(cfg)
                Phase.POTION -> doPotion(cfg)
                Phase.REFILL -> doRefill(cfg)
            }
        }
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        huntCycles = 0
        potionUsesInSlot = 0
        lastSkill1Ms = 0L
        lastSkill2Ms = 0L
        monsterFoundViaTree = false
        phase = Phase.HUNT
        patrolDir = 0
        patrolSteps = 0
        handler.post(loop)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    // ── FASE HUNT: muovi col joystick + attacca contemporaneamente ────────────
    private fun doHunt(cfg: BotConfig) {
        val now = System.currentTimeMillis()
        val useJoystick = cfg.joystickX > 0f && cfg.joystickY > 0f

        if (useJoystick) {
            // Spingi il joystick nella direzione di pattuglia (350ms di camminata)
            val dir = PATROL_DIRS[patrolDir]
            val endX = cfg.joystickX + dir[0] * cfg.joystickRadius
            val endY = cfg.joystickY + dir[1] * cfg.joystickRadius
            joystickPush(cfg.joystickX, cfg.joystickY, endX, endY, 350L)

            // Avanza nella direzione corrente; dopo PATROL_STEPS cambia direzione
            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) {
                patrolSteps = 0
                patrolDir = (patrolDir + 1) % PATROL_DIRS.size
            }
        } else {
            // Fallback: tocca l'area di ricerca per selezionare/avvicinarsi al mostro
            if (!monsterFoundViaTree) {
                val searchW = cfg.searchRight - cfg.searchLeft
                val searchH = cfg.searchBottom - cfg.searchTop
                if (searchW > 0 && searchH > 0) {
                    val x = cfg.searchLeft + Random.nextFloat() * searchW
                    val zoneH = searchH * 0.55f
                    val y = cfg.searchTop + Random.nextFloat() * zoneH
                    tap(x, y)
                }
            }
        }

        // Tappa il bottone attacco (dopo il joystick push, così i gesti non si sovrappongono)
        handler.postDelayed({
            if (BotState.isRunning) tap(cfg.attackX, cfg.attackY)
        }, 400L)

        // Abilità 1 se disponibile
        if (now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill1X, cfg.skill1Y)
                    lastSkill1Ms = System.currentTimeMillis()
                }
            }, 520L)
        }

        // Abilità 2 se disponibile
        if (now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill2X, cfg.skill2Y)
                    lastSkill2Ms = System.currentTimeMillis()
                }
            }, 640L)
        }

        huntCycles++

        // Stima uccisioni: 1 ogni 8 cicli di attacco
        if (huntCycles % 8 == 0) {
            BotState.killCount++
        }

        // Usa pozione ogni 30 cicli
        if (cfg.potionX > 0 && huntCycles % 30 == 0) {
            phase = Phase.POTION
            next(700L)
            return
        }

        // Prossimo ciclo: aspetta che il joystick finisca (350ms) + buffer (100ms) + attackDelay
        val loopDelay = if (useJoystick)
            350L + 100L + Random.nextLong(0L, 50L)
        else
            cfg.attackDelayMs + Random.nextLong(-20L, 40L)

        next(loopDelay)
    }

    // ── Usa pozione ──────────────────────────────────────────────────────────
    private fun doPotion(cfg: BotConfig) {
        tap(cfg.potionX, cfg.potionY)
        potionUsesInSlot++
        phase = if (potionUsesInSlot >= cfg.maxPotionsInSlot && cfg.backupPotionX > 0)
            Phase.REFILL else Phase.HUNT
        next(400L)
    }

    // ── Ricarica pozione dall'inventario ─────────────────────────────────────
    private fun doRefill(cfg: BotConfig) {
        swipe(cfg.backupPotionX, cfg.backupPotionY, cfg.potionX, cfg.potionY, 400L)
        potionUsesInSlot = 0
        phase = Phase.HUNT
        next(1000L)
    }

    // ── Cerca mostro nell'albero UI (giochi con viste Android standard) ───────
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

    // Spingi il joystick: pressione prolungata da centro verso direzione
    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(tx, ty)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(), null, null
        )
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 400L) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(), null, null
        )
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
