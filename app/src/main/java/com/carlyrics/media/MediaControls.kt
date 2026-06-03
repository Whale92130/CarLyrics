package com.carlyrics.media

import android.media.session.MediaController
import android.media.session.PlaybackState

object MediaControls {

    @Volatile
    private var controller: MediaController? = null

    fun setController(mediaController: MediaController) {
        controller = mediaController
    }

    fun clearController(mediaController: MediaController?) {
        if (mediaController == null || controller?.sessionToken == mediaController.sessionToken) {
            controller = null
        }
    }

    fun isPlaying(): Boolean =
        when (controller?.playbackState?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> true
            else -> false
        }

    fun togglePlayPause() {
        val controls = controller?.transportControls ?: return
        if (isPlaying()) {
            controls.pause()
        } else {
            controls.play()
        }
    }

    fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }
}
