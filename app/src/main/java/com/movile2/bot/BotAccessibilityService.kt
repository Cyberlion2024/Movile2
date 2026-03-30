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

    // Patrol direction index (0=N, 1=E, 2=S, 3=W) and steps in current direction
    private var patrolDir = 0
    private var patrolSteps = 0

    // Camera rotation direction: alternates left/right each camera cycle
    private var cameraLookDir = 1

    // Patrol pattern: steps per direction to make a rectangle (N, E, S, W)
    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)

    // Direction vectors (dx, dy) for joystick push
    private val PATROL_DIRS = arrayOf(
        floatArrayOf(0f, -1f),  // N
        floatArrayOf(1f,  0f),  // E
        floatArrayOf(0f,  1f),  // S
        floatArrayOf(-1f, 0f),  // W
    )

    // Loop cycle: every CAMERA_EVERY cycles do a camera rotation instead of joystick push
    private val CAMERA_EVERY = 4

    private enum class Phase { HUNT, POTION, REFILL }
    private var phase = Phase.HUNT

    // Full loop cycle duration (ms). All gestures must finish BEFORE this fires.
    // Joystick push = 350ms, attack tap at +400ms, skill1 at +520ms, skill2 at +640ms
    // Next loop at +800ms → no overlap with any gesture.
    private val CYCLE_MS = 800L

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
        // Tenta di toccare il mostro per nome nell'albero UI (solo se il gioco usa viste Android)
        if (!BotState.isRunning) return
        val cfg = BotConfig.load(this)
        if (cfg.monsterName.isNotBlank()) {
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
        phase = Phase.HUNT
        patrolDir = 0
        patrolSteps = 0
        cameraLookDir = 1
        handler.post(loop)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    // ── HUNT: movimento joystick + rotazione visuale + attacco ───────────────
    private fun doHunt(cfg: BotConfig) {
        val now = System.currentTimeMillis()
        val useJoystick = cfg.joystickX > 0f && cfg.joystickY > 0f
        val useCamera   = cfg.cameraAreaX > 0f && cfg.cameraAreaY > 0f

        // Ogni CAMERA_EVERY cicli: ruota la visuale per cercare i mostri
        val doCamera = useCamera && (huntCycles % CAMERA_EVERY == CAMERA_EVERY - 1)

        if (doCamera) {
            // Swipe orizzontale nell'area visuale per ruotare la telecamera
            val cx = cfg.cameraAreaX
            val cy = cfg.cameraAreaY
            val range = cfg.cameraSwipeRange
            val startX = cx - cameraLookDir * range
            val endX   = cx + cameraLookDir * range
            swipe(startX, cy, endX, cy, 250L)
            cameraLookDir = -cameraLookDir
        } else if (useJoystick) {
            // Spingi il joystick nella direzione di pattuglia
            val dir  = PATROL_DIRS[patrolDir]
            val endX = cfg.joystickX + dir[0] * cfg.joystickRadius
            val endY = cfg.joystickY + dir[1] * cfg.joystickRadius
            joystickPush(cfg.joystickX, cfg.joystickY, endX, endY, 350L)

            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) {
                patrolSteps = 0
                patrolDir = (patrolDir + 1) % PATROL_DIRS.size
            }
        }

        // ── Gesti di attacco: tutti schedulati DOPO che il gesto di movimento è terminato.
        // Joystick finisce a +350ms → tutto parte da +400ms in poi.
        // Camera swipe finisce a +250ms → tutto parte da +300ms in poi.
        // Ciclo totale = 800ms → nessun conflitto.
        val baseDelay = if (doCamera) 300L else 400L

        // Bottone attacco base
        handler.postDelayed({
            if (BotState.isRunning) tap(cfg.attackX, cfg.attackY)
        }, baseDelay)

        // Abilità 1 se cooldown scaduto
        if (now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill1X, cfg.skill1Y)
                    lastSkill1Ms = System.currentTimeMillis()
                }
            }, baseDelay + 120L)
        }

        // Abilità 2 se cooldown scaduto
        if (now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill2X, cfg.skill2Y)
                    lastSkill2Ms = System.currentTimeMillis()
                }
            }, baseDelay + 240L)
        }

        huntCycles++

        // Stima kill: 1 ogni 8 cicli
        if (huntCycles % 8 == 0) BotState.killCount++

        // Pozione ogni 30 cicli
        if (cfg.potionX > 0 && huntCycles % 30 == 0) {
            phase = Phase.POTION
            next(CYCLE_MS)
            return
        }

        next(CYCLE_MS + Random.nextLong(0L, 30L))
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

    // ── Cerca mostro per nome nell'albero UI (tap diretto sul target) ─────────
    private fun scanForMonster(node: AccessibilityNodeInfo?, name: String) {
        node ?: return
        if (node.text?.toString()?.contains(name, ignoreCase = true) == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) tap(rect.exactCenterX(), rect.exactCenterY())
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

    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, durationMs: Long) {
        val path = Path().apply { moveTo(cx, cy); lineTo(tx, ty) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(), null, null
        )
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
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
