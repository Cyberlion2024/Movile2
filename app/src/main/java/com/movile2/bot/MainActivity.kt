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

        val etInterval = findViewById<EditText>(R.id.etInterval)
        val btnAccessibility  = findViewById<Button>(R.id.btnAccessibility)
        val btnOverlay        = findViewById<Button>(R.id.btnOverlay)
        val btnSave           = findViewById<Button>(R.id.btnSave)
        val btnStart          = findViewById<Button>(R.id.btnStart)
        val btnStop           = findViewById<Button>(R.id.btnStop)

        // Carica intervallo salvato
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("pot_interval_ms", 3000L)
        etInterval.setText((saved / 1000).toString())
        BotState.potionIntervalMs = saved

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
            val seconds = etInterval.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 3L
            val ms = seconds * 1000L
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("pot_interval_ms", ms).apply()
            BotState.potionIntervalMs = ms
            Toast.makeText(this, "Salvato: ogni ${seconds}s", Toast.LENGTH_SHORT).show()
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
