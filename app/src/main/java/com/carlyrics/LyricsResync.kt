package com.carlyrics

import java.util.concurrent.CopyOnWriteArraySet

object LyricsResync {

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun request() {
        listeners.forEach { it.onResyncRequested() }
    }

    fun observe(listener: Listener) {
        listeners.add(listener)
    }

    fun stopObserving(listener: Listener) {
        listeners.remove(listener)
    }

    fun interface Listener {
        fun onResyncRequested()
    }
}
