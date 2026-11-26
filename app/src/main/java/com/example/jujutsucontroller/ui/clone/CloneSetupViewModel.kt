package com.example.jujutsucontroller.ui.clone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jujutsucontroller.domain.AuthType
import com.example.jujutsucontroller.domain.CloneParams
import com.example.jujutsucontroller.domain.GitCloneRepository
import com.example.jujutsucontroller.domain.RepositoryAlreadyExistsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloneSetupUiState(
    val templateName: String = "デフォルト初期設定",
    val gitUserName: String = "",
    val gitUserEmail: String = "",
    val repositoryUrl: String = "",
    val targetParentDir: String = "/data/data/com.termux/files/home/repos",
    val authType: AuthType = AuthType.HTTPS,
    val isAuthTypeManuallySelected: Boolean = false,
    val sshPrivateKeyPath: String = "/data/data/com.termux/files/home/.ssh/id_ed25519",

    val userNameError: String? = null,
    val userEmailError: String? = null,
    val repoUrlError: String? = null,
    val targetDirError: String? = null,

    val isCloning: Boolean = false,
    val isCancelling: Boolean = false,
    val progressMessage: String = "",
    val generalError: String? = null,
    val pendingOverwriteTargetPath: String? = null
)

sealed interface CloneSetupEffect {
    data class NavigateToWorkspace(val repositoryPath: String) : CloneSetupEffect
}

class CloneSetupViewModel(
    private val cloneRepository: GitCloneRepository
) : ViewModel() {
    private var useEnglish: Boolean = java.util.Locale.getDefault().language != "ja"

    fun setUseEnglish(enabled: Boolean) {
        useEnglish = enabled
    }


    private val _uiState = MutableStateFlow(CloneSetupUiState())
    val uiState: StateFlow<CloneSetupUiState> = _uiState.asStateFlow()

    private val _effects = Channel<CloneSetupEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var cloneJob: Job? = null
    private var currentGenerationId: Long = 0L

    fun onTemplateNameChanged(value: String) {
        _uiState.update { it.copy(templateName = value, generalError = null) }
    }

    fun onUserNameChanged(value: String) {
        _uiState.update { it.copy(gitUserName = value, userNameError = null, generalError = null) }
    }

    fun onUserEmailChanged(value: String) {
        _uiState.update { it.copy(gitUserEmail = value, userEmailError = null, generalError = null) }
    }

    fun onRepositoryUrlChanged(value: String) {
        _uiState.update { state ->
            val trimmed = value.trim()
            val inferredAuth = if (!state.isAuthTypeManuallySelected) {
                when {
                    trimmed.startsWith("git@") || trimmed.startsWith("ssh://") -> AuthType.SSH_KEY
                    trimmed.startsWith("https://") || trimmed.startsWith("http://") -> AuthType.HTTPS
                    else -> state.authType
                }
            } else {
                state.authType
            }
            state.copy(
                repositoryUrl = value,
                authType = inferredAuth,
                repoUrlError = null,
                generalError = null
            )
        }
    }

    fun onTargetParentDirChanged(value: String) {
        _uiState.update { it.copy(targetParentDir = value, targetDirError = null, generalError = null) }
    }

    fun onAuthTypeChanged(authType: AuthType) {
        _uiState.update {
            it.copy(authType = authType, isAuthTypeManuallySelected = true, generalError = null)
        }
    }

    fun onSshKeyPathChanged(value: String) {
        _uiState.update { it.copy(sshPrivateKeyPath = value, generalError = null) }
    }

    fun onConfirmOverwriteClicked() {
        _uiState.update { it.copy(pendingOverwriteTargetPath = null) }
        onStartCloneClicked(overwriteExisting = true)
    }

    fun onDismissOverwriteDialog() {
        _uiState.update { it.copy(pendingOverwriteTargetPath = null) }
    }

    fun applyTemplate(
        templateName: String = "デフォルト初期設定",
        userName: String,
        userEmail: String,
        repositoryUrl: String,
        parentDirectoryPath: String,
        authType: AuthType,
        sshPrivateKeyPath: String
    ) {
        _uiState.update { state ->
            state.copy(
                templateName = if (templateName.isNotBlank()) templateName else state.templateName,
                gitUserName = if (userName.isNotBlank()) userName else state.gitUserName,
                gitUserEmail = if (userEmail.isNotBlank()) userEmail else state.gitUserEmail,
                repositoryUrl = if (repositoryUrl.isNotBlank()) repositoryUrl else state.repositoryUrl,
                targetParentDir = if (parentDirectoryPath.isNotBlank()) parentDirectoryPath else state.targetParentDir,
                authType = authType,
                isAuthTypeManuallySelected = true,
                sshPrivateKeyPath = if (sshPrivateKeyPath.isNotBlank()) sshPrivateKeyPath else state.sshPrivateKeyPath,
                userNameError = null,
                userEmailError = null,
                repoUrlError = null,
                targetDirError = null,
                generalError = null
            )
        }
    }

    fun onStartCloneClicked(overwriteExisting: Boolean = false) {
        if (cloneJob?.isActive == true || _uiState.value.isCloning) return

        val state = _uiState.value
        val nameErr = if (state.gitUserName.trim().isEmpty()) {
            if (useEnglish) "Please enter a user name" else "ユーザー名を入力してください"
        } else null
        val emailErr = if (!isValidEmail(state.gitUserEmail.trim())) {
            if (useEnglish) "Please enter a valid email address" else "有効なメールアドレスを入力してください"
        } else null
        val urlErr = if (!isValidRepoUrl(state.repositoryUrl.trim())) {
            if (useEnglish) "Please enter an HTTPS or SSH repository URL" else "HTTPS または SSH のリポジトリURLを入力してください"
        } else null
        val dirErr = if (state.targetParentDir.trim().isEmpty()) {
            if (useEnglish) "Please specify a save folder" else "保存先フォルダを指定してください"
        } else null

        if (nameErr != null || emailErr != null || urlErr != null || dirErr != null) {
            _uiState.update {
                it.copy(
                    userNameError = nameErr,
                    userEmailError = emailErr,
                    repoUrlError = urlErr,
                    targetDirError = dirErr
                )
            }
            return
        }

        val generation = ++currentGenerationId
        cloneJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCloning = true,
                    isCancelling = false,
                    progressMessage = if (useEnglish) "Preparing..." else "準備中...",
                    generalError = null,
                    pendingOverwriteTargetPath = null
                )
            }

            try {
                val result = cloneRepository.cloneAndConfigure(
                    params = CloneParams(
                        gitUserName = state.gitUserName,
                        gitUserEmail = state.gitUserEmail,
                        repositoryUrl = state.repositoryUrl,
                        parentDirectoryPath = state.targetParentDir,
                        authType = state.authType,
                        sshPrivateKeyPath = state.sshPrivateKeyPath,
                        overwriteExisting = overwriteExisting
                    ),
                    onPhaseChanged = { phase ->
                        if (generation == currentGenerationId) {
                            _uiState.update { it.copy(progressMessage = phase) }
                        }
                    },
                    useEnglish = useEnglish
                )

                if (generation != currentGenerationId) return@launch

                result.fold(
                    onSuccess = { clonedRepoPath ->
                        _uiState.update {
                            it.copy(isCloning = false, isCancelling = false, progressMessage = "")
                        }
                        _effects.send(CloneSetupEffect.NavigateToWorkspace(clonedRepoPath))
                    },
                    onFailure = { throwable ->
                        if (throwable is RepositoryAlreadyExistsException) {
                            _uiState.update {
                                it.copy(
                                    isCloning = false,
                                    isCancelling = false,
                                    progressMessage = "",
                                    generalError = null,
                                    pendingOverwriteTargetPath = throwable.targetPath
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isCloning = false,
                                    isCancelling = false,
                                    progressMessage = "",
                                    generalError = throwable.message ?: if (useEnglish) "Clone failed." else "Clone に失敗しました。"
                                )
                            }
                        }
                    }
                )
            } catch (_: CancellationException) {
                if (generation == currentGenerationId) {
                    _uiState.update {
                        it.copy(
                            isCloning = false,
                            isCancelling = false,
                            progressMessage = "",
                            generalError = if (useEnglish) "Clone cancelled." else "Clone をキャンセルしました。"
                        )
                    }
                }
            }
        }
    }

    fun onCancelCloneClicked() {
        if (cloneJob?.isActive != true) return
        currentGenerationId++
        _uiState.update {
            it.copy(isCancelling = true, progressMessage = if (useEnglish) "Stopping..." else "停止処理中...")
        }
        cloneJob?.cancel()
    }

    private fun isValidEmail(email: String): Boolean {
        return email.contains("@") && email.substringAfter("@").contains(".")
    }

    private fun isValidRepoUrl(url: String): Boolean {
        return url.startsWith("https://") ||
            url.startsWith("http://") ||
            url.startsWith("git@") ||
            url.startsWith("ssh://")
    }
}

