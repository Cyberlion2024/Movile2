package com.movile2.bot

object BotState {
    // ── Modalità pozione ──────────────────────────────────────────────────────
    @Volatile var potionRunning: Boolean = false
    @Volatile var potionX: Float = 0f
    @Volatile var potionY: Float = 0f
    @Volatile var potionIntervalMs: Long = 3000L

    // ── Modalità raccolta terra ───────────────────────────────────────────────
    @Volatile var lootRunning: Boolean = false
    @Volatile var lootItemsFound: Int = 0
}
