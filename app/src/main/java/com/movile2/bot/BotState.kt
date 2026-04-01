package com.movile2.bot

import java.util.concurrent.CopyOnWriteArrayList

object BotState {
    // ── Modalità pozione ──────────────────────────────────────────────────────
    @Volatile var potionRunning: Boolean = false
    @Volatile var potionIntervalMs: Long = 3000L

    // Lista degli slot pozione impostati dall'utente (max 3)
    val potionSlots: CopyOnWriteArrayList<Pair<Float, Float>> = CopyOnWriteArrayList()

    // ── Modalità raccolta terra ───────────────────────────────────────────────
    @Volatile var lootRunning: Boolean = false
    @Volatile var lootItemsFound: Int = 0
}
