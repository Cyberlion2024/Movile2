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

        val etInterval       = findViewById<EditText>(R.id.etInterval)
        val etCharNames      = findViewById<EditText>(R.id.etCharNames)
        val etSkill1         = findViewById<EditText>(R.id.etSkill1)
        val etSkill2         = findViewById<EditText>(R.id.etSkill2)
        val etSkill3         = findViewById<EditText>(R.id.etSkill3)
        val etSkill4         = findViewById<EditText>(R.id.etSkill4)
        val etSkill5         = findViewById<EditText>(R.id.etSkill5)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnOverlay       = findViewById<Button>(R.id.btnOverlay)
        val btnSave          = findViewById<Button>(R.id.btnSave)
        val btnStart         = findViewById<Button>(R.id.btnStart)
        val btnStop          = findViewById<Button>(R.id.btnStop)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Carica intervallo pozione salvato
        val savedPot = prefs.getLong("pot_interval_ms", 3000L)
        etInterval.setText((savedPot / 1000).toString())
        BotState.potionIntervalMs = savedPot

        // Carica nomi personaggio salvati (default: bashy,Anyasama)
        val savedNames = prefs.getString("char_names", "bashy,Anyasama") ?: "bashy,Anyasama"
        etCharNames.setText(savedNames)
        applyCharNames(savedNames)

        val skillEts = listOf(etSkill1, etSkill2, etSkill3, etSkill4, etSkill5)
        skillEts.forEachIndexed { idx, et ->
            val saved = prefs.getLong("skill${idx + 1}_interval_ms", 5000L)
            et.setText((saved / 1000).toString())
            if (idx < BotState.skillIntervals.size) BotState.skillIntervals[idx] = saved
            else BotState.skillIntervals.add(saved)
        }

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
            val potSeconds = etInterval.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 3L
            val potMs = potSeconds * 1000L
            val editor = prefs.edit().putLong("pot_interval_ms", potMs)
            BotState.potionIntervalMs = potMs

            // Salva nomi personaggio
            val namesRaw = etCharNames.text.toString().trim()
            editor.putString("char_names", namesRaw)
            applyCharNames(namesRaw)

            val summary = StringBuilder("Salvato: pozze ${potSeconds}s")
            skillEts.forEachIndexed { idx, et ->
                val secs = et.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 5L
                val ms = secs * 1000L
                editor.putLong("skill${idx + 1}_interval_ms", ms)
                if (idx < BotState.skillIntervals.size) BotState.skillIntervals[idx] = ms
                else BotState.skillIntervals.add(ms)
                summary.append(", skill${idx + 1} ${secs}s")
            }
            editor.apply()
            Toast.makeText(this, summary.toString(), Toast.LENGTH_LONG).show()
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

    // Parsa la stringa "bashy,Anyasama" e aggiorna BotState.characterNames
    private fun applyCharNames(raw: String) {
        val parsed = raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        BotState.characterNames.clear()
        if (parsed.isEmpty()) {
            BotState.characterNames.add("bashy")
            BotState.characterNames.add("anyasama")
        } else {
            BotState.characterNames.addAll(parsed)
        }
    }
}
