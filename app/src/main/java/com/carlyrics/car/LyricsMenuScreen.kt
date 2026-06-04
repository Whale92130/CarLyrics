package com.carlyrics.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import com.carlyrics.AppReset
import com.carlyrics.media.MediaState

class
LyricsMenuScreen(carContext: CarContext) : Screen(carContext) {

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

        val clusterInstructionsRow = Row.Builder()
            .setTitle("HUD nav")
            .addText("Show lyrics as fake navigation prompts")
            .setToggle(
                Toggle.Builder { enabled ->
                    LyricsDisplaySettings.setClusterInstructionsEnabled(enabled)
                    invalidate()
                }
                    .setChecked(LyricsDisplaySettings.clusterInstructionsEnabled)
                    .build()
            )
            .build()

        val mediaControlsRow = Row.Builder()
            .setTitle("Media controls")
            .addText(LyricsDisplaySettings.mediaControlsMenuText())
            .setToggle(
                Toggle.Builder { enabled ->
                    LyricsDisplaySettings.setMediaControlsEnabled(enabled)
                    invalidate()
                }
                    .setChecked(LyricsDisplaySettings.mediaControlsEnabled)
                    .build()
            )
            .build()

        val nowPlayingRow = Row.Builder()
            .setTitle("Now playing")
            .addText(nowPlayingText())
            .build()

        val recenterLyricsRow = Row.Builder()
            .setTitle("Recenter Lyrics")
            .addText("Restore display layout defaults")
            .setOnClickListener {
                LyricsDisplaySettings.reset()
                invalidate()
                finish()
            }
            .build()

        val fixLyricsRow = Row.Builder()
            .setTitle("Fix Lyrics")
            .addText("Fetch lyrics again")
            .setOnClickListener {
                AppReset.request()
                invalidate()
                finish()
            }
            .build()

        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle("Menu")
            .setHeaderAction(Action.BACK)
            .setSingleList(
                ItemList.Builder()
                    .addItem(recenterLyricsRow)
                    .addItem(fixLyricsRow)
                    .addItem(clusterInstructionsRow)
                    .addItem(lightModeRow)
                    .addItem(mediaControlsRow)
                    .addItem(nowPlayingRow)
                    .build()
            )
            .build()
    }

    private fun nowPlayingText(): String {
        val track = MediaState.current ?: return "No song playing"
        return listOfNotNull(
            track.title?.takeIf { it.isNotBlank() },
            track.artist?.takeIf { it.isNotBlank() }
        )
            .joinToString(" - ")
            .ifBlank { "No song playing" }
    }
}
