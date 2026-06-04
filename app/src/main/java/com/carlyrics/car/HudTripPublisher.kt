package com.carlyrics.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import com.carlyrics.lyrics.CurrentLyric
import com.carlyrics.media.MediaState
import java.util.TimeZone

/**
 * Publishes the current lyric line as a fake navigation step so DHU's
 * instrument cluster, and compatible car clusters, can show it as turn text.
 *
 * This is disabled by default. It only starts a navigation session when the
 * user enables the cluster toggle in the car menu.
 */
class HudTripPublisher(carContext: CarContext) {

    private val navigationManager: NavigationManager =
        carContext.getCarService(NavigationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var attached = false
    private var tripActive = false
    private var lastCue: String? = null
    private var lastDestinationName: String? = null
    private var lastDestinationAddress: String? = null

    private val trackListener = MediaState.Listener {
        mainHandler.post {
            publish()
            scheduleTick()
        }
    }

    private val settingsListener = LyricsDisplaySettings.Listener {
        mainHandler.post { applyEnabledState() }
    }

    private val tick = object : Runnable {
        override fun run() {
            publish()
            scheduleTick()
        }
    }

    private val callback = object : NavigationManagerCallback {
        override fun onStopNavigation() {
            Log.d(TAG, "onStopNavigation()")
            endTrip()
        }
    }

    fun start() {
        if (attached) return
        attached = true
        MediaState.observe(trackListener)
        LyricsDisplaySettings.observe(settingsListener)
        applyEnabledState()
    }

    fun stop() {
        if (!attached) return
        attached = false
        LyricsDisplaySettings.stopObserving(settingsListener)
        MediaState.stopObserving(trackListener)
        endTrip()
    }

    private fun applyEnabledState() {
        if (!attached) return
        if (LyricsDisplaySettings.clusterInstructionsEnabled) {
            beginTrip()
        } else {
            endTrip()
        }
    }

    private fun beginTrip() {
        if (tripActive) {
            publish()
            scheduleTick()
            return
        }
        try {
            navigationManager.setNavigationManagerCallback(callback)
            navigationManager.navigationStarted()
            Log.d(TAG, "navigationStarted()")
        } catch (e: Exception) {
            Log.w(TAG, "navigationStarted failed", e)
            return
        }
        tripActive = true
        publish()
        scheduleTick()
    }

    private fun endTrip() {
        mainHandler.removeCallbacks(tick)
        if (!tripActive) return
        tripActive = false
        try {
            navigationManager.navigationEnded()
            Log.d(TAG, "navigationEnded()")
        } catch (e: Exception) {
            Log.w(TAG, "navigationEnded failed", e)
        }
        try {
            navigationManager.clearNavigationManagerCallback()
        } catch (e: Exception) {
            Log.w(TAG, "clearNavigationManagerCallback failed", e)
        }
        lastCue = null
        lastDestinationName = null
        lastDestinationAddress = null
    }

    private fun publish() {
        if (!tripActive) return

        val track = MediaState.current
        val cue = CurrentLyric.textFor(track, SystemClock.elapsedRealtime())
            .ifBlank { MUSIC_NOTE }
        val destinationName = track?.title?.takeIf { it.isNotBlank() } ?: "Car Lyrics"
        val destinationAddress = track?.artist?.takeIf { it.isNotBlank() } ?: " "

        if (cue == lastCue &&
            destinationName == lastDestinationName &&
            destinationAddress == lastDestinationAddress
        ) {
            return
        }

        val trip = try {
            buildTrip(cue, destinationName, destinationAddress)
        } catch (e: Exception) {
            Log.w(TAG, "Trip build failed cue='$cue'", e)
            return
        }

        try {
            navigationManager.updateTrip(trip)
            Log.d(TAG, "updateTrip(): $cue")
            lastCue = cue
            lastDestinationName = destinationName
            lastDestinationAddress = destinationAddress
        } catch (e: Exception) {
            Log.w(TAG, "updateTrip failed", e)
        }
    }

    private fun buildTrip(
        cue: String,
        destinationName: String,
        destinationAddress: String
    ): Trip {
        val maneuver = Maneuver.Builder(Maneuver.TYPE_STRAIGHT).build()
        val step = Step.Builder(cue)
            .setManeuver(maneuver)
            .setRoad(cue)
            .build()

        val arrival = DateTimeWithZone.create(System.currentTimeMillis(), TimeZone.getDefault())
        val zeroDistance = Distance.create(0.0, Distance.UNIT_METERS)
        val estimate = TravelEstimate.Builder(zeroDistance, arrival)
            .setRemainingTimeSeconds(0L)
            .build()

        val destination = Destination.Builder()
            .setName(destinationName)
            .setAddress(destinationAddress)
            .build()

        return Trip.Builder()
            .addStep(step, estimate)
            .addDestination(destination, estimate)
            .setCurrentRoad(destinationName)
            .build()
    }

    private fun scheduleTick() {
        mainHandler.removeCallbacks(tick)
        if (!tripActive) return
        val track = MediaState.current ?: return
        if (track.playbackSpeed <= 0f) return
        if (track.playbackPositionMillis == null) return
        mainHandler.postDelayed(tick, TICK_MILLIS)
    }

    companion object {
        private const val TAG = "HudTripPublisher"
        private const val TICK_MILLIS = 250L
        private const val MUSIC_NOTE = "♪"
    }
}
