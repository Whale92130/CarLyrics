package com.carlyrics.lyrics

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

data class LyricsQuery(
    val trackName: String,
    val artistName: String?,
    val albumName: String?,
    val durationSeconds: Long?
)

class LrcLibClient(
    private val baseUrl: String = "https://lrclib.net"
) {

    fun fetchLyrics(query: LyricsQuery): LyricsState {
        if (query.trackName.isBlank()) return LyricsState.NotFound

        return try {
            val exactResult = fetchByExactMetadata(query)?.toLyricsState()
            if (exactResult != null && exactResult != LyricsState.NotFound) {
                exactResult
            } else {
                search(query)?.toLyricsState() ?: LyricsState.NotFound
            }
        } catch (e: IOException) {
            LyricsState.Error("Network error")
        } catch (e: RuntimeException) {
            LyricsState.Error("Could not read lyrics")
        }
    }

    private fun fetchByExactMetadata(query: LyricsQuery): JSONObject? {
        if (query.artistName.isNullOrBlank()) return null
        if (query.albumName.isNullOrBlank()) return null
        val duration = query.durationSeconds ?: return null

        return requestObjectOrNull(
            "/api/get",
            mapOf(
                "track_name" to query.trackName,
                "artist_name" to query.artistName,
                "album_name" to query.albumName,
                "duration" to duration.toString()
            )
        )
    }

    private fun search(query: LyricsQuery): JSONObject? {
        val params = mutableMapOf<String, String?>(
            "track_name" to query.trackName,
            "artist_name" to query.artistName,
            "album_name" to query.albumName,
            "duration" to query.durationSeconds?.toString()
        )

        if (query.artistName.isNullOrBlank()) {
            params["q"] = query.trackName
        }

        val array = requestArray(
            "/api/search",
            params
        )

        val candidates = (0 until array.length())
            .mapNotNull { index -> array.optJSONObject(index) }
            .filter { record -> record.hasUsableLyrics() }
            .map { record -> record to score(record, query) }
            .filter { (_, score) -> score >= minimumScore(query) }

        return candidates.maxByOrNull { (_, score) -> score }?.first
    }

    private fun score(record: JSONObject, query: LyricsQuery): Int {
        var score = 0
        val recordTrack = record.optNullableString("trackName", "track_name", "name")
        val track = normalize(recordTrack)
        val requestedTrack = normalize(query.trackName)

        score += when {
            track.isEmpty() -> 0
            track == requestedTrack -> 80
            track.contains(requestedTrack) || requestedTrack.contains(track) -> 40
            else -> 0
        }

        val requestedArtist = normalize(query.artistName)
        if (requestedArtist.isNotEmpty()) {
            val artist = normalize(record.optNullableString("artistName", "artist_name"))
            score += when {
                artist.isEmpty() -> -30
                artist == requestedArtist -> 40
                artist.contains(requestedArtist) || requestedArtist.contains(artist) -> 20
                else -> -30
            }
        }

        val requestedAlbum = normalize(query.albumName)
        if (requestedAlbum.isNotEmpty()) {
            val album = normalize(record.optNullableString("albumName", "album_name"))
            if (album == requestedAlbum) score += 15
        }

        val requestedDuration = query.durationSeconds
        val recordDuration = record.optDurationSeconds()
        if (requestedDuration != null && recordDuration != null) {
            val delta = abs(recordDuration - requestedDuration)
            score += when {
                delta <= 2 -> 30
                delta <= 5 -> 10
                else -> -10
            }
        }

        if (record.hasUsableLyrics()) score += 5

        return score
    }

    private fun minimumScore(query: LyricsQuery): Int =
        if (query.artistName.isNullOrBlank()) 40 else 60

    private fun JSONObject.toLyricsState(): LyricsState {
        if (optBoolean("instrumental", false)) return LyricsState.Instrumental

        val syncedLyrics = optNullableString("syncedLyrics", "synced_lyrics")
        if (!syncedLyrics.isNullOrBlank()) {
            val lines = parseSyncedLyrics(syncedLyrics)
            if (lines.isNotEmpty()) return LyricsState.Found(lines, synced = true)
        }

        val plainLyrics = optNullableString("plainLyrics", "plain_lyrics")
        if (!plainLyrics.isNullOrBlank()) {
            val lines = plainLyrics
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(startMillis = null, text = it) }
                .toList()
            if (lines.isNotEmpty()) return LyricsState.Found(lines, synced = false)
        }

        return LyricsState.NotFound
    }

    private fun parseSyncedLyrics(rawLyrics: String): List<LyricLine> {
        return rawLyrics
            .lineSequence()
            .flatMap { line ->
                val matches = TIMESTAMP_REGEX.findAll(line).toList()
                if (matches.isEmpty()) return@flatMap emptySequence()

                val text = TIMESTAMP_REGEX.replace(line, "").trim()
                if (text.isEmpty()) return@flatMap emptySequence()

                matches.asSequence().mapNotNull { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    val fraction = match.groupValues[3]
                    val millis = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100L
                        2 -> fraction.toLong() * 10L
                        else -> fraction.take(3).padEnd(3, '0').toLong()
                    }
                    LyricLine(
                        startMillis = minutes * 60_000L + seconds * 1_000L + millis,
                        text = text
                    )
                }
            }
            .sortedBy { it.startMillis ?: Long.MAX_VALUE }
            .toList()
    }

    private fun requestObjectOrNull(path: String, params: Map<String, String?>): JSONObject? {
        val response = request(path, params)
        if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return null
        response.requireSuccess()
        return JSONObject(response.body)
    }

    private fun requestArray(path: String, params: Map<String, String?>): JSONArray {
        val response = request(path, params)
        if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return JSONArray()
        response.requireSuccess()
        return JSONArray(response.body)
    }

    private fun request(path: String, params: Map<String, String?>): HttpResponse {
        val url = URL(buildUrl(path, params))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(statusCode = code, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(path: String, params: Map<String, String?>): String {
        val builder = Uri.parse(baseUrl)
            .buildUpon()
            .encodedPath(path)

        params.forEach { (key, value) ->
            if (!value.isNullOrBlank()) builder.appendQueryParameter(key, value)
        }

        return builder.build().toString()
    }

    private fun HttpResponse.requireSuccess() {
        if (statusCode !in 200..299) {
            throw IOException("LRCLIB HTTP $statusCode")
        }
    }

    private fun JSONObject.optNullableString(vararg names: String): String? {
        for (name in names) {
            if (!isNull(name)) {
                val value = optString(name).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun JSONObject.optDurationSeconds(): Long? {
        val value = when {
            has("duration") && !isNull("duration") -> optDouble("duration", -1.0)
            has("durationSeconds") && !isNull("durationSeconds") -> optDouble("durationSeconds", -1.0)
            else -> -1.0
        }
        return value.takeIf { it >= 0.0 }?.let { Math.round(it) }
    }

    private fun JSONObject.hasUsableLyrics(): Boolean =
        optBoolean("instrumental", false) ||
            !optNullableString("syncedLyrics", "synced_lyrics").isNullOrBlank() ||
            !optNullableString("plainLyrics", "plain_lyrics").isNullOrBlank()

    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val USER_AGENT = "CarLyrics/1.0 Android"
        private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

        private fun normalize(value: String?): String =
            value
                ?.trim()
                ?.lowercase()
                ?.replace(Regex("\\s+"), " ")
                .orEmpty()
    }
}
