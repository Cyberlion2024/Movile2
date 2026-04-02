package com.movile2.bot

import java.util.concurrent.CopyOnWriteArrayList

object BotState {
    // ── Modalità attacco ──────────────────────────────────────────────────────
    @Volatile var attackRunning: Boolean = false
    @Volatile var attackPos: Pair<Float, Float>? = null
    @Volatile var mobNearby: Boolean = false

    // ── Modalità pozione ──────────────────────────────────────────────────────
    @Volatile var potionRunning: Boolean = false
    @Volatile var potionIntervalMs: Long = 3000L
    val potionSlots: CopyOnWriteArrayList<Pair<Float, Float>> = CopyOnWriteArrayList()

    // ── Modalità abilità (5 slot, ognuno con il suo intervallo indipendente) ──
    @Volatile var skillsRunning: Boolean = false
    val skillSlots: CopyOnWriteArrayList<Pair<Float, Float>> = CopyOnWriteArrayList()
    // Intervalli in ms per ogni slot abilità (indice 0-4)
    val skillIntervals: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList(
        listOf(5000L, 5000L, 5000L, 5000L, 5000L)
    )

    // ── Modalità raccolta terra ───────────────────────────────────────────────
    @Volatile var lootRunning: Boolean = false
    @Volatile var lootItemsFound: Int = 0

    // ── Joystick — pausa automatica dei bot ──────────────────────────────────
    @Volatile var joystickPos: Pair<Float, Float>? = null
    @Volatile var joystickActive: Boolean = false
}
