package com.carlyrics.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.carlyrics.lyrics.CurrentLyric
import com.carlyrics.lyrics.LyricsState
import com.carlyrics.media.MediaControls
import com.carlyrics.media.MediaState
import com.carlyrics.media.TrackInfo
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private var lyricTapBounds: RectF? = null
    private val transportButtons = mutableListOf<TransportButton>()

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
        LyricsDisplaySettings.setMeasuredSurfaceSize(
            surfaceContainer.width,
            surfaceContainer.height
        )
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
        lyricTapBounds = null
        transportButtons.clear()
        resetLyricTransition()
    }

    override fun onClick(x: Float, y: Float) {
        val action = transportButtons.firstOrNull { button ->
            button.bounds.contains(x, y)
        }?.action

        if (action != null) {
            when (action) {
                TransportAction.Previous -> MediaControls.skipToPrevious()
                TransportAction.PlayPause -> MediaControls.togglePlayPause()
                TransportAction.Next -> MediaControls.skipToNext()
            }
            render()
            return
        }

        if (lyricTapBounds?.contains(x, y) == true) {
            LyricsDisplaySettings.reset()
            render()
        }
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
                if (shouldShowTransportControls()) {
                    drawTransportControls(canvas, area)
                } else {
                    transportButtons.clear()
                }
                drawSongFooter(canvas, area, track)
                drawProgressBar(canvas, area, track)
            } else {
                transportButtons.clear()
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
        META_PAINT.textSize = footerTextSize(area)
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

    private fun footerTextSize(area: Rect): Float =
        (area.height().coerceAtLeast(1) *
            FOOTER_TEXT_SIZE_HEIGHT_RATIO *
            footerTextAspectScale(area) *
            FOOTER_TEXT_SIZE_MULTIPLIER)
            .coerceAtLeast(MIN_FOOTER_TEXT_SIZE)

    private fun footerTextAspectScale(area: Rect): Float {
        val height = area.height()
        if (height <= 0) return 1f
        val aspect = area.width().toFloat() / height.toFloat()
        return sqrt(aspect / FOOTER_TEXT_REFERENCE_ASPECT_RATIO)
            .coerceIn(MIN_FOOTER_TEXT_ASPECT_SCALE, MAX_FOOTER_TEXT_ASPECT_SCALE)
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

    private fun drawTransportControls(canvas: Canvas, area: Rect) {
        val buttons = transportButtonLayout(area)
        transportButtons.clear()
        transportButtons.addAll(buttons)

        configureTransportPaints()
        for (button in buttons) {
            val radius = button.bounds.width() * TRANSPORT_BUTTON_RADIUS_RATIO
            canvas.drawRoundRect(button.bounds, radius, radius, TRANSPORT_BUTTON_PAINT)
            canvas.drawRoundRect(button.bounds, radius, radius, TRANSPORT_BUTTON_STROKE_PAINT)
            when (button.action) {
                TransportAction.Previous -> drawPreviousIcon(canvas, button.bounds)
                TransportAction.PlayPause -> drawPlayPauseIcon(canvas, button.bounds)
                TransportAction.Next -> drawNextIcon(canvas, button.bounds)
            }
        }
    }

    private fun transportButtonLayout(area: Rect): List<TransportButton> {
        val size = transportButtonSize(area)
        val gap = size * TRANSPORT_BUTTON_GAP_RATIO
        val left = area.left + (area.width() * TRANSPORT_COLUMN_LEFT_RATIO)
            .coerceIn(MIN_TRANSPORT_COLUMN_LEFT, MAX_TRANSPORT_COLUMN_LEFT)
        val top = area.exactCenterY() - (size * 3f + gap * 2f) / 2f

        return listOf(
            TransportButton(
                TransportAction.Previous,
                RectF(left, top, left + size, top + size)
            ),
            TransportButton(
                TransportAction.PlayPause,
                RectF(left, top + size + gap, left + size, top + size * 2f + gap)
            ),
            TransportButton(
                TransportAction.Next,
                RectF(left, top + size * 2f + gap * 2f, left + size, top + size * 3f + gap * 2f)
            )
        )
    }

    private fun transportButtonSize(area: Rect): Float =
        (area.height() * TRANSPORT_BUTTON_HEIGHT_RATIO)
            .coerceIn(MIN_TRANSPORT_BUTTON_SIZE, MAX_TRANSPORT_BUTTON_SIZE)

    private fun transportControlsReservedWidth(area: Rect): Float {
        val size = transportButtonSize(area)
        val left = (area.width() * TRANSPORT_COLUMN_LEFT_RATIO)
            .coerceIn(MIN_TRANSPORT_COLUMN_LEFT, MAX_TRANSPORT_COLUMN_LEFT)
        return left + size + TRANSPORT_TEXT_CLEARANCE
    }

    private fun drawPreviousIcon(canvas: Canvas, bounds: RectF) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val w = bounds.width()
        val barX = cx - w * 0.20f
        val triangleLeft = cx + w * 0.18f
        val triangleRight = cx - w * 0.08f
        val top = cy - w * 0.18f
        val bottom = cy + w * 0.18f

        canvas.drawLine(barX, top, barX, bottom, TRANSPORT_ICON_STROKE_PAINT)
        val path = Path().apply {
            moveTo(triangleLeft, top)
            lineTo(triangleRight, cy)
            lineTo(triangleLeft, bottom)
            close()
        }
        canvas.drawPath(path, TRANSPORT_ICON_FILL_PAINT)
    }

    private fun drawNextIcon(canvas: Canvas, bounds: RectF) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val w = bounds.width()
        val barX = cx + w * 0.20f
        val triangleLeft = cx - w * 0.18f
        val triangleRight = cx + w * 0.08f
        val top = cy - w * 0.18f
        val bottom = cy + w * 0.18f

        val path = Path().apply {
            moveTo(triangleLeft, top)
            lineTo(triangleRight, cy)
            lineTo(triangleLeft, bottom)
            close()
        }
        canvas.drawPath(path, TRANSPORT_ICON_FILL_PAINT)
        canvas.drawLine(barX, top, barX, bottom, TRANSPORT_ICON_STROKE_PAINT)
    }

    private fun drawPlayPauseIcon(canvas: Canvas, bounds: RectF) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val w = bounds.width()
        if (MediaControls.isPlaying()) {
            val barWidth = w * 0.08f
            val barHeight = w * 0.34f
            val gap = w * 0.09f
            canvas.drawRoundRect(
                RectF(cx - gap - barWidth, cy - barHeight / 2f, cx - gap, cy + barHeight / 2f),
                barWidth / 2f,
                barWidth / 2f,
                TRANSPORT_ICON_FILL_PAINT
            )
            canvas.drawRoundRect(
                RectF(cx + gap, cy - barHeight / 2f, cx + gap + barWidth, cy + barHeight / 2f),
                barWidth / 2f,
                barWidth / 2f,
                TRANSPORT_ICON_FILL_PAINT
            )
        } else {
            val path = Path().apply {
                moveTo(cx - w * 0.12f, cy - w * 0.20f)
                lineTo(cx - w * 0.12f, cy + w * 0.20f)
                lineTo(cx + w * 0.20f, cy)
                close()
            }
            canvas.drawPath(path, TRANSPORT_ICON_FILL_PAINT)
        }
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
        lyricTapBounds = null
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
                darken = eased * PREVIOUS_LYRIC_DARKEN_WEIGHT,
                recordTapBounds = false
            )
            drawCenteredText(
                canvas = canvas,
                area = area,
                track = track,
                text = targetText,
                alpha = eased,
                verticalOffset = offset * (1f - eased),
                darken = 0f,
                recordTapBounds = true
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
                darken = 0f,
                recordTapBounds = true
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
        darken: Float,
        recordTapBounds: Boolean
    ) {
        val textArea = Rect(area)
        val horizontalMargin = (area.width() * HORIZONTAL_MARGIN_RATIO)
            .coerceIn(MIN_HORIZONTAL_MARGIN, MAX_HORIZONTAL_MARGIN)
            .roundToInt()
        val verticalMargin = (area.height() * VERTICAL_MARGIN_RATIO)
            .coerceIn(MIN_VERTICAL_MARGIN, MAX_VERTICAL_MARGIN)
            .roundToInt()
        textArea.inset(horizontalMargin, verticalMargin)
        if (track != null && shouldShowTransportControls()) {
            val controlSafeLeft = area.left + transportControlsReservedWidth(area).roundToInt()
            textArea.left = max(textArea.left, controlSafeLeft)
        }
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
        val firstBaseline = textArea.exactCenterY() + verticalOffset -
            totalHeight / 2f -
            TITLE_PAINT.ascent()

        if (recordTapBounds) {
            lyricTapBounds = lyricBounds(layout, TITLE_PAINT, textArea, firstBaseline, lineHeight)
        }

        var baseline = firstBaseline

        for (line in layout) {
            canvas.drawText(line, textArea.exactCenterX(), baseline, TITLE_PAINT)
            baseline += lineHeight
        }
        TITLE_PAINT.alpha = 255
        TITLE_PAINT.color = originalColor
        TITLE_PAINT.shader = null
    }

    private fun lyricBounds(
        layout: List<String>,
        paint: Paint,
        textArea: Rect,
        firstBaseline: Float,
        lineHeight: Float
    ): RectF {
        val maxLineWidth = layout.maxOfOrNull { line -> paint.measureText(line) } ?: 0f
        val centerX = textArea.exactCenterX()
        val lastBaseline = firstBaseline + lineHeight * (layout.size - 1).coerceAtLeast(0)
        return RectF(
            centerX - maxLineWidth / 2f,
            firstBaseline + paint.ascent(),
            centerX + maxLineWidth / 2f,
            lastBaseline + paint.descent()
        ).apply {
            inset(-LYRIC_TAP_PADDING, -LYRIC_TAP_PADDING)
        }
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

    private fun configureTransportPaints() {
        if (LyricsDisplaySettings.lightMode) {
            TRANSPORT_BUTTON_PAINT.color = LIGHT_TRANSPORT_BUTTON_COLOR
            TRANSPORT_BUTTON_STROKE_PAINT.color = LIGHT_TRANSPORT_STROKE_COLOR
            TRANSPORT_ICON_FILL_PAINT.color = Color.BLACK
            TRANSPORT_ICON_STROKE_PAINT.color = Color.BLACK
        } else {
            TRANSPORT_BUTTON_PAINT.color = DARK_TRANSPORT_BUTTON_COLOR
            TRANSPORT_BUTTON_STROKE_PAINT.color = DARK_TRANSPORT_STROKE_COLOR
            TRANSPORT_ICON_FILL_PAINT.color = Color.WHITE
            TRANSPORT_ICON_STROKE_PAINT.color = Color.WHITE
        }
    }

    private fun shouldShowTransportControls(): Boolean {
        return LyricsDisplaySettings.shouldShowMediaControls()
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
        private const val FOOTER_TEXT_SIZE_HEIGHT_RATIO = 24f / 480f
        private const val FOOTER_TEXT_SIZE_MULTIPLIER = 1.5f
        private const val MIN_FOOTER_TEXT_SIZE = 16f
        private const val FOOTER_TEXT_REFERENCE_ASPECT_RATIO = 16f / 10f
        private const val MIN_FOOTER_TEXT_ASPECT_SCALE = 0.90f
        private const val MAX_FOOTER_TEXT_ASPECT_SCALE = 1.35f
        private const val LINE_SPACING_MULTIPLIER = 1.08f
        private const val LYRIC_TRANSITION_MILLIS = 700L
        private const val PREVIOUS_LYRIC_FADE_MULTIPLIER = 2.4f
        private const val PREVIOUS_LYRIC_DARKEN_WEIGHT = 0.72f
        private const val LYRIC_TRANSITION_OFFSET_RATIO = 0.035f
        private const val MIN_LYRIC_TRANSITION_OFFSET = 18f
        private const val MAX_LYRIC_TRANSITION_OFFSET = 34f
        private const val LYRIC_TAP_PADDING = 28f
        private const val FOOTER_BOTTOM_MARGIN = 18f
        private const val FOOTER_HORIZONTAL_MARGIN = 48f
        private const val DOWNLOAD_ICON_SIZE = 18f
        private const val DOWNLOAD_ICON_GAP = 8f
        private const val DOWNLOAD_ICON_STROKE = 2.6f
        private const val PROGRESS_HORIZONTAL_MARGIN = 64f
        private const val PROGRESS_BOTTOM_MARGIN = 7f
        private const val PROGRESS_TRACK_STROKE = 5f
        private const val PROGRESS_FILL_STROKE = 5f
        private const val TRANSPORT_BUTTON_HEIGHT_RATIO = 0.105f
        private const val MIN_TRANSPORT_BUTTON_SIZE = 42f
        private const val MAX_TRANSPORT_BUTTON_SIZE = 66f
        private const val TRANSPORT_BUTTON_GAP_RATIO = 0.24f
        private const val TRANSPORT_BUTTON_RADIUS_RATIO = 0.24f
        private const val TRANSPORT_COLUMN_LEFT_RATIO = 0f
        private const val MIN_TRANSPORT_COLUMN_LEFT = 8f
        private const val MAX_TRANSPORT_COLUMN_LEFT = 14f
        private const val TRANSPORT_TEXT_CLEARANCE = 24f
        private const val DARK_PROGRESS_TRACK_COLOR = 0xFF333333.toInt()
        private const val LIGHT_PROGRESS_TRACK_COLOR = 0xFFE0E0E0.toInt()
        private const val DARK_TRANSPORT_BUTTON_COLOR = 0xAA000000.toInt()
        private const val DARK_TRANSPORT_STROKE_COLOR = 0x88FFFFFF.toInt()
        private const val LIGHT_TRANSPORT_BUTTON_COLOR = 0xDDFFFFFF.toInt()
        private const val LIGHT_TRANSPORT_STROKE_COLOR = 0x88000000.toInt()
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

        private val TRANSPORT_BUTTON_PAINT = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val TRANSPORT_BUTTON_STROKE_PAINT = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        private val TRANSPORT_ICON_FILL_PAINT = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val TRANSPORT_ICON_STROKE_PAINT = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

    }

    private data class TransportButton(
        val action: TransportAction,
        val bounds: RectF
    )

    private enum class TransportAction {
        Previous,
        PlayPause,
        Next
    }
}
