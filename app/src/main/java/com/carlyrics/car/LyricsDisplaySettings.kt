package com.carlyrics.car

import java.util.concurrent.CopyOnWriteArraySet

object LyricsDisplaySettings {

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var lightMode: Boolean = false
        private set

    fun setLightMode(enabled: Boolean) {
        if (lightMode == enabled) return
        lightMode = enabled
        notifyChanged()
    }

    fun reset() {
        setLightMode(false)
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
