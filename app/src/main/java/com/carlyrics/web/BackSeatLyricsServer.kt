package com.carlyrics.web

import android.os.SystemClock
import com.carlyrics.car.LyricsDisplaySettings
import com.carlyrics.lyrics.CurrentLyric
import com.carlyrics.media.MediaState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Collections

object BackSeatLyricsServer {

    const val PORT = 8080

    private val lock = Any()

    @Volatile
    private var shouldRun = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean
        get() = shouldRun && serverSocket?.isClosed == false && worker?.isAlive == true

    fun start() {
        synchronized(lock) {
            if (shouldRun && worker?.isAlive == true) return
            shouldRun = true
            lastError = null
            worker = Thread(::runServer, "BackSeatLyricsServer").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            shouldRun = false
            serverSocket?.close()
            serverSocket = null
            worker?.interrupt()
            worker = null
        }
    }

    fun shareUrl(): String? =
        localIpAddress()?.let { address -> "http://$address:$PORT" }

    fun localIpAddress(): String? =
        try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { networkInterface ->
                    runCatching {
                        networkInterface.isUp && !networkInterface.isLoopback
                    }.getOrDefault(false)
                }
                .flatMap { networkInterface ->
                    Collections.list(networkInterface.inetAddresses)
                        .asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filter { address ->
                            !address.isLoopbackAddress && address.isSiteLocalAddress
                        }
                        .mapNotNull { address ->
                            address.hostAddress?.let { hostAddress ->
                                InterfaceAddress(networkInterface.name, hostAddress)
                            }
                        }
                }
                .sortedWith(compareBy<InterfaceAddress> { scoreAddress(it) }.thenBy { it.address })
                .firstOrNull()
                ?.address
        } catch (_: SocketException) {
            null
        }

    private fun runServer() {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", PORT))
            serverSocket = socket

            while (shouldRun) {
                val client = try {
                    socket.accept()
                } catch (error: SocketException) {
                    if (shouldRun) throw error else null
                }
                if (client == null) break
                Thread({ handleClient(client) }, "BackSeatLyricsClient").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (error: Exception) {
            if (shouldRun) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        } finally {
            shouldRun = false
            serverSocket = null
            runCatching { socket.close() }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = CLIENT_TIMEOUT_MILLIS
            val reader = BufferedReader(
                InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
            )
            val requestLine = reader.readLine().orEmpty()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val path = requestLine
                .split(' ')
                .getOrNull(1)
                ?.substringBefore('?')
                ?: "/"

            val response = when (path) {
                "/", "/index.html" -> Response("text/html; charset=utf-8", htmlPage())
                "/state" -> Response("application/json; charset=utf-8", stateJson())
                "/favicon.ico" -> Response("text/plain; charset=utf-8", "", "404 Not Found")
                else -> Response("text/plain; charset=utf-8", "Not found", "404 Not Found")
            }

            writeResponse(client, response)
        }
    }

    private fun writeResponse(socket: Socket, response: Response) {
        val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val writer = BufferedWriter(
            OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        )
        writer.write("HTTP/1.1 ${response.status}\r\n")
        writer.write("Content-Type: ${response.contentType}\r\n")
        writer.write("Content-Length: ${bodyBytes.size}\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()
        socket.getOutputStream().write(bodyBytes)
        socket.getOutputStream().flush()
    }

    private fun stateJson(): String {
        val track = MediaState.current
        val snapshot = CurrentLyric.snapshotFor(
            track,
            SystemClock.elapsedRealtime()
        )
        val positionMillis = snapshot.positionMillis
        val durationMillis = snapshot.durationMillis
        val songLabel = listOfNotNull(
            track?.title,
            track?.artist
        ).joinToString(" - ")

        return buildString {
            append('{')
            appendJsonField("title", snapshot.title)
            append(',')
            appendJsonField("artist", snapshot.artist.orEmpty())
            append(',')
            appendJsonField("previousLine", snapshot.previousLine.orEmpty())
            append(',')
            appendJsonField("currentLine", snapshot.currentLine)
            append(',')
            appendJsonField("nextLine", snapshot.nextLine.orEmpty())
            append(',')
            appendJsonArrayField("lines", snapshot.lines)
            append(',')
            append("\"currentIndex\":")
            append(snapshot.currentIndex)
            append(',')
            appendJsonField("lyricsStatus", snapshot.lyricsStatus)
            append(',')
            append("\"positionMillis\":")
            append(positionMillis ?: "null")
            append(',')
            append("\"durationMillis\":")
            append(durationMillis ?: "null")
            append(',')
            appendJsonField("positionText", positionMillis?.let(::formatTime).orEmpty())
            append(',')
            appendJsonField("durationText", durationMillis?.let(::formatTime).orEmpty())
            append(',')
            appendJsonField("songLabel", songLabel)
            append(',')
            appendJsonField("accentColor", track?.albumColors?.firstOrNull()?.let(::cssColor).orEmpty())
            append(',')
            append("\"lightMode\":")
            append(LyricsDisplaySettings.lightMode)
            append(',')
            append("\"lyricsSavedOnDevice\":")
            append(track?.lyricsSavedOnDevice == true)
            append(',')
            appendJsonField("serverStatus", "live")
            append('}')
        }
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append('"')
        append(name)
        append("\":\"")
        append(jsonEscape(value))
        append('"')
    }

    private fun StringBuilder.appendJsonArrayField(name: String, values: List<String>) {
        append('"')
        append(name)
        append("\":[")
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append('"')
            append(jsonEscape(value))
            append('"')
        }
        append(']')
    }

    private fun jsonEscape(value: String): String =
        buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private fun cssColor(color: Int): String =
        "#%06X".format(color and 0x00FFFFFF)

    private fun scoreAddress(address: InterfaceAddress): Int {
        val name = address.interfaceName.lowercase()
        val host = address.address
        return when {
            name.contains("ap") || name.contains("wlan") || name.contains("wifi") -> 0
            host.startsWith("192.168.") -> 1
            host.startsWith("172.") -> 2
            host.startsWith("10.") -> 3
            else -> 4
        }
    }

    private fun htmlPage(): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>CarLyrics Back-seat Lyrics</title>
          <style>
            :root {
              color-scheme: dark;
              font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              background: #0a0a0a;
              color: #ffffff;
              --bg: #0a0a0a;
              --fg: #ffffff;
              --muted: rgba(255, 255, 255, 0.54);
              --faint: rgba(255, 255, 255, 0.20);
              --button-bg: rgba(0, 0, 0, 0.66);
              --button-stroke: rgba(255, 255, 255, 0.54);
              --shadow: 0 2px 8px rgba(0, 0, 0, 0.95);
              --accent: #ffffff;
            }
            * { box-sizing: border-box; }
            html, body { width: 100%; min-height: 100%; }
            body {
              margin: 0;
              min-height: 100vh;
              overflow: hidden;
              background: var(--bg);
              color: var(--fg);
            }
            body.light {
              color-scheme: light;
              --bg: #ffffff;
              --fg: #000000;
              --muted: rgba(0, 0, 0, 0.54);
              --faint: rgba(0, 0, 0, 0.12);
              --button-bg: rgba(255, 255, 255, 0.86);
              --button-stroke: rgba(0, 0, 0, 0.36);
              --shadow: none;
            }
            main {
              position: relative;
              width: 100vw;
              height: 100vh;
              min-height: 100vh;
              background: var(--bg);
            }
            .connection {
              position: absolute;
              top: max(12px, env(safe-area-inset-top));
              right: max(12px, env(safe-area-inset-right));
              z-index: 3;
              color: var(--fg);
              background: var(--button-bg);
              border: 1px solid var(--button-stroke);
              border-radius: 12px;
              padding: 7px 10px;
              font-size: clamp(12px, 2vw, 14px);
              font-weight: 700;
              line-height: 1;
              text-shadow: var(--shadow);
            }
            .time {
              position: absolute;
              top: max(12px, env(safe-area-inset-top));
              left: max(12px, env(safe-area-inset-left));
              z-index: 3;
              color: var(--muted);
              font-size: clamp(12px, 2vw, 14px);
              font-weight: 700;
              text-shadow: var(--shadow);
            }
            .lyrics {
              position: absolute;
              inset:
                clamp(28px, 12vh, 80px)
                clamp(18px, 4vw, 54px);
              overflow-y: auto;
              padding: 38vh 0 42vh;
              text-align: center;
              scroll-behavior: smooth;
              scrollbar-width: none;
            }
            .lyrics::-webkit-scrollbar {
              display: none;
            }
            .lyric-line {
              width: min(100%, 1500px);
              margin: 0 auto;
              padding: clamp(9px, 2vh, 18px) 0;
              color: var(--muted);
              font-weight: 750;
              font-size: clamp(20px, 5vmin, 44px);
              line-height: 1.08;
              text-shadow: var(--shadow);
              overflow-wrap: anywhere;
              text-wrap: balance;
              transition:
                color 420ms cubic-bezier(0.22, 1, 0.36, 1),
                font-size 420ms cubic-bezier(0.22, 1, 0.36, 1),
                opacity 420ms cubic-bezier(0.22, 1, 0.36, 1),
                transform 420ms cubic-bezier(0.22, 1, 0.36, 1);
              opacity: 0.70;
              transform: scale(0.985);
              will-change: color, font-size, opacity, transform;
            }
            .lyric-line.active {
              color: var(--fg);
              font-weight: 900;
              font-size: clamp(42px, 13vmin, 124px);
              line-height: 1.02;
              text-shadow:
                var(--shadow),
                0 0 18px rgba(255, 255, 255, 0.28),
                0 0 34px rgba(255, 255, 255, 0.16);
              opacity: 1;
              transform: scale(1.025);
            }
            .footer {
              position: absolute;
              left: clamp(48px, 10vw, 96px);
              right: clamp(48px, 10vw, 96px);
              bottom: max(18px, calc(env(safe-area-inset-bottom) + 18px));
              display: flex;
              align-items: center;
              justify-content: center;
              gap: 8px;
              color: var(--fg);
              font-size: clamp(16px, 3.2vmin, 28px);
              font-weight: 800;
              line-height: 1.1;
              text-align: center;
              text-shadow: var(--shadow);
              white-space: nowrap;
              overflow: hidden;
            }
            .song {
              min-width: 0;
              overflow: hidden;
              text-overflow: ellipsis;
            }
            .saved {
              flex: 0 0 auto;
              display: none;
              width: 22px;
              height: 22px;
              color: var(--fg);
              filter: drop-shadow(var(--shadow));
              align-items: center;
              justify-content: center;
            }
            .saved.visible { display: inline-flex; }
            .saved svg {
              width: 100%;
              height: 100%;
              display: block;
              overflow: visible;
            }
            .progress {
              position: absolute;
              left: clamp(64px, 10vw, 128px);
              right: clamp(64px, 10vw, 128px);
              bottom: max(7px, calc(env(safe-area-inset-bottom) + 7px));
              height: 5px;
              background: var(--faint);
              border-radius: 999px;
              overflow: hidden;
            }
            .fill {
              height: 100%;
              width: 0%;
              background: var(--fg);
              border-radius: inherit;
              box-shadow: var(--shadow);
            }
            @media (max-width: 640px) {
              body { overflow: auto; }
              main { min-height: 100svh; }
              .lyrics {
                inset:
                  clamp(54px, 12vh, 76px)
                  clamp(12px, 4vw, 24px)
                  clamp(78px, 16vh, 110px);
                padding: 36vh 0 42vh;
              }
              .lyric-line.active {
                font-size: clamp(44px, 15.5vw, 82px);
              }
              .lyric-line {
                font-size: clamp(18px, 7vw, 34px);
              }
              .footer {
                left: 20px;
                right: 20px;
                font-size: clamp(15px, 5vw, 22px);
              }
              .progress {
                left: 28px;
                right: 28px;
              }
            }
          </style>
        </head>
        <body>
          <main>
            <div id="connection" class="connection">Connecting</div>
            <div id="time" class="time">--:-- / --:--</div>
            <section id="lyrics" class="lyrics" aria-live="polite">
              <div class="lyric-line active">No song playing</div>
            </section>
            <div class="footer">
              <span id="song" class="song">No song playing</span>
              <span id="saved" class="saved" aria-label="Saved on device">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 3v11" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>
                  <path d="M7.5 9.8 12 14.3l4.5-4.5" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M5.5 20.5h13" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>
                </svg>
              </span>
            </div>
            <div class="progress"><div id="progress" class="fill"></div></div>
          </main>
          <script>
            const elements = {
              lyrics: document.getElementById('lyrics'),
              connection: document.getElementById('connection'),
              time: document.getElementById('time'),
              progress: document.getElementById('progress'),
              song: document.getElementById('song'),
              saved: document.getElementById('saved')
            };
            let lastLinesKey = '';
            let lastIndex = -1;

            function update(data) {
              document.body.classList.toggle('light', Boolean(data.lightMode));
              document.documentElement.style.setProperty('--accent', data.accentColor || 'var(--fg)');
              renderLyrics(data);
              elements.connection.textContent = 'Live';
              elements.song.textContent = data.songLabel || data.title || 'No song playing';
              elements.saved.classList.toggle('visible', Boolean(data.lyricsSavedOnDevice));

              const position = data.positionText || '--:--';
              const duration = data.durationText || '--:--';
              elements.time.textContent = position + ' / ' + duration;

              const progress = data.positionMillis != null && data.durationMillis > 0
                ? Math.max(0, Math.min(1, data.positionMillis / data.durationMillis))
                : 0;
              elements.progress.style.width = (progress * 100).toFixed(1) + '%';
            }

            function renderLyrics(data) {
              const lines = Array.isArray(data.lines) && data.lines.length
                ? data.lines
                : [data.currentLine || 'No song playing'];
              const index = Math.max(0, Math.min(lines.length - 1, Number(data.currentIndex) || 0));
              const linesKey = JSON.stringify(lines);

              if (linesKey !== lastLinesKey) {
                const fragment = document.createDocumentFragment();
                lines.forEach((line, lineIndex) => {
                  const node = document.createElement('div');
                  node.className = 'lyric-line';
                  node.dataset.index = String(lineIndex);
                  node.textContent = line || ' ';
                  fragment.appendChild(node);
                });
                elements.lyrics.replaceChildren(fragment);
                lastLinesKey = linesKey;
                lastIndex = -1;
              }

              if (index !== lastIndex) {
                const previous = elements.lyrics.querySelector('.lyric-line.active');
                if (previous) previous.classList.remove('active');
                const active = elements.lyrics.querySelector('.lyric-line[data-index="' + index + '"]');
                if (active) {
                  active.classList.add('active');
                  requestAnimationFrame(() => {
                    active.scrollIntoView({ block: 'center', behavior: 'smooth' });
                  });
                }
                lastIndex = index;
              }
            }

            async function poll() {
              try {
                const response = await fetch('/state', { cache: 'no-store' });
                if (!response.ok) throw new Error('HTTP ' + response.status);
                update(await response.json());
              } catch (error) {
                elements.connection.textContent = 'Disconnected';
              } finally {
                setTimeout(poll, 500);
              }
            }

            poll();
          </script>
        </body>
        </html>
        """.trimIndent()

    private data class InterfaceAddress(
        val interfaceName: String,
        val address: String
    )

    private data class Response(
        val contentType: String,
        val body: String,
        val status: String = "200 OK"
    )

    private const val CLIENT_TIMEOUT_MILLIS = 2_000
}
