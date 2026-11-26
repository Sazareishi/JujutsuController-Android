package com.example.jujutsucontroller.domain

import com.example.jujutsucontroller.termux.TermuxCommandClient
import com.example.jujutsucontroller.termux.TermuxCommandClient.Companion.TERMUX_BIN_PREFIX
import com.example.jujutsucontroller.termux.TermuxExecutionException

enum class JjActionType(
    val label: String,
    val requiresInput: Boolean = false,
    val isGitCommand: Boolean = false
) {
    STATUS("状態 (status)"),
    LOG("履歴を更新"),
    DIFF("作業コピー差分"),
    COMMIT("変更を保存", requiresInput = true),
    DESCRIBE("説明を編集", requiresInput = true),
    NEW("新規作成 (new)"),
    FETCH("取得 (fetch)"),
    PUSH("送信 (push)"),
    BOOKMARK("ブックマーク", requiresInput = true),
    UNDO("取り消し (undo)"),

    GIT_STATUS("Git 状態 (status)", isGitCommand = true),
    GIT_LOG("Git 履歴 (log)", isGitCommand = true),
    GIT_FETCH("Git 取得 (fetch)", isGitCommand = true),
    GIT_PUSH("Git 送信 (push)", isGitCommand = true)
}

data class JjCommandOutput(
    val action: JjActionType,
    val commandSummary: String,
    val output: String,
    val isError: Boolean
)

class JjRepository(
    private val client: TermuxCommandClient
) {
    private val cargoJjPath = "/data/data/com.termux/files/home/.cargo/bin/jj"
    private val usrJjPath = "$TERMUX_BIN_PREFIX/jj"

    private suspend fun executeJj(
        args: Array<String>,
        workdir: String,
        timeoutMillis: Long
    ) = if (java.io.File(usrJjPath).exists() || !java.io.File(cargoJjPath).exists()) {
        client.execute(
            executablePath = usrJjPath,
            arguments = args,
            workdir = workdir,
            timeoutMillis = timeoutMillis
        )
    } else {
        client.execute(
            executablePath = cargoJjPath,
            arguments = args,
            workdir = workdir,
            timeoutMillis = timeoutMillis
        )
    }

    suspend fun runAction(
        repoPath: String,
        action: JjActionType,
        inputValue: String? = null
    ): JjCommandOutput {
        val trimmedInput = inputValue?.trim().orEmpty()
        val (args, summary, timeout) = when (action) {
            JjActionType.STATUS -> Triple(
                arrayOf("status"),
                "jj status",
                15_000L
            )
            JjActionType.LOG -> Triple(
                arrayOf("log", "--limit", "30"),
                "jj log --limit 30",
                20_000L
            )
            JjActionType.DIFF -> Triple(
                arrayOf("diff"),
                "jj diff",
                20_000L
            )
            JjActionType.COMMIT -> {
                if (trimmedInput.isNotEmpty()) {
                    Triple(arrayOf("commit", "-m", trimmedInput), "jj commit -m \"...\"", 30_000L)
                } else {
                    Triple(arrayOf("commit"), "jj commit", 30_000L)
                }
            }
            JjActionType.DESCRIBE -> {
                if (trimmedInput.isNotEmpty()) {
                    Triple(arrayOf("describe", "-m", trimmedInput), "jj describe -m \"...\"", 20_000L)
                } else {
                    Triple(arrayOf("describe"), "jj describe", 20_000L)
                }
            }
            JjActionType.NEW -> Triple(
                arrayOf("new"),
                "jj new",
                20_000L
            )
            JjActionType.FETCH -> Triple(
                arrayOf("git", "fetch"),
                "jj git fetch",
                120_000L
            )
            JjActionType.PUSH -> Triple(
                arrayOf("git", "push"),
                "jj git push",
                120_000L
            )
            JjActionType.BOOKMARK -> {
                if (trimmedInput.isNotEmpty()) {
                    Triple(
                        arrayOf("bookmark", "set", trimmedInput, "-r", "@"),
                        "jj bookmark set $trimmedInput -r @",
                        20_000L
                    )
                } else {
                    Triple(
                        arrayOf("bookmark", "list"),
                        "jj bookmark list",
                        15_000L
                    )
                }
            }
            JjActionType.UNDO -> Triple(
                arrayOf("undo"),
                "jj undo",
                20_000L
            )
            JjActionType.GIT_STATUS -> Triple(
                arrayOf("status"),
                "git status",
                15_000L
            )
            JjActionType.GIT_LOG -> Triple(
                arrayOf("log", "-n", "20", "--oneline"),
                "git log -n 20 --oneline",
                20_000L
            )
            JjActionType.GIT_FETCH -> Triple(
                arrayOf("fetch"),
                "git fetch",
                120_000L
            )
            JjActionType.GIT_PUSH -> Triple(
                arrayOf("push"),
                "git push",
                120_000L
            )
        }

        val execResult = if (action.isGitCommand) {
            client.execute(
                executablePath = "$TERMUX_BIN_PREFIX/git",
                arguments = args,
                workdir = repoPath,
                timeoutMillis = timeout
            )
        } else {
            executeJj(
                args = args,
                workdir = repoPath,
                timeoutMillis = timeout
            )
        }

        return execResult.fold(
            onSuccess = { res ->
                val combined = listOf(res.stdout.trim(), res.stderr.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                    .ifBlank { "完了しました（終了コード: ${res.exitCode}）" }
                JjCommandOutput(
                    action = action,
                    commandSummary = summary,
                    output = combined,
                    isError = false
                )
            },
            onFailure = { err ->
                val detail = when (err) {
                    is TermuxExecutionException -> {
                        val parts = mutableListOf<String>()
                        if (err.result.stderr.isNotBlank()) parts += err.result.stderr.trim()
                        if (err.result.stdout.isNotBlank()) parts += err.result.stdout.trim()
                        if (!err.result.errorMessage.isNullOrBlank()) parts += err.result.errorMessage.orEmpty()
                        parts += "終了コード: ${err.result.exitCode}"
                        parts.distinct().joinToString("\n")
                    }
                    else -> err.message ?: "コマンド実行に失敗しました。"
                }
                JjCommandOutput(
                    action = action,
                    commandSummary = summary,
                    output = detail,
                    isError = true
                )
            }
        )
    }

    suspend fun loadCommitHistory(repoPath: String, limit: Int = 30): Result<String> {
        val jjLog = executeJj(
            args = arrayOf("log", "--no-graph", "-n", limit.toString()),
            workdir = repoPath,
            timeoutMillis = 20_000L
        )
        if (jjLog.isSuccess) {
            val out = jjLog.getOrThrow().stdout.trim()
            if (out.isNotEmpty()) {
                return Result.success(out)
            }
        }

        val gitLog = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/git",
            arguments = arrayOf("log", "-n", limit.toString(), "--date=short", "--pretty=format:%h %ad %an : %s"),
            workdir = repoPath,
            timeoutMillis = 20_000L
        )
        return gitLog.fold(
            onSuccess = { res ->
                val text = res.stdout.trim().ifBlank { "コミット履歴がありません。" }
                Result.success(text)
            },
            onFailure = { err ->
                val jjErr = jjLog.exceptionOrNull()?.message
                val gitErr = err.message
                Result.failure(
                    IllegalStateException(
                        listOfNotNull(jjErr, gitErr).distinct().joinToString("\n").ifBlank {
                            "コミット履歴の取得に失敗しました。"
                        }
                    )
                )
            }
        )
    }
}