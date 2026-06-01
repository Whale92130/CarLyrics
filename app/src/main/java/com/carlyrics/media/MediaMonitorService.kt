package com.carlyrics.media

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.carlyrics.AppReset
import com.carlyrics.lyrics.LrcLibClient
import com.carlyrics.lyrics.LyricsQuery
import com.carlyrics.lyrics.LyricsState
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Watches active [MediaController]s on the phone and publishes the currently-
 * playing track into [MediaState].
 *
 * Implemented as a [NotificationListenerService] purely so the system grants us
 * the right to enumerate other apps' [android.media.session.MediaSession]s via
 * [MediaSessionManager.getActiveSessions]. We don't process notifications.
 *
 * Picks the "best" controller by skipping known wrapper apps (e.g. AA Media Mate)
 * that intercept media sessions to rebroadcast lyrics as the title field; those
 * pollute our display with rapidly-changing text and hide the real song info.
 */
class MediaMonitorService : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lyricsClient = LrcLibClient()
    private val lyricsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LrcLibLyrics").apply { isDaemon = true }
    }

    private val sessionManager by lazy {
        getSystemService(MediaSessionManager::class.java)
            ?: error("MediaSessionManager unavailable")
    }
    private val componentName by lazy {
        ComponentName(this, MediaMonitorService::class.java)
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachToBestController(controllers.orEmpty())
        }
    private val resetListener = AppReset.Listener {
        mainHandler.post { resetAndRefetchLyrics() }
    }

    private var attached: MediaController? = null
    private var lyricsRequest: Future<*>? = null
    private var lastLyricsLookupKey: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish(attached?.packageName, metadata, attached?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val metadata = attached?.metadata ?: return
            publish(attached?.packageName, metadata, state)
        }
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Notification listener connected; wiring MediaSessionManager")
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                componentName
            )
            AppReset.observe(resetListener)
            attachToBestController(sessionManager.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission not granted yet", e)
        }
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "Notification listener disconnected")
        runCatching {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        AppReset.stopObserving(resetListener)
        detach()
        clearTrack()
    }

    override fun onDestroy() {
        AppReset.stopObserving(resetListener)
        lyricsRequest?.cancel(true)
        lyricsExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun attachToBestController(controllers: List<MediaController>) {
        Log.d(TAG, "Active sessions: ${controllers.map { it.packageName }}")
        val best = controllers.firstOrNull { it.packageName !in BLOCKED_PACKAGES }
            ?: controllers.firstOrNull()

        if (best == null) {
            detach()
            clearTrack()
            return
        }
        if (best.sessionToken == attached?.sessionToken) return

        Log.d(TAG, "Attaching to ${best.packageName}")
        detach()
        attached = best
        best.registerCallback(controllerCallback)
        publish(best.packageName, best.metadata, best.playbackState)
    }

    private fun detach() {
        attached?.unregisterCallback(controllerCallback)
        attached = null
    }

    private fun publish(
        packageName: String?,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?
    ) {
        if (metadata == null) {
            clearTrack()
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMillis = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            .takeIf { it > 0L }

        Log.d(
            TAG,
            "[$packageName] title='$title' artist='$artist' album='$album' duration=$durationMillis"
        )

        if (title.isNullOrBlank()) {
            clearTrack()
            return
        }

        val track = TrackInfo(
            title = title.trim(),
            artist = artist?.trim()?.takeIf { it.isNotEmpty() },
            album = album?.trim()?.takeIf { it.isNotEmpty() },
            durationMillis = durationMillis,
            playbackPositionMillis = playbackPositionMillis(playbackState),
            playbackSpeed = playbackState?.playbackSpeed ?: 0f,
            playbackUpdatedAtElapsedMillis = playbackState?.lastPositionUpdateTime
                ?.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime(),
            albumColors = extractAlbumColors(metadata)
        )

        val current = MediaState.current
        val trackWithLyrics = if (current?.lookupKey == track.lookupKey) {
            track.copy(lyrics = current.lyrics)
        } else {
            track
        }

        MediaState.set(trackWithLyrics)
        maybeFetchLyrics(trackWithLyrics)
    }

    private fun resetAndRefetchLyrics() {
        Log.d(TAG, "Reset requested; refreshing media state and lyrics")

        val current = MediaState.current
        if (current == null) {
            runCatching {
                attachToBestController(sessionManager.getActiveSessions(componentName))
            }.onFailure { error ->
                Log.w(TAG, "Could not refresh active sessions during reset", error)
            }
            return
        }

        lastLyricsLookupKey = null
        lyricsRequest?.cancel(true)
        val resetTrack = current.copy(lyrics = LyricsState.Loading)
        MediaState.set(resetTrack)
        maybeFetchLyrics(resetTrack)
    }

    private fun maybeFetchLyrics(track: TrackInfo) {
        val lookupKey = track.lookupKey
        if (lookupKey == lastLyricsLookupKey) return

        lastLyricsLookupKey = lookupKey
        lyricsRequest?.cancel(true)
        lyricsRequest = lyricsExecutor.submit {
            val result = lyricsClient.fetchLyrics(track.toLyricsQuery())
            mainHandler.post {
                val current = MediaState.current ?: return@post
                if (current.lookupKey == lookupKey) {
                    MediaState.set(current.copy(lyrics = result))
                }
            }
        }
    }

    private fun clearTrack() {
        lastLyricsLookupKey = null
        lyricsRequest?.cancel(true)
        MediaState.set(null)
    }

    private fun playbackPositionMillis(playbackState: PlaybackState?): Long? {
        val position = playbackState?.position ?: return null
        return position.takeIf { it != PlaybackState.PLAYBACK_POSITION_UNKNOWN && it >= 0L }
    }

    private fun extractAlbumColors(metadata: MediaMetadata): List<Int> {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return emptyList()

        return dominantColors(bitmap)
    }

    private fun dominantColors(bitmap: Bitmap): List<Int> {
        val counts = mutableMapOf<Int, Int>()
        val step = (minOf(bitmap.width, bitmap.height) / COLOR_SAMPLE_SIZE).coerceAtLeast(1)
        val hsv = FloatArray(3)

        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) < MIN_COLOR_ALPHA) continue

                Color.colorToHSV(color, hsv)
                if (hsv[2] < MIN_COLOR_VALUE) continue

                val bucket = Color.rgb(
                    (Color.red(color) / COLOR_BUCKET_SIZE) * COLOR_BUCKET_SIZE,
                    (Color.green(color) / COLOR_BUCKET_SIZE) * COLOR_BUCKET_SIZE,
                    (Color.blue(color) / COLOR_BUCKET_SIZE) * COLOR_BUCKET_SIZE
                )
                counts[bucket] = (counts[bucket] ?: 0) + 1
            }
        }

        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Int>> { entry ->
                    Color.colorToHSV(entry.key, hsv)
                    entry.value * (0.65f + hsv[1])
                }
            )
            .map { it.key }
            .distinctBy { color ->
                Color.colorToHSV(color, hsv)
                "${(hsv[0] / 24f).toInt()}-${(hsv[1] * 4f).toInt()}"
            }
            .take(MAX_ALBUM_COLORS)
    }

    private fun TrackInfo.toLyricsQuery(): LyricsQuery =
        LyricsQuery(
            trackName = title,
            artistName = artist,
            albumName = album,
            durationSeconds = durationMillis?.let { (it + 500L) / 1_000L }
        )

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        private const val TAG = "MediaMonitorService"
        private const val COLOR_SAMPLE_SIZE = 48
        private const val COLOR_BUCKET_SIZE = 32
        private const val MIN_COLOR_ALPHA = 128
        private const val MIN_COLOR_VALUE = 0.12f
        private const val MAX_ALBUM_COLORS = 5

        /**
         * Wrapper apps that proxy other media sessions for Android Auto purposes
         * (lyrics injection, format conversion, etc.). Skipping these lets us
         * attach directly to the real music app.
         */
        private val BLOCKED_PACKAGES = setOf(
            "com.gululu.aamediamate",
            "com.google.android.projection.gearhead"
        )
    }
}
