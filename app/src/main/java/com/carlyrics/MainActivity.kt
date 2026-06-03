package com.carlyrics

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.carlyrics.lyrics.CachedLyricsEntry
import com.carlyrics.lyrics.LrcLibClient
import com.carlyrics.lyrics.LyricsCache
import com.carlyrics.lyrics.LyricsQuery
import com.carlyrics.lyrics.LyricsState
import com.carlyrics.media.MediaMonitorService
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

private data class ManualSong(
    val title: String,
    val artist: String?
) {
    fun displayName(): String =
        listOfNotNull(title, artist).joinToString(" - ")
}

private data class ManualImportSummary(
    val total: Int,
    val saved: Int,
    val notFound: Int,
    val errors: Int
)

class MainActivity : AppCompatActivity() {

    private val lyricsCache by lazy { LyricsCache(applicationContext) }
    private val lyricsClient by lazy { LrcLibClient(lyricsCache) }
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private var importJob: Future<*>? = null
    private var showingDetail = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = BACKGROUND_COLOR
        window.navigationBarColor = BACKGROUND_COLOR
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showingDetail) {
                        cancelImport()
                        showList()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
        showList()
    }

    override fun onDestroy() {
        cancelImport()
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!showingDetail) showList()
    }

    private fun showList() {
        showingDetail = false
        val entries = lyricsCache.listEntries()
            .filter { entry -> entry.state is LyricsState.Found }

        val root = rootLayout()
        root.addView(
            header(
                "Downloaded Lyrics",
                actionText = "Refresh",
                topPaddingDp = LIST_HEADER_TOP_PADDING_DP
            ) { showList() }
        )
        root.addView(
            primaryButton("Import Manually") { showManualImport() },
            horizontalMarginParams(topMarginDp = 0, bottomMarginDp = 10)
        )
        root.addView(
            primaryButton(notificationPermissionButtonText()) {
                openNotificationPermissionSettings()
            },
            horizontalMarginParams(topMarginDp = 0, bottomMarginDp = 10)
        )
        root.addView(
            storageSummary(entries),
            horizontalMarginParams(topMarginDp = 0, bottomMarginDp = 12)
        )

        if (entries.isEmpty()) {
            root.addView(emptyState(), weightedContentParams())
        } else {
            val scrollView = ScrollView(this).apply {
                isFillViewport = true
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(20.dp(), 8.dp(), 20.dp(), 24.dp())
                        entries.forEach { entry -> addView(entryRow(entry)) }
                    },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            root.addView(scrollView, weightedContentParams())
        }

        setContentView(root)
    }

    private fun notificationPermissionButtonText(): String =
        if (isNotificationListenerEnabled()) {
            "Notification Permission On"
        } else {
            "Enable Notification Permission"
        }

    private fun openNotificationPermissionSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        Toast.makeText(
            this,
            "Enable notification access for Car Lyrics.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val monitorClassName = MediaMonitorService::class.java.name

        return enabledListeners
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { component ->
                component.packageName == packageName &&
                    component.className == monitorClassName
            }
    }

    private fun showManualImport() {
        showingDetail = true
        val root = rootLayout()
        root.addView(
            header(
                "Import Songs",
                actionText = "Back",
                topPaddingDp = LIST_HEADER_TOP_PADDING_DP
            ) {
                cancelImport()
                showList()
            }
        )

        val songsInput = inputField("Song Name - Artist").apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            minLines = 10
            gravity = Gravity.TOP
            setText(prefs.getString(PREF_PENDING_SONG_LIST, "").orEmpty())
        }
        val statusText = bodyText(
            "Ready",
            SECONDARY_TEXT_COLOR,
            topMarginDp = 16
        )
        val logText = TextView(this).apply {
            setTextColor(MUTED_TEXT_COLOR)
            textSize = 13f
            setLineSpacing(4.dp().toFloat(), 1.0f)
        }
        val importButton = primaryButton("Start Import") {
            startManualImport(
                rawSongs = songsInput.text.toString(),
                button = it as Button,
                statusText = statusText,
                logText = logText
            )
        }

        val scrollView = ScrollView(this).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(22.dp(), 10.dp(), 22.dp(), 32.dp())
                    addView(songsInput)
                    addView(importButton, topMarginParams(16))
                    addView(statusText)
                    addView(logText, topMarginParams(14))
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(scrollView, weightedContentParams())
        setContentView(root)
    }

    private fun startManualImport(
        rawSongs: String,
        button: Button,
        statusText: TextView,
        logText: TextView
    ) {
        val songs = parseManualSongs(rawSongs)
        if (songs.isEmpty()) {
            statusText.text = "No songs to import"
            return
        }

        cancelImport()
        prefs.edit().putString(PREF_PENDING_SONG_LIST, rawSongs).apply()
        button.isEnabled = false
        statusText.text = "Importing..."
        logText.text = ""

        importJob = importExecutor.submit {
            try {
                val summary = importManualSongs(songs) { message ->
                    mainHandler.post {
                        appendLog(logText, message)
                        statusText.text = message
                    }
                }
                mainHandler.post {
                    statusText.text =
                        "Done: ${summary.saved}/${summary.total} saved, ${summary.notFound} not found, ${summary.errors} errors"
                    button.isEnabled = true
                }
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) return@submit
                mainHandler.post {
                    statusText.text = error.message ?: "Import failed"
                    button.isEnabled = true
                }
            }
        }
    }

    private fun cancelImport() {
        importJob?.cancel(true)
        importJob = null
    }

    private fun appendLog(logText: TextView, message: String) {
        val current = logText.text?.toString().orEmpty()
        logText.text = if (current.isBlank()) message else "$current\n$message"
    }

    private fun parseManualSongs(rawSongs: String): List<ManualSong> =
        rawSongs
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .map { line ->
                val separator = line.lastIndexOf(" - ")
                if (separator >= 0) {
                    ManualSong(
                        title = line.substring(0, separator).trim(),
                        artist = line.substring(separator + 3).trim().takeIf { it.isNotEmpty() }
                    )
                } else {
                    ManualSong(title = line, artist = null)
                }
            }
            .filter { song -> song.title.isNotEmpty() }
            .toList()

    private fun importManualSongs(
        songs: List<ManualSong>,
        onProgress: (String) -> Unit
    ): ManualImportSummary {
        var saved = 0
        var notFound = 0
        var errors = 0

        for ((index, song) in songs.withIndex()) {
            if (Thread.currentThread().isInterrupted) break
            val label = song.displayName()
            onProgress("${index + 1}/${songs.size}: Searching $label")
            val result = lyricsClient.fetchLyrics(
                LyricsQuery(
                    trackName = song.title,
                    artistName = song.artist,
                    albumName = null,
                    durationSeconds = null
                )
            )
            val resultText = when (result) {
                is LyricsState.Found -> {
                    saved++
                    "saved ${result.lines.size} lines"
                }
                LyricsState.Instrumental -> {
                    saved++
                    "saved instrumental"
                }
                LyricsState.NotFound -> {
                    notFound++
                    "not found"
                }
                is LyricsState.Error -> {
                    errors++
                    "error: ${result.message}"
                }
                LyricsState.Loading -> "loading"
            }
            onProgress("${index + 1}/${songs.size}: $label - $resultText")
        }

        return ManualImportSummary(
            total = songs.size,
            saved = saved,
            notFound = notFound,
            errors = errors
        )
    }

    private fun showDetail(entry: CachedLyricsEntry) {
        showingDetail = true
        val root = rootLayout()
        root.addView(
            header(
                "Lyrics",
                actionText = "Back",
                topPaddingDp = LIST_HEADER_TOP_PADDING_DP
            ) { showList() }
        )

        val scrollView = ScrollView(this).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(22.dp(), 10.dp(), 22.dp(), 32.dp())
                    addView(titleText(entry.trackName, sizeSp = 24f))
                    entry.artistName?.let { artist ->
                        addView(bodyText(artist, SECONDARY_TEXT_COLOR, topMarginDp = 4))
                    }
                    addView(bodyText(detailMeta(entry), MUTED_TEXT_COLOR, topMarginDp = 8))
                    addView(lyricsText(entry), topMarginParams(22))
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(scrollView, weightedContentParams())
        setContentView(root)
    }

    private fun entryRow(entry: CachedLyricsEntry): View {
        val found = entry.state as LyricsState.Found
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            background = rounded(CARD_COLOR)
            setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())
            setOnClickListener { showDetail(entry) }

            addView(titleText(entry.trackName, sizeSp = 18f))
            entry.artistName?.let { artist ->
                addView(bodyText(artist, SECONDARY_TEXT_COLOR, topMarginDp = 3))
            }
            addView(
                bodyText(
                    listOf(
                        if (found.synced) "Synced" else "Plain",
                        "${found.lines.size} lines",
                        formatStorage(entry.storageBytes),
                        "Saved ${savedDate(entry.cachedAtMillis)}"
                    ).joinToString(" | "),
                    MUTED_TEXT_COLOR,
                    topMarginDp = 8
                )
            )

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp()
            }
        }
    }

    private fun lyricsText(entry: CachedLyricsEntry): TextView {
        val found = entry.state as? LyricsState.Found
        val text = found
            ?.lines
            ?.joinToString(separator = "\n") { line -> line.text }
            .orEmpty()
        return TextView(this).apply {
            setTextColor(PRIMARY_TEXT_COLOR)
            textSize = 20f
            setLineSpacing(8.dp().toFloat(), 1.0f)
            this.text = text
        }
    }

    private fun detailMeta(entry: CachedLyricsEntry): String {
        val found = entry.state as LyricsState.Found
        return listOfNotNull(
            if (found.synced) "Synced lyrics" else "Plain lyrics",
            entry.albumName,
            entry.durationSeconds?.let { duration -> formatDuration(duration) },
            "${found.lines.size} lines",
            formatStorage(entry.storageBytes),
            "Saved ${savedDate(entry.cachedAtMillis)}"
        ).joinToString(" | ")
    }

    private fun storageSummary(entries: List<CachedLyricsEntry>): TextView =
        TextView(this).apply {
            val totalBytes = entries.sumOf { entry -> entry.storageBytes }
            text = "${entries.size} songs | ${formatStorage(totalBytes)} stored"
            setTextColor(MUTED_TEXT_COLOR)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun rootLayout(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND_COLOR)
        }

    private fun header(
        title: String,
        actionText: String,
        topPaddingDp: Int = DEFAULT_HEADER_TOP_PADDING_DP,
        action: () -> Unit
    ): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(20.dp(), topPaddingDp.dp(), 16.dp(), 12.dp())

            addView(
                titleText(title, sizeSp = 26f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                Button(context).apply {
                    text = actionText
                    isAllCaps = false
                    setTextColor(PRIMARY_TEXT_COLOR)
                    setOnClickListener { action() }
                }
            )
        }

    private fun primaryButton(text: String, onClick: (View) -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(PRIMARY_TEXT_COLOR)
            setOnClickListener(onClick)
        }

    private fun inputField(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setTextColor(PRIMARY_TEXT_COLOR)
            setHintTextColor(MUTED_TEXT_COLOR)
            background = rounded(CARD_COLOR)
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
        }

    private fun emptyState(): TextView =
        TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(28.dp(), 28.dp(), 28.dp(), 28.dp())
            setTextColor(SECONDARY_TEXT_COLOR)
            textSize = 18f
            text = "No downloaded lyrics yet"
        }

    private fun titleText(text: String, sizeSp: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(PRIMARY_TEXT_COLOR)
            textSize = sizeSp
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun bodyText(text: String, color: Int, topMarginDp: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = topMarginParams(topMarginDp)
        }

    private fun topMarginParams(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = topMarginDp.dp()
        }

    private fun horizontalMarginParams(
        topMarginDp: Int,
        bottomMarginDp: Int
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 20.dp()
            rightMargin = 20.dp()
            topMargin = topMarginDp.dp()
            bottomMargin = bottomMarginDp.dp()
        }

    private fun weightedContentParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

    private fun rounded(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = 8.dp().toFloat()
            setStroke(1.dp(), STROKE_COLOR)
        }

    private fun selectableForeground() =
        TypedValue()
            .also { value ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            }
            .let { value -> getDrawable(value.resourceId) }

    private fun savedDate(millis: Long): String =
        if (millis <= 0L) {
            "unknown"
        } else {
            DateFormat
                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(millis))
        }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "%d:%02d".format(minutes, remainingSeconds)
    }

    private fun formatStorage(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        return when {
            safeBytes < 1_024L -> "$safeBytes B"
            safeBytes < 1_024L * 1_024L -> "%.1f KB".format(safeBytes / 1_024.0)
            else -> "%.1f MB".format(safeBytes / (1_024.0 * 1_024.0))
        }
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).roundToInt()

    companion object {
        private val BACKGROUND_COLOR = Color.rgb(14, 16, 19)
        private val CARD_COLOR = Color.rgb(28, 32, 36)
        private val STROKE_COLOR = Color.rgb(55, 61, 67)
        private val PRIMARY_TEXT_COLOR = Color.rgb(246, 248, 250)
        private val SECONDARY_TEXT_COLOR = Color.rgb(190, 198, 207)
        private val MUTED_TEXT_COLOR = Color.rgb(138, 148, 158)
        private const val DEFAULT_HEADER_TOP_PADDING_DP = 18
        private const val LIST_HEADER_TOP_PADDING_DP = 44
        private const val PREFS_NAME = "manual_import"
        private const val PREF_PENDING_SONG_LIST = "pending_song_list"
    }
}
