package com.movile2.bot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var cfg: BotConfig

    private lateinit var etMonsterName: EditText
    private lateinit var etMaxKills: EditText
    private lateinit var etAttackDelay: EditText
    private lateinit var tvAttackCoord: TextView
    private lateinit var tvSkill1Coord: TextView
    private lateinit var etSkill1Cd: EditText
    private lateinit var tvSkill2Coord: TextView
    private lateinit var etSkill2Cd: EditText
    private lateinit var tvPotionCoord: TextView
    private lateinit var etMaxPotions: EditText
    private lateinit var tvBackupPotionCoord: TextView
    private lateinit var tvJoystickCoord: TextView
    private lateinit var etJoystickRadius: EditText
    private lateinit var tvCameraCoord: TextView
    private lateinit var etCameraSwipeRange: EditText

    private var pendingKey = ""

    companion object {
        private const val REQ = 101
        private const val K_ATTACK   = "attack"
        private const val K_SK1      = "sk1"
        private const val K_SK2      = "sk2"
        private const val K_POTION   = "potion"
        private const val K_BACKUP   = "backup"
        private const val K_JOYSTICK = "joystick"
        private const val K_CAMERA   = "camera"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cfg = BotConfig.load(this)
        bind()
        populate()
        hooks()
    }

    private fun bind() {
        etMonsterName       = findViewById(R.id.etMonsterName)
        etMaxKills          = findViewById(R.id.etMaxKills)
        etAttackDelay       = findViewById(R.id.etAttackDelay)
        tvAttackCoord       = findViewById(R.id.tvAttackCoord)
        tvSkill1Coord       = findViewById(R.id.tvSkill1Coord)
        etSkill1Cd          = findViewById(R.id.etSkill1Cd)
        tvSkill2Coord       = findViewById(R.id.tvSkill2Coord)
        etSkill2Cd          = findViewById(R.id.etSkill2Cd)
        tvPotionCoord       = findViewById(R.id.tvPotionCoord)
        etMaxPotions        = findViewById(R.id.etMaxPotions)
        tvBackupPotionCoord = findViewById(R.id.tvBackupPotionCoord)
        tvJoystickCoord     = findViewById(R.id.tvJoystickCoord)
        etJoystickRadius    = findViewById(R.id.etJoystickRadius)
        tvCameraCoord       = findViewById(R.id.tvCameraCoord)
        etCameraSwipeRange  = findViewById(R.id.etCameraSwipeRange)
    }

    private fun populate() {
        etMonsterName.setText(cfg.monsterName)
        etMaxKills.setText(cfg.maxKills.toString())
        etAttackDelay.setText(cfg.attackDelayMs.toString())
        tvAttackCoord.text       = xy(cfg.attackX, cfg.attackY)
        tvSkill1Coord.text       = xy(cfg.skill1X, cfg.skill1Y)
        etSkill1Cd.setText((cfg.skill1CooldownMs / 1000).toString())
        tvSkill2Coord.text       = xy(cfg.skill2X, cfg.skill2Y)
        etSkill2Cd.setText((cfg.skill2CooldownMs / 1000).toString())
        tvPotionCoord.text       = xy(cfg.potionX, cfg.potionY)
        etMaxPotions.setText(cfg.maxPotionsInSlot.toString())
        tvBackupPotionCoord.text = xy(cfg.backupPotionX, cfg.backupPotionY)
        tvJoystickCoord.text     = if (cfg.joystickX > 0f) xy(cfg.joystickX, cfg.joystickY) else "non impostato"
        etJoystickRadius.setText(cfg.joystickRadius.toInt().toString())
        tvCameraCoord.text       = if (cfg.cameraAreaX > 0f) xy(cfg.cameraAreaX, cfg.cameraAreaY) else "non impostato"
        etCameraSwipeRange.setText(cfg.cameraSwipeRange.toInt().toString())
    }

    private fun hooks() {
        fun pick(key: String, label: String, tv: TextView) {
            pendingKey = key
            startActivityForResult(
                Intent(this, CoordinatePickerActivity::class.java)
                    .putExtra(CoordinatePickerActivity.EXTRA_LABEL, label), REQ
            )
            tv.text = "tocca lo schermo…"
        }

        findViewById<Button>(R.id.btnPickAttack)   .setOnClickListener { pick(K_ATTACK,   "Bottone Attacco",         tvAttackCoord) }
        findViewById<Button>(R.id.btnPickSkill1)   .setOnClickListener { pick(K_SK1,      "Abilità 1",               tvSkill1Coord) }
        findViewById<Button>(R.id.btnPickSkill2)   .setOnClickListener { pick(K_SK2,      "Abilità 2",               tvSkill2Coord) }
        findViewById<Button>(R.id.btnPickPotion)   .setOnClickListener { pick(K_POTION,   "Slot Pozione",            tvPotionCoord) }
        findViewById<Button>(R.id.btnPickBackup)   .setOnClickListener { pick(K_BACKUP,   "Pozione di Riserva",      tvBackupPotionCoord) }
        findViewById<Button>(R.id.btnPickJoystick) .setOnClickListener { pick(K_JOYSTICK, "Centro Joystick",         tvJoystickCoord) }
        findViewById<Button>(R.id.btnPickCamera)   .setOnClickListener { pick(K_CAMERA,   "Punto Centrale Visuale",  tvCameraCoord) }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (!Settings.canDrawOverlays(this))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else
                toast("Permesso overlay già attivo ✓")
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            save()
            val i = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ || resultCode != Activity.RESULT_OK || data == null) return
        val x = data.getFloatExtra(CoordinatePickerActivity.RESULT_X, 0f)
        val y = data.getFloatExtra(CoordinatePickerActivity.RESULT_Y, 0f)
        cfg = when (pendingKey) {
            K_ATTACK   -> cfg.copy(attackX = x, attackY = y).also       { tvAttackCoord.text = xy(x, y) }
            K_SK1      -> cfg.copy(skill1X = x, skill1Y = y).also       { tvSkill1Coord.text = xy(x, y) }
            K_SK2      -> cfg.copy(skill2X = x, skill2Y = y).also       { tvSkill2Coord.text = xy(x, y) }
            K_POTION   -> cfg.copy(potionX = x, potionY = y).also       { tvPotionCoord.text = xy(x, y) }
            K_BACKUP   -> cfg.copy(backupPotionX = x, backupPotionY = y).also { tvBackupPotionCoord.text = xy(x, y) }
            K_JOYSTICK -> cfg.copy(joystickX = x, joystickY = y).also   { tvJoystickCoord.text = xy(x, y) }
            K_CAMERA   -> cfg.copy(cameraAreaX = x, cameraAreaY = y).also { tvCameraCoord.text = xy(x, y) }
            else       -> cfg
        }
    }

    private fun save() {
        cfg = cfg.copy(
            monsterName      = etMonsterName.text.toString().trim(),
            maxKills         = etMaxKills.text.toString().toIntOrNull()    ?: cfg.maxKills,
            attackDelayMs    = etAttackDelay.text.toString().toLongOrNull() ?: cfg.attackDelayMs,
            skill1CooldownMs = (etSkill1Cd.text.toString().toLongOrNull() ?: (cfg.skill1CooldownMs / 1000)) * 1000,
            skill2CooldownMs = (etSkill2Cd.text.toString().toLongOrNull() ?: (cfg.skill2CooldownMs / 1000)) * 1000,
            maxPotionsInSlot = etMaxPotions.text.toString().toIntOrNull()  ?: cfg.maxPotionsInSlot,
            joystickRadius   = etJoystickRadius.text.toString().toFloatOrNull() ?: cfg.joystickRadius,
            cameraSwipeRange = etCameraSwipeRange.text.toString().toFloatOrNull() ?: cfg.cameraSwipeRange,
        )
        BotConfig.save(this, cfg)
        toast("Impostazioni salvate ✓")
    }

    private fun xy(x: Float, y: Float) = "(${x.toInt()}, ${y.toInt()})"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
