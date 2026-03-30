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
    private var roomBounds = RoomBounds()
    private var lastMovePoint = MovePoint(0.5f, 0.6f)

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
                moveInsideRoom()
            }

            if (canUse("refill", now, 20_000L)) {
                refillPotionSlotFromInventory()
            }

            val delay = Random.nextLong(160L, 320L)
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
            if (config.learnPerimeter) {
                roomBounds.expandAround(lastMovePoint)
            }
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
            cooldowns.clear()
            roomBounds = RoomBounds()
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
            maxKills = prefs.getInt(BotConfig.KEY_MAX_KILLS, 50).coerceAtLeast(1),
            learnPerimeter = prefs.getBoolean(BotConfig.KEY_LEARN_PERIMETER, true)
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
        val ui = uiPoints()
        tap(ui.attackX, ui.attackY)
    }

    private fun useAvailableSkills(now: Long) {
        val ui = uiPoints()
        if (canUse("skill_1", now, config.skill1CooldownMs)) {
            tap(ui.skill1X, ui.skill1Y)
        }
        if (canUse("skill_2", now, config.skill2CooldownMs)) {
            tap(ui.skill2X, ui.skill2Y)
        }
        if (canUse("skill_3", now, config.skill3CooldownMs)) {
            tap(ui.skill3X, ui.skill3Y)
        }
    }

    private fun moveInsideRoom() {
        val from = MovePoint(0.5f, 0.78f)
        val to = roomBounds.randomPoint()
        lastMovePoint = to
        swipe(normX(from.x), normY(from.y), normX(to.x), normY(to.y))
    }

    private fun useHealthPotion() {
        val ui = uiPoints()
        tap(ui.potionSlotX, ui.potionSlotY)
    }

    private fun refillPotionSlotFromInventory() {
        val ui = uiPoints()
        tap(ui.inventoryButtonX, ui.inventoryButtonY)
        handler.postDelayed({
            swipe(ui.inventoryPotionX, ui.inventoryPotionY, ui.potionSlotX, ui.potionSlotY)
        }, 300L)
    }

    private fun canUse(key: String, now: Long, cooldownMs: Long): Boolean {
        val last = cooldowns[key] ?: 0L
        if (now - last < cooldownMs) return false
        cooldowns[key] = now
        return true
    }

    private fun uiPoints(): UiPoints {
        return UiPoints(
            attackX = normX(0.89f),
            attackY = normY(0.84f),
            skill1X = normX(0.79f),
            skill1Y = normY(0.73f),
            skill2X = normX(0.73f),
            skill2Y = normY(0.81f),
            skill3X = normX(0.68f),
            skill3Y = normY(0.87f),
            potionSlotX = normX(0.08f),
            potionSlotY = normY(0.82f),
            inventoryButtonX = normX(0.94f),
            inventoryButtonY = normY(0.52f),
            inventoryPotionX = normX(0.82f),
            inventoryPotionY = normY(0.72f)
        )
    }

    private fun normX(percent: Float): Float = resources.displayMetrics.widthPixels * percent

    private fun normY(percent: Float): Float = resources.displayMetrics.heightPixels * percent

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
    val learnPerimeter: Boolean = true,
    val skill1CooldownMs: Long = 4_000L,
    val skill2CooldownMs: Long = 6_500L,
    val skill3CooldownMs: Long = 10_000L
)

data class UiPoints(
    val attackX: Float,
    val attackY: Float,
    val skill1X: Float,
    val skill1Y: Float,
    val skill2X: Float,
    val skill2Y: Float,
    val skill3X: Float,
    val skill3Y: Float,
    val potionSlotX: Float,
    val potionSlotY: Float,
    val inventoryButtonX: Float,
    val inventoryButtonY: Float,
    val inventoryPotionX: Float,
    val inventoryPotionY: Float
)

data class MovePoint(val x: Float, val y: Float)

data class RoomBounds(
    var minX: Float = 0.22f,
    var maxX: Float = 0.78f,
    var minY: Float = 0.32f,
    var maxY: Float = 0.70f
) {
    fun expandAround(point: MovePoint) {
        minX = minOf(minX, point.x - 0.05f).coerceAtLeast(0.10f)
        maxX = maxOf(maxX, point.x + 0.05f).coerceAtMost(0.90f)
        minY = minOf(minY, point.y - 0.05f).coerceAtLeast(0.15f)
        maxY = maxOf(maxY, point.y + 0.05f).coerceAtMost(0.85f)
    }

    fun randomPoint(): MovePoint {
        val x = Random.nextFloat() * (maxX - minX) + minX
        val y = Random.nextFloat() * (maxY - minY) + minY
        return MovePoint(x, y)
    }
}
