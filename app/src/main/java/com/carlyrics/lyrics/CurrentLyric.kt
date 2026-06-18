package com.carlyrics.lyrics

import com.carlyrics.media.TrackInfo
import kotlin.math.roundToInt

data class CurrentLyricSnapshot(
    val title: String,
    val artist: String?,
    val previousLine: String?,
    val currentLine: String,
    val nextLine: String?,
    val lines: List<String>,
    val currentIndex: Int,
    val positionMillis: Long?,
    val durationMillis: Long?,
    val lyricsStatus: String
)

/**
 * Single source of truth for the text shown as the "current lyric line"
 * on the center-stack map surface.
 */
object CurrentLyric {

    fun textFor(track: TrackInfo?, nowElapsedMillis: Long): String {
        return snapshotFor(track, nowElapsedMillis).currentLine
    }

    fun snapshotFor(track: TrackInfo?, nowElapsedMillis: Long): CurrentLyricSnapshot {
        if (track == null) {
            return CurrentLyricSnapshot(
                title = "No song playing",
                artist = null,
                previousLine = null,
                currentLine = "No song playing",
                nextLine = null,
                lines = listOf("No song playing"),
                currentIndex = 0,
                positionMillis = null,
                durationMillis = null,
                lyricsStatus = "idle"
            )
        }

        return when (val lyrics = track.lyrics) {
            LyricsState.Loading -> basicSnapshot(track, "Finding lyrics...", "loading", nowElapsedMillis)
            LyricsState.Instrumental -> basicSnapshot(track, "Instrumental", "instrumental", nowElapsedMillis)
            LyricsState.NotFound -> basicSnapshot(track, "Lyrics not found", "not_found", nowElapsedMillis)
            is LyricsState.Error -> basicSnapshot(track, "Lyrics unavailable", "error", nowElapsedMillis)
            is LyricsState.Found -> foundSnapshot(track, lyrics, nowElapsedMillis)
        }
    }

    private fun basicSnapshot(
        track: TrackInfo,
        currentLine: String,
        lyricsStatus: String,
        nowElapsedMillis: Long
    ): CurrentLyricSnapshot =
        CurrentLyricSnapshot(
            title = track.title,
            artist = track.artist,
            previousLine = null,
            currentLine = currentLine,
            nextLine = null,
            lines = listOf(currentLine),
            currentIndex = 0,
            positionMillis = track.estimatedPositionMillis(nowElapsedMillis),
            durationMillis = track.durationMillis,
            lyricsStatus = lyricsStatus
        )

    private fun foundSnapshot(
        track: TrackInfo,
        lyrics: LyricsState.Found,
        nowElapsedMillis: Long
    ): CurrentLyricSnapshot {
        if (lyrics.lines.isEmpty()) return basicSnapshot(track, "Lyrics not found", "not_found", nowElapsedMillis)

        val currentIndex = currentIndex(track, lyrics, nowElapsedMillis)
        return CurrentLyricSnapshot(
            title = track.title,
            artist = track.artist,
            previousLine = lyrics.lines.getOrNull(currentIndex - 1)?.text,
            currentLine = lyrics.lines[currentIndex].text,
            nextLine = lyrics.lines.getOrNull(currentIndex + 1)?.text,
            lines = lyrics.lines.map { line -> line.text },
            currentIndex = currentIndex,
            positionMillis = track.estimatedPositionMillis(nowElapsedMillis),
            durationMillis = track.durationMillis,
            lyricsStatus = if (lyrics.synced) "synced" else "plain"
        )
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
