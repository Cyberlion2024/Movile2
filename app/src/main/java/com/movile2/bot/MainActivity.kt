package com.movile2.bot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val PREFS = "bot_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etInterval      = findViewById<EditText>(R.id.etInterval)
        val etSkillInterval = findViewById<EditText>(R.id.etSkillInterval)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnOverlay       = findViewById<Button>(R.id.btnOverlay)
        val btnSave          = findViewById<Button>(R.id.btnSave)
        val btnStart         = findViewById<Button>(R.id.btnStart)
        val btnStop          = findViewById<Button>(R.id.btnStop)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Carica valori salvati
        val savedPot   = prefs.getLong("pot_interval_ms", 3000L)
        val savedSkill = prefs.getLong("skill_interval_ms", 5000L)
        etInterval.setText((savedPot / 1000).toString())
        etSkillInterval.setText((savedSkill / 1000).toString())
        BotState.potionIntervalMs = savedPot
        BotState.skillIntervalMs  = savedSkill

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Permesso overlay già attivo", Toast.LENGTH_SHORT).show()
            }
        }

        btnSave.setOnClickListener {
            val potSeconds   = etInterval.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 3L
            val skillSeconds = etSkillInterval.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 5L
            val potMs   = potSeconds * 1000L
            val skillMs = skillSeconds * 1000L
            prefs.edit()
                .putLong("pot_interval_ms", potMs)
                .putLong("skill_interval_ms", skillMs)
                .apply()
            BotState.potionIntervalMs = potMs
            BotState.skillIntervalMs  = skillMs
            Toast.makeText(this, "Salvato: pozze ogni ${potSeconds}s, abilità ogni ${skillSeconds}s", Toast.LENGTH_SHORT).show()
        }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Prima abilita il permesso overlay", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Pannello avviato", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Pannello fermato", Toast.LENGTH_SHORT).show()
        }
    }
}
