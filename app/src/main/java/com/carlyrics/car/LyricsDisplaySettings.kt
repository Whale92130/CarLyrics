package com.carlyrics.car

import java.util.concurrent.CopyOnWriteArraySet

object LyricsDisplaySettings {

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var lightMode: Boolean = false
        private set

    @Volatile
    var clusterInstructionsEnabled: Boolean = false
        private set

    @Volatile
    var mediaControlsEnabled: Boolean = false
        private set

    @Volatile
    private var mediaControlsInitializedFromSurface: Boolean = false

    @Volatile
    var measuredSurfaceWidth: Int = 0
        private set

    @Volatile
    var measuredSurfaceHeight: Int = 0
        private set

    fun setLightMode(enabled: Boolean) {
        if (lightMode == enabled) return
        lightMode = enabled
        notifyChanged()
    }

    fun setClusterInstructionsEnabled(enabled: Boolean) {
        if (clusterInstructionsEnabled == enabled) return
        clusterInstructionsEnabled = enabled
        notifyChanged()
    }

    fun setMediaControlsEnabled(enabled: Boolean) {
        mediaControlsInitializedFromSurface = true
        if (mediaControlsEnabled == enabled) return
        mediaControlsEnabled = enabled
        notifyChanged()
    }

    fun setMeasuredSurfaceSize(width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(0)
        val safeHeight = height.coerceAtLeast(0)
        if (measuredSurfaceWidth == safeWidth && measuredSurfaceHeight == safeHeight) return
        measuredSurfaceWidth = safeWidth
        measuredSurfaceHeight = safeHeight
        if (!mediaControlsInitializedFromSurface && safeWidth > 0 && safeHeight > 0) {
            mediaControlsEnabled = shouldEnableMediaControlsForRatio(safeWidth, safeHeight)
            mediaControlsInitializedFromSurface = true
        }
        notifyChanged()
    }

    fun measuredSurfaceText(): String =
        if (measuredSurfaceWidth > 0 && measuredSurfaceHeight > 0) {
            "${measuredSurfaceWidth}x${measuredSurfaceHeight}"
        } else {
            "Not measured yet"
        }

    fun shouldShowMediaControls(): Boolean = mediaControlsEnabled

    fun mediaControlsMenuText(): String {
        val defaultState = if (shouldEnableMediaControlsForMeasuredSurface()) "on" else "off"
        return "Default $defaultState for ${measuredSurfaceText()}"
    }

    fun reset() {
        val changed = lightMode ||
            clusterInstructionsEnabled ||
            mediaControlsEnabled != shouldEnableMediaControlsForMeasuredSurface()
        lightMode = false
        clusterInstructionsEnabled = false
        mediaControlsEnabled = shouldEnableMediaControlsForMeasuredSurface()
        mediaControlsInitializedFromSurface = measuredSurfaceWidth > 0 && measuredSurfaceHeight > 0
        if (changed) notifyChanged()
    }

    fun observe(listener: Listener) {
        listeners.add(listener)
        listener.onChanged()
    }

    fun stopObserving(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it.onChanged() }
    }

    private fun shouldEnableMediaControlsForMeasuredSurface(): Boolean =
        shouldEnableMediaControlsForRatio(measuredSurfaceWidth, measuredSurfaceHeight)

    private fun shouldEnableMediaControlsForRatio(width: Int, height: Int): Boolean =
        height > 0 &&
            width.toFloat() / height.toFloat() > MEDIA_CONTROLS_MIN_ASPECT_RATIO

    fun interface Listener {
        fun onChanged()
    }

    private const val MEDIA_CONTROLS_MIN_ASPECT_RATIO = 2f
}
