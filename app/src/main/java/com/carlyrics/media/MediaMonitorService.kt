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
import com.carlyrics.LyricsResync
import com.carlyrics.lyrics.LrcLibClient
import com.carlyrics.lyrics.LyricsCache
import com.carlyrics.lyrics.LyricsQuery
import com.carlyrics.lyrics.LyricsState
import kotlin.math.abs
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
    private val lyricsCache by lazy { LyricsCache(applicationContext) }
    private val lyricsClient by lazy { LrcLibClient(lyricsCache) }
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
    private val resyncListener = LyricsResync.Listener {
        mainHandler.post { resyncLyricsWithoutFetch() }
    }

    private var attached: MediaController? = null
    private var lyricsRequest: Future<*>? = null
    private var lastLyricsLookupQuery: LyricsQuery? = null
    private var lyricsGeneration = 0
    private var pendingTrackStartSyncKey: String? = null
    private var pendingTrackStartSyncIndex = 0
    private var periodicPositionSyncKey: String? = null
    private var marriedNextYearPausedTrackKey: String? = null

    private val trackStartSyncRunnable = Runnable {
        syncPlaybackPositionAfterTrackStart()
    }
    private val periodicPositionSyncRunnable = Runnable {
        syncPlaybackPositionPeriodically()
    }

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
            LyricsResync.observe(resyncListener)
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
        LyricsResync.stopObserving(resyncListener)
        detach()
        clearTrack()
    }

    override fun onDestroy() {
        AppReset.stopObserving(resetListener)
        LyricsResync.stopObserving(resyncListener)
        cancelTrackStartPositionSync()
        cancelPeriodicPositionSync()
        lyricsRequest?.cancel(true)
        lyricsExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun attachToBestController(controllers: List<MediaController>) {
        Log.d(TAG, "Active sessions: ${controllers.map { it.packageName }}")
        val lyricCandidates = controllers.filterNot { controller ->
            shouldIgnoreForLyrics(controller.packageName)
        }
        val best = lyricCandidates.firstOrNull { it.packageName !in BLOCKED_PACKAGES }
            ?: lyricCandidates.firstOrNull()

        if (best == null) {
            detach()
            clearTrack()
            return
        }
        if (best.sessionToken == attached?.sessionToken) return

        Log.d(TAG, "Attaching to ${best.packageName}")
        detach()
        attached = best
        MediaControls.setController(best)
        best.registerCallback(controllerCallback)
        publish(best.packageName, best.metadata, best.playbackState)
    }

    private fun detach() {
        attached?.let { controller ->
            MediaControls.clearController(controller)
            controller.unregisterCallback(controllerCallback)
        }
        attached = null
    }

    private fun publish(
        packageName: String?,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?,
        fetchLyrics: Boolean = true
    ) {
        if (shouldIgnoreForLyrics(packageName)) {
            clearTrack()
            return
        }
        if (metadata == null) {
            clearTrack()
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val cleanArtist = stripLosslessSuffix(artist)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMillis = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            .takeIf { it > 0L }

        Log.d(
            TAG,
            "[$packageName] title='$title' artist='$cleanArtist' album='$album' duration=$durationMillis"
        )

        if (title.isNullOrBlank()) {
            clearTrack()
            return
        }
        if (isSpotifyDjInterlude(title, cleanArtist)) {
            clearTrack()
            return
        }

        val observedTrack = TrackInfo(
            title = title.trim(),
            artist = cleanArtist,
            album = album?.trim()?.takeIf { it.isNotEmpty() },
            durationMillis = durationMillis,
            playbackPositionMillis = playbackPositionMillis(playbackState),
            playbackSpeed = playbackState?.playbackSpeed ?: 0f,
            playbackUpdatedAtElapsedMillis = playbackState?.lastPositionUpdateTime
                ?.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime(),
            albumColors = extractAlbumColors(metadata)
        )

        val isNewLikelySong = MediaState.current
            ?.let { current -> !isSameLikelySong(observedTrack, current) }
            ?: true
        val track = mergeWithCurrent(observedTrack, MediaState.current)

        MediaState.set(track)
        if (fetchLyrics) {
            maybeFetchLyrics(track)
        }
        maybePauseMarriedNextYear(track)
        ensurePeriodicPositionSync(track.lookupKey)
        if (isNewLikelySong) {
            scheduleTrackStartPositionSync(track.lookupKey)
        }
    }

    private fun resyncLyricsWithoutFetch() {
        Log.d(TAG, "Resync requested; refreshing playback position without fetching lyrics")
        val controller = attached
        if (controller == null) {
            Log.d(TAG, "No attached media controller to resync")
            return
        }

        publish(
            packageName = controller.packageName,
            metadata = controller.metadata,
            playbackState = controller.playbackState,
            fetchLyrics = false
        )
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

        lastLyricsLookupQuery = null
        val resetTrack = current.copy(lyrics = LyricsState.Loading)
        MediaState.set(resetTrack)
        maybeFetchLyrics(resetTrack, forceNetwork = true)
    }

    private fun maybeFetchLyrics(track: TrackInfo, forceNetwork: Boolean = false) {
        val query = track.toLyricsQuery()
        if (query == lastLyricsLookupQuery) return

        if (!forceNetwork) {
            lyricsCache.get(query)?.let { cachedLyrics ->
                lastLyricsLookupQuery = query
                lyricsRequest?.cancel(true)
                MediaState.set(
                    track.copy(
                        lyrics = cachedLyrics,
                        lyricsSavedOnDevice = cachedLyrics is LyricsState.Found
                    )
                )
                return
            }
        }

        lastLyricsLookupQuery = query
        val generation = ++lyricsGeneration
        lyricsRequest?.cancel(true)
        lyricsRequest = lyricsExecutor.submit {
            if (!waitBeforeLyricsLookup()) return@submit
            val result = fetchLyricsWithRetries(query, ignoreCache = forceNetwork)
            val savedOnDevice = lyricsSavedOnDevice(query, result)
            mainHandler.post {
                if (generation != lyricsGeneration) return@post
                val current = MediaState.current ?: return@post
                if (current.toLyricsQuery() != query) return@post
                if (current.lyrics is LyricsState.Found) return@post
                if (result is LyricsState.Error && lastLyricsLookupQuery == query) {
                    lastLyricsLookupQuery = null
                }
                MediaState.set(
                    current.copy(
                        lyrics = result,
                        lyricsSavedOnDevice = savedOnDevice
                    )
                )
            }
        }
    }

    private fun clearTrack() {
        cancelTrackStartPositionSync()
        cancelPeriodicPositionSync()
        marriedNextYearPausedTrackKey = null
        lastLyricsLookupQuery = null
        lyricsRequest?.cancel(true)
        MediaState.set(null)
    }

    private fun maybePauseMarriedNextYear(track: TrackInfo) {
        if (!SpecialTracks.isMarriedNextYear(track)) {
            marriedNextYearPausedTrackKey = null
            return
        }
        if (marriedNextYearPausedTrackKey == track.lookupKey) return

        marriedNextYearPausedTrackKey = track.lookupKey
        MediaControls.pause()
    }

    private fun scheduleTrackStartPositionSync(trackKey: String) {
        pendingTrackStartSyncKey = trackKey
        pendingTrackStartSyncIndex = 0
        mainHandler.removeCallbacks(trackStartSyncRunnable)
        mainHandler.postDelayed(
            trackStartSyncRunnable,
            TRACK_START_POSITION_SYNC_DELAYS_MILLIS[pendingTrackStartSyncIndex]
        )
    }

    private fun cancelTrackStartPositionSync() {
        pendingTrackStartSyncKey = null
        pendingTrackStartSyncIndex = 0
        mainHandler.removeCallbacks(trackStartSyncRunnable)
    }

    private fun syncPlaybackPositionAfterTrackStart() {
        val expectedTrackKey = pendingTrackStartSyncKey ?: return

        val current = MediaState.current ?: return
        if (current.lookupKey != expectedTrackKey) {
            cancelTrackStartPositionSync()
            return
        }

        val controller = attached ?: return
        publish(
            packageName = controller.packageName,
            metadata = controller.metadata,
            playbackState = controller.playbackState
        )
        scheduleNextTrackStartPositionSync(expectedTrackKey)
    }

    private fun scheduleNextTrackStartPositionSync(trackKey: String) {
        val nextIndex = pendingTrackStartSyncIndex + 1
        if (nextIndex >= TRACK_START_POSITION_SYNC_DELAYS_MILLIS.size) {
            cancelTrackStartPositionSync()
            return
        }

        val current = MediaState.current
        if (current?.lookupKey != trackKey) {
            cancelTrackStartPositionSync()
            return
        }

        pendingTrackStartSyncKey = trackKey
        pendingTrackStartSyncIndex = nextIndex
        mainHandler.removeCallbacks(trackStartSyncRunnable)
        mainHandler.postDelayed(
            trackStartSyncRunnable,
            TRACK_START_POSITION_SYNC_DELAYS_MILLIS[nextIndex]
        )
    }

    private fun ensurePeriodicPositionSync(trackKey: String) {
        if (periodicPositionSyncKey == trackKey) return
        periodicPositionSyncKey = trackKey
        mainHandler.removeCallbacks(periodicPositionSyncRunnable)
        mainHandler.postDelayed(
            periodicPositionSyncRunnable,
            PERIODIC_POSITION_SYNC_MILLIS
        )
    }

    private fun cancelPeriodicPositionSync() {
        periodicPositionSyncKey = null
        mainHandler.removeCallbacks(periodicPositionSyncRunnable)
    }

    private fun syncPlaybackPositionPeriodically() {
        val expectedTrackKey = periodicPositionSyncKey ?: return
        val current = MediaState.current
        if (current?.lookupKey != expectedTrackKey) {
            cancelPeriodicPositionSync()
            return
        }

        val controller = attached
        if (controller == null) {
            cancelPeriodicPositionSync()
            return
        }

        publish(
            packageName = controller.packageName,
            metadata = controller.metadata,
            playbackState = controller.playbackState
        )
        rebaseCurrentPlaybackAnchor(expectedTrackKey)

        val refreshed = MediaState.current
        if (refreshed?.lookupKey == expectedTrackKey) {
            mainHandler.removeCallbacks(periodicPositionSyncRunnable)
            mainHandler.postDelayed(
                periodicPositionSyncRunnable,
                PERIODIC_POSITION_SYNC_MILLIS
            )
        } else {
            cancelPeriodicPositionSync()
        }
    }

    private fun rebaseCurrentPlaybackAnchor(trackKey: String) {
        val current = MediaState.current ?: return
        if (current.lookupKey != trackKey) return

        val now = SystemClock.elapsedRealtime()
        val position = current.estimatedPositionMillis(now) ?: return
        MediaState.set(
            current.copy(
                playbackPositionMillis = position,
                playbackUpdatedAtElapsedMillis = now
            )
        )
    }

    private fun waitBeforeLyricsLookup(): Boolean =
        try {
            Thread.sleep(LYRICS_LOOKUP_DEBOUNCE_MILLIS)
            !Thread.currentThread().isInterrupted
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun fetchLyricsWithRetries(
        query: LyricsQuery,
        ignoreCache: Boolean = false
    ): LyricsState {
        var result = lyricsClient.fetchLyrics(query, ignoreCache = ignoreCache)
        for (delayMillis in LYRICS_ERROR_RETRY_DELAYS_MILLIS) {
            if (result !is LyricsState.Error || Thread.currentThread().isInterrupted) return result
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return result
            }
            result = lyricsClient.fetchLyrics(query, ignoreCache = ignoreCache)
        }
        return result
    }

    private fun lyricsSavedOnDevice(query: LyricsQuery, result: LyricsState): Boolean =
        result is LyricsState.Found && lyricsCache.get(query) is LyricsState.Found

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

    private fun mergeWithCurrent(observed: TrackInfo, current: TrackInfo?): TrackInfo {
        if (current == null || !isSameLikelySong(observed, current)) return observed

        return observed.copy(
            artist = observed.artist ?: current.artist,
            album = observed.album ?: current.album,
            durationMillis = when {
                observed.durationMillis == null -> current.durationMillis
                current.durationMillis != null &&
                    durationsCompatible(observed.durationMillis, current.durationMillis) -> {
                    current.durationMillis
                }
                else -> observed.durationMillis
            },
            albumColors = observed.albumColors.ifEmpty { current.albumColors },
            lyrics = current.lyrics,
            lyricsSavedOnDevice = current.lyricsSavedOnDevice
        )
    }

    private fun isSameLikelySong(first: TrackInfo, second: TrackInfo): Boolean =
        normalize(first.title) == normalize(second.title) &&
            artistsCompatible(first.artist, second.artist) &&
            durationsCompatible(first.durationMillis, second.durationMillis)

    private fun artistsCompatible(first: String?, second: String?): Boolean {
        val left = normalize(first)
        val right = normalize(second)
        if (left.isEmpty() || right.isEmpty()) return true
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun durationsCompatible(first: Long?, second: Long?): Boolean {
        if (first == null || second == null) return true
        return abs(first - second) <= SAME_SONG_DURATION_TOLERANCE_MILLIS
    }

    private fun shouldIgnoreForLyrics(packageName: String?): Boolean =
        packageName
            ?.lowercase()
            ?.contains(AUDIBLE_PACKAGE_MARKER)
            ?: false

    private fun isSpotifyDjInterlude(title: String, artist: String?): Boolean =
        normalize(title) == SPOTIFY_DJ_UP_NEXT_TITLE &&
            normalize(artist).replace(" ", "") == SPOTIFY_DJ_ARTIST

    private fun normalize(value: String?): String =
        stripLosslessSuffix(value)
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()

    private fun stripLosslessSuffix(value: String?): String? =
        value
            ?.trim()
            ?.replace(LOSSLESS_SUFFIX_REGEX, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

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
        private const val SAME_SONG_DURATION_TOLERANCE_MILLIS = 3_000L
        private const val LYRICS_LOOKUP_DEBOUNCE_MILLIS = 500L
        private const val PERIODIC_POSITION_SYNC_MILLIS = 30_000L
        private val TRACK_START_POSITION_SYNC_DELAYS_MILLIS = longArrayOf(
            1_000L,
            1_000L,
            1_500L,
            1_500L,
            3_000L
        )
        private val LYRICS_ERROR_RETRY_DELAYS_MILLIS = longArrayOf(750L, 1_500L)
        private val LOSSLESS_SUFFIX_REGEX =
            Regex("""(?i)(?:\s*[-|/.•·]\s*|\s+)\(?lossless\)?$""")
        private const val AUDIBLE_PACKAGE_MARKER = "audible"
        private const val SPOTIFY_DJ_UP_NEXT_TITLE = "up next"
        private const val SPOTIFY_DJ_ARTIST = "djx"

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
