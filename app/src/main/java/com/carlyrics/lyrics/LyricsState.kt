package com.carlyrics.lyrics

data class LyricLine(
    val startMillis: Long?,
    val text: String
)

sealed class LyricsState {
    object Loading : LyricsState()
    data class Found(
        val lines: List<LyricLine>,
        val synced: Boolean
    ) : LyricsState()

    object Instrumental : LyricsState()
    object NotFound : LyricsState()
    data class Error(val message: String) : LyricsState()
}
