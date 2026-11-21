package com.example.jujutsucontroller.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errorCode: Int,
    val errorMessage: String?
) {
    val isSuccess: Boolean
        get() = errorCode == 0 && exitCode == 0
}

class TermuxExecutionException(
    message: String,
    val result: TermuxCommandResult
) : Exception(message)

class TermuxTimeoutException(message: String) : Exception(message)

object TermuxResultRegistry {
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    fun register(): Pair<String, CompletableDeferred<TermuxCommandResult>> {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        pendingRequests[requestId] = deferred
        return requestId to deferred
    }

    fun complete(requestId: String, result: TermuxCommandResult) {
        pendingRequests.remove(requestId)?.complete(result)
    }

    fun remove(requestId: String) {
        pendingRequests.remove(requestId)
    }
}

class TermuxCommandResultReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_REQUEST_ID = "extra_request_id"
        private const val TERMUX_RESULT_BUNDLE = "result"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val resultBundle = intent.getBundleExtra(TERMUX_RESULT_BUNDLE)

        val result = if (resultBundle != null) {
            TermuxCommandResult(
                stdout = resultBundle.getString("stdout").orEmpty(),
                stderr = resultBundle.getString("stderr").orEmpty(),
                exitCode = resultBundle.getInt("exitCode", -1),
                errorCode = resultBundle.getInt("err", 0),
                errorMessage = resultBundle.getString("errmsg")
            )
        } else {
            TermuxCommandResult(
                stdout = "",
                stderr = "",
                exitCode = -1,
                errorCode = -1,
                errorMessage = "Termux からの応答データが空でした。"
            )
        }

        TermuxResultRegistry.complete(requestId, result)
    }
}

class TermuxEnvironmentChecker(private val context: Context) {
    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_RELEASES_URL = "https://github.com/termux/termux-app/releases"
        const val SETUP_EXTERNAL_APPS_COMMAND =
            "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"
    }

    fun isTermuxInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    TERMUX_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasRunCommandPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_RUN_COMMAND
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class TermuxCommandClient(private val context: Context) {
    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val TERMUX_BIN_PREFIX = "/data/data/com.termux/files/usr/bin"
    }

    suspend fun execute(
        executablePath: String,
        arguments: Array<String> = emptyArray(),
        workdir: String? = null,
        timeoutMillis: Long = 15_000L
    ): Result<TermuxCommandResult> {
        val (requestId, deferred) = TermuxResultRegistry.register()

        try {
            val resultIntent = Intent(context, TermuxCommandResultReceiver::class.java).apply {
                putExtra(TermuxCommandResultReceiver.EXTRA_REQUEST_ID, requestId)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestId.hashCode(),
                resultIntent,
                flags
            )

            val serviceIntent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, executablePath)
                putExtra(EXTRA_ARGUMENTS, arguments)
                if (!workdir.isNullOrBlank()) {
                    putExtra(EXTRA_WORKDIR, workdir)
                }
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_SESSION_ACTION, "0")
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            try {
                context.startService(serviceIntent)
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    throw e
                }
            }

            val commandResult = withTimeout(timeoutMillis) {
                deferred.await()
            }

            return if (commandResult.isSuccess) {
                Result.success(commandResult)
            } else {
                val detail = commandResult.errorMessage
                    ?: commandResult.stderr.ifBlank { "終了コード: ${commandResult.exitCode}" }
                Result.failure(TermuxExecutionException(detail, commandResult))
            }
        } catch (_: TimeoutCancellationException) {
            return Result.failure(
                TermuxTimeoutException("Termux からの応答がタイムアウトしました（${timeoutMillis / 1000}秒）。")
            )
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            TermuxResultRegistry.remove(requestId)
        }
    }

    suspend fun verifyConnection(): Result<Unit> {
        val ping = execute(
            executablePath = "$TERMUX_BIN_PREFIX/echo",
            arguments = arrayOf("ok"),
            timeoutMillis = 8_000L
        )
        if (ping.isFailure) {
            return Result.failure(
                ping.exceptionOrNull() ?: IllegalStateException("Termux との疎通に失敗しました。")
            )
        }
        return Result.success(Unit)
    }
}