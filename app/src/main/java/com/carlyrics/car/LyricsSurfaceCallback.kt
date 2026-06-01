package com.carlyrics.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.carlyrics.lyrics.LyricsState
import com.carlyrics.media.MediaState
import com.carlyrics.media.TrackInfo
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the current LRCLIB lyric line onto the
 * [NavigationTemplate][androidx.car.app.navigation.model.NavigationTemplate] map surface.
 *
 * The text is wrapped and dynamically sized so the current lyric line stays large
 * without being clipped by the surface edges.
 */
class LyricsSurfaceCallback : SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            updateTicker()
        }
    }
    private val trackListener = MediaState.Listener {
        mainHandler.post {
            render()
            updateTicker()
        }
    }
    private val settingsListener = LyricsDisplaySettings.Listener {
        mainHandler.post {
            render()
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.d(TAG, "onSurfaceAvailable ${surfaceContainer.width}x${surfaceContainer.height}")
        this.surfaceContainer = surfaceContainer
        MediaState.observe(trackListener)
        LyricsDisplaySettings.observe(settingsListener)
        render()
        updateTicker()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        render()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = stableArea
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        MediaState.stopObserving(trackListener)
        LyricsDisplaySettings.stopObserving(settingsListener)
        mainHandler.removeCallbacks(ticker)
        this.surfaceContainer = null
        this.visibleArea = null
        this.stableArea = null
    }

    private fun render() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return

        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "lockCanvas failed", e)
            return
        } catch (e: IllegalStateException) {
            Log.w(TAG, "lockCanvas failed", e)
            return
        }

        try {
            val track = MediaState.current
            val area = drawableArea(canvas, container)
            configurePaints()
            canvas.drawColor(backgroundColor())
            drawCenteredLyrics(canvas, area, track)
            if (track != null) {
                drawSongFooter(canvas, area, track)
            }
            Log.d(
                TAG,
                "render canvas=${canvas.width}x${canvas.height} area=$area track=$track"
            )
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawableArea(canvas: Canvas, container: SurfaceContainer): Rect {
        val visible = visibleArea
        val base = if (visible != null && visible.width() > 0 && visible.height() > 0) {
            Rect(visible)
        } else if (canvas.width > 0 && canvas.height > 0) {
            Rect(0, 0, canvas.width, canvas.height)
        } else {
            val clip = Rect()
            if (canvas.getClipBounds(clip) && clip.width() > 0 && clip.height() > 0) {
                clip
            } else {
                Rect(0, 0, container.width.coerceAtLeast(1), container.height.coerceAtLeast(1))
            }
        }

        val stable = stableArea
        if (stable != null && stable.width() > 0 && stable.height() > 0) {
            val intersected = Rect(base)
            if (intersected.intersect(stable)) return intersected
        }

        return base
    }

    private fun drawSongFooter(canvas: Canvas, area: Rect, track: TrackInfo) {
        val maxWidth = (area.width() - FOOTER_HORIZONTAL_MARGIN * 2f).coerceAtLeast(1f)
        val label = listOfNotNull(track.title, track.artist)
            .joinToString(" - ")
        val text = ellipsize(label, META_PAINT, maxWidth)
        val baseline = area.bottom - FOOTER_BOTTOM_MARGIN - META_PAINT.descent()
        canvas.drawText(text, area.exactCenterX(), baseline, META_PAINT)
    }

    private fun drawCenteredLyrics(canvas: Canvas, area: Rect, track: TrackInfo?) {
        val text = if (track == null) {
            "No song playing"
        } else {
            lyricDisplay(track)
        }

        val textArea = Rect(area)
        val horizontalMargin = (area.width() * HORIZONTAL_MARGIN_RATIO)
            .coerceIn(MIN_HORIZONTAL_MARGIN, MAX_HORIZONTAL_MARGIN)
            .roundToInt()
        val verticalMargin = (area.height() * VERTICAL_MARGIN_RATIO)
            .coerceIn(MIN_VERTICAL_MARGIN, MAX_VERTICAL_MARGIN)
            .roundToInt()
        textArea.inset(horizontalMargin, verticalMargin)
        if (textArea.width() <= 0 || textArea.height() <= 0) return

        val layout = fitText(text, TITLE_PAINT, textArea.width().toFloat(), textArea.height().toFloat())
        TITLE_PAINT.shader = textGradient(track?.albumColors, textArea)
        val lineHeight = lineHeight(TITLE_PAINT)
        val totalHeight = lineHeight * layout.size
        var baseline = textArea.exactCenterY() - totalHeight / 2f - TITLE_PAINT.ascent()

        for (line in layout) {
            canvas.drawText(line, textArea.exactCenterX(), baseline, TITLE_PAINT)
            baseline += lineHeight
        }
        TITLE_PAINT.shader = null
    }

    private fun lyricDisplay(track: TrackInfo): String =
        when (val lyrics = track.lyrics) {
            LyricsState.Loading -> "Finding lyrics..."
            LyricsState.Instrumental -> "Instrumental"
            LyricsState.NotFound -> "Lyrics not found"
            is LyricsState.Error -> "Lyrics unavailable"
            is LyricsState.Found -> lyricDisplay(track, lyrics)
        }

    private fun lyricDisplay(track: TrackInfo, lyrics: LyricsState.Found): String {
        if (lyrics.lines.isEmpty()) return "Lyrics not found"

        val index = currentLyricIndex(track, lyrics)
        return lyrics.lines[index].text
    }

    private fun currentLyricIndex(track: TrackInfo, lyrics: LyricsState.Found): Int {
        val position = track.estimatedPositionMillis(SystemClock.elapsedRealtime()) ?: return 0

        if (lyrics.synced) {
            return lyrics.lines
                .indexOfLast { line -> line.startMillis?.let { it <= position } == true }
                .coerceAtLeast(0)
        }

        val duration = track.durationMillis ?: return 0
        if (duration <= 0L) return 0
        val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return (progress * lyrics.lines.lastIndex).roundToInt().coerceIn(0, lyrics.lines.lastIndex)
    }

    private fun updateTicker() {
        mainHandler.removeCallbacks(ticker)

        val track = MediaState.current ?: return
        if (track.playbackSpeed <= 0f) return

        val lyrics = track.lyrics as? LyricsState.Found
        val shouldTickLyrics = lyrics?.synced == true
        if (!shouldTickLyrics) return

        mainHandler.postDelayed(ticker, TICK_MILLIS)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text

        val available = maxWidth - paint.measureText(ELLIPSIS)
        if (available <= 0f) return ELLIPSIS

        val count = paint.breakText(text, true, available, null)
        return text.take(count).trimEnd() + ELLIPSIS
    }

    private fun fitText(
        text: String,
        paint: Paint,
        maxWidth: Float,
        maxHeight: Float
    ): List<String> {
        var size = MAX_TITLE_TEXT_SIZE
        while (size > MIN_TITLE_TEXT_SIZE) {
            paint.textSize = size
            val lines = wrapText(text, paint, maxWidth)
            if (lineHeight(paint) * lines.size <= maxHeight) return lines
            size -= TEXT_SIZE_STEP
        }

        paint.textSize = MIN_TITLE_TEXT_SIZE
        return wrapText(text, paint, maxWidth)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf("")

        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotBlank()) lines += current
                if (paint.measureText(word) <= maxWidth) {
                    current = word
                } else {
                    val broken = breakLongWord(word, paint, maxWidth)
                    lines.addAll(broken.dropLast(1))
                    current = broken.lastOrNull().orEmpty()
                }
            }
        }

        if (current.isNotBlank()) lines += current
        return lines
    }

    private fun breakLongWord(word: String, paint: Paint, maxWidth: Float): List<String> {
        val parts = mutableListOf<String>()
        var remaining = word
        while (remaining.isNotEmpty()) {
            val count = max(1, paint.breakText(remaining, true, maxWidth, null))
            parts += remaining.take(count)
            remaining = remaining.drop(count)
        }
        return parts
    }

    private fun lineHeight(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return (metrics.descent - metrics.ascent) * LINE_SPACING_MULTIPLIER
    }

    private fun backgroundColor(): Int =
        if (LyricsDisplaySettings.lightMode) LIGHT_BACKGROUND_COLOR else DARK_BACKGROUND_COLOR

    private fun configurePaints() {
        if (LyricsDisplaySettings.lightMode) {
            TITLE_PAINT.apply {
                color = Color.BLACK
                shader = null
                clearShadowLayer()
            }
            META_PAINT.apply {
                color = Color.BLACK
                shader = null
                clearShadowLayer()
            }
        } else {
            TITLE_PAINT.apply {
                color = Color.WHITE
                shader = null
                setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }
            META_PAINT.apply {
                color = Color.WHITE
                shader = null
                setShadowLayer(6f, 0f, 2f, Color.BLACK)
            }
        }
    }

    private fun textGradient(colors: List<Int>?, area: Rect): Shader? {
        val tint = colors
            ?.takeIf { it.isNotEmpty() }
            ?.first()
            ?.let { subtleTextColor(it) }
            ?: return null

        val base = baseTextColor()
        val radius = area.width().coerceAtLeast(area.height()) * 1.15f

        return RadialGradient(
            area.exactCenterX(),
            area.exactCenterY(),
            radius,
            intArrayOf(tint, base, tint),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun subtleTextColor(color: Int): Int =
        blend(baseTextColor(), color, ALBUM_TEXT_TINT_WEIGHT)

    private fun baseTextColor(): Int =
        if (LyricsDisplaySettings.lightMode) Color.BLACK else Color.WHITE

    private fun blend(base: Int, accent: Int, accentWeight: Float): Int {
        val baseWeight = 1f - accentWeight
        return Color.rgb(
            (Color.red(base) * baseWeight + Color.red(accent) * accentWeight)
                .roundToInt()
                .coerceIn(0, 255),
            (Color.green(base) * baseWeight + Color.green(accent) * accentWeight)
                .roundToInt()
                .coerceIn(0, 255),
            (Color.blue(base) * baseWeight + Color.blue(accent) * accentWeight)
                .roundToInt()
                .coerceIn(0, 255)
        )
    }

    companion object {
        private const val TAG = "LyricsSurfaceCallback"
        private const val DARK_BACKGROUND_COLOR = 0xFF0A0A0A.toInt()
        private const val LIGHT_BACKGROUND_COLOR = Color.WHITE
        private const val TICK_MILLIS = 350L
        private const val HORIZONTAL_MARGIN_RATIO = 0.08f
        private const val VERTICAL_MARGIN_RATIO = 0.12f
        private const val MIN_HORIZONTAL_MARGIN = 36f
        private const val MAX_HORIZONTAL_MARGIN = 88f
        private const val MIN_VERTICAL_MARGIN = 28f
        private const val MAX_VERTICAL_MARGIN = 80f
        private const val MAX_TITLE_TEXT_SIZE = 96f
        private const val MIN_TITLE_TEXT_SIZE = 34f
        private const val TEXT_SIZE_STEP = 2f
        private const val LINE_SPACING_MULTIPLIER = 1.08f
        private const val FOOTER_BOTTOM_MARGIN = 18f
        private const val FOOTER_HORIZONTAL_MARGIN = 48f
        private const val ELLIPSIS = "..."
        private const val ALBUM_TEXT_TINT_WEIGHT = 0.50f

        private val TITLE_PAINT = Paint().apply {
            color = Color.WHITE
            textSize = MAX_TITLE_TEXT_SIZE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }

        private val META_PAINT = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }

    }
}
