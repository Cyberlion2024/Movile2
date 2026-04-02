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

    // ── Modalità abilità ──────────────────────────────────────────────────────
    @Volatile var skillsRunning: Boolean = false
    @Volatile var skillIntervalMs: Long = 5000L
    val skillSlots: CopyOnWriteArrayList<Pair<Float, Float>> = CopyOnWriteArrayList()

    // ── Modalità raccolta terra ───────────────────────────────────────────────
    @Volatile var lootRunning: Boolean = false
    @Volatile var lootItemsFound: Int = 0

    // ── Joystick — pausa automatica dei bot ──────────────────────────────────
    @Volatile var joystickPos: Pair<Float, Float>? = null
    @Volatile var joystickActive: Boolean = false
}
