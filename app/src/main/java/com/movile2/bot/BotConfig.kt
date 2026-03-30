package com.movile2.bot

import android.content.Context

data class BotConfig(
    val monsterName: String = "",
    val maxKills: Int = 50,

    val attackX: Float = 950f,
    val attackY: Float = 700f,
    val attackDelayMs: Long = 200L,

    val skill1X: Float = 850f,
    val skill1Y: Float = 650f,
    val skill1CooldownMs: Long = 3000L,

    val skill2X: Float = 780f,
    val skill2Y: Float = 720f,
    val skill2CooldownMs: Long = 5000L,

    val potionX: Float = 0f,
    val potionY: Float = 0f,
    val maxPotionsInSlot: Int = 10,
    val backupPotionX: Float = 0f,
    val backupPotionY: Float = 0f,

    val searchLeft: Float = 150f,
    val searchTop: Float = 200f,
    val searchRight: Float = 930f,
    val searchBottom: Float = 850f,

    val joystickX: Float = 0f,
    val joystickY: Float = 0f,
    val joystickRadius: Float = 55f,
) {
    companion object {
        private const val PREFS = "bot_config"

        fun load(ctx: Context): BotConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return BotConfig(
                monsterName      = p.getString("monsterName", "") ?: "",
                maxKills         = p.getInt("maxKills", 50),
                attackX          = p.getFloat("attackX", 950f),
                attackY          = p.getFloat("attackY", 700f),
                attackDelayMs    = p.getLong("attackDelayMs", 200L),
                skill1X          = p.getFloat("skill1X", 850f),
                skill1Y          = p.getFloat("skill1Y", 650f),
                skill1CooldownMs = p.getLong("skill1CooldownMs", 3000L),
                skill2X          = p.getFloat("skill2X", 780f),
                skill2Y          = p.getFloat("skill2Y", 720f),
                skill2CooldownMs = p.getLong("skill2CooldownMs", 5000L),
                potionX          = p.getFloat("potionX", 0f),
                potionY          = p.getFloat("potionY", 0f),
                maxPotionsInSlot = p.getInt("maxPotionsInSlot", 10),
                backupPotionX    = p.getFloat("backupPotionX", 0f),
                backupPotionY    = p.getFloat("backupPotionY", 0f),
                searchLeft       = p.getFloat("searchLeft", 150f),
                searchTop        = p.getFloat("searchTop", 200f),
                searchRight      = p.getFloat("searchRight", 930f),
                searchBottom     = p.getFloat("searchBottom", 850f),
                joystickX        = p.getFloat("joystickX", 0f),
                joystickY        = p.getFloat("joystickY", 0f),
                joystickRadius   = p.getFloat("joystickRadius", 55f),
            )
        }

        fun save(ctx: Context, cfg: BotConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("monsterName", cfg.monsterName)
                putInt("maxKills", cfg.maxKills)
                putFloat("attackX", cfg.attackX)
                putFloat("attackY", cfg.attackY)
                putLong("attackDelayMs", cfg.attackDelayMs)
                putFloat("skill1X", cfg.skill1X)
                putFloat("skill1Y", cfg.skill1Y)
                putLong("skill1CooldownMs", cfg.skill1CooldownMs)
                putFloat("skill2X", cfg.skill2X)
                putFloat("skill2Y", cfg.skill2Y)
                putLong("skill2CooldownMs", cfg.skill2CooldownMs)
                putFloat("potionX", cfg.potionX)
                putFloat("potionY", cfg.potionY)
                putInt("maxPotionsInSlot", cfg.maxPotionsInSlot)
                putFloat("backupPotionX", cfg.backupPotionX)
                putFloat("backupPotionY", cfg.backupPotionY)
                putFloat("searchLeft", cfg.searchLeft)
                putFloat("searchTop", cfg.searchTop)
                putFloat("searchRight", cfg.searchRight)
                putFloat("searchBottom", cfg.searchBottom)
                putFloat("joystickX", cfg.joystickX)
                putFloat("joystickY", cfg.joystickY)
                putFloat("joystickRadius", cfg.joystickRadius)
                apply()
            }
        }
    }
}
