package com.movile2.bot

import android.content.Context

data class BotConfig(
    val monsterName: String = "",
    val playerName: String = "",
    val maxKills: Int = 50,
    val sessionMinutes: Int = 0,

    // 0f = usa coordinate automatiche calcolate dalla risoluzione schermo
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

    val potionX: Float = 0f,
    val potionY: Float = 0f,
    val maxPotionsInSlot: Int = 10,
    val backupPotionX: Float = 0f,
    val backupPotionY: Float = 0f,

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
                potionX           = p.getFloat("potionX", 0f),
                potionY           = p.getFloat("potionY", 0f),
                maxPotionsInSlot  = p.getInt("maxPotionsInSlot", 10),
                backupPotionX     = p.getFloat("backupPotionX", 0f),
                backupPotionY     = p.getFloat("backupPotionY", 0f),
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
                putFloat("potionX", cfg.potionX)
                putFloat("potionY", cfg.potionY)
                putInt("maxPotionsInSlot", cfg.maxPotionsInSlot)
                putFloat("backupPotionX", cfg.backupPotionX)
                putFloat("backupPotionY", cfg.backupPotionY)
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
