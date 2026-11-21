package com.example.jujutsucontroller

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.jujutsucontroller.domain.AuthType
import com.example.jujutsucontroller.domain.GitCloneRepository
import com.example.jujutsucontroller.termux.TermuxCommandClient
import com.example.jujutsucontroller.termux.TermuxEnvironmentChecker
import com.example.jujutsucontroller.ui.clone.CloneSetupEffect
import com.example.jujutsucontroller.ui.clone.CloneSetupViewModel
import com.example.jujutsucontroller.ui.setup.SetupStep
import com.example.jujutsucontroller.ui.setup.TermuxSetupEffect
import com.example.jujutsucontroller.ui.setup.TermuxSetupViewModel

sealed interface AppRoute {
    data object Setup : AppRoute
    data object Clone : AppRoute
    data class Workspace(val repoPath: String) : AppRoute
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContext = applicationContext
        val checker = TermuxEnvironmentChecker(appContext)
        val commandClient = TermuxCommandClient(appContext)
        val cloneRepository = GitCloneRepository(commandClient)

        val setupViewModel = TermuxSetupViewModel(checker, commandClient)
        val cloneViewModel = CloneSetupViewModel(cloneRepository)

        setContent {
            MaterialTheme {
                var currentRoute by remember { mutableStateOf<AppRoute>(AppRoute.Setup) }

                when (val route = currentRoute) {
                    AppRoute.Setup -> {
                        TermuxSetupRoute(
                            viewModel = setupViewModel,
                            onNavigateToClone = { currentRoute = AppRoute.Clone }
                        )
                    }

                    AppRoute.Clone -> {
                        CloneSetupRoute(
                            viewModel = cloneViewModel,
                            onNavigateToWorkspace = { repoPath ->
                                currentRoute = AppRoute.Workspace(repoPath)
                            }
                        )
                    }

                    is AppRoute.Workspace -> {
                        WorkspaceScreen(
                            repoPath = route.repoPath,
                            cloneRepository = cloneRepository,
                            onOpenNewClone = { currentRoute = AppRoute.Clone }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TermuxSetupRoute(
    viewModel: TermuxSetupViewModel,
    onNavigateToClone: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val activity = context.findActivity()
        val shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                TermuxEnvironmentChecker.PERMISSION_RUN_COMMAND
            )
        } ?: false
        viewModel.onPermissionResult(granted = granted, shouldShowRationale = shouldShowRationale)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                TermuxSetupEffect.OpenTermuxDownload -> {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(TermuxEnvironmentChecker.TERMUX_RELEASES_URL))
                    )
                }

                TermuxSetupEffect.RequestRunCommandPermission -> {
                    permissionLauncher.launch(TermuxEnvironmentChecker.PERMISSION_RUN_COMMAND)
                }

                TermuxSetupEffect.OpenAppSettings -> {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }

                is TermuxSetupEffect.CopyCommandAndLaunchTermux -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Termux Setup", effect.command))
                    Toast.makeText(
                        context,
                        context.getString(R.string.setup_command_copied),
                        Toast.LENGTH_LONG
                    ).show()
                    context.packageManager
                        .getLaunchIntentForPackage(TermuxEnvironmentChecker.TERMUX_PACKAGE)
                        ?.let { context.startActivity(it) }
                }

                TermuxSetupEffect.NavigateToCloneScreen -> onNavigateToClone()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(context.getString(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                context.getString(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusRow(
                context.getString(R.string.setup_step_termux),
                if (uiState.isTermuxInstalled) {
                    context.getString(R.string.setup_status_done)
                } else {
                    context.getString(R.string.setup_status_not_installed)
                }
            )
            StatusRow(
                context.getString(R.string.setup_step_permission),
                if (uiState.hasRunCommandPermission) {
                    context.getString(R.string.setup_status_granted)
                } else {
                    context.getString(R.string.setup_status_not_granted)
                }
            )
            StatusRow(
                context.getString(R.string.setup_step_connection),
                when {
                    uiState.step == SetupStep.READY -> context.getString(R.string.setup_status_done)
                    uiState.isVerifying -> context.getString(R.string.setup_status_verifying)
                    else -> context.getString(R.string.setup_status_action_required)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (uiState.step) {
                SetupStep.CHECKING, SetupStep.READY -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(context.getString(R.string.setup_checking_state))
                    }
                }

                SetupStep.INSTALL_TERMUX -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(context.getString(R.string.setup_install_title), style = MaterialTheme.typography.titleMedium)
                            Text(context.getString(R.string.setup_install_desc))
                            Button(
                                onClick = viewModel::onInstallTermuxClicked,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(context.getString(R.string.setup_open_termux_download))
                            }
                        }
                    }
                }

                SetupStep.GRANT_RUN_COMMAND -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(context.getString(R.string.setup_permission_title), style = MaterialTheme.typography.titleMedium)
                            Text(context.getString(R.string.setup_permission_desc))
                            Button(
                                onClick = viewModel::onRequestPermissionClicked,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(context.getString(R.string.setup_request_permission))
                            }
                        }
                    }
                }

                SetupStep.CONFIGURE_EXTERNAL_APPS -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(context.getString(R.string.setup_external_title), style = MaterialTheme.typography.titleMedium)
                            Text(context.getString(R.string.setup_external_desc))
                            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = TermuxEnvironmentChecker.SETUP_EXTERNAL_APPS_COMMAND,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Button(
                                onClick = viewModel::onCopyCommandAndOpenTermuxClicked,
                                enabled = !uiState.isVerifying,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(context.getString(R.string.setup_copy_and_open_termux))
                            }
                            OutlinedButton(
                                onClick = viewModel::onRetryVerificationClicked,
                                enabled = !uiState.isVerifying,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(context.getString(R.string.setup_retry_verification))
                            }
                        }
                    }
                }
            }

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun CloneSetupRoute(
    viewModel: CloneSetupViewModel,
    onNavigateToWorkspace: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val resolvedPath = resolvePrimaryStoragePath(treeUri)
            if (resolvedPath != null) {
                viewModel.onTargetParentDirChanged(resolvedPath)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CloneSetupEffect.NavigateToWorkspace -> {
                    onNavigateToWorkspace(effect.repositoryPath)
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(context.getString(R.string.clone_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                context.getString(R.string.clone_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = uiState.gitUserName,
                onValueChange = viewModel::onUserNameChanged,
                label = { Text(context.getString(R.string.clone_label_user_name)) },
                placeholder = { Text(context.getString(R.string.clone_hint_user_name)) },
                isError = uiState.userNameError != null,
                supportingText = uiState.userNameError?.let { { Text(it) } },
                enabled = !uiState.isCloning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.gitUserEmail,
                onValueChange = viewModel::onUserEmailChanged,
                label = { Text(context.getString(R.string.clone_label_user_email)) },
                placeholder = { Text(context.getString(R.string.clone_hint_user_email)) },
                isError = uiState.userEmailError != null,
                supportingText = uiState.userEmailError?.let { { Text(it) } },
                enabled = !uiState.isCloning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.repositoryUrl,
                onValueChange = viewModel::onRepositoryUrlChanged,
                label = { Text(context.getString(R.string.clone_label_repo_url)) },
                placeholder = { Text(context.getString(R.string.clone_hint_repo_url)) },
                isError = uiState.repoUrlError != null,
                supportingText = uiState.repoUrlError?.let { { Text(it) } },
                enabled = !uiState.isCloning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.authType == AuthType.HTTPS,
                    onClick = { viewModel.onAuthTypeChanged(AuthType.HTTPS) },
                    enabled = !uiState.isCloning,
                    label = { Text(context.getString(R.string.clone_auth_https)) }
                )
                FilterChip(
                    selected = uiState.authType == AuthType.SSH_KEY,
                    onClick = { viewModel.onAuthTypeChanged(AuthType.SSH_KEY) },
                    enabled = !uiState.isCloning,
                    label = { Text(context.getString(R.string.clone_auth_ssh)) }
                )
            }

            if (uiState.authType == AuthType.SSH_KEY) {
                OutlinedTextField(
                    value = uiState.sshPrivateKeyPath,
                    onValueChange = viewModel::onSshKeyPathChanged,
                    label = { Text(context.getString(R.string.clone_label_ssh_key_path)) },
                    enabled = !uiState.isCloning,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = uiState.targetParentDir,
                onValueChange = viewModel::onTargetParentDirChanged,
                label = { Text(context.getString(R.string.clone_label_parent_dir)) },
                isError = uiState.targetDirError != null,
                supportingText = uiState.targetDirError?.let { { Text(it) } },
                enabled = !uiState.isCloning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = { folderPickerLauncher.launch(null) },
                enabled = !uiState.isCloning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(context.getString(R.string.clone_choose_folder))
            }

            if (uiState.isCloning) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(uiState.progressMessage)
                        }
                        OutlinedButton(
                            onClick = viewModel::onCancelCloneClicked,
                            enabled = !uiState.isCancelling,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (uiState.isCancelling) {
                                    context.getString(R.string.clone_cancelling)
                                } else {
                                    context.getString(R.string.clone_cancel_button)
                                }
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = viewModel::onStartCloneClicked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.clone_start_button))
                }
            }

            if (uiState.generalError != null) {
                Text(
                    text = uiState.generalError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun WorkspaceScreen(
    repoPath: String,
    cloneRepository: GitCloneRepository,
    onOpenNewClone: () -> Unit
) {
    val context = LocalContext.current
    var statusOutput by remember(repoPath) {
        mutableStateOf(context.getString(R.string.workspace_loading_status))
    }
    var reloadTrigger by remember(repoPath) { mutableStateOf(0) }

    LaunchedEffect(repoPath, reloadTrigger) {
        statusOutput = context.getString(R.string.workspace_loading_status)
        cloneRepository.loadWorkspaceSummary(repoPath).fold(
            onSuccess = { statusOutput = it },
            onFailure = { statusOutput = it.message ?: "Error" }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(context.getString(R.string.workspace_title), style = MaterialTheme.typography.headlineSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(context.getString(R.string.workspace_active_repo), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = repoPath,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(context.getString(R.string.workspace_current_status), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusOutput,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { reloadTrigger++ }) {
                    Text(context.getString(R.string.workspace_refresh_status))
                }
                OutlinedButton(onClick = onOpenNewClone) {
                    Text(context.getString(R.string.workspace_open_new_clone))
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, statusText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun resolvePrimaryStoragePath(treeUri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    return if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) {
        "/storage/emulated/0/${parts[1]}".trimEnd('/')
    } else {
        null
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}