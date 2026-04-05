package com.movile2.bot

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════════════════
// AIPlayerEngine — motore decisionale vision-driven che sostituisce
// BotDecisionEngine. Invece di usare timer fissi, legge GameState
// (prodotto da GameStateAnalyzer ogni 200ms) e decide l'azione ottimale.
//
// ARCHITETTURA A PRIORITÀ:
//   P1. HP bassa → segnala bisogno di pozione (gestita dal potionLoop)
//   P2. PULL_HOLD: mob raggiunti → attacca fermo
//   P3. Mob in melee range → attacca
//   P4. Mob visibili ma lontani → avvicinati (APPROACH)
//   P5. Nessun mob → cerca ruotando (SEARCH / PULL_GATHER)
//
// Nessuna azione nel motore dipende da `Thread.sleep` o timer arbitrari.
// Ogni azione ha una durata suggerita (usata dal farmLoop per la gesture),
// ma il ciclo successivo riparte subito dal callback dell'azione corrente.
//
// La rilevazione skill cooldown e pozione è delegata rispettivamente a
// GameState.skillsReady (analisi visiva) e BotState.autoPotionHpThreshold.
// ═══════════════════════════════════════════════════════════════════════════
object AIPlayerEngine {

    enum class AIState {
        SEARCH,       // nessun mob → cammina esplorando
        APPROACH,     // mob visibili, fuori melee → avvicinati
        ATTACK,       // mob in melee range → attacca fermo
        PULL_GATHER,  // pull mode, mob insufficienti → cammina + aggro
        PULL_HOLD     // pull mode, N mob raggiunti → fermo + combatti
    }

    sealed class AIAction {
        // Muovi joystick nella direzione data per durationMs millisecondi
        data class Move(val dirX: Float, val dirY: Float, val durationMs: Long = 380L) : AIAction()
        // Premi il bottone attacco
        object Attack : AIAction()
        // Usa la skill nello slot specificato
        data class UseSkill(val slot: Int) : AIAction()
        // Usa la pozione (HP bassa)
        object UsePotion : AIAction()
        // Attendi (nessuna azione proficua in questo momento)
        data class Wait(val ms: Long = 150L) : AIAction()
    }

    @Volatile var currentState = AIState.SEARCH
        private set

    // Stato interno per SEARCH rotation e anti-stuck
    private var searchAngleDeg     = 0f
    private var lastSearchRotateAt = 0L
    private var stuckCheckAt       = 0L
    private var stuckDeviateUntil  = 0L
    private var stuckDeviateSign   = 1.0
    private var lastMobDirX        = 0f
    private var lastMobDirY        = -1f

    fun reset() {
        currentState       = AIState.SEARCH
        searchAngleDeg     = 0f
        lastSearchRotateAt = 0L
        stuckCheckAt       = 0L
        stuckDeviateUntil  = 0L
        stuckDeviateSign   = 1.0
        lastMobDirX        = 0f
        lastMobDirY        = -1f
    }

    // ───────────────────────────────────────────────────────────────────────
    // decide() — cuore del motore. Legge GameState e restituisce l'azione
    // ottimale. Chiamato da farmLoop ogni ciclo (~400ms reale).
    // ───────────────────────────────────────────────────────────────────────
    fun decide(state: GameState): AIAction {
        val now     = System.currentTimeMillis()
        val hasMobs = state.mobCount > 0
        val pullOk  = BotState.pullMode && state.mobCount >= BotState.pullTargetCount

        // ── Transizione stato ────────────────────────────────────────────
        val newState = when {
            pullOk                                          -> AIState.PULL_HOLD
            BotState.pullMode && !pullOk                    -> AIState.PULL_GATHER
            state.nearestMobInMeleeRange                    -> AIState.ATTACK
            hasMobs                                         -> AIState.APPROACH
            else                                            -> AIState.SEARCH
        }
        if (newState != currentState) {
            currentState       = newState
            stuckCheckAt       = now + 5_000L
            stuckDeviateUntil  = 0L
        }

        // ── PRIORITÀ 1: HP critica → usa pozione ─────────────────────────
        // Il farmLoop non gestisce direttamente la pozione (c'è potionLoop),
        // ma segnaliamo con UsePotion per attivarlo immediatamente se serve.
        if (state.hpPercent < BotState.autoPotionHpThreshold &&
            BotState.potionRunning && BotState.potionSlots.isNotEmpty()) {
            return AIAction.UsePotion
        }

        // ── PRIORITÀ 2: Skill pronte con mob presenti ─────────────────────
        // Solo se ci sono mob visibili O siamo in PULL_HOLD.
        // Le skill vengono controllate in ordine 0→4; la prima pronta viene usata.
        val shouldFireSkill = hasMobs || currentState == AIState.PULL_HOLD ||
                              currentState == AIState.ATTACK
        if (shouldFireSkill && BotState.skillsRunning) {
            val ready = state.skillsReady
            for (i in ready.indices) {
                if (ready[i] && i < BotState.skillSlots.size) {
                    val canFire = if (BotState.pullMode) {
                        state.mobCount >= BotState.pullTargetCount
                    } else true
                    if (canFire) return AIAction.UseSkill(i)
                }
            }
        }

        // ── Decisione principale per stato corrente ───────────────────────
        return when (currentState) {

            // ── SEARCH: nessun mob → cammina ruotando ogni 2s ───────────
            AIState.SEARCH -> {
                if (now - lastSearchRotateAt > 2_000L) {
                    searchAngleDeg     = (searchAngleDeg + 60f) % 360f
                    lastSearchRotateAt = now
                }
                val rad = Math.toRadians(searchAngleDeg.toDouble())
                AIAction.Move(sin(rad).toFloat(), -cos(rad).toFloat(), 380L)
            }

            // ── APPROACH: mob visibili, fuori range → avvicinati ─────────
            AIState.APPROACH -> {
                var dirX = state.nearestMobDir.first
                var dirY = state.nearestMobDir.second

                // Anti-stuck: se la direzione mob non cambia dopo 5s →
                // il personaggio è bloccato contro un ostacolo. Devia ±45°.
                if (now > stuckCheckAt && stuckDeviateUntil < now) {
                    val dot = dirX * lastMobDirX + dirY * lastMobDirY
                    if (dot > 0.96f) {
                        stuckDeviateUntil = now + 1_500L
                        stuckDeviateSign  = if (stuckDeviateSign > 0) -1.0 else 1.0
                    }
                    stuckCheckAt = now + 5_000L
                }
                lastMobDirX = dirX; lastMobDirY = dirY

                if (now < stuckDeviateUntil) {
                    val angle = Math.toRadians(45.0 * stuckDeviateSign)
                    val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
                    val rx = dirX * c - dirY * s
                    val ry = dirX * s + dirY * c
                    val len = sqrt(rx * rx + ry * ry).coerceAtLeast(0.001f)
                    dirX = rx / len; dirY = ry / len
                }
                AIAction.Move(dirX, dirY, 380L)
            }

            // ── ATTACK: mob in melee → attacca senza muoversi ────────────
            AIState.ATTACK -> AIAction.Attack

            // ── PULL_GATHER: raccogli mob → cammina verso di loro ─────────
            AIState.PULL_GATHER -> {
                if (hasMobs) {
                    AIAction.Move(state.nearestMobDir.first, state.nearestMobDir.second, 380L)
                } else {
                    // Nessun mob visibile: cerca ruotando
                    if (now - lastSearchRotateAt > 2_000L) {
                        searchAngleDeg     = (searchAngleDeg + 60f) % 360f
                        lastSearchRotateAt = now
                    }
                    val rad = Math.toRadians(searchAngleDeg.toDouble())
                    AIAction.Move(sin(rad).toFloat(), -cos(rad).toFloat(), 380L)
                }
            }

            // ── PULL_HOLD: N mob raggiunti → fermo + combatti ─────────────
            AIState.PULL_HOLD -> AIAction.Attack
        }
    }
}
