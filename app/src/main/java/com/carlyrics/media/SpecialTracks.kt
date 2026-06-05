package com.carlyrics.media

object SpecialTracks {

    const val MARRIED_NEXT_YEAR_SEEK_MILLIS = 147_000L

    fun isMarriedNextYear(track: TrackInfo?): Boolean =
        track != null && isMarriedNextYear(track.title, track.artist)

    fun isMarriedNextYear(title: String?, artist: String?): Boolean =
        normalize(title) == "married next year" && normalize(artist) == "rod wave"

    private fun normalize(value: String?): String =
        value
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
}
