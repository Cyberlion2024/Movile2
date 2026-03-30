package com.movile2.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {

    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val cooldowns = mutableMapOf<String, Long>()

    private var config = RuntimeBotConfig()
    private var killCount = 0
    private var inCombat = false
    private var targetSeenAtMs: Long = 0L
    private var lastPotionAtMs: Long = 0L
    private var lastRefillAtMs: Long = 0L

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return

            val now = SystemClock.elapsedRealtime()
            updateCombatState(now)

            if (killCount >= config.maxKills) {
                stopBot()
                return
            }

            if (canUse("potion", now, 8_000L)) {
                useHealthPotion()
            }

            if (hasTarget(now)) {
                attack()
                useAvailableSkills(now)
            } else {
                moveToSearchTarget()
            }

            if (canUse("refill", now, 20_000L)) {
                refillPotionSlotFromInventory()
            }

            val delay = Random.nextLong(140L, 280L)
            handler.postDelayed(this, delay)
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running.get()) return
        val target = config.monsterName.trim().lowercase(Locale.ROOT)
        if (target.isEmpty()) return

        val line = buildString {
            event?.text?.forEach { append(it).append(' ') }
            append(event?.contentDescription ?: "")
        }.trim().lowercase(Locale.ROOT)

        if (line.contains(target)) {
            targetSeenAtMs = SystemClock.elapsedRealtime()
            inCombat = true
        }
    }

    override fun onInterrupt() {
        stopBot()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBot()
        if (instance === this) {
            instance = null
        }
    }

    fun startBot() {
        if (running.compareAndSet(false, true)) {
            loadConfig()
            killCount = 0
            inCombat = false
            targetSeenAtMs = 0L
            lastPotionAtMs = 0L
            lastRefillAtMs = 0L
            cooldowns.clear()
            handler.post(loopRunnable)
        }
    }

    fun stopBot() {
        running.set(false)
        handler.removeCallbacks(loopRunnable)
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(BotConfig.PREFS_NAME, MODE_PRIVATE)
        config = RuntimeBotConfig(
            monsterName = prefs.getString(BotConfig.KEY_MONSTER_NAME, "") ?: "",
            maxKills = prefs.getInt(BotConfig.KEY_MAX_KILLS, 50).coerceAtLeast(1)
        )
    }

    private fun updateCombatState(now: Long) {
        if (inCombat && now - targetSeenAtMs > 2_200L) {
            killCount += 1
            inCombat = false
        }
    }

    private fun hasTarget(now: Long): Boolean {
        if (config.monsterName.isBlank()) return true
        return now - targetSeenAtMs <= 1_500L
    }

    private fun attack() {
        tap(config.attackX, config.attackY)
    }

    private fun useAvailableSkills(now: Long) {
        if (canUse("skill_1", now, config.skill1CooldownMs)) {
            tap(config.skill1X, config.skill1Y)
        }
        if (canUse("skill_2", now, config.skill2CooldownMs)) {
            tap(config.skill2X, config.skill2Y)
        }
        if (canUse("skill_3", now, config.skill3CooldownMs)) {
            tap(config.skill3X, config.skill3Y)
        }
    }

    private fun moveToSearchTarget() {
        swipe(config.moveFromX, config.moveFromY, config.moveToX, config.moveToY)
    }

    private fun useHealthPotion() {
        tap(config.potionSlotX, config.potionSlotY)
        lastPotionAtMs = SystemClock.elapsedRealtime()
    }

    private fun refillPotionSlotFromInventory() {
        // Placeholder flow:
        // 1) Open inventory
        // 2) Drag potion from inventory to potion slot
        tap(config.inventoryButtonX, config.inventoryButtonY)
        handler.postDelayed({
            swipe(
                config.inventoryPotionX,
                config.inventoryPotionY,
                config.potionSlotX,
                config.potionSlotY
            )
            lastRefillAtMs = SystemClock.elapsedRealtime()
        }, 300L)
    }

    private fun canUse(key: String, now: Long, cooldownMs: Long): Boolean {
        val last = cooldowns[key] ?: 0L
        if (now - last < cooldownMs) return false
        cooldowns[key] = now
        return true
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()

        dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
            .build()

        dispatchGesture(gesture, null, null)
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}

data class RuntimeBotConfig(
    val monsterName: String = "",
    val maxKills: Int = 50,
    val attackX: Float = 950f,
    val attackY: Float = 700f,
    val skill1X: Float = 850f,
    val skill1Y: Float = 650f,
    val skill2X: Float = 780f,
    val skill2Y: Float = 720f,
    val skill3X: Float = 720f,
    val skill3Y: Float = 770f,
    val skill1CooldownMs: Long = 4_000L,
    val skill2CooldownMs: Long = 6_500L,
    val skill3CooldownMs: Long = 10_000L,
    val moveFromX: Float = 500f,
    val moveFromY: Float = 800f,
    val moveToX: Float = 500f,
    val moveToY: Float = 400f,
    val potionSlotX: Float = 110f,
    val potionSlotY: Float = 720f,
    val inventoryButtonX: Float = 1740f,
    val inventoryButtonY: Float = 520f,
    val inventoryPotionX: Float = 1510f,
    val inventoryPotionY: Float = 970f
)
