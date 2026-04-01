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
    @Volatile var hpDisplayPct: Int = -1

    // Debug overlay: dove sta tappando la pozione (0 = non ancora trovata)
    @Volatile var potTapX: Float = 0f
    @Volatile var potTapY: Float = 0f
    // "AUTO" = rilevata da screenshot, "MANUALE" = configurata dall'utente, "" = non trovata
    @Volatile var potSource: String = ""

    var onUpdate: (() -> Unit)? = null
}
