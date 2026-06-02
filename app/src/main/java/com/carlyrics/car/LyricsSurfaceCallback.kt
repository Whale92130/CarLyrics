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
import com.carlyrics.lyrics.CurrentLyric
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
    private var animatedTrackKey: String? = null
    private var displayedLyricText: String? = null
    private var previousLyricText: String? = null
    private var lyricTransitionStartedAtElapsedMillis: Long = 0L

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
        resetLyricTransition()
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
                drawProgressBar(canvas, area, track)
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
        val iconWidth = if (track.lyricsSavedOnDevice) {
            DOWNLOAD_ICON_GAP + DOWNLOAD_ICON_SIZE
        } else {
            0f
        }
        val text = ellipsize(label, META_PAINT, (maxWidth - iconWidth).coerceAtLeast(1f))
        val textWidth = META_PAINT.measureText(text)
        val groupWidth = textWidth + iconWidth
        val textLeft = area.exactCenterX() - groupWidth / 2f
        val baseline = area.bottom - FOOTER_BOTTOM_MARGIN - META_PAINT.descent()

        val originalAlign = META_PAINT.textAlign
        META_PAINT.textAlign = Paint.Align.LEFT
        canvas.drawText(text, textLeft, baseline, META_PAINT)
        META_PAINT.textAlign = originalAlign

        if (track.lyricsSavedOnDevice) {
            drawDownloadIcon(canvas, textLeft + textWidth + DOWNLOAD_ICON_GAP, baseline)
        }
    }

    private fun drawProgressBar(canvas: Canvas, area: Rect, track: TrackInfo) {
        val duration = track.durationMillis ?: return
        if (duration <= 0L) return

        val position = track.estimatedPositionMillis(SystemClock.elapsedRealtime()) ?: return
        val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val left = area.left + PROGRESS_HORIZONTAL_MARGIN
        val right = area.right - PROGRESS_HORIZONTAL_MARGIN
        if (right <= left) return

        val y = area.bottom - PROGRESS_BOTTOM_MARGIN
        canvas.drawLine(left, y, right, y, PROGRESS_TRACK_PAINT)
        canvas.drawLine(left, y, left + (right - left) * progress, y, PROGRESS_FILL_PAINT)
    }

    private fun drawDownloadIcon(canvas: Canvas, left: Float, textBaseline: Float) {
        val textTop = textBaseline + META_PAINT.ascent()
        val textBottom = textBaseline + META_PAINT.descent()
        val top = textTop + (textBottom - textTop - DOWNLOAD_ICON_SIZE) / 2f
        val centerX = left + DOWNLOAD_ICON_SIZE / 2f
        val arrowTop = top + DOWNLOAD_ICON_SIZE * 0.12f
        val arrowBottom = top + DOWNLOAD_ICON_SIZE * 0.58f
        val headInset = DOWNLOAD_ICON_SIZE * 0.22f
        val trayTop = top + DOWNLOAD_ICON_SIZE * 0.78f
        val trayLeft = left + DOWNLOAD_ICON_SIZE * 0.22f
        val trayRight = left + DOWNLOAD_ICON_SIZE * 0.78f

        canvas.drawLine(centerX, arrowTop, centerX, arrowBottom, ICON_PAINT)
        canvas.drawLine(centerX, arrowBottom, centerX - headInset, arrowBottom - headInset, ICON_PAINT)
        canvas.drawLine(centerX, arrowBottom, centerX + headInset, arrowBottom - headInset, ICON_PAINT)
        canvas.drawLine(trayLeft, trayTop, trayRight, trayTop, ICON_PAINT)
        canvas.drawLine(trayLeft, trayTop, trayLeft, trayTop - DOWNLOAD_ICON_SIZE * 0.15f, ICON_PAINT)
        canvas.drawLine(trayRight, trayTop, trayRight, trayTop - DOWNLOAD_ICON_SIZE * 0.15f, ICON_PAINT)
    }

    private fun drawCenteredLyrics(canvas: Canvas, area: Rect, track: TrackInfo?) {
        val now = SystemClock.elapsedRealtime()
        val targetText = CurrentLyric.textFor(track, now)
        updateLyricTransition(track?.lookupKey, targetText, now)

        val previousText = previousLyricText
        val transitionProgress = lyricTransitionProgress(now)
        if (previousText != null && transitionProgress < 1f) {
            val eased = easeOutCubic(transitionProgress)
            val previousAlpha = (1f - transitionProgress * PREVIOUS_LYRIC_FADE_MULTIPLIER)
                .coerceIn(0f, 1f)
            val offset = (area.height() * LYRIC_TRANSITION_OFFSET_RATIO)
                .coerceIn(MIN_LYRIC_TRANSITION_OFFSET, MAX_LYRIC_TRANSITION_OFFSET)
            drawCenteredText(
                canvas = canvas,
                area = area,
                track = track,
                text = previousText,
                alpha = previousAlpha,
                verticalOffset = -offset * eased,
                darken = eased * PREVIOUS_LYRIC_DARKEN_WEIGHT
            )
            drawCenteredText(
                canvas = canvas,
                area = area,
                track = track,
                text = targetText,
                alpha = eased,
                verticalOffset = offset * (1f - eased),
                darken = 0f
            )
        } else {
            previousLyricText = null
            drawCenteredText(
                canvas = canvas,
                area = area,
                track = track,
                text = targetText,
                alpha = 1f,
                verticalOffset = 0f,
                darken = 0f
            )
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        area: Rect,
        track: TrackInfo?,
        text: String,
        alpha: Float,
        verticalOffset: Float,
        darken: Float
    ) {
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
        val originalColor = TITLE_PAINT.color
        if (darken > 0f) {
            TITLE_PAINT.shader = null
            TITLE_PAINT.color = blend(
                baseTextColor(),
                Color.BLACK,
                darken.coerceIn(0f, 1f)
            )
        } else {
            TITLE_PAINT.shader = textGradient(track?.albumColors, textArea)
        }
        TITLE_PAINT.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val lineHeight = lineHeight(TITLE_PAINT)
        val totalHeight = lineHeight * layout.size
        var baseline = textArea.exactCenterY() + verticalOffset - totalHeight / 2f - TITLE_PAINT.ascent()

        for (line in layout) {
            canvas.drawText(line, textArea.exactCenterX(), baseline, TITLE_PAINT)
            baseline += lineHeight
        }
        TITLE_PAINT.alpha = 255
        TITLE_PAINT.color = originalColor
        TITLE_PAINT.shader = null
    }

    private fun updateLyricTransition(trackKey: String?, targetText: String, nowElapsedMillis: Long) {
        if (trackKey != animatedTrackKey) {
            animatedTrackKey = trackKey
            displayedLyricText = targetText
            previousLyricText = null
            lyricTransitionStartedAtElapsedMillis = 0L
            return
        }

        val currentText = displayedLyricText
        if (currentText == null) {
            displayedLyricText = targetText
            return
        }
        if (currentText == targetText) return

        previousLyricText = currentText
        displayedLyricText = targetText
        lyricTransitionStartedAtElapsedMillis = nowElapsedMillis
    }

    private fun resetLyricTransition() {
        animatedTrackKey = null
        displayedLyricText = null
        previousLyricText = null
        lyricTransitionStartedAtElapsedMillis = 0L
    }

    private fun lyricTransitionProgress(nowElapsedMillis: Long): Float {
        val previousText = previousLyricText ?: return 1f
        if (previousText.isEmpty()) return 1f
        val elapsed = nowElapsedMillis - lyricTransitionStartedAtElapsedMillis
        return (elapsed.toFloat() / LYRIC_TRANSITION_MILLIS.toFloat()).coerceIn(0f, 1f)
    }

    private fun isLyricTransitionActive(nowElapsedMillis: Long): Boolean =
        previousLyricText != null && lyricTransitionProgress(nowElapsedMillis) < 1f

    private fun easeOutCubic(value: Float): Float {
        val inverse = 1f - value.coerceIn(0f, 1f)
        return 1f - inverse * inverse * inverse
    }

    private fun updateTicker() {
        mainHandler.removeCallbacks(ticker)

        val now = SystemClock.elapsedRealtime()
        if (isLyricTransitionActive(now)) {
            mainHandler.postDelayed(ticker, TICK_MILLIS)
            return
        }

        val track = MediaState.current ?: return
        if (track.playbackSpeed <= 0f) return

        val lyrics = track.lyrics as? LyricsState.Found
        val shouldTickLyrics = lyrics?.synced == true
        val shouldTickProgress = track.durationMillis != null && track.playbackPositionMillis != null
        if (!shouldTickLyrics && !shouldTickProgress) return

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
            ICON_PAINT.apply {
                color = Color.BLACK
                clearShadowLayer()
            }
            PROGRESS_TRACK_PAINT.apply {
                color = LIGHT_PROGRESS_TRACK_COLOR
                clearShadowLayer()
            }
            PROGRESS_FILL_PAINT.apply {
                color = Color.BLACK
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
            ICON_PAINT.apply {
                color = Color.WHITE
                setShadowLayer(6f, 0f, 2f, Color.BLACK)
            }
            PROGRESS_TRACK_PAINT.apply {
                color = DARK_PROGRESS_TRACK_COLOR
                clearShadowLayer()
            }
            PROGRESS_FILL_PAINT.apply {
                color = Color.WHITE
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
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
        private const val TICK_MILLIS = 100L
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
        private const val LYRIC_TRANSITION_MILLIS = 700L
        private const val PREVIOUS_LYRIC_FADE_MULTIPLIER = 2.4f
        private const val PREVIOUS_LYRIC_DARKEN_WEIGHT = 0.72f
        private const val LYRIC_TRANSITION_OFFSET_RATIO = 0.035f
        private const val MIN_LYRIC_TRANSITION_OFFSET = 18f
        private const val MAX_LYRIC_TRANSITION_OFFSET = 34f
        private const val FOOTER_BOTTOM_MARGIN = 18f
        private const val FOOTER_HORIZONTAL_MARGIN = 48f
        private const val DOWNLOAD_ICON_SIZE = 18f
        private const val DOWNLOAD_ICON_GAP = 8f
        private const val DOWNLOAD_ICON_STROKE = 2.6f
        private const val PROGRESS_HORIZONTAL_MARGIN = 64f
        private const val PROGRESS_BOTTOM_MARGIN = 7f
        private const val PROGRESS_TRACK_STROKE = 5f
        private const val PROGRESS_FILL_STROKE = 5f
        private const val DARK_PROGRESS_TRACK_COLOR = 0xFF333333.toInt()
        private const val LIGHT_PROGRESS_TRACK_COLOR = 0xFFE0E0E0.toInt()
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

        private val ICON_PAINT = Paint().apply {
            color = Color.WHITE
            strokeWidth = DOWNLOAD_ICON_STROKE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }

        private val PROGRESS_TRACK_PAINT = Paint().apply {
            color = DARK_PROGRESS_TRACK_COLOR
            strokeWidth = PROGRESS_TRACK_STROKE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        private val PROGRESS_FILL_PAINT = Paint().apply {
            color = Color.WHITE
            strokeWidth = PROGRESS_FILL_STROKE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }

    }
}
