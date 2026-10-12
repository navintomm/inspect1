package com.colorinspector.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.colorinspector.app.R
import com.colorinspector.app.databinding.DialogColorCustomBinding
import com.colorinspector.app.databinding.DialogColorPickerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Microsoft Word-style colour-picker bottom sheet.
 *
 * Shows:
 *   • A selected-colour preview strip (swatch + name + HEX/RGB)
 *   • Theme Colors grid (10 columns × 6 rows of themed shades/tints)
 *   • Standard Colors strip (10 common colours)
 *   • "More Colors…" → custom HEX / RGB entry sub-dialog
 *   • Cancel  /  Use Colour  buttons
 *
 * The chosen colour is delivered to [onColorPicked] ONLY when the user taps
 * "Use Colour", so it can safely override the ViewModel background reference.
 */
class ColorPickerDialog : BottomSheetDialogFragment() {

    // ── Companion / factory ───────────────────────────────────────────────────
    companion object {
        private const val TAG = "ColorPickerDialog"
        private const val ARG_INITIAL = "initial_color"

        fun show(context: Context, initialColor: Int, onColorPicked: (Int) -> Unit) {
            // BottomSheetDialogFragment requires a FragmentManager; obtain it from context.
            val host = context as? androidx.fragment.app.FragmentActivity
                ?: error("ColorPickerDialog.show() requires a FragmentActivity context")
            val frag = ColorPickerDialog().apply {
                arguments = Bundle().apply { putInt(ARG_INITIAL, initialColor) }
                this.onColorPicked = onColorPicked
            }
            frag.show(host.supportFragmentManager, TAG)
        }

        // ─── Theme Colors  (10 columns × 6 rows) ──────────────────────────────
        // Columns: White/Neutral, Near-black, Violet-row, Indigo, Blue, Teal,
        //          Green, Yellow, Orange, Red.  Rows: base → progressively darker.
        private val THEME_COLORS: List<Int> = listOf(
            // Row 0 – very light / base
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE1BEE7.toInt(), 0xFFC5CAE9.toInt(),
            0xFFBBDEFB.toInt(), 0xFFB2EBF2.toInt(), 0xFFC8E6C9.toInt(), 0xFFFFF9C4.toInt(),
            0xFFFFE0B2.toInt(), 0xFFFFCDD2.toInt(),
            // Row 1 – light
            0xFFF5F5F5.toInt(), 0xFF212121.toInt(), 0xFFCE93D8.toInt(), 0xFF9FA8DA.toInt(),
            0xFF90CAF9.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFF59D.toInt(),
            0xFFFFCC80.toInt(), 0xFFEF9A9A.toInt(),
            // Row 2 – medium-light
            0xFFEEEEEE.toInt(), 0xFF424242.toInt(), 0xFFBA68C8.toInt(), 0xFF7986CB.toInt(),
            0xFF64B5F6.toInt(), 0xFF4DD0E1.toInt(), 0xFF81C784.toInt(), 0xFFFFF176.toInt(),
            0xFFFFB74D.toInt(), 0xFFE57373.toInt(),
            // Row 3 – medium
            0xFFBDBDBD.toInt(), 0xFF616161.toInt(), 0xFFAB47BC.toInt(), 0xFF5C6BC0.toInt(),
            0xFF42A5F5.toInt(), 0xFF26C6DA.toInt(), 0xFF66BB6A.toInt(), 0xFFFFEE58.toInt(),
            0xFFFFA726.toInt(), 0xFFEF5350.toInt(),
            // Row 4 – medium-dark
            0xFF9E9E9E.toInt(), 0xFF757575.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt(),
            0xFF1E88E5.toInt(), 0xFF00ACC1.toInt(), 0xFF43A047.toInt(), 0xFFFFD600.toInt(),
            0xFFFB8C00.toInt(), 0xFFE53935.toInt(),
            // Row 5 – dark
            0xFF757575.toInt(), 0xFF9E9E9E.toInt(), 0xFF6A1B9A.toInt(), 0xFF283593.toInt(),
            0xFF1565C0.toInt(), 0xFF00838F.toInt(), 0xFF2E7D32.toInt(), 0xFFF9A825.toInt(),
            0xFFE65100.toInt(), 0xFFB71C1C.toInt()
        )

        // ─── Standard Colors (single row of 10) ───────────────────────────────
        private val STANDARD_COLORS: List<Int> = listOf(
            0xFFFFFFFF.toInt(), // White
            0xFF000000.toInt(), // Black
            0xFF9E9E9E.toInt(), // Grey
            0xFFF44336.toInt(), // Red
            0xFFFF9800.toInt(), // Orange
            0xFFFFEB3B.toInt(), // Yellow
            0xFF4CAF50.toInt(), // Green
            0xFF2196F3.toInt(), // Blue
            0xFF9C27B0.toInt(), // Purple
            0xFF795548.toInt()  // Brown
        )

        // ─── Colour naming ────────────────────────────────────────────────────
        private val NAMED: Map<Int, String> = mapOf(
            0xFFFFFFFF.toInt() to "White",
            0xFF000000.toInt() to "Black",
            0xFF9E9E9E.toInt() to "Grey",
            0xFFBDBDBD.toInt() to "Light Grey",
            0xFF616161.toInt() to "Dark Grey",
            0xFF757575.toInt() to "Grey 600",
            0xFFF44336.toInt() to "Red",
            0xFFE53935.toInt() to "Red 600",
            0xFFB71C1C.toInt() to "Dark Red",
            0xFFFF9800.toInt() to "Orange",
            0xFFFB8C00.toInt() to "Orange 600",
            0xFFE65100.toInt() to "Deep Orange",
            0xFFFFEB3B.toInt() to "Yellow",
            0xFFFFD600.toInt() to "Yellow 600",
            0xFF4CAF50.toInt() to "Green",
            0xFF43A047.toInt() to "Green 600",
            0xFF2E7D32.toInt() to "Dark Green",
            0xFF2196F3.toInt() to "Blue",
            0xFF1E88E5.toInt() to "Blue 600",
            0xFF1565C0.toInt() to "Dark Blue",
            0xFF9C27B0.toInt() to "Purple",
            0xFF8E24AA.toInt() to "Purple 600",
            0xFF6A1B9A.toInt() to "Dark Purple",
            0xFF795548.toInt() to "Brown",
            0xFFE1BEE7.toInt() to "Lavender",
            0xFFCE93D8.toInt() to "Light Violet",
            0xFFBA68C8.toInt() to "Violet",
            0xFFAB47BC.toInt() to "Violet 400"
        )

        fun colorName(color: Int): String {
            NAMED[color]?.let { return it }
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
            if (max < 40)  return "Black"
            if (min > 215) return "White"
            if (delta < 25) return "Grey"
            val hue = when (max) {
                r    -> ((60f * ((g - b).toFloat() / delta)) + 360f) % 360f
                g    -> 60f * ((b - r).toFloat() / delta) + 120f
                else -> 60f * ((r - g).toFloat() / delta) + 240f
            }
            return when {
                hue < 20  -> "Red"
                hue < 45  -> "Orange"
                hue < 75  -> "Yellow"
                hue < 155 -> "Green"
                hue < 195 -> "Cyan"
                hue < 260 -> "Blue"
                hue < 320 -> "Violet"
                else      -> "Red"
            }
        }

        private fun hexString(color: Int) =
            "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))

        private fun rgbString(color: Int) =
            "RGB(${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)})"
    }

    // ─── State ────────────────────────────────────────────────────────────────
    var onColorPicked: ((Int) -> Unit)? = null
    private var pendingColor: Int = Color.WHITE

    private var _binding: DialogColorPickerBinding? = null
    private val binding get() = _binding!!

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.let {
                val behaviour = BottomSheetBehavior.from(it)
                behaviour.state = BottomSheetBehavior.STATE_EXPANDED
                behaviour.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogColorPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingColor = arguments?.getInt(ARG_INITIAL, Color.WHITE) ?: Color.WHITE

        setupThemeGrid()
        setupStandardGrid()
        updatePreview(pendingColor)

        binding.tvMoreColors.setOnClickListener { showCustomColorDialog() }

        binding.btnCancelColor.setOnClickListener { dismiss() }

        binding.btnUseColor.setOnClickListener {
            onColorPicked?.invoke(pendingColor)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── Theme grid ───────────────────────────────────────────────────────────
    private fun setupThemeGrid() {
        val grid = binding.themeColorGrid
        grid.colors  = THEME_COLORS
        grid.columns = 10

        // Pre-select if initial colour matches
        val initIdx = THEME_COLORS.indexOf(pendingColor)
        if (initIdx >= 0) grid.selectedIndex = initIdx

        grid.onColorTapped = { color, _ ->
            pendingColor = color
            // Deselect standard grid
            binding.standardColorGrid.selectedIndex = -1
            updatePreview(color)
        }
    }

    // ─── Standard grid ────────────────────────────────────────────────────────
    private fun setupStandardGrid() {
        val grid = binding.standardColorGrid
        grid.colors  = STANDARD_COLORS
        grid.columns = 10

        val initIdx = STANDARD_COLORS.indexOf(pendingColor)
        if (initIdx >= 0) grid.selectedIndex = initIdx

        grid.onColorTapped = { color, _ ->
            pendingColor = color
            binding.themeColorGrid.selectedIndex = -1
            updatePreview(color)
        }
    }

    // ─── Preview strip ────────────────────────────────────────────────────────
    private fun updatePreview(color: Int) {
        binding.previewSwatch.setBackgroundColor(color)
        binding.tvColorName.text = colorName(color)
        binding.tvColorHex.text  = "${hexString(color)}   ${rgbString(color)}"
    }

    // ─── More Colors sub-dialog ───────────────────────────────────────────────
    private fun showCustomColorDialog() {
        val ctx = requireContext()
        val customBinding = DialogColorCustomBinding.inflate(LayoutInflater.from(ctx))

        // Initialise RGB sliders from pendingColor
        var r = Color.red(pendingColor)
        var g = Color.green(pendingColor)
        var b = Color.blue(pendingColor)

        fun updateCustomPreview() {
            val c = Color.rgb(r, g, b)
            customBinding.customPreviewSwatch.setBackgroundColor(c)
            customBinding.tvRedVal.text   = r.toString()
            customBinding.tvGreenVal.text = g.toString()
            customBinding.tvBlueVal.text  = b.toString()
            customBinding.etHex.setText("#%02X%02X%02X".format(r, g, b))
        }

        customBinding.seekRed.progress   = r
        customBinding.seekGreen.progress = g
        customBinding.seekBlue.progress  = b
        updateCustomPreview()

        // Slider listeners
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (sb.id) {
                    customBinding.seekRed.id   -> r = p
                    customBinding.seekGreen.id -> g = p
                    customBinding.seekBlue.id  -> b = p
                }
                updateCustomPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        customBinding.seekRed.setOnSeekBarChangeListener(seekListener)
        customBinding.seekGreen.setOnSeekBarChangeListener(seekListener)
        customBinding.seekBlue.setOnSeekBarChangeListener(seekListener)

        // HEX field: sync → sliders on valid 7-char input
        var updatingHex = false
        customBinding.etHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                val text = s?.toString() ?: return
                if (text.length == 7 && text.startsWith("#")) {
                    runCatching {
                        val parsed = Color.parseColor(text)
                        updatingHex = true
                        r = Color.red(parsed);   customBinding.seekRed.progress   = r
                        g = Color.green(parsed); customBinding.seekGreen.progress = g
                        b = Color.blue(parsed);  customBinding.seekBlue.progress  = b
                        customBinding.customPreviewSwatch.setBackgroundColor(parsed)
                        customBinding.tvRedVal.text   = r.toString()
                        customBinding.tvGreenVal.text = g.toString()
                        customBinding.tvBlueVal.text  = b.toString()
                        updatingHex = false
                    }
                }
            }
        })

        AlertDialog.Builder(ctx)
            .setTitle("Custom Colour")
            .setView(customBinding.root)
            .setPositiveButton("Use This Colour") { _, _ ->
                val chosen = Color.rgb(r, g, b)
                pendingColor = chosen
                // Clear any grid selection and update main preview
                binding.themeColorGrid.selectedIndex    = -1
                binding.standardColorGrid.selectedIndex = -1
                updatePreview(chosen)
            }
            .setNegativeButton("Back", null)
            .show()
    }
}
