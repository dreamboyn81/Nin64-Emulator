package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class GameControlSettingsActivity : AppCompatActivity() {
    private lateinit var romKey: String
    private var gameTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        romKey = intent.getStringExtra(EXTRA_ROM_KEY) ?: run {
            finish()
            return
        }
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }
        root.addView(createTopBar())
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dp, 20.dp, 20.dp, 20.dp)

                addView(menuButton(getString(R.string.controls_edit_touch_layout)) {
                    TouchLayoutActivity.launch(this@GameControlSettingsActivity, romKey, gameTitle)
                })
                addView(menuButton(getString(R.string.controls_edit_controller_mapping)) {
                    GamepadMappingActivity.launch(this@GameControlSettingsActivity, romKey, gameTitle)
                })
                addView(menuButton(getString(R.string.controls_reset_controls)) {
                    ControlsRepository.resetPerGame(this@GameControlSettingsActivity, romKey)
                    Toast.makeText(
                        this@GameControlSettingsActivity,
                        getString(R.string.controls_per_game_reset_done),
                        Toast.LENGTH_SHORT,
                    ).show()
                })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )
        setContentView(root)
    }

    private fun createTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 0, 16.dp, 0)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                64.dp,
            )

            addView(
                ImageButton(this@GameControlSettingsActivity).apply {
                    setImageResource(R.drawable.ic_back)
                    background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                        .useAndReturnDrawable(0)
                    contentDescription = getString(R.string.navigate_up)
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(48.dp, 48.dp),
            )

            addView(
                TextView(this@GameControlSettingsActivity).apply {
                    text = scopedTitle(getString(R.string.controls_game_controls))
                    setTextColor(Color.rgb(33, 33, 33))
                    textSize = 20f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(8.dp, 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    private fun menuButton(textValue: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = textValue
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 12.dp
            }
        }

    private fun scopedTitle(base: String): String =
        if (gameTitle.isNullOrBlank()) base else "$base - $gameTitle"

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun android.content.res.TypedArray.useAndReturnDrawable(index: Int): android.graphics.drawable.Drawable? {
        return try {
            getDrawable(index)
        } finally {
            recycle()
        }
    }

    companion object {
        private const val EXTRA_ROM_KEY = "extra_rom_key"
        private const val EXTRA_GAME_TITLE = "extra_game_title"

        fun launch(context: Context, romKey: String, gameTitle: String) {
            context.startActivity(
                Intent(context, GameControlSettingsActivity::class.java)
                    .putExtra(EXTRA_ROM_KEY, romKey)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
