package com.carlyrics.car

import java.util.concurrent.CopyOnWriteArraySet

object LyricsDisplaySettings {

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var lightMode: Boolean = false
        private set

    @Volatile
    var hudTripEnabled: Boolean = true
        private set

    fun setLightMode(enabled: Boolean) {
        if (lightMode == enabled) return
        lightMode = enabled
        notifyChanged()
    }

    fun setHudTripEnabled(enabled: Boolean) {
        if (hudTripEnabled == enabled) return
        hudTripEnabled = enabled
        notifyChanged()
    }

    fun reset() {
        val changed = lightMode || !hudTripEnabled
        lightMode = false
        hudTripEnabled = true
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

    fun interface Listener {
        fun onChanged()
    }
}
