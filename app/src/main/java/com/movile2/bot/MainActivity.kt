package com.movile2.bot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(BotConfig.PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etMonsterName = findViewById<EditText>(R.id.etMonsterName)
        val etMaxKills = findViewById<EditText>(R.id.etMaxKills)
        val cbLearnPerimeter = findViewById<CheckBox>(R.id.cbLearnPerimeter)

        etMonsterName.setText(prefs.getString(BotConfig.KEY_MONSTER_NAME, ""))
        etMaxKills.setText(prefs.getInt(BotConfig.KEY_MAX_KILLS, 50).toString())
        cbLearnPerimeter.isChecked = prefs.getBoolean(BotConfig.KEY_LEARN_PERIMETER, true)

        findViewById<Button>(R.id.btnSaveConfig).setOnClickListener {
            val maxKills = etMaxKills.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 50
            prefs.edit()
                .putString(BotConfig.KEY_MONSTER_NAME, etMonsterName.text.toString().trim())
                .putInt(BotConfig.KEY_MAX_KILLS, maxKills)
                .putBoolean(BotConfig.KEY_LEARN_PERIMETER, cbLearnPerimeter.isChecked)
                .apply()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }
    }
}

object BotConfig {
    const val PREFS_NAME = "bot_config"
    const val KEY_MONSTER_NAME = "monster_name"
    const val KEY_MAX_KILLS = "max_kills"
    const val KEY_LEARN_PERIMETER = "learn_perimeter"
}
