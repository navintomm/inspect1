package com.colorinspector.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.colorinspector.app.databinding.ActivityResultsBinding

class ResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultsBinding

    companion object {
        private const val EXTRA_SPOT_COUNT   = "spotCount"
        private const val EXTRA_BG_COLOR     = "bgColor"
        private const val EXTRA_SENSITIVITY  = "sensitivity"
        private const val EXTRA_COLOR_NAME   = "colorName"

        fun start(
            context: Context,
            resultBitmap: Bitmap,
            spotCount: Int,
            bgColor: Int,
            sensitivity: Int,
            colorName: String
        ) {
            // Store bitmap in companion cache (simple approach for inter-activity transfer)
            bitmapCache = resultBitmap
            context.startActivity(Intent(context, ResultsActivity::class.java).apply {
                putExtra(EXTRA_SPOT_COUNT,  spotCount)
                putExtra(EXTRA_BG_COLOR,    bgColor)
                putExtra(EXTRA_SENSITIVITY, sensitivity)
                putExtra(EXTRA_COLOR_NAME,  colorName)
            })
        }

        // Static cache – cleared after use
        var bitmapCache: Bitmap? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val spotCount   = intent.getIntExtra(EXTRA_SPOT_COUNT, 0)
        val bgColor     = intent.getIntExtra(EXTRA_BG_COLOR, Color.WHITE)
        val sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, 50)
        val colorName   = intent.getStringExtra(EXTRA_COLOR_NAME) ?: "Unknown"

        binding.tvResultSpots.text       = "Spots Detected: $spotCount"
        binding.tvResultBgColor.text     = "Background Colour: $colorName"
        binding.tvResultSensitivity.text = "Sensitivity: $sensitivity%"
        binding.resultColorSwatch.setBackgroundColor(bgColor)

        val bmp = bitmapCache
        bitmapCache = null
        if (bmp != null) {
            binding.resultImage.setImageBitmap(bmp)
        }

        binding.btnNewInspection.setOnClickListener {
            startActivity(Intent(this, InspectionActivity::class.java))
            finish()
        }

        binding.btnBackToHome.setOnClickListener { finish() }
    }
}
