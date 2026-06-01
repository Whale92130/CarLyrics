package com.carlyrics

import java.util.concurrent.CopyOnWriteArraySet

object AppReset {

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun request() {
        listeners.forEach { it.onResetRequested() }
    }

    fun observe(listener: Listener) {
        listeners.add(listener)
    }

    fun stopObserving(listener: Listener) {
        listeners.remove(listener)
    }

    fun interface Listener {
        fun onResetRequested()
    }
}
