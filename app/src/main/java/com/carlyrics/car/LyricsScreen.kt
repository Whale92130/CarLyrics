package com.carlyrics.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.carlyrics.lyrics.LyricsState
import com.carlyrics.media.MediaState
import com.carlyrics.media.TrackInfo
import kotlin.math.roundToInt

/**
 * Car-side screen. Renders the current track in `NavigationTemplate`'s maneuver
 * panel and registers a [LyricsSurfaceCallback] to draw the track block onto the
 * map surface.
 *
 * `setSurfaceCallback` is wired in `onCreate` via a lifecycle observer rather than
 * directly in `init`, because `init` runs on the IPC binder thread before the host
 * has finished provisioning the surface — registering there gets the callback
 * silently dropped and `onSurfaceAvailable` never fires.
 */
class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    private val surfaceCallback = LyricsSurfaceCallback()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val templateTicker = object : Runnable {
        override fun run() {
            invalidate()
            updateTemplateTicker()
        }
    }
    private val trackListener = MediaState.Listener {
        mainHandler.post {
            invalidate()
            updateTemplateTicker()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java)
                    .setSurfaceCallback(surfaceCallback)
                MediaState.observe(trackListener)
            }

            override fun onStop(owner: LifecycleOwner) {
                MediaState.stopObserving(trackListener)
                mainHandler.removeCallbacks(templateTicker)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val display = lyricDisplay(MediaState.current)
        val rowBuilder = Row.Builder()
            .setTitle(display.primary)
        if (!display.secondary.isNullOrBlank()) {
            rowBuilder.addText(display.secondary)
        }
        val pane = Pane.Builder()
            .addRow(rowBuilder.build())
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Lyrics").build())
            .build()

        @Suppress("DEPRECATION")
        return MapTemplate.Builder()
            .setPane(pane)
            .setActionStrip(actionStrip)
            .build()
    }

    private fun lyricDisplay(track: TrackInfo?): LyricDisplay {
        if (track == null) return LyricDisplay("No song playing", "CarLyrics")

        val songLabel = listOfNotNull(track.title, track.artist).joinToString(" - ")
        return when (val lyrics = track.lyrics) {
            LyricsState.Loading -> LyricDisplay("Finding lyrics...", songLabel)
            LyricsState.Instrumental -> LyricDisplay("Instrumental", songLabel)
            LyricsState.NotFound -> LyricDisplay("Lyrics not found", songLabel)
            is LyricsState.Error -> LyricDisplay("Lyrics unavailable", songLabel)
            is LyricsState.Found -> lyricDisplay(track, lyrics, songLabel)
        }
    }

    private fun lyricDisplay(
        track: TrackInfo,
        lyrics: LyricsState.Found,
        songLabel: String
    ): LyricDisplay {
        if (lyrics.lines.isEmpty()) return LyricDisplay("Lyrics not found", songLabel)

        val index = currentLyricIndex(track, lyrics)
        return LyricDisplay(
            primary = lyrics.lines[index].text,
            secondary = songLabel
        )
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

    private fun updateTemplateTicker() {
        mainHandler.removeCallbacks(templateTicker)

        val track = MediaState.current ?: return
        val lyrics = track.lyrics as? LyricsState.Found ?: return
        if (!lyrics.synced || track.playbackSpeed <= 0f) return

        mainHandler.postDelayed(templateTicker, TEMPLATE_TICK_MILLIS)
    }

    private data class LyricDisplay(
        val primary: String,
        val secondary: String?
    )

    private companion object {
        private const val TEMPLATE_TICK_MILLIS = 1_000L
    }
}
