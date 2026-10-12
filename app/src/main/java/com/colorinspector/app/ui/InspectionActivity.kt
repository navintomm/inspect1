package com.colorinspector.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.view.View
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.os.Bundle
import com.colorinspector.app.databinding.ActivityInspectionBinding
import com.colorinspector.app.model.*
import com.colorinspector.app.opencv.ColorDetector
import com.colorinspector.app.ui.dialogs.ColorPickerDialog
import com.colorinspector.app.viewmodel.InspectionViewModel
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InspectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInspectionBinding
    private val viewModel: InspectionViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService
    private var latestBitmap: Bitmap? = null

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInspectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupShapeChips()
        setupButtons()
        setupSeekBars()
        observeViewModel()
        startCamera()

        // Show selection overlay and panel immediately
        binding.selectionOverlay.visibility = View.VISIBLE
        binding.panelShapeSelection.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera
    // ─────────────────────────────────────────────────────────────────────────
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bmp = imageProxy.toBitmap()
                        latestBitmap = bmp
                        viewModel.feedFrame(bmp)
                        imageProxy.close()
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                showSnack("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shape chips
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupShapeChips() {
        binding.chipCircle.setOnClickListener    { selectShape(SelectionShape.CIRCLE) }
        binding.chipOval.setOnClickListener      { selectShape(SelectionShape.OVAL) }
        binding.chipSquare.setOnClickListener    { selectShape(SelectionShape.SQUARE) }
        binding.chipRectangle.setOnClickListener { selectShape(SelectionShape.RECTANGLE) }
        binding.chipFreehand.setOnClickListener  { selectShape(SelectionShape.FREEHAND) }
    }

    private fun selectShape(shape: SelectionShape) {
        viewModel.setShape(shape)
        binding.selectionOverlay.currentShape = shape
        binding.freehandControls.visibility =
            if (shape == SelectionShape.FREEHAND) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Buttons
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupButtons() {
        // Freehand
        binding.btnFreehandClear.setOnClickListener    { binding.selectionOverlay.clearFreehandAndRedraw() }
        binding.btnFreehandUndo.setOnClickListener     { binding.selectionOverlay.undoFreehand() }
        binding.btnFreehandComplete.setOnClickListener { binding.selectionOverlay.completeFreehand() }

        // Confirm area
        binding.btnConfirmArea.setOnClickListener {
            val region = binding.selectionOverlay.currentRegion()
            if (region == null) {
                showSnack(
                    if (binding.selectionOverlay.currentShape == SelectionShape.FREEHAND)
                        "Draw a region, then tap Done."
                    else "Position the shape over your object first."
                )
                return@setOnClickListener
            }
            val bmp = latestBitmap ?: run { showSnack("Camera not ready yet."); return@setOnClickListener }
            // Scale region from view-space → bitmap-space so ColorDetector operates correctly
            val scaleX = bmp.width.toFloat() / binding.selectionOverlay.width
            val scaleY = bmp.height.toFloat() / binding.selectionOverlay.height
            val scaledRegion = region.scaledTo(scaleX, scaleY)
            viewModel.confirmRegion(scaledRegion, bmp.width, bmp.height)
            viewModel.sampleBackgroundFromFrame(bmp)
        }

        // Background colour panel
        binding.btnPickColor.setOnClickListener {
            val current = viewModel.bgColor.value ?: Color.WHITE
            ColorPickerDialog.show(this, current) { viewModel.setBackgroundColorManually(it) }
        }

        // Tap on camera preview to sample colour (only during BACKGROUND_SAMPLING)
        binding.cameraPreview.setOnTouchListener { _, event ->
            if (viewModel.phase.value == InspectionPhase.BACKGROUND_SAMPLING &&
                event.action == android.view.MotionEvent.ACTION_UP
            ) {
                val bmp = latestBitmap ?: return@setOnTouchListener false
                val scaleX = bmp.width.toFloat() / binding.cameraPreview.width
                val scaleY = bmp.height.toFloat() / binding.cameraPreview.height
                viewModel.sampleColorAtPoint(bmp, PointF(event.x * scaleX, event.y * scaleY))
                true
            } else false
        }

        // Detection controls
        binding.btnStartDetection.setOnClickListener { viewModel.startDetection() }
        binding.btnPause.setOnClickListener   { viewModel.pauseDetection() }
        binding.btnResume.setOnClickListener  { viewModel.resumeDetection() }
        binding.btnStop.setOnClickListener    { viewModel.stopDetection(latestBitmap) }
        binding.btnReset.setOnClickListener   { viewModel.resetInspection() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SeekBars
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupSeekBars() {
        binding.seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                viewModel.setSensitivity(p)
                binding.tvSensitivityValue.text = "$p%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.seekMinSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val area = 100 + (p * 19)
                viewModel.setMinSpotArea(area)
                binding.tvMinSizeValue.text = "${area}px²"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Observe ViewModel
    // ─────────────────────────────────────────────────────────────────────────
    private fun observeViewModel() {
        viewModel.phase.observe(this) { updateUiForPhase(it) }

        viewModel.bgColor.observe(this) { color ->
            if (color != null) {
                binding.tvBgColorSwatch.setBackgroundColor(color)
                binding.tvBgColorLabel.text = colorName(color)
                binding.bgColorGroup.visibility     = View.VISIBLE
                binding.btnStartDetection.visibility = View.VISIBLE
            }
        }

        viewModel.detectionResult.observe(this) { result ->
            binding.detectionOverlay.spots = result.spots
            // Scale bounding boxes from bitmap-space to view-space
            val bmp = latestBitmap
            if (bmp != null && binding.cameraPreview.width > 0) {
                binding.detectionOverlay.scaleX2 = binding.cameraPreview.width.toFloat() / bmp.width
                binding.detectionOverlay.scaleY2 = binding.cameraPreview.height.toFloat() / bmp.height
            }
        }

        viewModel.spotCount.observe(this) { count ->
            binding.tvSpotCount.text = "Spots Detected: $count"
        }

        viewModel.statusMessage.observe(this) { binding.tvStatus.text = it }

        viewModel.phase.observe(this) { phase ->
            if (phase == InspectionPhase.RESULTS) {
                launchResults()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase → UI
    // ─────────────────────────────────────────────────────────────────────────
    private fun updateUiForPhase(phase: InspectionPhase) {
        // Hide all panels first
        binding.panelShapeSelection.visibility     = View.GONE
        binding.panelBgColor.visibility            = View.GONE
        binding.panelDetectionControls.visibility  = View.GONE
        binding.selectionOverlay.visibility        = View.GONE
        binding.detectionOverlay.visibility        = View.GONE
        binding.tvSpotCount.visibility             = View.GONE

        when (phase) {
            InspectionPhase.AREA_SELECTION -> {
                binding.panelShapeSelection.visibility = View.VISIBLE
                binding.selectionOverlay.visibility    = View.VISIBLE
            }
            InspectionPhase.BACKGROUND_SAMPLING -> {
                binding.panelBgColor.visibility     = View.VISIBLE
                binding.selectionOverlay.visibility = View.VISIBLE   // region still visible
            }
            InspectionPhase.DETECTING -> {
                binding.panelDetectionControls.visibility = View.VISIBLE
                binding.detectionOverlay.visibility       = View.VISIBLE
                binding.tvSpotCount.visibility            = View.VISIBLE
                binding.btnPause.visibility   = View.VISIBLE
                binding.btnResume.visibility  = View.GONE
            }
            InspectionPhase.PAUSED -> {
                binding.panelDetectionControls.visibility = View.VISIBLE
                binding.detectionOverlay.visibility       = View.VISIBLE
                binding.tvSpotCount.visibility            = View.VISIBLE
                binding.btnPause.visibility   = View.GONE
                binding.btnResume.visibility  = View.VISIBLE
            }
            InspectionPhase.RESULTS, InspectionPhase.IDLE -> { /* handled separately */ }
        }
    }

    private fun launchResults() {
        val bgColor = viewModel.bgColor.value ?: Color.WHITE
        val sensitivity = viewModel.params.value?.sensitivity ?: 50
        val spotCount = viewModel.spotCount.value ?: 0
        ResultsActivity.start(this, latestBitmap ?: placeholderBitmap(), spotCount, bgColor, sensitivity, colorName(bgColor))
        viewModel.resetInspection()
    }

    private fun placeholderBitmap(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    // ─────────────────────────────────────────────────────────────────────────
    // Colour naming
    // ─────────────────────────────────────────────────────────────────────────
    private fun colorName(color: Int): String {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
        if (max < 40)  return "Black"
        if (min > 215) return "White"
        if (delta < 30) return "Grey"
        val hue = when (max) {
            r    -> ((60f * ((g - b).toFloat() / delta)) + 360f) % 360f
            g    -> 60f * ((b - r).toFloat() / delta) + 120f
            else -> 60f * ((r - g).toFloat() / delta) + 240f
        }
        return when {
            hue < 30  -> "Red"
            hue < 60  -> "Orange"
            hue < 90  -> "Yellow"
            hue < 150 -> "Green"
            hue < 210 -> "Cyan"
            hue < 270 -> "Blue"
            hue < 330 -> "Violet"
            else      -> "Red"
        }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
