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
 * Simulates an active navigation trip so the current lyric line appears in the
 * head-unit HUD (and the turn-card slot of compatible hosts). Coexists with
 * [LyricsSurfaceCallback]: both pull their text from [CurrentLyric] so the HUD
 * cue always matches the line drawn on the map surface.
 *
 * Two lifecycles are tracked separately:
 *  - [start] / [stop] bind to the [androidx.car.app.Session] lifetime.
 *  - The trip itself only runs while [LyricsDisplaySettings.hudTripEnabled] is
 *    true. Toggling the setting starts or ends the trip without recreating the
 *    publisher, so the host releases nav back to Maps/Waze immediately.
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
        if (LyricsDisplaySettings.hudTripEnabled) {
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
        val destinationName = track?.title?.takeIf { it.isNotBlank() } ?: "CarLyrics"
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
            .setRoad(destinationName)
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
