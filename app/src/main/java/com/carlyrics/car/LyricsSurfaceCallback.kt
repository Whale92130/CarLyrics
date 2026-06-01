package com.carlyrics.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.carlyrics.lyrics.LyricsState
import com.carlyrics.media.MediaState
import com.carlyrics.media.TrackInfo
import kotlin.math.roundToInt

/**
 * Draws the current LRCLIB lyric line onto the
 * [NavigationTemplate][androidx.car.app.navigation.model.NavigationTemplate] map surface.
 *
 * The centered text path intentionally mirrors the original title/artist renderer:
 * one large centered line with an optional smaller line beneath it.
 */
class LyricsSurfaceCallback : SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect? = null

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

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.d(TAG, "onSurfaceAvailable ${surfaceContainer.width}x${surfaceContainer.height}")
        this.surfaceContainer = surfaceContainer
        MediaState.observe(trackListener)
        render()
        updateTicker()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        render()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        MediaState.stopObserving(trackListener)
        mainHandler.removeCallbacks(ticker)
        this.surfaceContainer = null
        this.visibleArea = null
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
            canvas.drawColor(BACKGROUND_COLOR)
            val area = drawableArea(canvas, container)
            drawCenteredBlock(canvas, area, track)
            drawSongInfo(canvas, area, track)
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
        if (visible != null && visible.width() > 0 && visible.height() > 0) {
            return visible
        }

        if (canvas.width > 0 && canvas.height > 0) {
            return Rect(0, 0, canvas.width, canvas.height)
        }

        val clip = Rect()
        if (canvas.getClipBounds(clip) && clip.width() > 0 && clip.height() > 0) {
            return clip
        }

        return Rect(0, 0, container.width.coerceAtLeast(1), container.height.coerceAtLeast(1))
    }

    private fun drawCenteredBlock(canvas: Canvas, area: Rect, track: TrackInfo?) {
        val cx = area.exactCenterX()
        val maxWidth = (area.width() - HORIZONTAL_MARGIN * 2f).coerceAtLeast(1f)

        val primary: String
        val secondary: String?

        if (track == null) {
            primary = "No song playing"
            secondary = null
        } else {
            val display = lyricDisplay(track)
            primary = display.primary
            secondary = display.secondary
        }

        val primaryText = ellipsize(primary, TITLE_PAINT, maxWidth)
        val secondaryText = secondary
            ?.takeIf { it.isNotBlank() }
            ?.let { ellipsize(it, ARTIST_PAINT, maxWidth) }

        val titleHeight = TITLE_PAINT.descent() - TITLE_PAINT.ascent()
        val artistHeight = ARTIST_PAINT.descent() - ARTIST_PAINT.ascent()
        val totalHeight = titleHeight + if (secondaryText != null) GAP + artistHeight else 0f

        val blockTop = area.exactCenterY() - totalHeight / 2f
        val titleBaseline = blockTop - TITLE_PAINT.ascent()
        canvas.drawText(primaryText, cx, titleBaseline, TITLE_PAINT)

        if (secondaryText != null) {
            val artistBaseline = titleBaseline + TITLE_PAINT.descent() + GAP - ARTIST_PAINT.ascent()
            canvas.drawText(secondaryText, cx, artistBaseline, ARTIST_PAINT)
        }
    }

    private fun drawSongInfo(canvas: Canvas, area: Rect, track: TrackInfo?) {
        if (track == null) return

        val maxWidth = (area.width() - HORIZONTAL_MARGIN * 2f).coerceAtLeast(1f)
        val title = ellipsize(track.title, META_TITLE_PAINT, maxWidth)
        val artist = track.artist?.let { ellipsize(it, META_ARTIST_PAINT, maxWidth) }
        val left = area.left + HORIZONTAL_MARGIN
        val titleBaseline = area.top + META_TOP_MARGIN - META_TITLE_PAINT.ascent()

        canvas.drawText(title, left, titleBaseline, META_TITLE_PAINT)
        if (!artist.isNullOrBlank()) {
            val artistBaseline = titleBaseline + META_TITLE_PAINT.descent() + META_GAP -
                META_ARTIST_PAINT.ascent()
            canvas.drawText(artist, left, artistBaseline, META_ARTIST_PAINT)
        }
    }

    private fun lyricDisplay(track: TrackInfo): LyricDisplay =
        when (val lyrics = track.lyrics) {
            LyricsState.Loading -> LyricDisplay("Finding lyrics...", null)
            LyricsState.Instrumental -> LyricDisplay("Instrumental", null)
            LyricsState.NotFound -> LyricDisplay("Lyrics not found", null)
            is LyricsState.Error -> LyricDisplay("Lyrics unavailable", null)
            is LyricsState.Found -> lyricDisplay(track, lyrics)
        }

    private fun lyricDisplay(track: TrackInfo, lyrics: LyricsState.Found): LyricDisplay {
        if (lyrics.lines.isEmpty()) return LyricDisplay("Lyrics not found", null)

        val index = currentLyricIndex(track, lyrics)
        val primary = lyrics.lines[index].text
        val secondary = lyrics.lines.getOrNull(index + 1)?.text
        return LyricDisplay(primary, secondary)
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
        val lyrics = track.lyrics as? LyricsState.Found ?: return
        if (!lyrics.synced || track.playbackSpeed <= 0f) return

        mainHandler.postDelayed(ticker, TICK_MILLIS)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text

        val available = maxWidth - paint.measureText(ELLIPSIS)
        if (available <= 0f) return ELLIPSIS

        val count = paint.breakText(text, true, available, null)
        return text.take(count).trimEnd() + ELLIPSIS
    }

    private data class LyricDisplay(
        val primary: String,
        val secondary: String?
    )

    companion object {
        private const val TAG = "LyricsSurfaceCallback"
        private const val BACKGROUND_COLOR = 0xFF0A0A0A.toInt()
        private const val HORIZONTAL_MARGIN = 48f
        private const val META_TOP_MARGIN = 24f
        private const val META_GAP = 4f
        private const val GAP = 16f
        private const val TICK_MILLIS = 350L
        private const val ELLIPSIS = "..."

        private val TITLE_PAINT = Paint().apply {
            color = Color.WHITE
            textSize = 56f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }

        private val ARTIST_PAINT = Paint().apply {
            color = 0xFFBBBBBB.toInt()
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        private val META_TITLE_PAINT = Paint().apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
            isFakeBoldText = true
        }

        private val META_ARTIST_PAINT = Paint().apply {
            color = 0xFFB8B8B8.toInt()
            textSize = 22f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
        }
    }
}
