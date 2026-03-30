package com.movile2.bot

object BotState {
    @Volatile var killCount: Int = 0
    @Volatile var isRunning: Boolean = false
    var onUpdate: (() -> Unit)? = null
}
