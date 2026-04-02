package com.movile2.bot

import java.util.concurrent.CopyOnWriteArrayList

object BotState {
    // ── Attacco ───────────────────────────────────────────────────────────────
    @Volatile var attackRunning: Boolean = false
    @Volatile var attackPos: Pair<Float, Float>? = null
    @Volatile var mobNearby: Boolean = false

    // ── Pozione ───────────────────────────────────────────────────────────────
    @Volatile var potionRunning: Boolean = false
    @Volatile var potionIntervalMs: Long = 3000L
    val potionSlots: CopyOnWriteArrayList<Pair<Float, Float>> = CopyOnWriteArrayList()

    // ── Abilità ───────────────────────────────────────────────────────────────
    @Volatile var skillsRunning: Boolean = false
    val skillSlots: CopyOnWriteArrayList<Pair<Float, Float>> = CopyOnWriteArrayList()
    val skillIntervals: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList(
        listOf(5000L, 5000L, 5000L, 5000L, 5000L)
    )

    // ── Raccolta terra ────────────────────────────────────────────────────────
    // Quando attivo: raccoglie yang (oro) e oggetti con nome verde.
    // NON attacca mai — stoppa l'attacco al momento dell'attivazione.
    @Volatile var lootRunning: Boolean = false
    @Volatile var lootItemsFound: Int = 0

    // ── Camminata bot ─────────────────────────────────────────────────────────
    // Il bot spinge il joystick in avanti continuamente.
    // Si ferma solo quando l'utente preme STOP o STOP TUTTO.
    @Volatile var walkRunning: Boolean = false
    @Volatile var joystickPos: Pair<Float, Float>? = null

    // ── Pausa joystick manuale ────────────────────────────────────────────────
    // Quando true: l'utente sta usando il joystick manualmente.
    // Il bot mette in pausa tutte le azioni e riprende dopo JOY_RESUME_DELAY_MS.
    @Volatile var joystickActive: Boolean = false
}
