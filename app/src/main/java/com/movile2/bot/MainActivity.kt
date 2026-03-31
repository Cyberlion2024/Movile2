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
    private lateinit var etPlayerName: EditText
    private lateinit var etMaxKills: EditText
    private lateinit var etSessionMinutes: EditText
    private lateinit var etAttackDelay: EditText
    private lateinit var tvAttackCoord: TextView
    private lateinit var tvSkill1Coord: TextView
    private lateinit var etSkill1Cd: EditText
    private lateinit var tvSkill2Coord: TextView
    private lateinit var etSkill2Cd: EditText
    private lateinit var tvSkill3Coord: TextView
    private lateinit var etSkill3Cd: EditText
    private lateinit var tvSkill4Coord: TextView
    private lateinit var etSkill4Cd: EditText
    private lateinit var tvSkill5Coord: TextView
    private lateinit var etSkill5Cd: EditText

    // 7 slot pozione + inventario
    private lateinit var tvPotion1Coord: TextView
    private lateinit var tvPotion2Coord: TextView
    private lateinit var tvPotion3Coord: TextView
    private lateinit var tvPotion4Coord: TextView
    private lateinit var tvPotion5Coord: TextView
    private lateinit var tvPotion6Coord: TextView
    private lateinit var tvPotion7Coord: TextView
    private lateinit var tvInventoryPotionCoord: TextView

    private lateinit var tvJoystickCoord: TextView
    private lateinit var etJoystickRadius: EditText
    private lateinit var tvCameraCoord: TextView
    private lateinit var etCameraSwipeRange: EditText
    private lateinit var tvHpBarCoord: TextView
    private lateinit var etHpBarFullWidth: EditText
    private lateinit var etHpPotionThreshold: EditText
    private lateinit var tvPlayerCoord: TextView
    private lateinit var etDefenseRadius: EditText

    private var pendingKey = ""
    private var calibrationQueue: ArrayDeque<Pair<String, String>>? = null

    companion object {
        private const val REQ = 101
        private const val K_ATTACK   = "attack"
        private const val K_SK1      = "sk1"
        private const val K_SK2      = "sk2"
        private const val K_SK3      = "sk3"
        private const val K_SK4      = "sk4"
        private const val K_SK5      = "sk5"
        private const val K_POT1     = "pot1"
        private const val K_POT2     = "pot2"
        private const val K_POT3     = "pot3"
        private const val K_POT4     = "pot4"
        private const val K_POT5     = "pot5"
        private const val K_POT6     = "pot6"
        private const val K_POT7     = "pot7"
        private const val K_INV_POT  = "invPot"
        private const val K_JOYSTICK = "joystick"
        private const val K_CAMERA   = "camera"
        private const val K_HP       = "hp"
        private const val K_PLAYER   = "player"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        migrateToAutoCoords()
        cfg = BotConfig.load(this)
        bind()
        populate()
        hooks()
    }

    private fun migrateToAutoCoords() {
        val prefs = getSharedPreferences("bot_config", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_coords_v7", false)) return
        val coordKeys = listOf(
            "attackX","attackY","skill1X","skill1Y","skill2X","skill2Y",
            "skill3X","skill3Y","skill4X","skill4Y","skill5X","skill5Y",
            "potionX","potionY","backupPotionX","backupPotionY",
            "joystickX","joystickY","joystickRadius",
            "cameraAreaX","cameraAreaY","cameraSwipeRange",
            "playerX","playerY","defenseRadiusPx",
            "hpBarX","hpBarY","hpBarFullWidth"
        )
        prefs.edit().apply {
            coordKeys.forEach { remove(it) }
            remove("auto_coords_v5"); remove("auto_coords_v6")
            putBoolean("auto_coords_v7", true)
            apply()
        }
    }

    private fun resetToAuto() {
        val prefs = getSharedPreferences("bot_config", android.content.Context.MODE_PRIVATE)
        val coordKeys = listOf(
            "attackX","attackY","skill1X","skill1Y","skill2X","skill2Y",
            "skill3X","skill3Y","skill4X","skill4Y","skill5X","skill5Y",
            "potion1X","potion1Y","potion2X","potion2Y","potion3X","potion3Y",
            "potion4X","potion4Y","potion5X","potion5Y","potion6X","potion6Y",
            "potion7X","potion7Y","inventoryPotionX","inventoryPotionY",
            "joystickX","joystickY","joystickRadius",
            "cameraAreaX","cameraAreaY","cameraSwipeRange",
            "hpBarX","hpBarY","hpBarFullWidth",
            "playerX","playerY","defenseRadiusPx"
        )
        prefs.edit().apply { coordKeys.forEach { remove(it) }; apply() }
        cfg = BotConfig.load(this)
        populate()
        toast("Coordinate resettate → modalità auto ✓")
    }

    private fun bind() {
        etMonsterName        = findViewById(R.id.etMonsterName)
        etPlayerName         = findViewById(R.id.etPlayerName)
        etMaxKills           = findViewById(R.id.etMaxKills)
        etSessionMinutes     = findViewById(R.id.etSessionMinutes)
        etAttackDelay        = findViewById(R.id.etAttackDelay)
        tvAttackCoord        = findViewById(R.id.tvAttackCoord)
        tvSkill1Coord        = findViewById(R.id.tvSkill1Coord)
        etSkill1Cd           = findViewById(R.id.etSkill1Cd)
        tvSkill2Coord        = findViewById(R.id.tvSkill2Coord)
        etSkill2Cd           = findViewById(R.id.etSkill2Cd)
        tvSkill3Coord        = findViewById(R.id.tvSkill3Coord)
        etSkill3Cd           = findViewById(R.id.etSkill3Cd)
        tvSkill4Coord        = findViewById(R.id.tvSkill4Coord)
        etSkill4Cd           = findViewById(R.id.etSkill4Cd)
        tvSkill5Coord        = findViewById(R.id.tvSkill5Coord)
        etSkill5Cd           = findViewById(R.id.etSkill5Cd)
        tvPotion1Coord       = findViewById(R.id.tvPotion1Coord)
        tvPotion2Coord       = findViewById(R.id.tvPotion2Coord)
        tvPotion3Coord       = findViewById(R.id.tvPotion3Coord)
        tvPotion4Coord       = findViewById(R.id.tvPotion4Coord)
        tvPotion5Coord       = findViewById(R.id.tvPotion5Coord)
        tvPotion6Coord       = findViewById(R.id.tvPotion6Coord)
        tvPotion7Coord       = findViewById(R.id.tvPotion7Coord)
        tvInventoryPotionCoord = findViewById(R.id.tvInventoryPotionCoord)
        tvJoystickCoord      = findViewById(R.id.tvJoystickCoord)
        etJoystickRadius     = findViewById(R.id.etJoystickRadius)
        tvCameraCoord        = findViewById(R.id.tvCameraCoord)
        etCameraSwipeRange   = findViewById(R.id.etCameraSwipeRange)
        tvHpBarCoord         = findViewById(R.id.tvHpBarCoord)
        etHpBarFullWidth     = findViewById(R.id.etHpBarFullWidth)
        etHpPotionThreshold  = findViewById(R.id.etHpPotionThreshold)
        tvPlayerCoord        = findViewById(R.id.tvPlayerCoord)
        etDefenseRadius      = findViewById(R.id.etDefenseRadius)
    }

    private fun coord(x: Float, y: Float) = if (x > 0f) xy(x, y) else "non impostato"

    private fun populate() {
        etMonsterName.setText(cfg.monsterName)
        etPlayerName.setText(cfg.playerName)
        etMaxKills.setText(cfg.maxKills.toString())
        etSessionMinutes.setText(cfg.sessionMinutes.toString())
        etAttackDelay.setText(cfg.attackDelayMs.toString())
        tvAttackCoord.text       = coord(cfg.attackX, cfg.attackY)
        tvSkill1Coord.text       = coord(cfg.skill1X, cfg.skill1Y)
        etSkill1Cd.setText((cfg.skill1CooldownMs / 1000).toString())
        tvSkill2Coord.text       = coord(cfg.skill2X, cfg.skill2Y)
        etSkill2Cd.setText((cfg.skill2CooldownMs / 1000).toString())
        tvSkill3Coord.text       = coord(cfg.skill3X, cfg.skill3Y)
        etSkill3Cd.setText((cfg.skill3CooldownMs / 1000).toString())
        tvSkill4Coord.text       = coord(cfg.skill4X, cfg.skill4Y)
        etSkill4Cd.setText((cfg.skill4CooldownMs / 1000).toString())
        tvSkill5Coord.text       = coord(cfg.skill5X, cfg.skill5Y)
        etSkill5Cd.setText((cfg.skill5CooldownMs / 1000).toString())
        tvPotion1Coord.text      = coord(cfg.potion1X, cfg.potion1Y)
        tvPotion2Coord.text      = coord(cfg.potion2X, cfg.potion2Y)
        tvPotion3Coord.text      = coord(cfg.potion3X, cfg.potion3Y)
        tvPotion4Coord.text      = coord(cfg.potion4X, cfg.potion4Y)
        tvPotion5Coord.text      = coord(cfg.potion5X, cfg.potion5Y)
        tvPotion6Coord.text      = coord(cfg.potion6X, cfg.potion6Y)
        tvPotion7Coord.text      = coord(cfg.potion7X, cfg.potion7Y)
        tvInventoryPotionCoord.text = coord(cfg.inventoryPotionX, cfg.inventoryPotionY)
        tvJoystickCoord.text     = coord(cfg.joystickX, cfg.joystickY)
        etJoystickRadius.setText(if (cfg.joystickRadius > 0f) cfg.joystickRadius.toInt().toString() else "0")
        tvCameraCoord.text       = coord(cfg.cameraAreaX, cfg.cameraAreaY)
        etCameraSwipeRange.setText(if (cfg.cameraSwipeRange > 0f) cfg.cameraSwipeRange.toInt().toString() else "0")
        tvHpBarCoord.text        = coord(cfg.hpBarX, cfg.hpBarY)
        etHpBarFullWidth.setText(cfg.hpBarFullWidth.toString())
        etHpPotionThreshold.setText((cfg.hpPotionThreshold * 100).toInt().toString())
        tvPlayerCoord.text       = coord(cfg.playerX, cfg.playerY)
        etDefenseRadius.setText(cfg.defenseRadiusPx.toString())
    }

    private fun hooks() {
        fun pick(key: String, label: String, tv: TextView) {
            pendingKey = key
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, CoordinatePickerActivity::class.java)
                    .putExtra(CoordinatePickerActivity.EXTRA_LABEL, label), REQ
            )
            tv.text = "tocca lo schermo…"
        }

        findViewById<Button>(R.id.btnPickAttack)  .setOnClickListener { pick(K_ATTACK,   "Bottone Attacco",            tvAttackCoord) }
        findViewById<Button>(R.id.btnPickSkill1)  .setOnClickListener { pick(K_SK1,      "Abilità 1",                  tvSkill1Coord) }
        findViewById<Button>(R.id.btnPickSkill2)  .setOnClickListener { pick(K_SK2,      "Abilità 2",                  tvSkill2Coord) }
        findViewById<Button>(R.id.btnPickSkill3)  .setOnClickListener { pick(K_SK3,      "Abilità 3",                  tvSkill3Coord) }
        findViewById<Button>(R.id.btnPickSkill4)  .setOnClickListener { pick(K_SK4,      "Abilità 4",                  tvSkill4Coord) }
        findViewById<Button>(R.id.btnPickSkill5)  .setOnClickListener { pick(K_SK5,      "Abilità 5",                  tvSkill5Coord) }
        findViewById<Button>(R.id.btnPickPotion1) .setOnClickListener { pick(K_POT1,     "Slot Pozione 1",             tvPotion1Coord) }
        findViewById<Button>(R.id.btnPickPotion2) .setOnClickListener { pick(K_POT2,     "Slot Pozione 2",             tvPotion2Coord) }
        findViewById<Button>(R.id.btnPickPotion3) .setOnClickListener { pick(K_POT3,     "Slot Pozione 3",             tvPotion3Coord) }
        findViewById<Button>(R.id.btnPickPotion4) .setOnClickListener { pick(K_POT4,     "Slot Pozione 4",             tvPotion4Coord) }
        findViewById<Button>(R.id.btnPickPotion5) .setOnClickListener { pick(K_POT5,     "Slot Pozione 5",             tvPotion5Coord) }
        findViewById<Button>(R.id.btnPickPotion6) .setOnClickListener { pick(K_POT6,     "Slot Pozione 6",             tvPotion6Coord) }
        findViewById<Button>(R.id.btnPickPotion7) .setOnClickListener { pick(K_POT7,     "Slot Pozione 7",             tvPotion7Coord) }
        findViewById<Button>(R.id.btnPickInvPotion).setOnClickListener { pick(K_INV_POT, "Pozione Rossa Inventario",   tvInventoryPotionCoord) }
        findViewById<Button>(R.id.btnPickJoystick).setOnClickListener { pick(K_JOYSTICK, "Centro Joystick",            tvJoystickCoord) }
        findViewById<Button>(R.id.btnPickCamera)  .setOnClickListener { pick(K_CAMERA,   "Punto Centrale Visuale",     tvCameraCoord) }
        findViewById<Button>(R.id.btnPickHpBar)   .setOnClickListener { pick(K_HP,       "Bordo Sinistro Barra HP",    tvHpBarCoord) }
        findViewById<Button>(R.id.btnPickPlayer)  .setOnClickListener { pick(K_PLAYER,   "Centro Personaggio",          tvPlayerCoord) }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnResetCoords).setOnClickListener { resetToAuto() }

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
            K_ATTACK   -> cfg.copy(attackX = x, attackY = y).also          { tvAttackCoord.text = xy(x, y) }
            K_SK1      -> cfg.copy(skill1X = x, skill1Y = y).also          { tvSkill1Coord.text = xy(x, y) }
            K_SK2      -> cfg.copy(skill2X = x, skill2Y = y).also          { tvSkill2Coord.text = xy(x, y) }
            K_SK3      -> cfg.copy(skill3X = x, skill3Y = y).also          { tvSkill3Coord.text = xy(x, y) }
            K_SK4      -> cfg.copy(skill4X = x, skill4Y = y).also          { tvSkill4Coord.text = xy(x, y) }
            K_SK5      -> cfg.copy(skill5X = x, skill5Y = y).also          { tvSkill5Coord.text = xy(x, y) }
            K_POT1     -> cfg.copy(potion1X = x, potion1Y = y).also        { tvPotion1Coord.text = xy(x, y) }
            K_POT2     -> cfg.copy(potion2X = x, potion2Y = y).also        { tvPotion2Coord.text = xy(x, y) }
            K_POT3     -> cfg.copy(potion3X = x, potion3Y = y).also        { tvPotion3Coord.text = xy(x, y) }
            K_POT4     -> cfg.copy(potion4X = x, potion4Y = y).also        { tvPotion4Coord.text = xy(x, y) }
            K_POT5     -> cfg.copy(potion5X = x, potion5Y = y).also        { tvPotion5Coord.text = xy(x, y) }
            K_POT6     -> cfg.copy(potion6X = x, potion6Y = y).also        { tvPotion6Coord.text = xy(x, y) }
            K_POT7     -> cfg.copy(potion7X = x, potion7Y = y).also        { tvPotion7Coord.text = xy(x, y) }
            K_INV_POT  -> cfg.copy(inventoryPotionX = x, inventoryPotionY = y).also { tvInventoryPotionCoord.text = xy(x, y) }
            K_JOYSTICK -> cfg.copy(joystickX = x, joystickY = y).also      { tvJoystickCoord.text = xy(x, y) }
            K_CAMERA   -> cfg.copy(cameraAreaX = x, cameraAreaY = y).also  { tvCameraCoord.text = xy(x, y) }
            K_HP       -> cfg.copy(hpBarX = x, hpBarY = y).also            { tvHpBarCoord.text = xy(x, y) }
            K_PLAYER   -> cfg.copy(playerX = x, playerY = y).also          { tvPlayerCoord.text = xy(x, y) }
            else       -> cfg
        }
        continueCalibrationIfNeeded()
    }

    private fun startCalibration() {
        calibrationQueue = ArrayDeque(
            listOf(
                K_ATTACK to "Bottone Attacco (spada grande)",
                K_SK1 to "Abilità 1",
                K_SK2 to "Abilità 2",
                K_SK3 to "Abilità 3",
                K_SK4 to "Abilità 4",
                K_SK5 to "Abilità 5",
                K_POTION to "Pozione rossa",
                K_BACKUP to "Pozione backup",
                K_JOYSTICK to "Centro Joystick",
                K_HP to "Bordo sinistro barra HP",
                K_PLAYER to "Centro personaggio (petto)"
            )
        )
        toast("Calibrazione avviata: tocca i punti richiesti in sequenza")
        continueCalibrationIfNeeded()
    }

    private fun continueCalibrationIfNeeded() {
        val q = calibrationQueue ?: return
        if (q.isEmpty()) {
            calibrationQueue = null
            BotConfig.save(this, cfg)
            populate()
            toast("Calibrazione completata ✓")
            return
        }
        val (key, label) = q.removeFirst()
        pendingKey = key
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, CoordinatePickerActivity::class.java)
                .putExtra(CoordinatePickerActivity.EXTRA_LABEL, label), REQ
        )
    }

    private fun save() {
        cfg = cfg.copy(
            monsterName       = etMonsterName.text.toString().trim(),
            playerName        = etPlayerName.text.toString().trim(),
            maxKills          = etMaxKills.text.toString().toIntOrNull()        ?: cfg.maxKills,
            sessionMinutes    = etSessionMinutes.text.toString().toIntOrNull()  ?: cfg.sessionMinutes,
            attackDelayMs     = etAttackDelay.text.toString().toLongOrNull()    ?: cfg.attackDelayMs,
            skill1CooldownMs  = (etSkill1Cd.text.toString().toLongOrNull()      ?: (cfg.skill1CooldownMs / 1000)) * 1000,
            skill2CooldownMs  = (etSkill2Cd.text.toString().toLongOrNull()      ?: (cfg.skill2CooldownMs / 1000)) * 1000,
            skill3CooldownMs  = (etSkill3Cd.text.toString().toLongOrNull()      ?: (cfg.skill3CooldownMs / 1000)) * 1000,
            skill4CooldownMs  = (etSkill4Cd.text.toString().toLongOrNull()      ?: (cfg.skill4CooldownMs / 1000)) * 1000,
            skill5CooldownMs  = (etSkill5Cd.text.toString().toLongOrNull()      ?: (cfg.skill5CooldownMs / 1000)) * 1000,
            joystickRadius    = etJoystickRadius.text.toString().toFloatOrNull() ?: cfg.joystickRadius,
            cameraSwipeRange  = etCameraSwipeRange.text.toString().toFloatOrNull() ?: cfg.cameraSwipeRange,
            hpBarFullWidth    = etHpBarFullWidth.text.toString().toIntOrNull()  ?: cfg.hpBarFullWidth,
            hpPotionThreshold = (etHpPotionThreshold.text.toString().toFloatOrNull() ?: (cfg.hpPotionThreshold * 100)) / 100f,
            defenseRadiusPx   = etDefenseRadius.text.toString().toIntOrNull()  ?: cfg.defenseRadiusPx,
        )
        BotConfig.save(this, cfg)
        toast("Impostazioni salvate ✓")
    }

    private fun xy(x: Float, y: Float) = "(${x.toInt()}, ${y.toInt()})"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
