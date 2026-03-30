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

    private var gridCol = 0
    private var gridRow = 0
    private var gridDir = 1
    private val COLS = 5
    private val ROWS = 4

    private enum class Phase { SEARCH, ATTACK, POTION, REFILL }
    private var phase = Phase.SEARCH

    private val loop = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            val cfg = BotConfig.load(this@BotAccessibilityService)

            if (cfg.maxKills > 0 && BotState.killCount >= cfg.maxKills) {
                stopBot(); return
            }

            when (phase) {
                Phase.SEARCH -> runSearch(cfg)
                Phase.ATTACK -> runAttack(cfg)
                Phase.POTION -> runPotion(cfg)
                Phase.REFILL -> runRefill(cfg)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        gridCol = 0; gridRow = 0; gridDir = 1
        attackCycles = 0; potionUsesInSlot = 0
        lastSkill1Ms = 0L; lastSkill2Ms = 0L
        phase = Phase.SEARCH
        handler.post(loop)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun runSearch(cfg: BotConfig) {
        val (x, y) = nextGridPos(cfg)
        tap(x, y)
        phase = Phase.ATTACK
        next(700L)
    }

    private fun runAttack(cfg: BotConfig) {
        val now = System.currentTimeMillis()
        tap(cfg.attackX, cfg.attackY)

        if (now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill1X, cfg.skill1Y)
                    lastSkill1Ms = System.currentTimeMillis()
                }
            }, 130L)
        }

        if (now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill2X, cfg.skill2Y)
                    lastSkill2Ms = System.currentTimeMillis()
                }
            }, 270L)
        }

        attackCycles++

        if (attackCycles % 8 == 0) {
            BotState.killCount++
        }

        if (cfg.potionX > 0 && attackCycles % 20 == 0) {
            phase = Phase.POTION
            next(350L); return
        }

        if (attackCycles % 12 == 0) {
            phase = Phase.SEARCH
        }

        next(cfg.attackDelayMs + Random.nextLong(-30L, 60L))
    }

    private fun runPotion(cfg: BotConfig) {
        tap(cfg.potionX, cfg.potionY)
        potionUsesInSlot++
        phase = if (potionUsesInSlot >= cfg.maxPotionsInSlot && cfg.backupPotionX > 0)
            Phase.REFILL else Phase.ATTACK
        next(300L)
    }

    private fun runRefill(cfg: BotConfig) {
        swipe(cfg.backupPotionX, cfg.backupPotionY, cfg.potionX, cfg.potionY)
        potionUsesInSlot = 0
        phase = Phase.ATTACK
        next(900L)
    }

    private fun nextGridPos(cfg: BotConfig): Pair<Float, Float> {
        val cw = (cfg.searchRight - cfg.searchLeft) / COLS
        val ch = (cfg.searchBottom - cfg.searchTop) / ROWS
        val jitter = Random.nextFloat() * 16f - 8f
        val x = cfg.searchLeft + cw * gridCol + cw / 2f + jitter
        val y = cfg.searchTop  + ch * gridRow + ch / 2f + jitter

        gridCol += gridDir
        if (gridCol >= COLS || gridCol < 0) {
            gridDir = -gridDir
            gridCol += gridDir
            gridRow = (gridRow + 1) % ROWS
        }
        return Pair(x, y)
    }

    private fun scanForMonster(node: AccessibilityNodeInfo?, name: String) {
        node ?: return
        if (node.text?.toString()?.contains(name, ignoreCase = true) == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            tap(rect.exactCenterX(), rect.exactCenterY())
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
                .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
                .build(), null, null
        )
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
