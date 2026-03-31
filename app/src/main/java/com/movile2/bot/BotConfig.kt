package com.movile2.bot

import android.content.Context

data class BotConfig(
    val monsterName: String = "",
    val playerName: String = "",
    val maxKills: Int = 50,
    val sessionMinutes: Int = 0,

    val attackX: Float = 0f,
    val attackY: Float = 0f,
    val attackDelayMs: Long = 200L,

    val skill1X: Float = 0f,
    val skill1Y: Float = 0f,
    val skill1CooldownMs: Long = 3000L,

    val skill2X: Float = 0f,
    val skill2Y: Float = 0f,
    val skill2CooldownMs: Long = 5000L,

    val skill3X: Float = 0f,
    val skill3Y: Float = 0f,
    val skill3CooldownMs: Long = 8000L,

    val skill4X: Float = 0f,
    val skill4Y: Float = 0f,
    val skill4CooldownMs: Long = 12000L,

    val skill5X: Float = 0f,
    val skill5Y: Float = 0f,
    val skill5CooldownMs: Long = 15000L,

    // ── Slot pozione vita (fino a 7) ─────────────────────────────────────────
    // Ogni slot è configurabile dall'utente toccando lo schermo.
    // Quando uno slot si svuota (pixel detection → nessun rosso), il bot passa
    // al successivo. Quando tutti sono vuoti, trascina dall'inventario al slot 1.
    val potion1X: Float = 0f, val potion1Y: Float = 0f,
    val potion2X: Float = 0f, val potion2Y: Float = 0f,
    val potion3X: Float = 0f, val potion3Y: Float = 0f,
    val potion4X: Float = 0f, val potion4Y: Float = 0f,
    val potion5X: Float = 0f, val potion5Y: Float = 0f,
    val potion6X: Float = 0f, val potion6Y: Float = 0f,
    val potion7X: Float = 0f, val potion7Y: Float = 0f,

    // Posizione pozione rossa nell'inventario (per il refill automatico)
    val inventoryPotionX: Float = 0f,
    val inventoryPotionY: Float = 0f,

    val joystickX: Float = 0f,
    val joystickY: Float = 0f,
    val joystickRadius: Float = 0f,

    val cameraAreaX: Float = 0f,
    val cameraAreaY: Float = 0f,
    val cameraSwipeRange: Float = 0f,

    val hpBarX: Float = 0f,
    val hpBarY: Float = 0f,
    val hpBarFullWidth: Int = 0,
    val hpPotionThreshold: Float = 0.85f,

    val playerX: Float = 0f,
    val playerY: Float = 0f,
    val defenseRadiusPx: Int = 0,
) {
    // Restituisce la lista degli slot configurati (x,y) non zero, nell'ordine 1→7
    fun potionSlots(): List<Pair<Float, Float>> = listOfNotNull(
        if (potion1X > 0f && potion1Y > 0f) potion1X to potion1Y else null,
        if (potion2X > 0f && potion2Y > 0f) potion2X to potion2Y else null,
        if (potion3X > 0f && potion3Y > 0f) potion3X to potion3Y else null,
        if (potion4X > 0f && potion4Y > 0f) potion4X to potion4Y else null,
        if (potion5X > 0f && potion5Y > 0f) potion5X to potion5Y else null,
        if (potion6X > 0f && potion6Y > 0f) potion6X to potion6Y else null,
        if (potion7X > 0f && potion7Y > 0f) potion7X to potion7Y else null,
    )

    companion object {
        private const val PREFS = "bot_config"

        fun load(ctx: Context): BotConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return BotConfig(
                monsterName       = p.getString("monsterName", "") ?: "",
                playerName        = p.getString("playerName", "") ?: "",
                maxKills          = p.getInt("maxKills", 50),
                sessionMinutes    = p.getInt("sessionMinutes", 0),
                attackX           = p.getFloat("attackX", 0f),
                attackY           = p.getFloat("attackY", 0f),
                attackDelayMs     = p.getLong("attackDelayMs", 200L),
                skill1X           = p.getFloat("skill1X", 0f),
                skill1Y           = p.getFloat("skill1Y", 0f),
                skill1CooldownMs  = p.getLong("skill1CooldownMs", 3000L),
                skill2X           = p.getFloat("skill2X", 0f),
                skill2Y           = p.getFloat("skill2Y", 0f),
                skill2CooldownMs  = p.getLong("skill2CooldownMs", 5000L),
                skill3X           = p.getFloat("skill3X", 0f),
                skill3Y           = p.getFloat("skill3Y", 0f),
                skill3CooldownMs  = p.getLong("skill3CooldownMs", 8000L),
                skill4X           = p.getFloat("skill4X", 0f),
                skill4Y           = p.getFloat("skill4Y", 0f),
                skill4CooldownMs  = p.getLong("skill4CooldownMs", 12000L),
                skill5X           = p.getFloat("skill5X", 0f),
                skill5Y           = p.getFloat("skill5Y", 0f),
                skill5CooldownMs  = p.getLong("skill5CooldownMs", 15000L),
                potion1X          = p.getFloat("potion1X", 0f),
                potion1Y          = p.getFloat("potion1Y", 0f),
                potion2X          = p.getFloat("potion2X", 0f),
                potion2Y          = p.getFloat("potion2Y", 0f),
                potion3X          = p.getFloat("potion3X", 0f),
                potion3Y          = p.getFloat("potion3Y", 0f),
                potion4X          = p.getFloat("potion4X", 0f),
                potion4Y          = p.getFloat("potion4Y", 0f),
                potion5X          = p.getFloat("potion5X", 0f),
                potion5Y          = p.getFloat("potion5Y", 0f),
                potion6X          = p.getFloat("potion6X", 0f),
                potion6Y          = p.getFloat("potion6Y", 0f),
                potion7X          = p.getFloat("potion7X", 0f),
                potion7Y          = p.getFloat("potion7Y", 0f),
                inventoryPotionX  = p.getFloat("inventoryPotionX", 0f),
                inventoryPotionY  = p.getFloat("inventoryPotionY", 0f),
                joystickX         = p.getFloat("joystickX", 0f),
                joystickY         = p.getFloat("joystickY", 0f),
                joystickRadius    = p.getFloat("joystickRadius", 0f),
                cameraAreaX       = p.getFloat("cameraAreaX", 0f),
                cameraAreaY       = p.getFloat("cameraAreaY", 0f),
                cameraSwipeRange  = p.getFloat("cameraSwipeRange", 0f),
                hpBarX            = p.getFloat("hpBarX", 0f),
                hpBarY            = p.getFloat("hpBarY", 0f),
                hpBarFullWidth    = p.getInt("hpBarFullWidth", 0),
                hpPotionThreshold = p.getFloat("hpPotionThreshold", 0.85f),
                playerX           = p.getFloat("playerX", 0f),
                playerY           = p.getFloat("playerY", 0f),
                defenseRadiusPx   = p.getInt("defenseRadiusPx", 0),
            )
        }

        fun save(ctx: Context, cfg: BotConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("monsterName", cfg.monsterName)
                putString("playerName", cfg.playerName)
                putInt("maxKills", cfg.maxKills)
                putInt("sessionMinutes", cfg.sessionMinutes)
                putFloat("attackX", cfg.attackX)
                putFloat("attackY", cfg.attackY)
                putLong("attackDelayMs", cfg.attackDelayMs)
                putFloat("skill1X", cfg.skill1X)
                putFloat("skill1Y", cfg.skill1Y)
                putLong("skill1CooldownMs", cfg.skill1CooldownMs)
                putFloat("skill2X", cfg.skill2X)
                putFloat("skill2Y", cfg.skill2Y)
                putLong("skill2CooldownMs", cfg.skill2CooldownMs)
                putFloat("skill3X", cfg.skill3X)
                putFloat("skill3Y", cfg.skill3Y)
                putLong("skill3CooldownMs", cfg.skill3CooldownMs)
                putFloat("skill4X", cfg.skill4X)
                putFloat("skill4Y", cfg.skill4Y)
                putLong("skill4CooldownMs", cfg.skill4CooldownMs)
                putFloat("skill5X", cfg.skill5X)
                putFloat("skill5Y", cfg.skill5Y)
                putLong("skill5CooldownMs", cfg.skill5CooldownMs)
                putFloat("potion1X", cfg.potion1X)
                putFloat("potion1Y", cfg.potion1Y)
                putFloat("potion2X", cfg.potion2X)
                putFloat("potion2Y", cfg.potion2Y)
                putFloat("potion3X", cfg.potion3X)
                putFloat("potion3Y", cfg.potion3Y)
                putFloat("potion4X", cfg.potion4X)
                putFloat("potion4Y", cfg.potion4Y)
                putFloat("potion5X", cfg.potion5X)
                putFloat("potion5Y", cfg.potion5Y)
                putFloat("potion6X", cfg.potion6X)
                putFloat("potion6Y", cfg.potion6Y)
                putFloat("potion7X", cfg.potion7X)
                putFloat("potion7Y", cfg.potion7Y)
                putFloat("inventoryPotionX", cfg.inventoryPotionX)
                putFloat("inventoryPotionY", cfg.inventoryPotionY)
                putFloat("joystickX", cfg.joystickX)
                putFloat("joystickY", cfg.joystickY)
                putFloat("joystickRadius", cfg.joystickRadius)
                putFloat("cameraAreaX", cfg.cameraAreaX)
                putFloat("cameraAreaY", cfg.cameraAreaY)
                putFloat("cameraSwipeRange", cfg.cameraSwipeRange)
                putFloat("hpBarX", cfg.hpBarX)
                putFloat("hpBarY", cfg.hpBarY)
                putInt("hpBarFullWidth", cfg.hpBarFullWidth)
                putFloat("hpPotionThreshold", cfg.hpPotionThreshold)
                putFloat("playerX", cfg.playerX)
                putFloat("playerY", cfg.playerY)
                putInt("defenseRadiusPx", cfg.defenseRadiusPx)
                apply()
            }
        }
    }
}
