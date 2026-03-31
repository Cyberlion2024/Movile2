package com.movile2.bot

object BotState {
    @Volatile var killCount: Int = 0
    @Volatile var isRunning: Boolean = false
    @Volatile var sessionStartMs: Long = 0L

    // HP monitoring
    @Volatile var lastHpRatio: Float = 1.0f
    @Volatile var hpDropCycles: Int = 0
    @Volatile var hpStableCycles: Int = 0
    @Volatile var underAttack: Boolean = false

    // Per debug overlay: % vita corrente (0-100), -1 = non configurata
    @Volatile var hpDisplayPct: Int = -1

    var onUpdate: (() -> Unit)? = null
}
