package com.movile2.bot

object BotState {
    @Volatile var killCount: Int = 0
    @Volatile var isRunning: Boolean = false
    @Volatile var sessionStartMs: Long = 0L

    // HP monitoring
    @Volatile var lastHpRatio: Float = 1.0f       // ultimo valore HP letto (0.0-1.0)
    @Volatile var hpDropCycles: Int = 0           // cicli consecutivi in cui HP è calato
    @Volatile var hpStableCycles: Int = 0         // cicli consecutivi in cui HP è stabile
    @Volatile var underAttack: Boolean = false    // true = modalità difesa attiva

    var onUpdate: (() -> Unit)? = null
}
