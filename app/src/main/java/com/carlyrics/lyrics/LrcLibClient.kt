package com.carlyrics.lyrics

import android.net.Uri
import android.util.Log
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

/**
 * Looks up lyrics on LRCLIB (https://lrclib.net/docs).
 *
 * Cascading strategy:
 *   1. /api/search?track_name=…&artist_name=…
 *   2. /api/search?track_name=…&album_name=…
 *   3. /api/search?track_name=… filtered to records whose duration is within
 *      [DURATION_FILTER_TOLERANCE_SECONDS] of the playing track
 *
 * Within each step, records without usable lyrics are dropped, then the
 * remaining records are ranked by duration closeness with synced lyrics
 * preferred as a tiebreak. Inputs are passed to LRCLIB unmodified — no title
 * stripping or artist cleaning — so the cascading fallbacks carry the load
 * when notification metadata is noisy.
 */
class LrcLibClient(
    private val cache: LyricsCache? = null,
    private val baseUrl: String = "https://lrclib.net"
) {

    fun fetchLyrics(query: LyricsQuery, ignoreCache: Boolean = false): LyricsState {
        if (query.trackName.isBlank()) return LyricsState.NotFound

        if (!ignoreCache) {
            cache?.get(query)?.let { cached ->
                Log.d(TAG, "Cache hit for ${query.debugSummary()}: ${cached.debugName()}")
                return cached
            }
        }

        Log.d(TAG, "Querying LRCLIB for ${query.debugSummary()} ignoreCache=$ignoreCache")
        val state = try {
            val best = findBest(query)
            best?.toLyricsState() ?: LyricsState.NotFound
        } catch (e: IOException) {
            Log.w(TAG, "Network error for ${query.debugSummary()}", e)
            return LyricsState.Error("Network error")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Parse error for ${query.debugSummary()}", e)
            return LyricsState.Error("Could not read lyrics")
        }

        Log.d(TAG, "Lookup result: ${state.debugName()}")
        if (state is LyricsState.Found || state is LyricsState.Instrumental) {
            cache?.put(query, state)
        }
        return state
    }

    private fun findBest(query: LyricsQuery): JSONObject? {
        if (!query.artistName.isNullOrBlank()) {
            val results = search(
                trackName = query.trackName,
                artistName = query.artistName,
                albumName = null
            )
            pickBest(results, query)?.let {
                Log.d(TAG, "Matched on track+artist: ${it.debugSummary()}")
                return it
            }
        }

        if (!query.albumName.isNullOrBlank()) {
            val results = search(
                trackName = query.trackName,
                artistName = null,
                albumName = query.albumName
            )
            pickBest(results, query)?.let {
                Log.d(TAG, "Matched on track+album: ${it.debugSummary()}")
                return it
            }
        }

        if (query.durationSeconds != null) {
            val results = search(
                trackName = query.trackName,
                artistName = null,
                albumName = null
            )
            val withinTolerance = results.filter { it.matchesDuration(query.durationSeconds) }
            pickBest(withinTolerance, query)?.let {
                Log.d(TAG, "Matched on track+duration: ${it.debugSummary()}")
                return it
            }
        }

        return null
    }

    private fun search(
        trackName: String,
        artistName: String?,
        albumName: String?
    ): List<JSONObject> {
        val params = linkedMapOf<String, String>("track_name" to trackName)
        if (!artistName.isNullOrBlank()) params["artist_name"] = artistName
        if (!albumName.isNullOrBlank()) params["album_name"] = albumName

        Log.d(TAG, "GET /api/search params=$params")
        val array = requestArray("/api/search", params)
        Log.d(TAG, "/api/search returned ${array.length()} candidates")
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun pickBest(records: List<JSONObject>, query: LyricsQuery): JSONObject? {
        val usable = records.filter { it.hasUsableLyrics() }
        if (usable.isEmpty()) return null

        val target = query.durationSeconds
        return usable.minWithOrNull(
            compareBy(
                { record ->
                    if (target == null) 0L
                    else record.optDurationSeconds()?.let { abs(it - target) } ?: Long.MAX_VALUE
                },
                { record -> if (record.hasSyncedLyrics()) 0 else 1 }
            )
        )
    }

    private fun JSONObject.matchesDuration(target: Long): Boolean {
        val recorded = optDurationSeconds() ?: return false
        return abs(recorded - target) <= DURATION_FILTER_TOLERANCE_SECONDS
    }

    private fun JSONObject.toLyricsState(): LyricsState {
        if (optBoolean("instrumental", false)) return LyricsState.Instrumental

        val synced = optNullableString("syncedLyrics", "synced_lyrics")
        if (!synced.isNullOrBlank()) {
            val lines = parseSyncedLyrics(synced)
            if (lines.isNotEmpty()) return LyricsState.Found(lines, synced = true)
        }

        val plain = optNullableString("plainLyrics", "plain_lyrics")
        if (!plain.isNullOrBlank()) {
            val lines = plain
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(startMillis = null, text = it) }
                .toList()
            if (lines.isNotEmpty()) return LyricsState.Found(lines, synced = false)
        }

        return LyricsState.NotFound
    }

    private fun parseSyncedLyrics(raw: String): List<LyricLine> {
        return raw
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

    private fun requestArray(path: String, params: Map<String, String>): JSONArray {
        val response = request(path, params)
        if (response.statusCode in 400..499) {
            Log.d(TAG, "$path returned ${response.statusCode}; treating as no matches")
            return JSONArray()
        }
        if (response.statusCode !in 200..299) {
            throw IOException("LRCLIB HTTP ${response.statusCode}")
        }
        return JSONArray(response.body)
    }

    private fun request(path: String, params: Map<String, String>): HttpResponse {
        val requestUrl = buildUrl(path, params)
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.d(TAG, "LRCLIB GET $requestUrl -> HTTP $code")
            HttpResponse(statusCode = code, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(path: String, params: Map<String, String>): String {
        val builder = Uri.parse(baseUrl)
            .buildUpon()
            .encodedPath(path)

        params.forEach { (key, value) ->
            if (value.isNotBlank()) builder.appendQueryParameter(key, value)
        }

        return builder.build().toString()
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

    private fun JSONObject.hasSyncedLyrics(): Boolean =
        !optNullableString("syncedLyrics", "synced_lyrics").isNullOrBlank()

    private fun JSONObject.hasUsableLyrics(): Boolean =
        optBoolean("instrumental", false) ||
            hasSyncedLyrics() ||
            !optNullableString("plainLyrics", "plain_lyrics").isNullOrBlank()

    private fun JSONObject.debugSummary(): String {
        val id = optLong("id", -1L).takeIf { it >= 0L }?.toString() ?: "?"
        val track = optNullableString("trackName", "track_name", "name").orEmpty()
        val artist = optNullableString("artistName", "artist_name").orEmpty()
        val album = optNullableString("albumName", "album_name").orEmpty()
        val duration = optDurationSeconds()?.toString().orEmpty()
        val lyricType = when {
            optBoolean("instrumental", false) -> "instrumental"
            hasSyncedLyrics() -> "synced"
            !optNullableString("plainLyrics", "plain_lyrics").isNullOrBlank() -> "plain"
            else -> "none"
        }
        return "id=$id track='$track' artist='$artist' album='$album' duration=$duration lyrics=$lyricType"
    }

    private fun LyricsQuery.debugSummary(): String =
        "track='$trackName' artist='$artistName' album='$albumName' duration=$durationSeconds"

    private fun LyricsState.debugName(): String =
        when (this) {
            is LyricsState.Found -> "Found(lines=${lines.size}, synced=$synced)"
            LyricsState.Instrumental -> "Instrumental"
            LyricsState.Loading -> "Loading"
            LyricsState.NotFound -> "NotFound"
            is LyricsState.Error -> "Error($message)"
        }

    private data class HttpResponse(val statusCode: Int, val body: String)

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val USER_AGENT = "CarLyrics/1.0 Android"
        private const val DURATION_FILTER_TOLERANCE_SECONDS = 5L
        private const val TAG = "LrcLibClient"
        private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    }
}
