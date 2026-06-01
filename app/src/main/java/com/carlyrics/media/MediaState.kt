package com.carlyrics.media

import com.carlyrics.lyrics.LyricsState
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToLong

/**
 * Snapshot of the currently-playing track surfaced by [MediaMonitorService],
 * including enough playback context for the car surface to advance synced lyrics.
 */
data class TrackInfo(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMillis: Long?,
    val playbackPositionMillis: Long?,
    val playbackSpeed: Float,
    val playbackUpdatedAtElapsedMillis: Long,
    val albumColors: List<Int> = emptyList(),
    val lyrics: LyricsState = LyricsState.Loading,
    val lyricsSavedOnDevice: Boolean = false
) {
    val lookupKey: String
        get() = listOf(
            normalize(title),
            normalize(artist),
            durationMillis?.let { ((it + 500L) / 1_000L).toString() }.orEmpty()
        ).joinToString("|")

    fun estimatedPositionMillis(nowElapsedMillis: Long): Long? {
        val position = playbackPositionMillis ?: return null
        val elapsed = ((nowElapsedMillis - playbackUpdatedAtElapsedMillis).coerceAtLeast(0L) *
            playbackSpeed).roundToLong()
        val estimated = (position + elapsed).coerceAtLeast(0L)
        return durationMillis?.let { estimated.coerceAtMost(it) } ?: estimated
    }
}

/**
 * Process-wide hand-off between [MediaMonitorService] (running in the phone app's
 * main process) and [com.carlyrics.car.LyricsSurfaceCallback] (running in the same
 * process when Android Auto binds to [com.carlyrics.car.LyricsCarAppService]).
 */
object MediaState {

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var current: TrackInfo? = null
        private set

    fun set(track: TrackInfo?) {
        if (track == current) return
        current = track
        listeners.forEach { it.onChanged(track) }
    }

    fun observe(listener: Listener) {
        listeners.add(listener)
        listener.onChanged(current)
    }

    fun stopObserving(listener: Listener) {
        listeners.remove(listener)
    }

    fun interface Listener {
        fun onChanged(track: TrackInfo?)
    }
}

private fun normalize(value: String?): String =
    value
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
