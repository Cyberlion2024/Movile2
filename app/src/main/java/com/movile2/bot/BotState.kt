package com.movile2.bot

import java.util.concurrent.CopyOnWriteArrayList

object BotState {
    // ── Attacco ───────────────────────────────────────────────────────────────
    @Volatile var attackRunning: Boolean = false
    @Volatile var attackPos: Pair<Float, Float>? = null
    @Volatile var mobNearby: Boolean = false

    // ── Rilevamento mob (scanner pixel) ──────────────────────────────────────
    // Numero di cluster di nomi rossi distinti rilevati nell'ultimo scan.
    // Un cluster = un mob separato sullo schermo.
    // Aggiornato ogni ~500ms dal mobScanner in BotAccessibilityService.
    @Volatile var detectedMobCount: Int = 0

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
    // Raccoglie Yang e solo oggetti con il nome del personaggio dell'utente.
    // NON ferma l'attacco — attacco e loot coesistono.
    @Volatile var lootRunning: Boolean = false
    @Volatile var lootItemsFound: Int = 0

    // Nomi personaggio dell'utente (in minuscolo per confronto case-insensitive).
    // Il bot raccoglierà solo Yang + oggetti con etichetta corrispondente a uno di questi nomi.
    val characterNames: CopyOnWriteArrayList<String> = CopyOnWriteArrayList(
        listOf("bashy", "anyasama")
    )

    // ── Camminata bot ─────────────────────────────────────────────────────────
    @Volatile var walkRunning: Boolean = false
    @Volatile var joystickPos: Pair<Float, Float>? = null
    @Volatile var joystickRadius: Float = 130f   // raggio outer circle in px

    // ── Direzione mob (aggiornata dal mob scanner) ────────────────────────────
    // Vettore normalizzato dal personaggio verso il centroide dei mob rossi.
    // (0, -1) = avanti (su) di default quando nessun mob è rilevato.
    @Volatile var mobDirX: Float = 0f
    @Volatile var mobDirY: Float = -1f

    // ── Raggruppamento mob (Pull mode) ────────────────────────────────────────
    // Quando pullMode=true le abilità si attivano solo se detectedMobCount
    // >= pullTargetCount. Il bot cammina/attacca normalmente per "tirare" i mob.
    // UmobNamer.ismob + medusman confermati in libUE4.so → nomi ROSSI = mob nemici.
    // Verde = gruppo (ignorato); bianco = proprio personaggio (ignorato).
    @Volatile var pullMode: Boolean = false
    @Volatile var pullTargetCount: Int = 3      // quanti mob raggruppare (1..5)

    // ── Pausa joystick manuale ────────────────────────────────────────────────
    // Quando true: l'utente sta usando il joystick manualmente.
    // Il bot mette in pausa tutte le azioni e riprende dopo JOY_RESUME_DELAY_MS.
    @Volatile var joystickActive: Boolean = false
}
