package com.colorinspector.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.colorinspector.app.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startInspection()
        else Snackbar.make(binding.root, "Camera permission is required.", Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialise OpenCV
        if (!OpenCVLoader.initLocal()) {
            Snackbar.make(binding.root, "OpenCV failed to load.", Snackbar.LENGTH_LONG).show()
        }

        binding.btnStartInspection.setOnClickListener { checkPermissionAndStart() }
    }

    private fun checkPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> startInspection()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startInspection() {
        startActivity(Intent(this, InspectionActivity::class.java))
    }
}
