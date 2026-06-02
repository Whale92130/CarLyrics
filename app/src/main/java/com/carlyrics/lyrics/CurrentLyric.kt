package com.carlyrics.lyrics

import com.carlyrics.media.TrackInfo
import kotlin.math.roundToInt

/**
 * Single source of truth for the text shown as the "current lyric line"
 * on both the center-stack map surface and the head-unit HUD trip.
 */
object CurrentLyric {

    fun textFor(track: TrackInfo?, nowElapsedMillis: Long): String {
        if (track == null) return "No song playing"
        return when (val lyrics = track.lyrics) {
            LyricsState.Loading -> "Finding lyrics..."
            LyricsState.Instrumental -> "Instrumental"
            LyricsState.NotFound -> "Lyrics not found"
            is LyricsState.Error -> "Lyrics unavailable"
            is LyricsState.Found -> foundText(track, lyrics, nowElapsedMillis)
        }
    }

    private fun foundText(
        track: TrackInfo,
        lyrics: LyricsState.Found,
        nowElapsedMillis: Long
    ): String {
        if (lyrics.lines.isEmpty()) return "Lyrics not found"
        return lyrics.lines[currentIndex(track, lyrics, nowElapsedMillis)].text
    }

    private fun currentIndex(
        track: TrackInfo,
        lyrics: LyricsState.Found,
        nowElapsedMillis: Long
    ): Int {
        val position = track.estimatedPositionMillis(nowElapsedMillis) ?: return 0

        if (lyrics.synced) {
            return lyrics.lines
                .indexOfLast { line -> line.startMillis?.let { it <= position } == true }
                .coerceAtLeast(0)
        }

        val duration = track.durationMillis ?: return 0
        if (duration <= 0L) return 0
        val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return (progress * lyrics.lines.lastIndex)
            .roundToInt()
            .coerceIn(0, lyrics.lines.lastIndex)
    }
}
