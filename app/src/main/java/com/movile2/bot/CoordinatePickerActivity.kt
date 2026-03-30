package com.movile2.bot

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CoordinatePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LABEL = "label"
        const val RESULT_X    = "res_x"
        const val RESULT_Y    = "res_y"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Tocca il punto"

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(140, 0, 0, 0))

        val hint = TextView(this).apply {
            text = "👆 Tocca: $label"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(30, 24, 30, 24)
            setBackgroundColor(Color.argb(210, 10, 10, 40))
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; it.topMargin = 100 }
        root.addView(hint, lp)

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                setResult(RESULT_OK, Intent().apply {
                    putExtra(RESULT_X, e.rawX)
                    putExtra(RESULT_Y, e.rawY)
                })
                finish()
                true
            } else false
        }
        setContentView(root)
    }
}
