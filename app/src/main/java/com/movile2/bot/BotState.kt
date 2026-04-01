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

    // Debug overlay pozione
    @Volatile var potTapX: Float = 0f
    @Volatile var potTapY: Float = 0f
    @Volatile var potSource: String = ""

    // ── Modalità Solo Pozioni ─────────────────────────────────────────────────
    @Volatile var potionOnlyMode: Boolean = false

    // ── Modalità Raccolta Terra ───────────────────────────────────────────────
    // Quando true: scansiona lo schermo e raccoglie yang + oggetti da terra
    // uno per uno, senza attaccare né muoversi.
    @Volatile var lootOnlyMode: Boolean = false
    // Numero di oggetti trovati nell'ultimo scan
    @Volatile var lootItemsFound: Int = 0

    var onUpdate: (() -> Unit)? = null
}
