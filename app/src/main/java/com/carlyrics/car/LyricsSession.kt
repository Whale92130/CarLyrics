package com.carlyrics.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Per-connection session for the car-side surface.
 *
 * One [Session] is created each time the host binds to the app; it owns the
 * initial [Screen] and survives configuration changes within a single drive.
 */
class LyricsSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen = LyricsScreen(carContext)
}
