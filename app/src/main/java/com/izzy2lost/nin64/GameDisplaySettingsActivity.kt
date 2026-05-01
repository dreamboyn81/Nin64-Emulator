package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class GameDisplaySettingsActivity : AppCompatActivity() {
    private val prefs by lazy {
        getSharedPreferences(DisplayOptionsRepository.PREFS_NAME, MODE_PRIVATE)
    }

    private lateinit var romKey: String
    private var gameTitle: String? = null
    private lateinit var aspectSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNin64EdgeToEdge()
        romKey = intent.getStringExtra(EXTRA_ROM_KEY) ?: run {
            finish()
            return
        }
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)

        setContentView(R.layout.activity_game_display_settings)
        findViewById<View>(R.id.topBar).applyTopBarInsets()
        findViewById<View>(R.id.displayScroll).applyBottomContentInsets()

        val base = getString(R.string.display_game_display)
        findViewById<TextView>(R.id.titleText).text =
            if (gameTitle.isNullOrBlank()) base else "$base - $gameTitle"

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        aspectSpinner = findViewById(R.id.aspectSpinner)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)

        bindDisplaySpinners()

        findViewById<MaterialButton>(R.id.resetDisplayButton).setOnClickListener {
            DisplayOptionsRepository.resetPerGame(this, romKey)
            bindDisplaySpinners()
            Toast.makeText(this, getString(R.string.display_per_game_reset_done), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindDisplaySpinners() {
        bindPerGameSpinner(
            spinner = aspectSpinner,
            labelsRes = R.array.aspect_labels,
            valuesRes = R.array.aspect_values,
            globalPrefKey = DisplayOptionsRepository.PREF_ASPECT,
            perGamePrefKey = DisplayOptionsRepository.perGameAspectKey(romKey),
            defaultValue = DisplayOptionsRepository.DEFAULT_ASPECT,
        )
        bindPerGameSpinner(
            spinner = resolutionSpinner,
            labelsRes = R.array.resolution_labels,
            valuesRes = R.array.resolution_values,
            globalPrefKey = DisplayOptionsRepository.PREF_RES_FACTOR,
            perGamePrefKey = DisplayOptionsRepository.perGameResolutionFactorKey(romKey),
            defaultValue = DisplayOptionsRepository.DEFAULT_RES_FACTOR,
        )
    }

    private fun bindPerGameSpinner(
        spinner: Spinner,
        labelsRes: Int,
        valuesRes: Int,
        globalPrefKey: String,
        perGamePrefKey: String,
        defaultValue: String,
    ) {
        spinner.onItemSelectedListener = null

        val labels = resources.getStringArray(labelsRes)
        val values = resources.getStringArray(valuesRes)
        val globalValue = prefs.getString(globalPrefKey, defaultValue) ?: defaultValue
        val globalIndex = values.indexOf(globalValue).let {
            if (it < 0) values.indexOf(defaultValue).coerceAtLeast(0) else it
        }
        val displayLabels = listOf(getString(R.string.settings_use_global_value, labels[globalIndex])) + labels
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val current = prefs.getString(perGamePrefKey, null)
        val index = current
            ?.let(values::indexOf)
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        spinner.setSelection(index, false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().apply {
                    if (position == 0) {
                        remove(perGamePrefKey)
                    } else {
                        putString(perGamePrefKey, values[position - 1])
                    }
                }.apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    companion object {
        private const val EXTRA_ROM_KEY = "extra_rom_key"
        private const val EXTRA_GAME_TITLE = "extra_game_title"

        fun launch(context: Context, romKey: String, gameTitle: String) {
            context.startActivity(
                Intent(context, GameDisplaySettingsActivity::class.java)
                    .putExtra(EXTRA_ROM_KEY, romKey)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
