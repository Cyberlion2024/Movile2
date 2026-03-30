package com.movile2.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {

    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return

            attack()
            useSkills()

            if (Random.nextFloat() < 0.3f) {
                move()
            }

            val delay = Random.nextLong(120L, 320L)
            handler.postDelayed(this, delay)
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this bot runs on a timed loop.
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
            handler.post(loopRunnable)
        }
    }

    fun stopBot() {
        running.set(false)
        handler.removeCallbacks(loopRunnable)
    }

    private fun attack() {
        tap(950f, 700f) // Main sword button
    }

    private fun useSkills() {
        tap(850f, 650f)
        handler.postDelayed({ tap(780f, 720f) }, 180L)
    }

    private fun move() {
        swipe(500f, 800f, 500f, 400f)
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
