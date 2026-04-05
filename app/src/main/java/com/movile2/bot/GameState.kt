package com.movile2.bot

// ═══════════════════════════════════════════════════════════════════════════
// GameState — snapshot completo dello stato del gioco derivato dall'analisi
// visiva dello screenshot. Prodotto da GameStateAnalyzer ogni 200ms e
// consumato da AIPlayerEngine per prendere decisioni senza timer fissi.
// ═══════════════════════════════════════════════════════════════════════════

data class MobInfo(
    val x: Float,           // centroide X in pixel (coord schermo)
    val y: Float,           // centroide Y in pixel (coord schermo)
    val rank: Int,          // 0=normale, 1-2=elite, 3-4=boss
    val clusterSize: Int    // grandezza cluster (proxy per distanza/dimensione)
)

data class GameState(
    // ── Stato personaggio ─────────────────────────────────────────────────
    val hpPercent: Float,                       // 0.0 (morto) → 1.0 (pieno)

    // ── Mob rilevati ──────────────────────────────────────────────────────
    val mobsVisible: List<MobInfo>,             // tutti i mob sullo schermo
    val mobCount: Int,                          // totale mob distinti
    val nearestMobDir: Pair<Float, Float>,      // vettore normalizzato verso il mob più vicino
    val nearestMobInMeleeRange: Boolean,        // mob nella zona corpo a corpo centrale

    // ── Skill ────────────────────────────────────────────────────────────
    val skillsReady: BooleanArray,              // true = skill NON in cooldown (pronta)

    // ── Metadati schermo ─────────────────────────────────────────────────
    val screenW: Int,
    val screenH: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        // Stato neutro usato prima del primo scan
        val EMPTY = GameState(
            hpPercent              = 1f,
            mobsVisible            = emptyList(),
            mobCount               = 0,
            nearestMobDir          = 0f to -1f,
            nearestMobInMeleeRange = false,
            skillsReady            = BooleanArray(5) { true },
            screenW                = 0,
            screenH                = 0
        )
    }
}
