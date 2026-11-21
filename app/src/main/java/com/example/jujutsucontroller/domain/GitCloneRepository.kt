package com.example.jujutsucontroller.domain

import com.example.jujutsucontroller.termux.TermuxCommandClient
import com.example.jujutsucontroller.termux.TermuxCommandClient.Companion.TERMUX_BIN_PREFIX
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class AuthType {
    HTTPS,
    SSH_KEY
}

class RepositoryAlreadyExistsException(val targetPath: String) :
    IllegalStateException("保存先にすでに同名フォルダが存在します: $targetPath")

data class CloneParams(
    val gitUserName: String,
    val gitUserEmail: String,
    val repositoryUrl: String,
    val parentDirectoryPath: String,
    val authType: AuthType,
    val sshPrivateKeyPath: String? = null,
    val overwriteExisting: Boolean = false
)

class GitCloneRepository(
    private val client: TermuxCommandClient
) {
    private val operationMutex = Mutex()

    suspend fun cloneAndConfigure(
        params: CloneParams,
        onPhaseChanged: (String) -> Unit
    ): Result<String> = operationMutex.withLock {
        val trimmedUrl = params.repositoryUrl.trim()
        val trimmedName = params.gitUserName.trim()
        val trimmedEmail = params.gitUserEmail.trim()
        val repoName = extractRepoName(trimmedUrl)
        val targetRepoDir = File(params.parentDirectoryPath.trim(), repoName).absolutePath

        onPhaseChanged("保存先フォルダを確認しています...")
        val mkdirResult = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/mkdir",
            arguments = arrayOf("-p", params.parentDirectoryPath.trim()),
            timeoutMillis = 10_000L
        )
        if (mkdirResult.isFailure) {
            return Result.failure(
                IllegalStateException("保存先フォルダを作成できませんでした。Termux のストレージ権限を確認してください。")
            )
        }

        val existsCheck = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/test",
            arguments = arrayOf("-e", targetRepoDir),
            timeoutMillis = 5_000L
        )
        if (existsCheck.isSuccess) {
            if (!params.overwriteExisting) {
                return Result.failure(RepositoryAlreadyExistsException(targetRepoDir))
            }
            onPhaseChanged("既存の同名フォルダを削除しています...")
            if (targetRepoDir.startsWith("/storage/") && targetRepoDir.length > 20) {
                val removeExisting = client.execute(
                    executablePath = "$TERMUX_BIN_PREFIX/rm",
                    arguments = arrayOf("-rf", targetRepoDir),
                    timeoutMillis = 15_000L
                )
                if (removeExisting.isFailure) {
                    return Result.failure(
                        IllegalStateException("既存フォルダの削除に失敗しました: $targetRepoDir")
                    )
                }
            } else {
                return Result.failure(
                    IllegalStateException("安全のためこのパスの自動上書きは許可されていません: $targetRepoDir")
                )
            }
        }

        onPhaseChanged("リポジトリを Clone しています...")
        val cloneResult = try {
            executeCloneCommand(
                url = trimmedUrl,
                targetDir = targetRepoDir,
                authType = params.authType,
                sshKeyPath = params.sshPrivateKeyPath?.trim()
            )
        } catch (e: Exception) {
            withContext(NonCancellable) {
                cleanupAbortedClone(targetRepoDir)
            }
            throw e
        }

        if (cloneResult.isFailure) {
            withContext(NonCancellable) {
                cleanupAbortedClone(targetRepoDir)
            }
            return Result.failure(
                cloneResult.exceptionOrNull() ?: IllegalStateException("Clone に失敗しました。")
            )
        }

        onPhaseChanged("Git ユーザー情報（user.name / user.email）を設定しています...")
        client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/git",
            arguments = arrayOf("config", "--global", "--add", "safe.directory", targetRepoDir),
            timeoutMillis = 10_000L
        )

        val setName = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/git",
            arguments = arrayOf("config", "--local", "user.name", trimmedName),
            workdir = targetRepoDir,
            timeoutMillis = 10_000L
        )
        if (setName.isFailure) {
            withContext(NonCancellable) {
                cleanupAbortedClone(targetRepoDir)
            }
            val detail = setName.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
            return Result.failure(
                IllegalStateException(
                    if (detail != null) "git config user.name の設定に失敗しました: $detail"
                    else "git config user.name の設定に失敗しました。"
                )
            )
        }

        val setEmail = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/git",
            arguments = arrayOf("config", "--local", "user.email", trimmedEmail),
            workdir = targetRepoDir,
            timeoutMillis = 10_000L
        )
        if (setEmail.isFailure) {
            withContext(NonCancellable) {
                cleanupAbortedClone(targetRepoDir)
            }
            val detail = setEmail.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
            return Result.failure(
                IllegalStateException(
                    if (detail != null) "git config user.email の設定に失敗しました: $detail"
                    else "git config user.email の設定に失敗しました。"
                )
            )
        }
        onPhaseChanged("Jujutsu ワークスペースを準備しています...")
        client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/jj",
            arguments = arrayOf("git", "init", "--colocate"),
            workdir = targetRepoDir,
            timeoutMillis = 15_000L
        )

        return Result.success(targetRepoDir)
    }

    suspend fun loadWorkspaceSummary(repoPath: String): Result<String> {
        val statusRes = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/jj",
            arguments = arrayOf("status"),
            workdir = repoPath,
            timeoutMillis = 10_000L
        )
        if (statusRes.isSuccess) {
            return Result.success(statusRes.getOrThrow().stdout.ifBlank { "変更なし（クリーン）" })
        }

        val gitStatusRes = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/git",
            arguments = arrayOf("status", "--short"),
            workdir = repoPath,
            timeoutMillis = 10_000L
        )
        return if (gitStatusRes.isSuccess) {
            Result.success(gitStatusRes.getOrThrow().stdout.ifBlank { "変更なし（git status クリーン）" })
        } else {
            Result.failure(
                statusRes.exceptionOrNull() ?: IllegalStateException("リポジトリ状態の取得に失敗しました。")
            )
        }
    }

    private suspend fun executeCloneCommand(
        url: String,
        targetDir: String,
        authType: AuthType,
        sshKeyPath: String?
    ) = when (authType) {
        AuthType.HTTPS -> {
            client.execute(
                executablePath = "$TERMUX_BIN_PREFIX/git",
                arguments = arrayOf("clone", url, targetDir),
                timeoutMillis = 180_000L
            )
        }

        AuthType.SSH_KEY -> {
            if (!sshKeyPath.isNullOrBlank()) {
                val sshCmd = "ssh -i \"$sshKeyPath\" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
                client.execute(
                    executablePath = "$TERMUX_BIN_PREFIX/env",
                    arguments = arrayOf(
                        "GIT_SSH_COMMAND=$sshCmd",
                        "$TERMUX_BIN_PREFIX/git",
                        "clone",
                        url,
                        targetDir
                    ),
                    timeoutMillis = 180_000L
                )
            } else {
                client.execute(
                    executablePath = "$TERMUX_BIN_PREFIX/git",
                    arguments = arrayOf("clone", url, targetDir),
                    timeoutMillis = 180_000L
                )
            }
        }
    }

    private suspend fun cleanupAbortedClone(targetDir: String) {
        client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/pkill",
            arguments = arrayOf("-f", "git.*clone.*$targetDir"),
            timeoutMillis = 5_000L
        )
        if (targetDir.isNotBlank() && targetDir != "/") {
            client.execute(
                executablePath = "$TERMUX_BIN_PREFIX/rm",
                arguments = arrayOf("-rf", targetDir),
                timeoutMillis = 15_000L
            )
        }
    }

    fun extractRepoName(url: String): String {
        val cleaned = url.trim().removeSuffix("/")
        val lastSegment = cleaned.substringAfterLast('/').substringAfterLast(':')
        return lastSegment.removeSuffix(".git").ifBlank { "repository" }
    }
}