package com.carlyrics.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle

class LyricsMenuScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val lightModeRow = Row.Builder()
            .setTitle("Light mode")
            .addText("Use a light lyrics surface")
            .setToggle(
                Toggle.Builder { enabled ->
                    LyricsDisplaySettings.setLightMode(enabled)
                    invalidate()
                }
                    .setChecked(LyricsDisplaySettings.lightMode)
                    .build()
            )
            .build()

        val resetRow = Row.Builder()
            .setTitle("Reset")
            .addText("Restore default display settings")
            .setOnClickListener {
                LyricsDisplaySettings.reset()
                invalidate()
            }
            .build()

        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle("Menu")
            .setHeaderAction(Action.BACK)
            .setSingleList(
                ItemList.Builder()
                    .addItem(lightModeRow)
                    .addItem(resetRow)
                    .build()
            )
            .build()
    }
}
