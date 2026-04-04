package com.movile2.bot

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════════════════
// BotDecisionEngine — AI leggera offline, zero dipendenze esterne.
//
// Implementa una macchina a stati finiti con logica contestuale.
// Il farmLoop chiama decide() ogni ciclo (~380ms) e riceve un BotAction
// che descrive cosa fare.
//
// STATI:
//   SEARCH      → nessun mob visibile → cammina ruotando direzione ogni 3s, NON attacca
//   APPROACH    → mob rilevati → cammina verso di loro E attacca
//   PULL_GATHER → pull mode, mob insufficienti → cammina + attacca per aggroare
//                 (in Mobile2 devi ATTACCARE i mob per farli aggroare, non basta avvicinarsi)
//   PULL_HOLD   → pull mode, N mob raggiunti → stai fermo e combatti con skill
//
// ANTI-STUCK:
//   Se in APPROACH per >5s e la direzione mob non cambia significativamente
//   (bot probabilmente bloccato contro un ostacolo), devia di ±45°.
// ═══════════════════════════════════════════════════════════════════════════
object BotDecisionEngine {

    enum class BotAIState { SEARCH, APPROACH, PULL_GATHER, PULL_HOLD }

    data class BotAction(
        val shouldWalk: Boolean,
        val dirX: Float,
        val dirY: Float,
        val stateLabel: String
    )

    @Volatile var currentState = BotAIState.SEARCH
        private set

    // Stato interno — usato solo da decide(), non esposto al resto del bot
    private var stateEnteredAt     = System.currentTimeMillis()
    private var searchAngleDeg     = 0f      // Angolo corrente di ricerca (°)
    private var lastSearchRotateAt = 0L      // Quando abbiamo ruotato l'ultima volta
    private var lastMobDirX        = 0f
    private var lastMobDirY        = -1f
    private var stuckCheckAt       = 0L
    private var stuckDeviateUntil  = 0L
    private var stuckDeviateSign   = 1.0     // +1 o -1 per deviare a sinistra/destra

    fun reset() {
        currentState       = BotAIState.SEARCH
        stateEnteredAt     = System.currentTimeMillis()
        searchAngleDeg     = 0f
        lastSearchRotateAt = 0L
        lastMobDirX        = 0f
        lastMobDirY        = -1f
        stuckCheckAt       = 0L
        stuckDeviateUntil  = 0L
        stuckDeviateSign   = 1.0
    }

    fun decide(): BotAction {
        val now     = System.currentTimeMillis()
        val mobs    = BotState.detectedMobCount
        val hasMobs = mobs > 0
        val pullOk  = BotState.pullMode && mobs >= BotState.pullTargetCount

        // ── Transizione di stato ──────────────────────────────────────────
        val newState = when {
            pullOk                           -> BotAIState.PULL_HOLD
            BotState.pullMode && !pullOk     -> BotAIState.PULL_GATHER
            hasMobs                          -> BotAIState.APPROACH
            else                             -> BotAIState.SEARCH
        }
        if (newState != currentState) {
            currentState   = newState
            stateEnteredAt = now
            stuckCheckAt   = now + 5000L
            // Reset stuck quando cambiamo stato
            stuckDeviateUntil = 0L
        }

        // ── Logica per stato ─────────────────────────────────────────────
        return when (currentState) {

            BotAIState.SEARCH -> {
                // Ruota la direzione ogni 2s per scandire l'area in modo
                // più dinamico. Con 60° per passo, 6 passi = 360° = ciclo completo
                // ogni 12s. Ridotto da 3s a 2s: il bot cambia prima direzione
                // invece di sembrare bloccato in linea retta.
                if (now - lastSearchRotateAt > 2_000L) {
                    searchAngleDeg     = (searchAngleDeg + 60f) % 360f
                    lastSearchRotateAt = now
                }
                val rad = Math.toRadians(searchAngleDeg.toDouble())
                val dx  = sin(rad).toFloat()
                val dy  = -cos(rad).toFloat()
                BotAction(true, dx, dy, "CERCA(${searchAngleDeg.toInt()}°)")
            }

            BotAIState.APPROACH -> {
                var dirX = BotState.mobDirX
                var dirY = BotState.mobDirY

                // ── Anti-stuck: dopo 5s in APPROACH, controlla se la
                //    direzione mob è rimasta praticamente identica.
                //    In quel caso il personaggio è probabilmente bloccato.
                if (now > stuckCheckAt && stuckDeviateUntil < now) {
                    val dot = dirX * lastMobDirX + dirY * lastMobDirY
                    if (dot > 0.96f) {
                        // Devia di 45° per aggirare l'ostacolo
                        stuckDeviateUntil = now + 1_500L
                        stuckDeviateSign  = if (stuckDeviateSign > 0) -1.0 else 1.0
                    }
                    stuckCheckAt = now + 5_000L
                }
                lastMobDirX = dirX; lastMobDirY = dirY

                if (now < stuckDeviateUntil) {
                    // Rotazione ±45° attorno alla direzione originale
                    val angle = Math.toRadians(45.0 * stuckDeviateSign)
                    val cos45 = cos(angle).toFloat(); val sin45 = sin(angle).toFloat()
                    val rx = dirX * cos45 - dirY * sin45
                    val ry = dirX * sin45 + dirY * cos45
                    val len = sqrt(rx * rx + ry * ry).coerceAtLeast(0.001f)
                    dirX = rx / len; dirY = ry / len
                    BotAction(true, dirX, dirY, "AVVICINA(devia${if (stuckDeviateSign > 0) "+" else "-"})")
                } else {
                    BotAction(true, dirX, dirY, "AVVICINA")
                }
            }

            BotAIState.PULL_GATHER -> {
                // Cammina verso i mob (se visibili) o con angolo di ricerca
                if (hasMobs) {
                    BotAction(true, BotState.mobDirX, BotState.mobDirY, "PULL-RACCOGLI")
                } else {
                    if (now - lastSearchRotateAt > 2_000L) {
                        searchAngleDeg     = (searchAngleDeg + 60f) % 360f
                        lastSearchRotateAt = now
                    }
                    val rad = Math.toRadians(searchAngleDeg.toDouble())
                    BotAction(true, sin(rad).toFloat(), -cos(rad).toFloat(), "PULL-CERCA")
                }
            }

            BotAIState.PULL_HOLD -> {
                // Abbastanza mob raggruppati: stai fermo e combatti.
                // Le skill scattano da sole (skillLoops controllano pullTargetCount).
                BotAction(false, 0f, 0f, "PULL-HOLD(${mobs}mob)")
            }
        }
    }
}
