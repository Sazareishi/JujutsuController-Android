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
    private val cargoJjPath = "/data/data/com.termux/files/home/.cargo/bin/jj"
    private val usrJjPath = "$TERMUX_BIN_PREFIX/jj"

    private fun resolveJjExecutable(): String {
        return if (File(usrJjPath).exists() || !File(cargoJjPath).exists()) {
            usrJjPath
        } else {
            cargoJjPath
        }
    }

    suspend fun cloneAndConfigure(
        params: CloneParams,
        onPhaseChanged: (String) -> Unit,
        useEnglish: Boolean = java.util.Locale.getDefault().language != "ja"
    ): Result<String> = operationMutex.withLock {
        val trimmedUrl = params.repositoryUrl.trim()
        val trimmedName = params.gitUserName.trim()
        val trimmedEmail = params.gitUserEmail.trim()
        val trimmedParent = params.parentDirectoryPath.trim().ifEmpty {
            "/data/data/com.termux/files/home/repos"
        }
        val repoName = extractRepoName(trimmedUrl)
        val targetRepoDir = File(trimmedParent, repoName).absolutePath

        onPhaseChanged(if (useEnglish) "Checking save folder..." else "保存先フォルダを確認しています...")
        val mkdirResult = client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/mkdir",
            arguments = arrayOf("-p", trimmedParent),
            timeoutMillis = 10_000L
        )
        if (mkdirResult.isFailure) {
            return Result.failure(
                IllegalStateException(if (useEnglish) "Could not create save directory. Check the path or Termux permissions." else "保存先フォルダを作成できませんでした。パスまたは Termux の権限を確認してください。")
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
            onPhaseChanged(if (useEnglish) "Removing existing folder..." else "既存の同名フォルダを削除しています...")
            val safeToDelete = targetRepoDir.length > 10 && targetRepoDir != "/" && targetRepoDir != "/data/data/com.termux/files/home"
            if (safeToDelete) {
                val removeExisting = client.execute(
                    executablePath = "$TERMUX_BIN_PREFIX/rm",
                    arguments = arrayOf("-rf", targetRepoDir),
                    timeoutMillis = 15_000L
                )
                if (removeExisting.isFailure) {
                    return Result.failure(
                        IllegalStateException(if (useEnglish) "Failed to remove existing folder: $targetRepoDir" else "既存フォルダの削除に失敗しました: $targetRepoDir")
                    )
                }
            } else {
                return Result.failure(
                    IllegalStateException(if (useEnglish) "Automatic overwrite is not allowed for safety on this path: $targetRepoDir" else "安全のためこのパスの自動上書きは許可されていません: $targetRepoDir")
                )
            }
        }

        onPhaseChanged(if (useEnglish) "Cloning repository with jj git clone..." else "jj git clone でリポジトリを複製しています...")
        val cloneResult = try {
            executeJjGitClone(
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
                cloneResult.exceptionOrNull() ?: IllegalStateException(if (useEnglish) "jj git clone failed." else "jj git clone に失敗しました。")
            )
        }

        onPhaseChanged(if (useEnglish) "Configuring Jujutsu author info (user.name / user.email)..." else "Jujutsu 作成者情報（user.name / user.email）を設定しています...")
        val jjExec = resolveJjExecutable()
        client.execute(
            executablePath = jjExec,
            arguments = arrayOf("config", "set", "--repo", "user.name", trimmedName),
            workdir = targetRepoDir,
            timeoutMillis = 10_000L
        )
        client.execute(
            executablePath = jjExec,
            arguments = arrayOf("config", "set", "--repo", "user.email", trimmedEmail),
            workdir = targetRepoDir,
            timeoutMillis = 10_000L
        )

        return Result.success(targetRepoDir)
    }

    suspend fun loadWorkspaceSummary(repoPath: String,
        useEnglish: Boolean = java.util.Locale.getDefault().language != "ja"): Result<String> {
        val statusRes = client.execute(
            executablePath = resolveJjExecutable(),
            arguments = arrayOf("status"),
            workdir = repoPath,
            timeoutMillis = 10_000L
        )
        return if (statusRes.isSuccess) {
            Result.success(statusRes.getOrThrow().stdout.ifBlank { if (useEnglish) "Clean (no changes)" else "変更なし（クリーン）" })
        } else {
            Result.failure(
                statusRes.exceptionOrNull() ?: IllegalStateException(if (useEnglish) "Failed to load jj status." else "jj status の取得に失敗しました。")
            )
        }
    }

    private suspend fun executeJjGitClone(
        url: String,
        targetDir: String,
        authType: AuthType,
        sshKeyPath: String?
    ) = when (authType) {
        AuthType.HTTPS -> {
            client.execute(
                executablePath = resolveJjExecutable(),
                arguments = arrayOf("git", "clone", url, targetDir),
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
                        resolveJjExecutable(),
                        "git",
                        "clone",
                        url,
                        targetDir
                    ),
                    timeoutMillis = 180_000L
                )
            } else {
                client.execute(
                    executablePath = resolveJjExecutable(),
                    arguments = arrayOf("git", "clone", url, targetDir),
                    timeoutMillis = 180_000L
                )
            }
        }
    }

    private suspend fun cleanupAbortedClone(targetDir: String) {
        client.execute(
            executablePath = "$TERMUX_BIN_PREFIX/pkill",
            arguments = arrayOf("-f", "jj.*git.*clone.*$targetDir"),
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
