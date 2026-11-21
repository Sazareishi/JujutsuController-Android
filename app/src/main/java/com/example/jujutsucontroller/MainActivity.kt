package com.example.jujutsucontroller

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.example.jujutsucontroller.domain.JjActionType
import com.example.jujutsucontroller.domain.JjCommandOutput
import com.example.jujutsucontroller.domain.JjRepository
import com.example.jujutsucontroller.domain.AppPreferencesRepository
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
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
        val jjRepository = JjRepository(commandClient)
        val prefsRepository = AppPreferencesRepository(appContext)

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
                        var templateRevision by remember { mutableStateOf(0) }
                        val savedSettings = remember(currentRoute, templateRevision) { prefsRepository.loadSettings() }
                        CloneSetupRoute(
                            viewModel = cloneViewModel,
                            savedSettings = savedSettings,
                            onSaveTemplate = { currentUi, customTemplateName ->
                                prefsRepository.saveTemplate(
                                    templateName = customTemplateName.ifBlank { savedSettings.templateName },
                                    userName = currentUi.gitUserName,
                                    userEmail = currentUi.gitUserEmail,
                                    repositoryUrl = currentUi.repositoryUrl,
                                    parentDirectoryPath = currentUi.targetParentDir,
                                    authType = currentUi.authType,
                                    sshPrivateKeyPath = currentUi.sshPrivateKeyPath
                                )
                                templateRevision++
                            },
                            onReopenExistingRepository = { repoPath ->
                                prefsRepository.setActiveRepository(repoPath)
                                currentRoute = AppRoute.Workspace(repoPath)
                            },
                            onNavigateToWorkspace = { repoPath ->
                                val state = cloneViewModel.uiState.value
                                prefsRepository.saveCloneSession(
                                    userName = state.gitUserName,
                                    userEmail = state.gitUserEmail,
                                    repositoryUrl = state.repositoryUrl,
                                    parentDirectoryPath = state.targetParentDir,
                                    clonedRepoPath = repoPath,
                                    authType = state.authType,
                                    sshPrivateKeyPath = state.sshPrivateKeyPath,
                                    templateName = state.templateName
                                )
                                currentRoute = AppRoute.Workspace(repoPath)
                            }
                        )
                    }
                    is AppRoute.Workspace -> {
                        LaunchedEffect(route.repoPath) { prefsRepository.setActiveRepository(route.repoPath) }
                        WorkspaceScreen(
                            repoPath = route.repoPath,
                            cloneRepository = cloneRepository,
                            jjRepository = jjRepository,
                            onOpenNewClone = { currentRoute = AppRoute.Clone },
                            onEditRepository = { oldPath, newPath, newUrl, newParentDir ->
                                val cleanedOld = oldPath.trim()
                                val cleanedNew = newPath.trim().ifEmpty { cleanedOld }
                                val settings = prefsRepository.loadSettings()
                                if (cleanedOld.isNotEmpty() && cleanedOld != cleanedNew) {
                                    prefsRepository.removeRepository(cleanedOld)
                                }
                                if (cleanedNew.isNotEmpty()) {
                                    prefsRepository.setActiveRepository(cleanedNew)
                                    prefsRepository.saveCloneSession(
                                        userName = settings.gitUserName,
                                        userEmail = settings.gitUserEmail,
                                        repositoryUrl = newUrl.trim().ifEmpty { settings.repositoryUrl },
                                        parentDirectoryPath = newParentDir.trim().ifEmpty { settings.parentDirectoryPath },
                                        clonedRepoPath = cleanedNew,
                                        authType = settings.resolvedAuthType(),
                                        sshPrivateKeyPath = settings.sshPrivateKeyPath,
                                        templateName = settings.templateName
                                    )
                                    if (cleanedNew != cleanedOld) {
                                        currentRoute = AppRoute.Workspace(cleanedNew)
                                    }
                                }
                            }
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
    savedSettings: com.example.jujutsucontroller.domain.SavedAppSettings = com.example.jujutsucontroller.domain.SavedAppSettings(),
    onSaveTemplate: (com.example.jujutsucontroller.ui.clone.CloneSetupUiState, String) -> Unit = { _, _ -> },
    onReopenExistingRepository: (String) -> Unit = {},
    onNavigateToWorkspace: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showSaveTemplatePrompt by remember { mutableStateOf(false) }
    var pendingCloneAfterTemplatePrompt by remember { mutableStateOf(false) }
    var showTemplatePickerDialog by remember { mutableStateOf(false) }
    var dismissedSaveSignature by remember { mutableStateOf("") }
    var suppressPickerOnce by remember { mutableStateOf(false) }

    val templateInteraction = remember { MutableInteractionSource() }
    val userNameInteraction = remember { MutableInteractionSource() }
    val userEmailInteraction = remember { MutableInteractionSource() }
    val repoUrlInteraction = remember { MutableInteractionSource() }

    val templateFocused by templateInteraction.collectIsFocusedAsState()
    val userNameFocused by userNameInteraction.collectIsFocusedAsState()
    val userEmailFocused by userEmailInteraction.collectIsFocusedAsState()
    val repoUrlFocused by repoUrlInteraction.collectIsFocusedAsState()

    val anyInputFocused = templateFocused || userNameFocused || userEmailFocused || repoUrlFocused
    var prevAnyInputFocused by remember { mutableStateOf(false) }

    val currentSig = "${uiState.templateName.trim()}|${uiState.gitUserName.trim()}|${uiState.gitUserEmail.trim()}|${uiState.repositoryUrl.trim()}"
    val hasEnteredContent = uiState.gitUserName.isNotBlank() || uiState.gitUserEmail.isNotBlank() || uiState.repositoryUrl.isNotBlank()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CloneSetupEffect.NavigateToWorkspace -> onNavigateToWorkspace(effect.repositoryPath)
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            val resolved = resolveSelectedFolderPath(context, treeUri)
            if (resolved != null) {
                viewModel.onTargetParentDirChanged(resolved)
            } else {
                Toast.makeText(
                    context,
                    "選択したフォルダのパスを取得できませんでした",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val overwritePath = uiState.pendingOverwriteTargetPath
    if (overwritePath != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissOverwriteDialog,
            title = { Text("同名のファイル・フォルダが存在します") },
            text = {
                Text(
                    "保存先にすでに同名パスが存在します。\n\n$overwritePath\n\n既存のものを削除して書き換え（再 Clone）を行いますか？"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onConfirmOverwriteClicked) {
                    Text("書き換える")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissOverwriteDialog) {
                    Text("キャンセル")
                }
            }
        )
    }

    val proceedCloneAction: () -> Unit = {
        val existingRepo = savedSettings.registeredRepositories.firstOrNull { repo ->
            val folder = uiState.repositoryUrl.trim()
                .trimEnd('/')
                .substringAfterLast('/')
                .substringAfterLast(':')
                .removeSuffix(".git")
            folder.isNotEmpty() && repo.trimEnd('/').endsWith("/")
        }
        if (existingRepo != null && java.io.File(existingRepo).exists()) {
            onReopenExistingRepository(existingRepo)
        } else {
            viewModel.onStartCloneClicked()
        }
    }

    if (showSaveTemplatePrompt) {
        AlertDialog(
            onDismissRequest = {
                dismissedSaveSignature = currentSig
                showSaveTemplatePrompt = false
                pendingCloneAfterTemplatePrompt = false
            },
            title = { Text("テンプレートの保存") },
            text = {
                Text(
                    "この入力内容をテンプレートとして保存しますか？\n\n" +
                        "テンプレート名: ${uiState.templateName.ifBlank { "デフォルト初期設定" }}\n" +
                        "Git ユーザー名: ${uiState.gitUserName.ifBlank { "未入力" }}\n" +
                        "メールアドレス: ${uiState.gitUserEmail.ifBlank { "未入力" }}\n" +
                        "リポジトリ URL: ${uiState.repositoryUrl.ifBlank { "未入力" }}"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveTemplate(uiState, uiState.templateName.ifBlank { "デフォルト初期設定" })
                        dismissedSaveSignature = currentSig
                        val shouldStart = pendingCloneAfterTemplatePrompt
                        pendingCloneAfterTemplatePrompt = false
                        showSaveTemplatePrompt = false
                        if (shouldStart) proceedCloneAction()
                    }
                ) {
                    Text("保存して開始")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismissedSaveSignature = currentSig
                        val shouldStart = pendingCloneAfterTemplatePrompt
                        pendingCloneAfterTemplatePrompt = false
                        showSaveTemplatePrompt = false
                        if (shouldStart) proceedCloneAction()
                    }
                ) {
                    Text("保存せず開始")
                }
            }
        )
    }

    if (showTemplatePickerDialog && savedSettings.templates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                suppressPickerOnce = true
                showTemplatePickerDialog = false
            },
            title = { Text("保存されたテンプレートでログイン") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "タップすると入力欄に反映されます（開始はしません）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    savedSettings.templates.forEach { tpl ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                suppressPickerOnce = true
                                showTemplatePickerDialog = false
                                viewModel.applyTemplate(
                                    templateName = tpl.templateName,
                                    userName = tpl.gitUserName,
                                    userEmail = tpl.gitUserEmail,
                                    repositoryUrl = tpl.repositoryUrl,
                                    parentDirectoryPath = tpl.parentDirectoryPath,
                                    authType = tpl.resolvedAuthType(),
                                    sshPrivateKeyPath = tpl.sshPrivateKeyPath
                                )
                            }
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = tpl.templateName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "${tpl.gitUserName.ifBlank { "-" }} <${tpl.gitUserEmail.ifBlank { "-" }}>",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                if (tpl.repositoryUrl.isNotBlank()) {
                                    Text(
                                        text = tpl.repositoryUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        suppressPickerOnce = true
                        showTemplatePickerDialog = false
                    }
                ) {
                    Text("このまま入力")
                }
            }
        )
    }

    val scrollState = rememberScrollState()
    val openTemplateListOnTap: () -> Unit = {
        if (savedSettings.templates.isNotEmpty() && !suppressPickerOnce && !uiState.isCloning) {
            showTemplatePickerDialog = true
        }
    }

    LaunchedEffect(
        templateInteraction,
        userNameInteraction,
        userEmailInteraction,
        repoUrlInteraction,
        savedSettings.templates.size,
        uiState.isCloning
    ) {
        val sources = listOf(
            templateInteraction,
            userNameInteraction,
            userEmailInteraction,
            repoUrlInteraction
        )
        sources.forEach { source ->
            launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is FocusInteraction.Focus -> {
                            openTemplateListOnTap()
                        }
                        is FocusInteraction.Unfocus -> {
                            suppressPickerOnce = false
                        }
                    }
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(R.string.clone_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = context.getString(R.string.clone_subtitle),
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = uiState.templateName,
                    onValueChange = { newValue ->
                        suppressPickerOnce = false
                        viewModel.onTemplateNameChanged(newValue)
                    },
                    label = { Text("テンプレート名") },
                    placeholder = { Text("例: メイン開発用") },
                    enabled = !uiState.isCloning,
                    singleLine = true,
                    interactionSource = templateInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.gitUserName,
                    onValueChange = { newValue ->
                        suppressPickerOnce = false
                        viewModel.onUserNameChanged(newValue)
                    },
                    label = { Text(context.getString(R.string.clone_label_user_name)) },
                    placeholder = { Text(context.getString(R.string.clone_hint_user_name)) },
                    isError = uiState.userNameError != null,
                    supportingText = uiState.userNameError?.let { msg -> { Text(msg) } },
                    enabled = !uiState.isCloning,
                    singleLine = true,
                    interactionSource = userNameInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.gitUserEmail,
                    onValueChange = { newValue ->
                        suppressPickerOnce = false
                        viewModel.onUserEmailChanged(newValue)
                    },
                    label = { Text(context.getString(R.string.clone_label_user_email)) },
                    placeholder = { Text(context.getString(R.string.clone_hint_user_email)) },
                    isError = uiState.userEmailError != null,
                    supportingText = uiState.userEmailError?.let { msg -> { Text(msg) } },
                    enabled = !uiState.isCloning,
                    singleLine = true,
                    interactionSource = userEmailInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.repositoryUrl,
                    onValueChange = { newValue ->
                        suppressPickerOnce = false
                        viewModel.onRepositoryUrlChanged(newValue)
                    },
                    label = { Text(context.getString(R.string.clone_label_repo_url)) },
                    placeholder = { Text(context.getString(R.string.clone_hint_repo_url)) },
                    isError = uiState.repoUrlError != null,
                    supportingText = uiState.repoUrlError?.let { msg -> { Text(msg) } },
                    enabled = !uiState.isCloning,
                    singleLine = true,
                    interactionSource = repoUrlInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
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
                    supportingText = uiState.targetDirError?.let { msg -> { Text(msg) } },
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
                        onClick = {
                            val alreadySaved = savedSettings.templates.any { tpl ->
                                tpl.templateName.trim() == uiState.templateName.trim() &&
                                    tpl.gitUserName.trim() == uiState.gitUserName.trim() &&
                                    tpl.gitUserEmail.trim() == uiState.gitUserEmail.trim() &&
                                    tpl.repositoryUrl.trim() == uiState.repositoryUrl.trim()
                            }
                            val hasAnyInput = uiState.templateName.isNotBlank() ||
                                uiState.gitUserName.isNotBlank() ||
                                uiState.gitUserEmail.isNotBlank() ||
                                uiState.repositoryUrl.isNotBlank()
                            if (hasAnyInput && !alreadySaved) {
                                pendingCloneAfterTemplatePrompt = true
                                showSaveTemplatePrompt = true
                            } else {
                                proceedCloneAction()
                            }
                        },
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
}

@Composable
fun WorkspaceScreen(
    repoPath: String,
    cloneRepository: GitCloneRepository,
    jjRepository: JjRepository,
    onOpenNewClone: () -> Unit,
    onEditRepository: (oldRepoPath: String, newRepoPath: String, newRepoUrl: String, newParentDir: String) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var wasPaused by remember(repoPath) { mutableStateOf(false) }

    var statusOutput by remember(repoPath) {
        mutableStateOf(context.getString(R.string.workspace_loading_status))
    }
    var reloadTrigger by remember(repoPath) { mutableStateOf(0) }
    var isRunningAction by remember(repoPath) { mutableStateOf(false) }
    var historyTrigger by remember(repoPath) { mutableStateOf(0) }

    var repoFiles by remember(repoPath) { mutableStateOf<List<String>>(emptyList()) }
    var filesTrigger by remember(repoPath) { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner, repoPath) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    wasPaused = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPaused) {
                        wasPaused = false
                        filesTrigger++
                        reloadTrigger++
                        historyTrigger++
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var editingRelativePath by remember(repoPath) { mutableStateOf<String?>(null) }
    var editingContent by remember(repoPath) { mutableStateOf("") }
    var editingError by remember(repoPath) { mutableStateOf<String?>(null) }
    var isSavingFile by remember(repoPath) { mutableStateOf(false) }

    LaunchedEffect(repoPath, filesTrigger, reloadTrigger) {
        val rootDir = File(repoPath)
        val collected = withContext(Dispatchers.IO) {
            if (!rootDir.exists() || !rootDir.isDirectory) {
                emptyList()
            } else {
                rootDir.walkTopDown()
                    .onEnter { dir ->
                        val name = dir.name
                        name != ".git" && name != ".jj"
                    }
                    .filter { it.isFile }
                    .map { file ->
                        file.relativeTo(rootDir).path.replace('\\', '/')
                    }
                    .sorted()
                    .toList()
            }
        }
        repoFiles = collected
    }

        fun resolveRepoTargetFile(relPath: String): File? {
        val cleanedRel = relPath.trim().trimStart('/')
        if (cleanedRel.isEmpty()) return null
        val rootDir = File(repoPath)
        val targetFile = File(rootDir, cleanedRel)
        val canonicalRoot = runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)
        val canonicalTarget = runCatching { targetFile.canonicalPath }.getOrDefault(targetFile.absolutePath)
        if (!canonicalTarget.startsWith(canonicalRoot + File.separator) && canonicalTarget != canonicalRoot) {
            return null
        }
        return targetFile
    }

    fun openInExternalApp(relPath: String) {
        val targetFile = resolveRepoTargetFile(relPath)
        if (targetFile == null) {
            editingError = "リポジトリ外のパスは開けません。"
            return
        }
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                if (!targetFile.exists()) {
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText("", Charsets.UTF_8)
                }
                val authority = "${context.packageName}.fileprovider"
                runCatching {
                    FileProvider.getUriForFile(context, authority, targetFile)
                }.getOrElse {
                    Uri.fromFile(targetFile)
                }
            }
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, "text/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "外部アプリで開く").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launchResult = runCatching { context.startActivity(chooser) }
            if (launchResult.isFailure) {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(viewIntent) }.onFailure {
                    editingError = "外部アプリを起動できませんでした: ${it.message ?: ""}"
                }
            }
        }
    }

    val externalFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri: Uri? ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(pickedUri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size > 512 * 1024) {
                            Pair("", "512KB を超えるファイルは読み込めません。")
                        } else if (bytes.contains(0.toByte())) {
                            Pair("", "バイナリファイルはテキスト編集できません。")
                        } else {
                            Pair(String(bytes, Charsets.UTF_8), null)
                        }
                    } ?: Pair("", "選択したファイルを開けませんでした。")
                }.getOrElse { err ->
                    Pair("", err.message ?: "ファイルの読み込みに失敗しました。")
                }
            }
            val lastSegment = pickedUri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }
                ?: "imported_file.txt"
            val currentPath = editingRelativePath?.trim().orEmpty()
            editingRelativePath = if (currentPath.isNotEmpty()) currentPath else lastSegment
            editingContent = loaded.first
            editingError = loaded.second
        }
    }
fun openFileEditor(relPath: String) {
        val cleanedRel = relPath.trim().trimStart('/')
        val rootDir = File(repoPath)
        val targetFile = if (cleanedRel.isEmpty()) null else File(rootDir, cleanedRel)
        val canonicalRoot = runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)
        val canonicalTarget = targetFile?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

        if (canonicalTarget == null || (!canonicalTarget.startsWith(canonicalRoot + File.separator) && canonicalTarget != canonicalRoot)) {
            editingRelativePath = cleanedRel
            editingContent = ""
            editingError = "リポジトリ外のパスは編集できません。"
            return
        }

        if (targetFile.exists() && targetFile.isFile) {
            val bytes = runCatching { targetFile.readBytes() }.getOrNull()
            if (bytes == null) {
                editingRelativePath = cleanedRel
                editingContent = ""
                editingError = "ファイルを読み込めませんでした（ストレージ権限やパスを確認してください）。"
            } else if (bytes.size > 512 * 1024) {
                editingRelativePath = cleanedRel
                editingContent = ""
                editingError = "512KB を超えるファイルは編集できません。"
            } else if (bytes.contains(0.toByte())) {
                editingRelativePath = cleanedRel
                editingContent = ""
                editingError = "バイナリファイルはテキスト編集できません。"
            } else {
                editingRelativePath = cleanedRel
                editingContent = String(bytes, Charsets.UTF_8)
                editingError = null
            }
        } else {
            editingRelativePath = cleanedRel
            editingContent = ""
            editingError = null
        }
    }

    val activeEditPath = editingRelativePath
    if (activeEditPath != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isSavingFile) {
                    editingRelativePath = null
                    editingError = null
                }
            },
            title = { Text("リポジトリ内ファイルの中身を編集") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = activeEditPath,
                        onValueChange = { updatedPath ->
                            editingRelativePath = updatedPath
                            editingError = null
                        },
                        label = { Text("ファイルパス（リポジトリ内相対）") },
                        singleLine = true,
                        enabled = !isSavingFile,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editingContent,
                        onValueChange = { updatedText ->
                            editingContent = updatedText
                            editingError = null
                        },
                        label = { Text("ファイル内容") },
                        enabled = !isSavingFile,
                        minLines = 8,
                        maxLines = 16,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (editingError != null) {
                        Text(
                            text = editingError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isSavingFile,
                    onClick = {
                        val rel = activeEditPath.trim().trimStart('/')
                        if (rel.isEmpty()) {
                            editingError = "ファイルパスを入力してください。"
                            return@Button
                        }
                        val rootDir = File(repoPath)
                        val targetFile = File(rootDir, rel)
                        val canonicalRoot = runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)
                        val canonicalTarget = runCatching { targetFile.canonicalPath }.getOrDefault(targetFile.absolutePath)
                        if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                            editingError = "リポジトリ外には保存できません。"
                            return@Button
                        }

                        scope.launch {
                            isSavingFile = true
                            try {
                                withContext(Dispatchers.IO) {
                                    targetFile.parentFile?.mkdirs()
                                    targetFile.writeText(editingContent, Charsets.UTF_8)
                                }
                                editingRelativePath = null
                                editingError = null
                                filesTrigger++
                                reloadTrigger++
                                historyTrigger++
                            } catch (e: Exception) {
                                editingError = e.message ?: "保存に失敗しました。"
                            } finally {
                                isSavingFile = false
                            }
                        }
                    }
                ) {
                    Text(if (isSavingFile) "保存中..." else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSavingFile,
                    onClick = {
                        editingRelativePath = null
                        editingError = null
                    }
                ) {
                    Text("閉じる")
                }
            }
        )
    }

    var lastCommandOutput by remember(repoPath) { mutableStateOf<JjCommandOutput?>(null) }
    var commitHistory by remember(repoPath) { mutableStateOf("履歴を読み込み中...") }
    var pendingInputAction by remember(repoPath) { mutableStateOf<JjActionType?>(null) }
    var inputText by remember(repoPath) { mutableStateOf("") }

    fun triggerAction(action: JjActionType, input: String? = null) {
        if (isRunningAction) return
        scope.launch {
            isRunningAction = true
            try {
                val result = jjRepository.runAction(
                    repoPath = repoPath,
                    action = action,
                    inputValue = input
                )
                lastCommandOutput = result
                if (action == JjActionType.STATUS && !result.isError) {
                    statusOutput = result.output
                }
                if (!result.isError && action in setOf(
                        JjActionType.LOG,
                        JjActionType.COMMIT,
                        JjActionType.DESCRIBE,
                        JjActionType.NEW,
                        JjActionType.FETCH,
                        JjActionType.PUSH,
                        JjActionType.BOOKMARK,
                        JjActionType.UNDO,
                        JjActionType.GIT_FETCH,
                        JjActionType.GIT_PUSH
                    )
                ) {
                    historyTrigger++
                }
            } finally {
                isRunningAction = false
            }
        }
    }

    LaunchedEffect(repoPath, reloadTrigger) {
        statusOutput = context.getString(R.string.workspace_loading_status)
        cloneRepository.loadWorkspaceSummary(repoPath).fold(
            onSuccess = { statusOutput = it },
            onFailure = { statusOutput = it.message ?: "Error" }
        )
    }

    LaunchedEffect(repoPath, historyTrigger) {
        commitHistory = "履歴を読み込み中..."
        jjRepository.loadCommitHistory(repoPath).fold(
            onSuccess = { commitHistory = it },
            onFailure = { commitHistory = "履歴取得エラー: ${it.message ?: "不明なエラー"}" }
        )
    }

    var showRepoEditDialog by remember(repoPath) { mutableStateOf(false) }
    var editRepoPath by remember(repoPath) { mutableStateOf(repoPath) }
    var editRepoUrl by remember(repoPath) { mutableStateOf("") }
    var editParentDir by remember(repoPath) {
        mutableStateOf(repoPath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "/storage/emulated/0/jjc"))
    }

    if (showRepoEditDialog) {
        AlertDialog(
            onDismissRequest = { showRepoEditDialog = false },
            title = { Text("リポジトリを編集") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editRepoPath,
                        onValueChange = { editRepoPath = it },
                        label = { Text("リポジトリパス") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editRepoUrl,
                        onValueChange = { editRepoUrl = it },
                        label = { Text("リポジトリ URL（任意）") },
                        placeholder = { Text("https://... または git@...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editParentDir,
                        onValueChange = { editParentDir = it },
                        label = { Text("保存先親フォルダ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRepoEditDialog = false
                        onEditRepository(repoPath, editRepoPath, editRepoUrl, editParentDir)
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRepoEditDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
    val dialogAction = pendingInputAction
    if (dialogAction != null) {
        val (titleText, hintText) = when (dialogAction) {
            JjActionType.COMMIT -> "変更を保存 (jj commit)" to "コミットメッセージ（任意）"
            JjActionType.DESCRIBE -> "説明を編集 (jj describe)" to "チェンジの説明文（任意）"
            JjActionType.BOOKMARK -> "ブックマーク (jj bookmark set)" to "ブックマーク名（空欄で一覧表示）"
            else -> dialogAction.label to "入力値"
        }
        AlertDialog(
            onDismissRequest = {
                if (!isRunningAction) {
                    pendingInputAction = null
                    inputText = ""
                }
            },
            title = { Text(titleText) },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text(hintText) },
                    singleLine = dialogAction == JjActionType.BOOKMARK,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val valueToSend = inputText
                        pendingInputAction = null
                        inputText = ""
                        triggerAction(dialogAction, valueToSend)
                    }
                ) {
                    Text("実行")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingInputAction = null
                        inputText = ""
                    }
                ) {
                    Text("キャンセル")
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(R.string.workspace_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                    val currentRepoName = remember(repoPath) {
                        repoPath.trimEnd('/').substringAfterLast('/').ifBlank { repoPath }
                    }
                    Text(
                        text = currentRepoName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f, fill = false)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("リポジトリ内ファイル編集", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = { filesTrigger++ },
                                enabled = !isRunningAction && !isSavingFile
                            ) {
                                Text("一覧更新")
                            }
                        }

                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                            enabled = !isRunningAction && !isSavingFile,
                            onClick = { openFileEditor("README.md") }
                        ) {
                            Text(
                                text = "新規ファイルを作成・編集",
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        if (repoFiles.isEmpty()) {
                            Text(
                                text = "リポジトリ内の編集可能なファイルが見つかりません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            repoFiles.take(40).forEach { relFile ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isRunningAction && !isSavingFile) {
                                            openFileEditor(relFile)
                                        }
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = relFile,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
                                    Text(
                                        text = "中身を編集",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            if (repoFiles.size > 40) {
                                Text(
                                    text = "ほか ${repoFiles.size - 40} 件のファイルがあります。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Jujutsu 操作", style = MaterialTheme.typography.titleMedium)
                            if (isRunningAction) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }

                        val jjActions = JjActionType.entries.filterNot { it.isGitCommand }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            jjActions.chunked(2).forEach { rowActions ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowActions.forEach { action ->
                                        OutlinedButton(
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                            enabled = !isRunningAction,
                                            onClick = {
                                                if (action.requiresInput) {
                                                    inputText = ""
                                                    pendingInputAction = action
                                                } else {
                                                    triggerAction(action)
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = action.label,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Git 操作", style = MaterialTheme.typography.titleMedium)
                            if (isRunningAction) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }

                        val gitActions = JjActionType.entries.filter { it.isGitCommand }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            gitActions.chunked(2).forEach { rowActions ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowActions.forEach { action ->
                                        OutlinedButton(
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                            enabled = !isRunningAction,
                                            onClick = {
                                                if (action.requiresInput) {
                                                    inputText = ""
                                                    pendingInputAction = action
                                                } else {
                                                    triggerAction(action)
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = action.label,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            enabled = !isRunningAction,
                            onClick = onOpenNewClone
                        ) {
                            Text(
                                text = "別のリポジトリを Clone",
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                val cmdOutput = lastCommandOutput
                if (cmdOutput != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "実行結果 (${cmdOutput.commandSummary})",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (cmdOutput.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = cmdOutput.output,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("コミット履歴", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = { historyTrigger++ },
                                enabled = !isRunningAction
                            ) {
                                Text("履歴を再取得")
                            }
                        }
                        Text(
                            text = commitHistory,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    enabled = !isRunningAction,
                    onClick = { reloadTrigger++ }
                ) {
                    Text(context.getString(R.string.workspace_refresh_status))
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

private fun resolveSelectedFolderPath(context: Context, treeUri: Uri): String? {
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":", limit = 2)
        if (split.size == 2) {
            val type = split[0]
            val relative = split[1].trimStart('/')
            if (type.equals("primary", ignoreCase = true)) {
                val base = android.os.Environment.getExternalStorageDirectory().absolutePath
                if (relative.isEmpty()) base else "$base/$relative"
            } else {
                "/storage/$type/$relative".trimEnd('/')
            }
        } else {
            treeUri.path
        }
    }.getOrNull()
}
