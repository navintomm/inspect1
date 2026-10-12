package com.colorinspector.app.viewmodel

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorinspector.app.model.*
import com.colorinspector.app.opencv.ColorDetector
import kotlinx.coroutines.*

class InspectionViewModel : ViewModel() {

    // ── Phase ────────────────────────────────────────────────────────────────
    private val _phase = MutableLiveData(InspectionPhase.AREA_SELECTION)
    val phase: LiveData<InspectionPhase> = _phase

    // ── Selection shape ──────────────────────────────────────────────────────
    private val _selectedShape = MutableLiveData(SelectionShape.RECTANGLE)
    val selectedShape: LiveData<SelectionShape> = _selectedShape

    // ── Confirmed region ─────────────────────────────────────────────────────
    private val _confirmedRegion = MutableLiveData<InspectionRegion?>()
    val confirmedRegion: LiveData<InspectionRegion?> = _confirmedRegion

    // ── Background colour ────────────────────────────────────────────────────
    private val _bgColor = MutableLiveData<Int?>(null)
    val bgColor: LiveData<Int?> = _bgColor

    // ── Detection params ─────────────────────────────────────────────────────
    private val _params = MutableLiveData(DetectionParams())
    val params: LiveData<DetectionParams> = _params

    // ── Detection result ─────────────────────────────────────────────────────
    private val _detectionResult = MutableLiveData<DetectionResult>()
    val detectionResult: LiveData<DetectionResult> = _detectionResult

    // ── Spot count ───────────────────────────────────────────────────────────
    private val _spotCount = MutableLiveData(0)
    val spotCount: LiveData<Int> = _spotCount

    // ── Final result bitmap ──────────────────────────────────────────────────
    private val _resultBitmap = MutableLiveData<Bitmap?>()
    val resultBitmap: LiveData<Bitmap?> = _resultBitmap

    // ── Status message ───────────────────────────────────────────────────────
    private val _statusMessage = MutableLiveData<String>("")
    val statusMessage: LiveData<String> = _statusMessage

    // ── Internal ─────────────────────────────────────────────────────────────
    private var detectionJob: Job? = null
    private var latestBitmap: Bitmap? = null
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0

    // =========================================================================
    // Phase transitions
    // =========================================================================

    fun setShape(shape: SelectionShape) {
        _selectedShape.value = shape
    }

    fun confirmRegion(region: InspectionRegion, width: Int, height: Int) {
        _confirmedRegion.value = region
        previewWidth = width
        previewHeight = height
        _phase.value = InspectionPhase.BACKGROUND_SAMPLING
        _statusMessage.value = "Analysing background colour…"
    }

    fun sampleBackgroundFromFrame(bitmap: Bitmap) {
        val region = _confirmedRegion.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val color = ColorDetector.sampleBackgroundColor(
                bitmap, region, bitmap.width, bitmap.height
            )
            withContext(Dispatchers.Main) {
                _bgColor.value = color
                _statusMessage.value = "Background detected. Press Start Detection."
            }
        }
    }

    fun setBackgroundColorManually(color: Int) {
        _bgColor.value = color
        _statusMessage.value = "Background colour set manually."
    }

    fun sampleColorAtPoint(bitmap: Bitmap, point: PointF) {
        val color = ColorDetector.sampleColorAtPoint(bitmap, point)
        _bgColor.value = color
        _statusMessage.value = "Background colour sampled from tap."
    }

    // =========================================================================
    // Detection lifecycle
    // =========================================================================

    fun startDetection() {
        if (_bgColor.value == null || _confirmedRegion.value == null) return
        _phase.value = InspectionPhase.DETECTING
        _statusMessage.value = "Detecting…"
        runDetectionLoop()
    }

    fun pauseDetection() {
        detectionJob?.cancel()
        _phase.value = InspectionPhase.PAUSED
        _statusMessage.value = "Paused."
    }

    fun resumeDetection() {
        _phase.value = InspectionPhase.DETECTING
        _statusMessage.value = "Detecting…"
        runDetectionLoop()
    }

    fun stopDetection(finalBitmap: Bitmap?) {
        detectionJob?.cancel()
        _phase.value = InspectionPhase.RESULTS
        _resultBitmap.value = finalBitmap
        _statusMessage.value = "Detection stopped. ${_spotCount.value} spot(s) detected."
    }

    fun resetInspection() {
        detectionJob?.cancel()
        ColorDetector.resetAccumulator()
        _confirmedRegion.value = null
        _bgColor.value = null
        _detectionResult.value = DetectionResult(emptyList())
        _spotCount.value = 0
        _resultBitmap.value = null
        _params.value = DetectionParams()
        _phase.value = InspectionPhase.AREA_SELECTION
        _statusMessage.value = ""
    }

    // =========================================================================
    // Frame feed (called from CameraX ImageAnalysis)
    // =========================================================================

    fun feedFrame(bitmap: Bitmap) {
        latestBitmap = bitmap
    }

    // =========================================================================
    // Params
    // =========================================================================

    fun setSensitivity(sensitivity: Int) {
        val tolerance = ColorDetector.sensitivityToTolerance(sensitivity)
        _params.value = _params.value!!.copy(
            sensitivity = sensitivity,
            colorTolerance = tolerance
        )
    }

    fun setMinSpotArea(area: Int) {
        _params.value = _params.value!!.copy(minSpotAreaPx = area)
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private fun runDetectionLoop() {
        detectionJob?.cancel()
        detectionJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val bmp = latestBitmap
                val region = _confirmedRegion.value
                val color = _bgColor.value
                val p = _params.value

                if (bmp != null && region != null && color != null && p != null) {
                    try {
                        val result = ColorDetector.detect(bmp, region, color, p)
                        withContext(Dispatchers.Main) {
                            _detectionResult.value = result
                            _spotCount.value = result.spots.size
                        }
                    } catch (e: Exception) {
                        // Frame processing error – skip frame
                    }
                }
                delay(80) // ~12 fps analysis
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        detectionJob?.cancel()
    }
}
