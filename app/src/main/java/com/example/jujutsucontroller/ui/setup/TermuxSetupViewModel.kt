package com.example.jujutsucontroller.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jujutsucontroller.termux.TermuxCommandClient
import com.example.jujutsucontroller.termux.TermuxEnvironmentChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep {
    CHECKING,
    INSTALL_TERMUX,
    GRANT_RUN_COMMAND,
    CONFIGURE_EXTERNAL_APPS,
    READY
}

data class TermuxSetupUiState(
    val step: SetupStep = SetupStep.CHECKING,
    val isTermuxInstalled: Boolean = false,
    val hasRunCommandPermission: Boolean = false,
    val isVerifying: Boolean = false,
    val errorMessage: String? = null
)

sealed interface TermuxSetupEffect {
    data object OpenTermuxDownload : TermuxSetupEffect
    data object RequestRunCommandPermission : TermuxSetupEffect
    data object OpenAppSettings : TermuxSetupEffect
    data class CopyCommandAndLaunchTermux(val command: String) : TermuxSetupEffect
    data object NavigateToCloneScreen : TermuxSetupEffect
}

class TermuxSetupViewModel(
    private val checker: TermuxEnvironmentChecker,
    private val commandClient: TermuxCommandClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(TermuxSetupUiState())
    val uiState: StateFlow<TermuxSetupUiState> = _uiState.asStateFlow()

    private val _effects = Channel<TermuxSetupEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var verifyJob: Job? = null
    private var hasNavigated = false

    fun onResume() {
        refreshState()
    }

    fun refreshState() {
        val installed = checker.isTermuxInstalled()
        val hasPermission = checker.hasRunCommandPermission()

        if (!installed) {
            verifyJob?.cancel()
            _uiState.update {
                it.copy(
                    step = SetupStep.INSTALL_TERMUX,
                    isTermuxInstalled = false,
                    hasRunCommandPermission = false,
                    isVerifying = false,
                    errorMessage = null
                )
            }
            return
        }

        if (!hasPermission) {
            verifyJob?.cancel()
            _uiState.update {
                it.copy(
                    step = SetupStep.GRANT_RUN_COMMAND,
                    isTermuxInstalled = true,
                    hasRunCommandPermission = false,
                    isVerifying = false,
                    errorMessage = null
                )
            }
            return
        }

        runConnectionVerification()
    }

    fun onInstallTermuxClicked() {
        viewModelScope.launch { _effects.send(TermuxSetupEffect.OpenTermuxDownload) }
    }

    fun onRequestPermissionClicked() {
        viewModelScope.launch { _effects.send(TermuxSetupEffect.RequestRunCommandPermission) }
    }

    fun onPermissionResult(granted: Boolean, shouldShowRationale: Boolean) {
        if (granted) {
            refreshState()
        } else {
            _uiState.update {
                it.copy(
                    step = SetupStep.GRANT_RUN_COMMAND,
                    hasRunCommandPermission = false,
                    errorMessage = if (!shouldShowRationale) {
                        "権限が拒否されています。アプリ設定画面から RUN_COMMAND 権限を有効にしてください。"
                    } else {
                        "Termux と連携するには RUN_COMMAND 権限の許可が必要です。"
                    }
                )
            }
            if (!shouldShowRationale) {
                viewModelScope.launch { _effects.send(TermuxSetupEffect.OpenAppSettings) }
            }
        }
    }

    fun onCopyCommandAndOpenTermuxClicked() {
        viewModelScope.launch {
            _effects.send(
                TermuxSetupEffect.CopyCommandAndLaunchTermux(
                    TermuxEnvironmentChecker.SETUP_EXTERNAL_APPS_COMMAND
                )
            )
        }
    }

    fun onRetryVerificationClicked() {
        refreshState()
    }

    private fun runConnectionVerification() {
        if (verifyJob?.isActive == true) return

        verifyJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    step = SetupStep.CONFIGURE_EXTERNAL_APPS,
                    isTermuxInstalled = true,
                    hasRunCommandPermission = true,
                    isVerifying = true,
                    errorMessage = null
                )
            }

            commandClient.verifyConnection().fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(step = SetupStep.READY, isVerifying = false, errorMessage = null)
                    }
                    if (!hasNavigated) {
                        hasNavigated = true
                        _effects.send(TermuxSetupEffect.NavigateToCloneScreen)
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            step = SetupStep.CONFIGURE_EXTERNAL_APPS,
                            isVerifying = false,
                            errorMessage = throwable.message
                                ?: "Termux と通信できません。Termux を起動して外部実行を許可してください。"
                        )
                    }
                }
            )
        }
    }
}