package com.carlyrics.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Per-connection session for the car-side surface.
 *
 * One [Session] is created each time the host binds to the app; it owns the
 * initial [Screen] and survives configuration changes within a single drive.
 */
class LyricsSession : Session() {

    private var hudPublisher: HudTripPublisher? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                hudPublisher?.stop()
                hudPublisher = null
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        if (hudPublisher == null) {
            hudPublisher = HudTripPublisher(carContext).also { it.start() }
        }
        return LyricsScreen(carContext)
    }
}
