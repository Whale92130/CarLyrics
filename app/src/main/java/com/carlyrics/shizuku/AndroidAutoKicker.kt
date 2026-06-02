package com.carlyrics.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Force-stops Android Auto and re-registers the CarLyrics CarAppService via
 * Shizuku. Equivalent to running:
 *
 *   am force-stop com.google.android.projection.gearhead
 *   pm disable com.carlyrics/.car.LyricsCarAppService
 *   (wait 1s)
 *   pm enable  com.carlyrics/.car.LyricsCarAppService
 *   monkey -p com.carlyrics -c android.intent.category.LAUNCHER 1
 *   am start-service -a androidx.car.app.CarAppService -n com.carlyrics/.car.LyricsCarAppService
 *
 * The `am force-stop` + `pm disable/enable` commands need shell uid; Shizuku
 * provides that without root. The disable/enable cycle is the kick-or-bust
 * step; if it fails the call returns Failure. The trailing monkey/start-service
 * warmup is best-effort: failures are surfaced in the success message but do
 * not abort.
 */
object AndroidAutoKicker {

    private const val APP_PACKAGE = "com.carlyrics"
    private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val CAR_SERVICE_COMPONENT =
        "com.carlyrics/com.carlyrics.car.LyricsCarAppService"
    private const val CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
    private const val DISABLE_ENABLE_GAP_MS = 1000L
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 4242

    sealed class Status {
        object NotInstalled : Status()
        object NotRunning : Status()
        object PermissionRequired : Status()
        object Ready : Status()
    }

    sealed class Result {
        data class Success(val warnings: List<String> = emptyList()) : Result()
        data class Failure(val message: String) : Result()
    }

    fun status(context: Context): Status {
        if (!Shizuku.pingBinder()) {
            return if (isShizukuInstalled(context)) Status.NotRunning else Status.NotInstalled
        }
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Status.Ready
        } else {
            Status.PermissionRequired
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    fun kick(context: Context): Result {
        val current = status(context)
        if (current !is Status.Ready) {
            return Result.Failure(
                when (current) {
                    Status.NotInstalled -> "Shizuku not installed"
                    Status.NotRunning -> "Shizuku not running"
                    Status.PermissionRequired -> "Shizuku permission required"
                    Status.Ready -> "Unknown"
                }
            )
        }

        val stop = exec(arrayOf("am", "force-stop", GEARHEAD_PACKAGE))
        if (stop.exitCode != 0) {
            return Result.Failure("force-stop failed (${stop.exitCode}): ${stop.output}")
        }

        val disable = exec(arrayOf("pm", "disable", CAR_SERVICE_COMPONENT))
        if (disable.exitCode != 0) {
            return Result.Failure("pm disable failed (${disable.exitCode}): ${disable.output}")
        }

        try {
            Thread.sleep(DISABLE_ENABLE_GAP_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val enable = exec(arrayOf("pm", "enable", CAR_SERVICE_COMPONENT))
        if (enable.exitCode != 0) {
            return Result.Failure("pm enable failed (${enable.exitCode}): ${enable.output}")
        }

        val warnings = mutableListOf<String>()

        val monkey = exec(
            arrayOf(
                "monkey", "-p", APP_PACKAGE,
                "-c", "android.intent.category.LAUNCHER", "1"
            )
        )
        if (monkey.exitCode != 0) {
            warnings += "monkey: ${monkey.output.ifBlank { "exit ${monkey.exitCode}" }}"
        }

        val warm = exec(
            arrayOf(
                "am", "start-service",
                "-a", CAR_APP_SERVICE_ACTION,
                "-n", CAR_SERVICE_COMPONENT
            )
        )
        if (warm.exitCode != 0) {
            warnings += "start-service: ${warm.output.ifBlank { "exit ${warm.exitCode}" }}"
        }

        return Result.Success(warnings)
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun exec(command: Array<String>): CommandResult {
        return try {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = newProcess.invoke(null, command, null, null) as Process
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val errors = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            CommandResult(exitCode, (output + errors).trim())
        } catch (error: Throwable) {
            CommandResult(-1, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun isShizukuInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
