package com.carlyrics.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Car-side screen for the map surface. The template intentionally has no pane or
 * navigation info so lyrics are rendered only by [LyricsSurfaceCallback].
 */
class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    private val surfaceCallback = LyricsSurfaceCallback()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                registerSurfaceCallback()
            }
        })
    }

    override fun onGetTemplate(): Template {
        registerSurfaceCallback()
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("Lyrics").build())
                    .build()
            )
            .build()
    }

    private fun registerSurfaceCallback() {
        carContext.getCarService(AppManager::class.java)
            .setSurfaceCallback(surfaceCallback)
    }
}
