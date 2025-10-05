package com.virk.waveradio

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * A lightweight, high-performance real-time audio visualizer.
 *
 * Features:
 * - Renders frequency spectrum as vertical bars
 * - Each bar has a unique hue (full rainbow spread)
 * - Theme support shifts the entire color palette (e.g., Ocean = blue-dominant)
 * - White horizontal "peak hold" bars show maximum recent amplitude
 * - Smooth height interpolation for fluid animation
 * - Fully compatible with RadioController (uses string-based theme names)
 *
 * Design Philosophy:
 * - No heavy effects (no particles, blur, or sensors)
 * - Minimal allocations in onDraw()
 * - Optimized for 60 FPS on mid-range devices
 */
class AudioVisualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // === PAINTS FOR DRAWING ===
    /** Paint for main frequency bars with anti-aliasing for smooth edges */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Paint for white peak indicator bars */
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.FILL
    }

    // === AUDIO & VISUAL STATE ===
    /** Raw FFT byte array (interleaved real/imaginary values) from audio processor */
    private var audioData: ByteArray? = null

    /** Total number of frequency bars to display */
    private var barCount = 16  // Increased for better visualization

    /** Horizontal gap (in pixels) between adjacent bars */
    private var barGap = 2f

    /** Width of each bar (uniform for performance and simplicity) */
    private var barWidths = FloatArray(0)

    /** Precomputed left X-coordinate of each bar for O(1) access during drawing and touch */
    private var barLeftEdges = FloatArray(0)

    /** Smoothed current height of each bar (to reduce visual jitter) */
    private var smoothedHeights = FloatArray(0)

    /** Tracked peak height for each bar (used for peak hold visualization) */
    private var peakHeights = FloatArray(0)

    /** Countdown timer for how many frames to hold the peak before decay begins */
    private var peakHoldTime = IntArray(0)

    // === THEMING SYSTEM ===
    /**
     * Maps theme names (used by RadioController) to base hue offsets (in degrees).
     * This allows the full-spectrum bar colors to be "rotated" into a mood:
     * - Rainbow: full spectrum starting at red (0°)
     * - Ocean: shifted toward blues/cyans (200°)
     * - Sunset: warm oranges/reds (30°)
     * - Forest: greens (120°)
     * - Neon: vibrant purples/pinks (280°)
     * - Fire: intense reds/oranges (0° with different saturation)
     */
    private val themeBaseHues = mapOf(
        "Rainbow" to 0f,
        "Ocean"   to 200f,
        "Sunset"  to 30f,
        "Forest"  to 120f,
        "Neon"    to 280f,
        "Fire"    to 0f
    )

    /** Current base hue offset (in degrees) applied to all bars */
    private var currentBaseHue = 0f // Default: Rainbow

    // === TUNING PARAMETERS ===
    /** Amplifies input audio magnitude for more visible bars */
    private var sensitivity: Float = 1.2f

    /** Smoothing factor for bar height (0 = instant, 1 = frozen). Higher = smoother. */
    private val smoothing = 0.7f

    /** Pixels per frame that peak decays once hold time expires */
    private val peakDecay = 3f

    /** Number of animation frames to hold peak at max before decay */
    private val peakHold = 10

    /** Padding from bottom to prevent clipping and improve aesthetics */
    private val bottomPadding = 8f

    /** Height (in pixels) of the white peak indicator bar */
    private val peakBarHeight = 3f

    // === INITIALIZATION ===
    init {
        // Required for custom drawing (e.g., shadowLayer if added later)
        // Also ensures consistent rendering on all devices
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // Initialize arrays
        resetArrays()
    }

    /**
     * Called when view dimensions change (e.g., rotation, first layout).
     * Recalculates bar layout and resets internal state arrays.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateLayout(w)
        resetArrays()
    }

    /**
     * Computes uniform bar widths and their left-edge positions.
     * Uses simple arithmetic for O(n) layout (fast and predictable).
     */
    private fun calculateLayout(totalWidth: Int) {
        val totalGap = (barCount - 1) * barGap
        val usableWidth = totalWidth - totalGap
        val baseWidth = usableWidth / barCount

        // Initialize bar widths (all equal)
        barWidths = FloatArray(barCount) { baseWidth.toFloat() }

        // Compute cumulative left positions: [0, w+gap, 2*(w+gap), ...]
        barLeftEdges = FloatArray(barCount)
        for (i in 0 until barCount) {
            barLeftEdges[i] = (i * (baseWidth + barGap)).toFloat()
        }
    }

    /**
     * Reinitializes dynamic arrays to match current barCount.
     * Called on size change or manual reset.
     */
    private fun resetArrays() {
        smoothedHeights = FloatArray(barCount)
        peakHeights = FloatArray(barCount)
        peakHoldTime = IntArray(barCount)
    }

    // === PUBLIC API (MUST MATCH RadioController CONTRACT) ===

    /**
     * Feeds new FFT audio data to the visualizer.
     * Triggers a redraw on the next animation frame.
     *
     * @param data Interleaved real/imaginary byte array from FFT processor
     */
    fun updateVisualizer(data: ByteArray) {
        audioData = data
        postInvalidateOnAnimation()
    }

    /**
     * Switches color theme by name (e.g., "Ocean", "Sunset").
     * Updates base hue and triggers immediate redraw.
     *
     * @param themeName Must match a key in [themeBaseHues]
     */
    fun setTheme(themeName: String) {
        currentBaseHue = themeBaseHues[themeName] ?: 0f
        invalidate()
    }

    /**
     * Adjusts audio sensitivity (0.1x to 2.0x).
     * Useful for quiet or loud audio sources.
     */
    fun setSensitivity(level: Float) {
        sensitivity = level.coerceIn(0.1f, 2.0f)
    }

    /**
     * Resets all visual state (heights, peaks) to zero.
     * Useful when switching stations or stopping playback.
     */
    fun reset() {
        resetArrays()
        invalidate()
    }

    /**
     * Returns list of available theme names for UI (e.g., settings screen).
     */
    fun getAvailableThemes(): List<String> = themeBaseHues.keys.toList()

    // === MAIN RENDERING LOOP ===

    /**
     * Draws the entire visualizer on each frame.
     * Flow:
     * 1. Process audio → magnitudes
     * 2. For each bar:
     *    - Compute smoothed height
     *    - Generate unique color based on position + theme
     *    - Draw gradient-filled bar
     *    - Update and draw peak hold bar if applicable
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Early exit if no audio data or zero height/width
        val data = audioData ?: return
        if (width == 0 || height == 0) return

        // Convert raw FFT bytes to normalized magnitudes [0.0, 1.0]
        val magnitudes = processAudio(data)

        // Hue step to distribute full 360° spectrum across all bars
        val hueStep = 360f / barCount

        // Render each bar
        for (i in magnitudes.indices) {
            // Normalize and scale magnitude to screen height
            val mag = magnitudes[i]
            val targetHeight = (mag * sensitivity * height * 0.8f).coerceAtLeast(0f)

            // Smooth height to reduce flicker (exponential moving average)
            smoothedHeights[i] = smoothedHeights[i] * smoothing + targetHeight * (1 - smoothing)
            val barHeight = smoothedHeights[i].coerceAtMost(height.toFloat() - bottomPadding)

            // Skip drawing if bar height is negligible
            if (barHeight < 1f) continue

            // Compute bar boundaries
            val left = barLeftEdges[i]
            val right = left + barWidths[i]
            val bottom = height.toFloat() - bottomPadding
            val top = bottom - barHeight

            // === COLOR GENERATION ===
            // Assign unique hue per bar, offset by current theme
            val hue = (currentBaseHue + i * hueStep) % 360f

            // Special handling for Fire theme - more intense reds/oranges
            val saturation = if (currentBaseHue == 0f && themeBaseHues["Fire"] == 0f) {
                0.9f  // More saturated for fire effect
            } else {
                0.85f
            }

            val value = if (currentBaseHue == 0f && themeBaseHues["Fire"] == 0f) {
                1.0f  // Brighter for fire effect
            } else {
                0.95f
            }

            // Bright top color (high saturation/value)
            val brightColor = Color.HSVToColor(floatArrayOf(hue, saturation, value))
            // Darker bottom color for gradient depth
            val darkColor = Color.HSVToColor(floatArrayOf(hue, saturation * 0.8f, value * 0.6f))

            // Vertical gradient: dark (bottom) → bright (top)
            barPaint.shader = LinearGradient(
                left, bottom, left, top,
                darkColor,
                brightColor,
                Shader.TileMode.CLAMP
            )

            // Draw rounded rectangle for modern look
            val cornerRadius = barWidths[i] * 0.3f
            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, barPaint)

            // === PEAK HOLD LOGIC ===
            if (barHeight > peakHeights[i]) {
                // New peak: update and reset hold timer
                peakHeights[i] = barHeight
                peakHoldTime[i] = peakHold
            } else {
                // Decay peak after hold time expires
                if (peakHoldTime[i] <= 0) {
                    peakHeights[i] = max(0f, peakHeights[i] - peakDecay)
                } else {
                    peakHoldTime[i]--
                }
            }

            // Draw white peak bar ONLY if it's above current bar height
            if (peakHeights[i] > barHeight + peakBarHeight) {
                val peakTop = bottom - peakHeights[i]
                val peakBottom = peakTop + peakBarHeight
                canvas.drawRect(left, peakTop, right, peakBottom, peakPaint)
            }
        }
    }

    /**
     * Converts interleaved FFT byte array (real, imag, real, imag...) into
     * normalized magnitude values [0.0, 1.0].
     *
     * @param data Raw FFT output (must be even-length)
     * @return Float array of magnitudes, length = barCount
     */
    private fun processAudio(data: ByteArray): FloatArray {
        // Safety: FFT data must be even-length (real/imag pairs)
        if (data.size % 2 != 0) return FloatArray(barCount)

        // Only process as many frequency bins as we have bars
        val n = minOf(data.size / 2, barCount)
        val out = FloatArray(barCount) // remaining bars stay 0

        for (i in 0 until n) {
            val re = data[i * 2].toInt()
            val im = data[i * 2 + 1].toInt()
            // Magnitude = sqrt(re² + im²), normalized by max 8-bit value (128)
            val magnitude = sqrt((re * re + im * im).toDouble()).toFloat() / 128f
            // Apply logarithmic scale for better visual distribution
            out[i] = log10((magnitude * 9) + 1)
        }
        return out
    }

    /**
     * Handles touch input. On tap, cycles to next theme.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val themes = themeBaseHues.keys.toList()
        // Find current theme index
        val currentIndex = themes.indexOfFirst { themeBaseHues[it] == currentBaseHue }
        // Cycle to next theme (wrap around)
        val nextIndex = (currentIndex + 1) % themes.size
        setTheme(themes[nextIndex])
        return true
    }
}