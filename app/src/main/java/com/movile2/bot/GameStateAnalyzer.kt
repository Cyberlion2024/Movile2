package com.movile2.bot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════════════════
// GameStateAnalyzer — analisi visiva completa dello schermo in un'unica
// passata. Sostituisce il mob scanner pixel-based con un sistema che legge:
//
//   1. HP del personaggio (barra rossa in alto a sinistra)
//   2. Mob nemici: nomi rossi (rank 0-2) e gialli/oro (rank 3-4 elite/boss)
//      confermati dall'analisi di UmobNamer nel libUE4.so
//   3. Mob in melee range: nome rosso nella zona centrale dello schermo
//   4. Skill cooldown: analisi colore medio del bottone (grigio = cooldown)
//
// Tutti i calcoli girano on-device senza API esterne (puro pixel analysis).
// Lo screenshot viene fatto da BotAccessibilityService ogni 200ms e passato
// qui come Bitmap ARGB_8888.
// ═══════════════════════════════════════════════════════════════════════════
object GameStateAnalyzer {

    // ───────────────────────────────────────────────────────────────────────
    // Analisi unica: una sola passata produce GameState completo.
    // Chiamato da gameStateScanner nel service con il bitmap dello screenshot.
    // ───────────────────────────────────────────────────────────────────────
    fun analyze(bmp: Bitmap, skillSlots: List<Pair<Float, Float>>): GameState {
        val w = bmp.width; val h = bmp.height

        val hpPercent = detectHpPercent(bmp, w, h)
        val (mobs, nearestDir) = detectMobs(bmp, w, h)
        val inMelee = isMobInMeleeRange(mobs, w, h)
        val skillsReady = detectSkillsReady(bmp, skillSlots)

        return GameState(
            hpPercent              = hpPercent,
            mobsVisible            = mobs,
            mobCount               = mobs.size,
            nearestMobDir          = nearestDir,
            nearestMobInMeleeRange = inMelee,
            skillsReady            = skillsReady,
            screenW                = w,
            screenH                = h
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. HP DEL PERSONAGGIO
    //
    // La barra HP di Mobile2 (UE4 UMG) è in alto a sinistra.
    // Dal libUE4.so: CFhp/TFhp = rapporto HP. La barra è rossa (R>200,G<60,B<60).
    // Strategia: scansione orizzontale colonna per colonna nella zona HP.
    // L'ultima colonna con densità di pixel rossi > 30% = bordo destro HP.
    // hpPercent = (bordo_destro - inizio_barra) / larghezza_barra
    //
    // Zona calibrata su Mobile2 Global 2.23 (screenshot 2400x1080):
    //   X: 5%-38% schermo (barra può estendersi fino al 38% a HP pieno)
    //   Y: 2.5%-5.5% schermo (banda sottile in alto)
    // ═══════════════════════════════════════════════════════════════════════
    fun detectHpPercent(bmp: Bitmap, w: Int, h: Int): Float {
        val x0 = (w * 0.05f).toInt()
        val x1 = (w * 0.38f).toInt()
        val y0 = (h * 0.025f).toInt()
        val y1 = (h * 0.055f).toInt()
        val step = 2

        var lastRedX = x0
        var hasAnyRed = false
        val colHeight = ((y1 - y0) / step).coerceAtLeast(1)
        val minRedDensity = 0.30f  // almeno 30% pixel rossi in colonna

        var sx = x0
        while (sx < x1) {
            var redCount = 0
            var sy = y0
            while (sy < y1) {
                val p = bmp.getPixel(sx.coerceAtMost(w - 1), sy.coerceAtMost(h - 1))
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                // Rosso HP: R forte, G e B bassi
                if (r > 180 && g < 80 && b < 80) redCount++
                sy += step
            }
            if (redCount.toFloat() / colHeight >= minRedDensity) {
                lastRedX = sx
                hasAnyRed = true
            }
            sx += step
        }

        if (!hasAnyRed) return 1f  // default pieno se non rilevato
        return ((lastRedX - x0).toFloat() / (x1 - x0).toFloat()).coerceIn(0.01f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. RILEVAMENTO MOB
    //
    // Basato sull'analisi di UmobNamer dal libUE4.so (confermato):
    //   medusman=true + ismob=true → nome ROSSO  (mob nemico)
    //   rank >= 3                  → nome GIALLO  (elite / boss)
    //   group=true                 → nome VERDE   (gruppo, ignorato)
    //   isLocal=true               → nome BIANCO  (noi, ignorato)
    //
    // Soglie colore allargate rispetto alla v17 per coprire varianti
    // cromatiche di diversi rank e lighting conditions:
    //   Rosso mob: R>150, G<130, B<140, R-G>40
    //   Giallo elite/boss: R>190, G>150, G<230, B<80, R-B>120
    //
    // Griglia 30×30px con BFS 8-direzionale. Cluster < 2 celle = rumore.
    // ═══════════════════════════════════════════════════════════════════════
    fun detectMobs(bmp: Bitmap, w: Int, h: Int): Pair<List<MobInfo>, Pair<Float, Float>> {
        // Zona espansa: prende mob anche nella metà bassa dello schermo
        val x0 = (w * 0.10f).toInt(); val x1 = (w * 0.90f).toInt()
        val y0 = (h * 0.05f).toInt(); val y1 = (h * 0.80f).toInt()
        val cellPx = 30
        val step = 3

        val cols = (x1 - x0) / cellPx
        val rows = (y1 - y0) / cellPx
        if (cols <= 0 || rows <= 0) return emptyList<MobInfo>() to (0f to -1f)

        val hotRed    = Array(rows) { BooleanArray(cols) }
        val hotYellow = Array(rows) { BooleanArray(cols) }

        // Classificazione pixel per cella
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx0 = x0 + col * cellPx
                val cy0 = y0 + row * cellPx
                var redHits = 0; var yellowHits = 0
                var sx = cx0
                while (sx < (cx0 + cellPx).coerceAtMost(x1)) {
                    var sy = cy0
                    while (sy < (cy0 + cellPx).coerceAtMost(y1)) {
                        val p = bmp.getPixel(sx.coerceAtMost(w - 1), sy.coerceAtMost(h - 1))
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                        // Mob nemico — rosso (UmobNamer.medusman=true)
                        if (r > 150 && g < 130 && b < 140 && r - g > 40) redHits++
                        // Elite/Boss — giallo/oro (rank >= 3)
                        if (r > 190 && g in 150..229 && b < 80 && r - b > 120) yellowHits++
                        sy += step
                    }
                    sx += step
                }
                hotRed[row][col]    = redHits >= 2
                hotYellow[row][col] = yellowHits >= 2
            }
        }

        // BFS 8-direzionale per estrarre cluster (mob distinti)
        fun extractClusters(hot: Array<BooleanArray>, rank: Int): List<MobInfo> {
            val visited = Array(rows) { BooleanArray(cols) }
            val result  = mutableListOf<MobInfo>()
            val dr = intArrayOf(-1, 1, 0, 0, -1, -1,  1,  1)
            val dc = intArrayOf( 0, 0,-1, 1, -1,  1, -1,  1)
            for (sr in 0 until rows) {
                for (sc in 0 until cols) {
                    if (!hot[sr][sc] || visited[sr][sc]) continue
                    val queue = ArrayDeque<Pair<Int, Int>>()
                    queue.add(sr to sc)
                    visited[sr][sc] = true
                    var sumR = 0; var sumC = 0; var size = 0
                    while (queue.isNotEmpty()) {
                        val (r, c) = queue.removeFirst()
                        sumR += r; sumC += c; size++
                        for (d in 0 until 8) {
                            val nr = r + dr[d]; val nc = c + dc[d]
                            if (nr in 0 until rows && nc in 0 until cols &&
                                hot[nr][nc] && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                    if (size >= 2) {
                        val cx = x0 + (sumC.toFloat() / size) * cellPx + cellPx / 2f
                        val cy = y0 + (sumR.toFloat() / size) * cellPx + cellPx / 2f
                        result.add(MobInfo(cx, cy, rank, size))
                    }
                }
            }
            return result
        }

        val allMobs = extractClusters(hotRed, 0) + extractClusters(hotYellow, 3)

        // Direzione normalizzata verso il mob più vicino dal centro del personaggio
        val charX = w * 0.50f
        val charY = h * 0.60f
        val nearest = allMobs.minByOrNull { m ->
            val dx = m.x - charX; val dy = m.y - charY; dx * dx + dy * dy
        }
        val dir = if (nearest != null) {
            val dx = nearest.x - charX; val dy = nearest.y - charY
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            (dx / len) to (dy / len)
        } else 0f to -1f

        return allMobs to dir
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. MOB IN MELEE RANGE
    //
    // Se un nome rosso è nella zona CENTRALE dello schermo (±20% dal centro),
    // il mob è già a distanza di attacco corpo a corpo. Il personaggio non
    // deve avvicinarsi — può attaccare direttamente.
    //
    // Zona melee calibrata su Mobile2: centro-schermo ±20% x, 35-75% y
    // (la testa del mob è sopra al suo body, il personaggio è a 60% y)
    // ═══════════════════════════════════════════════════════════════════════
    fun isMobInMeleeRange(mobs: List<MobInfo>, w: Int, h: Int): Boolean {
        val meleeX0 = w * 0.28f; val meleeX1 = w * 0.72f
        val meleeY0 = h * 0.35f; val meleeY1 = h * 0.75f
        return mobs.any { m -> m.x in meleeX0..meleeX1 && m.y in meleeY0..meleeY1 }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. RILEVAMENTO SKILL IN COOLDOWN
    //
    // UE4 UMG visualizza il cooldown come overlay scuro semi-trasparente
    // sopra l'icona della skill, con un arco bianco che si riduce in senso
    // orario. Il colore medio dell'icona in cooldown diventa grigio/scuro.
    //
    // Algoritmo:
    //   - Campiona un'area di raggio 18px intorno al centro del bottone
    //   - Calcola media R, G, B
    //   - Se R≈G≈B (varianza < 25 per canale) → grigio = cooldown
    //   - Se avg < 90 → troppo scuro = cooldown
    //   - Altrimenti → pronto (colori saturi)
    //
    // Usa posizioni configurate dall'utente (BotState.skillSlots).
    // ═══════════════════════════════════════════════════════════════════════
    fun detectSkillsReady(bmp: Bitmap, skillSlots: List<Pair<Float, Float>>): BooleanArray {
        val ready = BooleanArray(skillSlots.size) { true }
        val sampleRadius = 18

        for ((idx, slot) in skillSlots.withIndex()) {
            val cx = slot.first.toInt()
            val cy = slot.second.toInt()
            if (cx <= 0 || cy <= 0 || cx >= bmp.width || cy >= bmp.height) continue

            var sumR = 0L; var sumG = 0L; var sumB = 0L; var n = 0

            var sx = (cx - sampleRadius).coerceAtLeast(0)
            while (sx <= (cx + sampleRadius).coerceAtMost(bmp.width - 1)) {
                var sy = (cy - sampleRadius).coerceAtLeast(0)
                while (sy <= (cy + sampleRadius).coerceAtMost(bmp.height - 1)) {
                    val p = bmp.getPixel(sx, sy)
                    sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p)
                    n++
                    sy += 2
                }
                sx += 2
            }
            if (n == 0) continue

            val avgR = (sumR / n).toInt()
            val avgG = (sumG / n).toInt()
            val avgB = (sumB / n).toInt()
            val avg  = (avgR + avgG + avgB) / 3

            // Grigio = tutti i canali vicini alla media
            val isGray = abs(avgR - avg) < 25 && abs(avgG - avg) < 25 && abs(avgB - avg) < 25
            // Scuro = probabilmente overlay cooldown
            val isDark = avg < 90

            ready[idx] = !(isGray || isDark)
        }
        return ready
    }
}
