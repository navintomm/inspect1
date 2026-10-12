# Color Inspector — Android App

A real-time colour-difference inspection app built with **Kotlin**, **CameraX**, and **OpenCV 4.9**.

---

## Features

| Feature | Details |
|---|---|
| **Shape Selection** | Circle · Oval · Square · Rectangle · Freehand |
| **Drag & Resize** | Move any shape; drag corner/edge handles to resize; Circle/Square stay proportional |
| **Freehand Drawing** | Draw around irregular objects; Undo / Clear / Done |
| **Background Sampling** | Auto-detected via Lab-space mean; tap the screen to sample manually; HSV colour-picker dialog |
| **OpenCV Detection** | ΔE (Lab space) thresholding → morphological noise removal → contour detection |
| **Bounding Boxes** | Live boxes per detected spot with corner accents and "Spot N" labels |
| **Controls** | Pause / Resume / Stop / Reset · Sensitivity slider · Min Spot Size slider |
| **Results Screen** | Final frame with spot count, background colour swatch, sensitivity summary |

---

## Architecture

```
app/
├── model/          Models.kt          — data classes, enums
├── opencv/         ColorDetector.kt   — all OpenCV processing (stateless)
├── viewmodel/      InspectionViewModel.kt
└── ui/
    ├── MainActivity.kt
    ├── InspectionActivity.kt
    ├── ResultsActivity.kt
    ├── dialogs/    ColorPickerDialog.kt
    └── views/
        ├── SelectionOverlayView.kt   — shape drawing + touch handling
        └── DetectionOverlayView.kt  — bounding box rendering
```

### Detection Pipeline

```
CameraX frame (RGBA bitmap)
        │
        ▼
  buildMask(region)           ← only inspect pixels inside the user's shape
        │
        ▼
  RGB → Lab (OpenCV)
        │
        ▼
  Per-pixel ΔE vs. bgColor    ← Lab Euclidean distance
        │
        ▼
  Threshold → binary mask     ← controlled by sensitivity slider
        │
        ▼
  Morphological OPEN/CLOSE    ← removes tiny noise
        │
        ▼
  findContours → filter area  ← controlled by min-spot-size slider
        │
        ▼
  DetectedSpot list → LiveData → DetectionOverlayView (boxes)
```

---

## Setup in Android Studio

### 1. Clone / open the project
Open `c:\...\inspect` in **Android Studio Hedgehog** (2023.1.1) or newer.

### 2. Sync Gradle
Gradle will automatically download:
- OpenCV `4.9.0` via Maven Central (`org.opencv:opencv:4.9.0`)
- CameraX `1.3.4`
- Material Components `1.12.0`

### 3. Permissions
The app requests **`CAMERA`** at runtime. No other permissions are needed.

### 4. Run
Connect a physical Android device (API 26+) and press **Run ▶**.

> ⚠️ **Important:** OpenCV's `ImageAnalysis` processing works best on a real device.
> The emulator's camera is a software simulation and may give unexpected results.

---

## Customising the Detection Algorithm

All OpenCV logic is isolated in [`ColorDetector.kt`](app/src/main/java/com/colorinspector/app/opencv/ColorDetector.kt).

Key knobs:

| Parameter | Location | Effect |
|---|---|---|
| `colorTolerance` | `DetectionParams` | ΔE threshold for "different" pixels |
| `morphKernelSize` | `DetectionParams` | Noise removal kernel size |
| `minSpotAreaPx` | `DetectionParams` | Ignore spots smaller than this (px²) |
| Detection FPS | `InspectionViewModel` → `delay(80)` | Adjust analysis rate |

To swap from simple ΔE to CIEDE2000 or another metric, edit only the `deltaE()` function in `ColorDetector.kt`.

---

## Theme

White + Violet (Material You `#7B1FA2` family). All colours are in `res/values/colors.xml`.

---

## Known Limitations

- Results screen shows the **last live frame** as the result image. For a fully annotated result image, draw OpenCV rectangles onto the bitmap before passing to `ResultsActivity`.
- On some devices the `org.opencv:opencv` Maven artefact requires at minimum **arm64-v8a** or **x86_64** ABI — ensure your device or emulator matches.
