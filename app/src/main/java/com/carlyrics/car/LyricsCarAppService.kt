package com.carlyrics.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the car-side surface of CarLyrics.
 *
 * Declared in the manifest with the NAVIGATION category so Android Auto exposes
 * the app under the navigation role. The host instantiates this service when
 * the user opens CarLyrics from the head unit and asks for a [Session] to drive
 * the UI.
 *
 * Sideload-only: [createHostValidator] returns [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR]
 * so the Desktop Head Unit and any debug host can connect without signature checks.
 * Tighten before any public distribution.
 */
class LyricsCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = LyricsSession()
}
