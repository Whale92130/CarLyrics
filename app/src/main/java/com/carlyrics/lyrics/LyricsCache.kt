package com.carlyrics.lyrics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class CachedLyricsEntry(
    val trackName: String,
    val artistName: String?,
    val albumName: String?,
    val durationSeconds: Long?,
    val cachedAtMillis: Long,
    val storageBytes: Long,
    val state: LyricsState
)

/**
 * On-device JSON cache for resolved LRCLIB lookups.
 *
 * The key is derived from the normalized track + artist only so the same song
 * played from different sources (Spotify, YouTube Music, etc.) shares a single
 * cache entry. Album / duration are stored alongside the entry purely for
 * debugging — they aren't part of the lookup key.
 *
 * Only positive results ([LyricsState.Found], [LyricsState.Instrumental]) are
 * persisted. [LyricsState.NotFound] and [LyricsState.Error] are left to retry
 * on the next play in case the song eventually shows up on LRCLIB.
 */
class LyricsCache(context: Context) {

    private val baseDir: File = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    fun get(query: LyricsQuery): LyricsState? {
        val file = fileFor(query)
        if (!file.exists()) return null

        return try {
            val state = decode(JSONObject(file.readText()))
            if (state == null) {
                Log.w(TAG, "Discarding malformed cache entry ${file.name}")
                file.delete()
            }
            state
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable cache entry ${file.name}", e)
            file.delete()
            null
        }
    }

    fun put(query: LyricsQuery, state: LyricsState) {
        val obj = encode(query, state) ?: return
        val file = fileFor(query)
        try {
            val tmp = File(baseDir, file.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "Saved cache entry ${file.name} (${state.debugName()})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache entry ${file.name}", e)
        }
    }

    fun listEntries(): List<CachedLyricsEntry> {
        val files = baseDir.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty()

        return files
            .mapNotNull { file -> readEntry(file) }
            .sortedByDescending { entry -> entry.cachedAtMillis }
            .distinctBy { entry -> entry.normalizedKey() }
    }

    private fun readEntry(file: File): CachedLyricsEntry? =
        try {
            val entry = decodeEntry(JSONObject(file.readText()), file.length())
            if (entry == null) {
                Log.w(TAG, "Discarding malformed cache entry ${file.name}")
                file.delete()
            }
            entry
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable cache entry ${file.name}", e)
            file.delete()
            null
        }

    private fun encode(query: LyricsQuery, state: LyricsState): JSONObject? {
        val obj = JSONObject()
        obj.put("version", VERSION)
        obj.put("trackName", query.trackName)
        obj.put("artistName", query.artistName ?: JSONObject.NULL)
        obj.put("albumName", query.albumName ?: JSONObject.NULL)
        obj.put("durationSeconds", query.durationSeconds ?: JSONObject.NULL)
        obj.put("cachedAtMillis", System.currentTimeMillis())

        when (state) {
            is LyricsState.Found -> {
                obj.put("type", if (state.synced) TYPE_SYNCED else TYPE_PLAIN)
                val arr = JSONArray()
                state.lines.forEach { line ->
                    val l = JSONObject()
                    l.put("startMillis", line.startMillis ?: JSONObject.NULL)
                    l.put("text", line.text)
                    arr.put(l)
                }
                obj.put("lines", arr)
            }
            LyricsState.Instrumental -> obj.put("type", TYPE_INSTRUMENTAL)
            else -> return null
        }
        return obj
    }

    private fun decodeEntry(obj: JSONObject, storageBytes: Long): CachedLyricsEntry? {
        val state = decode(obj) ?: return null
        val trackName = obj.optNullableString("trackName") ?: return null
        val durationSeconds = if (obj.isNull("durationSeconds")) {
            null
        } else {
            obj.optLong("durationSeconds").takeIf { it >= 0L }
        }
        return CachedLyricsEntry(
            trackName = trackName,
            artistName = obj.optNullableString("artistName"),
            albumName = obj.optNullableString("albumName"),
            durationSeconds = durationSeconds,
            cachedAtMillis = obj.optLong("cachedAtMillis", 0L),
            storageBytes = storageBytes,
            state = state
        )
    }

    private fun decode(obj: JSONObject): LyricsState? {
        return when (obj.optString("type")) {
            TYPE_INSTRUMENTAL -> LyricsState.Instrumental
            TYPE_SYNCED, TYPE_PLAIN -> {
                val synced = obj.optString("type") == TYPE_SYNCED
                val arr = obj.optJSONArray("lines") ?: return null
                val lines = (0 until arr.length()).mapNotNull { i ->
                    val l = arr.optJSONObject(i) ?: return@mapNotNull null
                    val text = l.optString("text").takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val start = if (l.isNull("startMillis")) null else l.optLong("startMillis")
                    LyricLine(startMillis = start, text = text)
                }
                if (lines.isEmpty()) null else LyricsState.Found(lines, synced)
            }
            else -> null
        }
    }

    private fun fileFor(query: LyricsQuery): File {
        val key = listOf(
            normalize(query.trackName),
            normalize(query.artistName)
        ).joinToString("|")
        return File(baseDir, sha1(key) + ".json")
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotEmpty() }
    }

    private fun normalize(value: String?): String =
        stripLosslessSuffix(value)
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()

    private fun CachedLyricsEntry.normalizedKey(): String =
        listOf(
            normalize(trackName),
            normalize(artistName)
        ).joinToString("|")

    private fun stripLosslessSuffix(value: String?): String? =
        value
            ?.trim()
            ?.replace(LOSSLESS_SUFFIX_REGEX, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun LyricsState.debugName(): String =
        when (this) {
            is LyricsState.Found -> "Found(lines=${lines.size}, synced=$synced)"
            LyricsState.Instrumental -> "Instrumental"
            LyricsState.Loading -> "Loading"
            LyricsState.NotFound -> "NotFound"
            is LyricsState.Error -> "Error($message)"
        }

    companion object {
        private const val TAG = "LyricsCache"
        private const val CACHE_DIR_NAME = "lyrics"
        private const val VERSION = 1
        private const val TYPE_SYNCED = "synced"
        private const val TYPE_PLAIN = "plain"
        private const val TYPE_INSTRUMENTAL = "instrumental"
        private val LOSSLESS_SUFFIX_REGEX =
            Regex("""(?i)(?:\s*[-|/.•·]\s*|\s+)\(?lossless\)?$""")
    }
}
