package com.example.jujutsucontroller

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.jujutsucontroller.domain.AppPreferencesRepository
import com.example.jujutsucontroller.domain.AuthType
import com.example.jujutsucontroller.domain.CloneParams
import com.example.jujutsucontroller.domain.GitCloneRepository
import com.example.jujutsucontroller.domain.JjActionType
import com.example.jujutsucontroller.domain.JjCommandOutput
import com.example.jujutsucontroller.domain.JjRepository
import com.example.jujutsucontroller.domain.SavedAppSettings
import com.example.jujutsucontroller.termux.TermuxCommandClient
import com.example.jujutsucontroller.termux.TermuxEnvironmentChecker
import com.example.jujutsucontroller.ui.clone.CloneSetupEffect
import com.example.jujutsucontroller.ui.clone.CloneSetupViewModel
import com.example.jujutsucontroller.ui.setup.SetupStep
import com.example.jujutsucontroller.ui.setup.TermuxSetupEffect
import com.example.jujutsucontroller.ui.setup.TermuxSetupViewModel
import kotlinx.coroutines.launch
import java.io.File

private val JjcBgDark = Color(0xFF070B10)
private val JjcSurfaceDark = Color(0xFF0E141D)
private val JjcCardDark = Color(0xFF141C27)
private val JjcBorderDark = Color(0xFF283342)
private val JjcAccentBlue = Color(0xFF49C5FF)
private val JjcAccentTextDark = Color(0xFF06131C)
private val JjcTextPrimary = Color(0xFFE6EDF5)
private val JjcTextSecondary = Color(0xFF9AA7B8)
private val JjcErrorText = Color(0xFFFF8A8A)
private val JjcErrorCardBg = Color(0xFF2B1115)
private val JjcErrorCardBorder = Color(0xFF8B2E36)

private val JjcDarkColorScheme = darkColorScheme(
    primary = JjcAccentBlue,
    onPrimary = JjcAccentTextDark,
    primaryContainer = Color(0xFF133347),
    onPrimaryContainer = Color(0xFFBBE7FF),
    secondary = Color(0xFF253244),
    onSecondary = JjcTextPrimary,
    background = JjcBgDark,
    onBackground = JjcTextPrimary,
    surface = JjcSurfaceDark,
    onSurface = JjcTextPrimary,
    surfaceVariant = JjcCardDark,
    onSurfaceVariant = JjcTextSecondary,
    outline = JjcBorderDark,
    error = JjcErrorText,
    onError = Color(0xFF220707),
    errorContainer = JjcErrorCardBg,
    onErrorContainer = JjcErrorText
)

private val JjcShapeSmall = RoundedCornerShape(8.dp)
private val JjcShapeMedium = RoundedCornerShape(10.dp)

enum class AppLanguage(val labelJa: String, val labelEn: String) {
    SYSTEM("システム設定", "System"),
    JA("日本語", "日本語"),
    EN("English", "English")
}


private fun resolveUseEnglish(language: AppLanguage): Boolean {
    return when (language) {
        AppLanguage.SYSTEM -> java.util.Locale.getDefault().language != "ja"
        AppLanguage.JA -> false
        AppLanguage.EN -> true
    }
}

sealed interface AppRoute {
    data object Setup : AppRoute
    data object Clone : AppRoute
    data class InitialSettings(val isOnboarding: Boolean = false) : AppRoute
    data class RepositorySelector(val currentRepoPath: String) : AppRoute
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
            MaterialTheme(colorScheme = JjcDarkColorScheme) {
                var currentRoute by remember { mutableStateOf<AppRoute>(AppRoute.Setup) }
                var settingsRevision by remember { mutableStateOf(0) }
                val savedSettings = remember(currentRoute, settingsRevision) {
                    prefsRepository.loadSettings()
                }
                var selectedLanguage by remember {
                    mutableStateOf(
                        runCatching { AppLanguage.valueOf(prefsRepository.loadAppLanguage()) }
                            .getOrDefault(AppLanguage.SYSTEM)
                    )
                }
                val useEnglish = resolveUseEnglish(selectedLanguage)

                when (val route = currentRoute) {
                    AppRoute.Setup -> {
                        TermuxSetupRoute(
                            viewModel = setupViewModel,
                            useEnglish = useEnglish,
                            selectedLanguage = selectedLanguage,
                            onSelectLanguage = { newLang ->
                                selectedLanguage = newLang
                                prefsRepository.saveAppLanguage(newLang.name)
                            },
                            onNavigateToClone = {
                                val latest = prefsRepository.loadSettings()
                                val hasAuthorInfo =
                                    latest.gitUserName.isNotBlank() && latest.gitUserEmail.isNotBlank()
                                val target = latest.activeRepositoryPath

                                currentRoute = when {
                                    !hasAuthorInfo -> AppRoute.InitialSettings(isOnboarding = true)
                                    !target.isNullOrBlank() -> AppRoute.Workspace(target)
                                    else -> AppRoute.Clone
                                }
                            }
                        )
                    }

                    AppRoute.Clone -> {
                        CloneSetupRoute(
                            viewModel = cloneViewModel,
                            savedSettings = savedSettings,
                            useEnglish = useEnglish,
                            onNavigateToWorkspace = { repoPath ->
                                val state = cloneViewModel.uiState.value
                                prefsRepository.saveCloneSession(
                                    userName = state.gitUserName.ifBlank { savedSettings.gitUserName },
                                    userEmail = state.gitUserEmail.ifBlank { savedSettings.gitUserEmail },
                                    repositoryUrl = state.repositoryUrl,
                                    parentDirectoryPath = state.targetParentDir.ifBlank { savedSettings.parentDirectoryPath },
                                    clonedRepoPath = repoPath,
                                    authType = state.authType,
                                    sshPrivateKeyPath = state.sshPrivateKeyPath.ifBlank { savedSettings.sshPrivateKeyPath },
                                    templateName = state.templateName
                                )
                                settingsRevision++
                                currentRoute = AppRoute.Workspace(repoPath)
                            }
                        )
                    }

                    is AppRoute.InitialSettings -> {
                        InitialSettingsRoute(
                            savedSettings = savedSettings,
                            isOnboarding = route.isOnboarding,
                            selectedLanguage = selectedLanguage,
                            onSelectLanguage = { newLang ->
                                selectedLanguage = newLang
                                prefsRepository.saveAppLanguage(newLang.name)
                            },
                            onSaveSettings = { uName, uEmail, pDir, auth, sshKey ->
                                prefsRepository.saveAppLanguage(selectedLanguage.name)
                                prefsRepository.updateInitialSettings(uName, uEmail, pDir, auth, sshKey)
                                settingsRevision++
                                val updated = prefsRepository.loadSettings()
                                val returnRepo = updated.activeRepositoryPath
                                currentRoute = if (!returnRepo.isNullOrBlank()) {
                                    AppRoute.Workspace(returnRepo)
                                } else {
                                    AppRoute.Clone
                                }
                            },
                            onBack = {
                                val returnRepo = savedSettings.activeRepositoryPath
                                currentRoute = if (!returnRepo.isNullOrBlank()) {
                                    AppRoute.Workspace(returnRepo)
                                } else {
                                    AppRoute.Clone
                                }
                            }
                        )
                    }

                    is AppRoute.RepositorySelector -> {
                        RepositorySelectorRoute(
                            currentRepoPath = route.currentRepoPath,
                            prefsRepository = prefsRepository,
                            jjRepository = jjRepository,
                            useEnglish = useEnglish,
                            onSelectRepository = { chosenRepo ->
                                prefsRepository.setActiveRepository(chosenRepo)
                                settingsRevision++
                                currentRoute = AppRoute.Workspace(chosenRepo)
                            },
                            onBackToWorkspace = { targetRepo ->
                                val dest = targetRepo.ifBlank { route.currentRepoPath }
                                currentRoute = if (dest.isNotBlank()) AppRoute.Workspace(dest) else AppRoute.Clone
                            }
                        )
                    }

                    is AppRoute.Workspace -> {
                        LaunchedEffect(route.repoPath) {
                            prefsRepository.setActiveRepository(route.repoPath)
                        }
                        WorkspaceScreen(
                            repoPath = route.repoPath,
                            jjRepository = jjRepository,
                            useEnglish = useEnglish,
                            onOpenRepositorySelector = {
                                currentRoute = AppRoute.RepositorySelector(route.repoPath)
                            },
                            onOpenInitialSettings = {
                                currentRoute = AppRoute.InitialSettings(isOnboarding = false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JjcScreenHeader(
    title: String,
    subtitle: String,
    detail: String? = null,
    useEnglish: Boolean = java.util.Locale.getDefault().language != "ja",
    trailing: (@Composable () -> Unit)? = null
) {
    var isDetailTruncated by remember(detail) { mutableStateOf(false) }
    var showFullDetailDialog by remember(detail) { mutableStateOf(false) }

    if (showFullDetailDialog && !detail.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showFullDetailDialog = false },
            containerColor = JjcCardDark,
            title = { Text(if (useEnglish) "Repository Path" else "リポジトリパス", color = JjcAccentBlue) },
            text = {
                Text(
                    text = detail,
                    color = JjcTextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = { showFullDetailDialog = false }) {
                    Text(if (useEnglish) "Close" else "閉じる", color = JjcAccentBlue)
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JjcBgDark
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        color = JjcAccentBlue,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        color = JjcTextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!detail.isNullOrBlank()) {
                        val pathModifier = if (isDetailTruncated) {
                            Modifier.clickable { showFullDetailDialog = true }
                        } else {
                            Modifier
                        }
                        Text(
                            text = detail,
                            color = if (isDetailTruncated) JjcAccentBlue else JjcTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { layoutResult ->
                                isDetailTruncated = layoutResult.hasVisualOverflow
                            },
                            modifier = pathModifier
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailing()
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = JjcBorderDark,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun JjcInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    errorMessage: String? = null,
    enabled: Boolean = true,
    monospace: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            isError = errorMessage != null,
            singleLine = true,
            shape = JjcShapeSmall,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = JjcSurfaceDark,
                unfocusedContainerColor = JjcSurfaceDark,
                disabledContainerColor = JjcCardDark,
                focusedBorderColor = JjcAccentBlue,
                unfocusedBorderColor = JjcBorderDark,
                focusedLabelColor = JjcAccentBlue,
                unfocusedLabelColor = JjcTextSecondary,
                focusedTextColor = JjcTextPrimary,
                unfocusedTextColor = JjcTextPrimary,
                errorBorderColor = JjcErrorText,
                errorLabelColor = JjcErrorText
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (errorMessage != null) {
            JjcErrorText(message = errorMessage)
        }
    }
}

@Composable
private fun JjcErrorText(
    message: String,
    useEnglish: Boolean = java.util.Locale.getDefault().language != "ja"
) {
    Text(
        text = if (useEnglish) "Error: $message" else "エラー: $message",
        color = JjcErrorText,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun JjcErrorCard(
    title: String,
    message: String,
    useEnglish: Boolean = java.util.Locale.getDefault().language != "ja"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = JjcShapeMedium,
        colors = CardDefaults.cardColors(containerColor = JjcErrorCardBg),
        border = BorderStroke(1.dp, JjcErrorCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = JjcErrorText,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = message.ifBlank { if (useEnglish) "An unknown error occurred." else "不明なエラーが発生しました。" },
                color = JjcTextPrimary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun JjcPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = JjcShapeMedium,
        colors = ButtonDefaults.buttonColors(
            containerColor = JjcAccentBlue,
            contentColor = JjcAccentTextDark,
            disabledContainerColor = JjcAccentBlue.copy(alpha = 0.4f),
            disabledContentColor = JjcAccentTextDark.copy(alpha = 0.6f)
        ),
        modifier = modifier.height(48.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun JjcSecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = JjcShapeMedium,
        border = BorderStroke(1.dp, JjcBorderDark),
        modifier = modifier.height(48.dp)
    ) {
        Text(
            text = text,
            color = if (enabled) JjcTextPrimary else JjcTextSecondary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JjcAuthTypeSelector(
    selected: AuthType,
    onSelect: (AuthType) -> Unit
) {
    val availableAuthTypes = remember { AuthType.entries.toList() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        availableAuthTypes.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = JjcShapeSmall,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = JjcCardDark,
                    labelColor = JjcTextPrimary,
                    selectedContainerColor = Color(0xFF133347),
                    selectedLabelColor = JjcAccentBlue
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == option,
                    borderColor = JjcBorderDark,
                    selectedBorderColor = JjcAccentBlue
                ),
                label = { Text(option.name, maxLines = 1, softWrap = false) }
            )
        }
    }
}

@Composable
fun TermuxSetupRoute(
    viewModel: TermuxSetupViewModel,
    useEnglish: Boolean = java.util.Locale.getDefault().language != "ja",
    selectedLanguage: AppLanguage = AppLanguage.SYSTEM,
    onSelectLanguage: (AppLanguage) -> Unit = {},
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

    Surface(modifier = Modifier.fillMaxSize(), color = JjcBgDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            JjcScreenHeader(
                title = if (useEnglish) "Termux Setup" else context.getString(R.string.setup_title),
                subtitle = if (useEnglish) "Verify Termux connection and run-command permission" else "Termux の接続と実行権限を確認",
                useEnglish = useEnglish
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = JjcShapeMedium,
                    colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                    border = BorderStroke(1.dp, JjcBorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                    Text(
                        text = if (useEnglish) "Display Language" else "表示言語",
                        color = JjcTextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach { langOption ->
                            val chipText = if (useEnglish) langOption.labelEn else langOption.labelJa
                            FilterChip(
                                selected = selectedLanguage == langOption,
                                onClick = { onSelectLanguage(langOption) },
                                shape = JjcShapeSmall,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = JjcCardDark,
                                    labelColor = JjcTextPrimary,
                                    selectedContainerColor = Color(0xFF133347),
                                    selectedLabelColor = JjcAccentBlue
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selectedLanguage == langOption,
                                    borderColor = JjcBorderDark,
                                    selectedBorderColor = JjcAccentBlue
                                ),
                                label = { Text(chipText, maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    HorizontalDivider(color = JjcBorderDark, thickness = 1.dp)
                        StatusRow(
                            label = if (useEnglish) "1. Install Termux" else context.getString(R.string.setup_step_termux),
                            value = if (uiState.isTermuxInstalled) {
                                if (useEnglish) "Done" else context.getString(R.string.setup_status_done)
                            } else {
                                if (useEnglish) "Not Installed" else context.getString(R.string.setup_status_not_installed)
                            }
                        )
                        StatusRow(
                            label = if (useEnglish) "2. RUN_COMMAND Permission" else context.getString(R.string.setup_step_permission),
                            value = if (uiState.hasRunCommandPermission) {
                                if (useEnglish) "Granted" else context.getString(R.string.setup_status_granted)
                            } else {
                                if (useEnglish) "Not Granted" else context.getString(R.string.setup_status_not_granted)
                            }
                        )
                        StatusRow(
                            label = if (useEnglish) "3. Termux Connection Check" else context.getString(R.string.setup_step_connection),
                            value = when {
                                uiState.step == SetupStep.READY -> if (useEnglish) "Done" else context.getString(R.string.setup_status_done)
                                uiState.isVerifying -> if (useEnglish) "Verifying..." else context.getString(R.string.setup_status_verifying)
                                else -> if (useEnglish) "Action Required" else context.getString(R.string.setup_status_action_required)
                            }
                        )
                    }
                }

                when (uiState.step) {
                    SetupStep.CHECKING, SetupStep.READY -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = JjcShapeMedium,
                            colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                            border = BorderStroke(1.dp, JjcBorderDark)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = JjcAccentBlue
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(if (useEnglish) "Checking Termux state..." else context.getString(R.string.setup_checking_state), color = JjcTextSecondary)
                            }
                        }
                    }
                    SetupStep.INSTALL_TERMUX -> {
                        Card(
                            shape = JjcShapeMedium,
                            colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                            border = BorderStroke(1.dp, JjcBorderDark)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = if (useEnglish) "Please install Termux" else context.getString(R.string.setup_install_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JjcTextPrimary
                                )
                                Text(if (useEnglish) "Termux is required to run Git and Jujutsu commands." else context.getString(R.string.setup_install_desc), color = JjcTextSecondary)
                                JjcPrimaryButton(
                                    text = if (useEnglish) "Open Termux Download" else context.getString(R.string.setup_open_termux_download),
                                    onClick = viewModel::onInstallTermuxClicked
                                )
                            }
                        }
                    }
                    SetupStep.GRANT_RUN_COMMAND -> {
                        Card(
                            shape = JjcShapeMedium,
                            colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                            border = BorderStroke(1.dp, JjcBorderDark)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = if (useEnglish) "Please allow command execution permission" else context.getString(R.string.setup_permission_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JjcTextPrimary
                                )
                                Text(if (useEnglish) "RUN_COMMAND permission is required to send commands to Termux." else context.getString(R.string.setup_permission_desc), color = JjcTextSecondary)
                                JjcPrimaryButton(
                                    text = if (useEnglish) "Grant Permission" else context.getString(R.string.setup_request_permission),
                                    onClick = viewModel::onRequestPermissionClicked
                                )
                            }
                        }
                    }
                    SetupStep.CONFIGURE_EXTERNAL_APPS -> {
                        Card(
                            shape = JjcShapeMedium,
                            colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                            border = BorderStroke(1.dp, JjcBorderDark)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = if (useEnglish) "Configure external apps in Termux" else context.getString(R.string.setup_external_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JjcTextPrimary
                                )
                                Text(if (useEnglish) "Run the command in Termux to enable external commands." else context.getString(R.string.setup_external_desc), color = JjcTextSecondary)
                                Surface(
                                    color = JjcSurfaceDark,
                                    shape = JjcShapeSmall,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = TermuxEnvironmentChecker.SETUP_EXTERNAL_APPS_COMMAND,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = JjcTextPrimary,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                                if (uiState.isVerifying) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = JjcAccentBlue
                                        )
                                        Text(
                                            text = "Termux の外部実行設定を確認しています...",
                                            color = JjcTextSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                JjcPrimaryButton(
                                    text = if (useEnglish) "Copy Command & Open Termux" else context.getString(R.string.setup_copy_and_open_termux),
                                    onClick = viewModel::onCopyCommandAndOpenTermuxClicked,
                                    enabled = !uiState.isVerifying
                                )
                                JjcSecondaryButton(
                                    text = if (uiState.isVerifying) "確認中..." else "設定完了後に再確認",
                                    onClick = viewModel::onRetryVerificationClicked,
                                    enabled = !uiState.isVerifying
                                )
                            }
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    JjcErrorCard(
                        title = if (useEnglish) "Setup Error" else "セットアップエラー",
                        message = uiState.errorMessage!!,
                        useEnglish = useEnglish
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloneSetupRoute(
    viewModel: CloneSetupViewModel,
    savedSettings: SavedAppSettings = SavedAppSettings(),
    useEnglish: Boolean = java.util.Locale.getDefault().language != "ja",
    onNavigateToWorkspace: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(useEnglish) { viewModel.setUseEnglish(useEnglish) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localUrl by remember(savedSettings.repositoryUrl) {
        mutableStateOf(uiState.repositoryUrl.ifBlank { savedSettings.repositoryUrl })
    }
    var localSaveDir by remember(savedSettings.parentDirectoryPath) {
        mutableStateOf(
            uiState.targetParentDir.ifBlank {
                savedSettings.parentDirectoryPath.ifBlank { "/data/data/com.termux/files/home/repos" }
            }
        )
    }
    var urlError by remember { mutableStateOf<String?>(null) }
    var saveDirError by remember { mutableStateOf<String?>(null) }
    var showFolderPickerConfirm by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            val resolved = resolvePickedTreeUriToPath(pickedUri)
            if (resolved.isNotBlank()) {
                localSaveDir = resolved
                saveDirError = null
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CloneSetupEffect.NavigateToWorkspace -> onNavigateToWorkspace(effect.repositoryPath)
            }
        }
    }

    if (showFolderPickerConfirm) {
        AlertDialog(
            onDismissRequest = { showFolderPickerConfirm = false },
            containerColor = JjcCardDark,
            title = { Text(if (useEnglish) "Select Save Folder" else "保存先フォルダの選択", color = JjcTextPrimary) },
            text = {
                Text(
                    text = if (useEnglish) "Open the folder picker to choose a save directory?" else "保存先フォルダを選択しますか？",
                    color = JjcTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFolderPickerConfirm = false
                        folderPickerLauncher.launch(null)
                    }
                ) {
                    Text("はい")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderPickerConfirm = false }) {
                    Text("いいえ", color = JjcTextSecondary)
                }
            }
        )
    }

    val overwriteTarget = uiState.pendingOverwriteTargetPath
    if (overwriteTarget != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissOverwriteDialog,
            containerColor = JjcCardDark,
            title = { Text(if (useEnglish) "Folder Already Exists" else "同名フォルダが存在します", color = JjcTextPrimary) },
            text = {
                Text(
                    text = if (useEnglish) "A folder already exists at the destination. Delete it and clone fresh?\n\n$overwriteTarget" else "保存先にすでにフォルダが存在します。削除して新しくクローンしますか？\n\n$overwriteTarget",
                    color = JjcTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(onClick = viewModel::onConfirmOverwriteClicked) {
                    Text(if (useEnglish) "Delete & Clone" else "削除してクローン")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissOverwriteDialog) {
                    Text(if (useEnglish) "Cancel" else "キャンセル", color = JjcTextSecondary)
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = JjcBgDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            JjcScreenHeader(
                title = if (useEnglish) "Clone Repository" else context.getString(R.string.clone_title),
                subtitle = if (useEnglish) "Specify repository URL and save path to clone" else "リポジトリ URL と保存先パスを指定してクローン",
                useEnglish = useEnglish
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = JjcShapeMedium,
                    colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                    border = BorderStroke(1.dp, JjcBorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        JjcInputField(
                            value = localUrl,
                            onValueChange = {
                                localUrl = it
                                urlError = null
                            },
                            label = if (useEnglish) "Repository URL" else "リポジトリ URL",
                            placeholder = "https://github.com/owner/repo.git",
                            errorMessage = urlError,
                            enabled = !uiState.isCloning,
                            monospace = true
                        )

                        JjcInputField(
                            value = localSaveDir,
                            onValueChange = {
                                localSaveDir = it
                                saveDirError = null
                            },
                            label = if (useEnglish) "Save Directory Path" else "保存先パス",
                            placeholder = "/data/data/com.termux/files/home/repos",
                            errorMessage = saveDirError,
                            enabled = !uiState.isCloning,
                            monospace = true
                        )

                        JjcSecondaryButton(
                            text = if (useEnglish) "Choose Save Folder" else "保存先フォルダを選択",
                            onClick = { showFolderPickerConfirm = true },
                            enabled = !uiState.isCloning
                        )
                    }
                }

                JjcPrimaryButton(
                    text = if (uiState.isCloning) { if (useEnglish) "Cloning..." else "クローン中..." } else { if (useEnglish) "Clone & Start" else context.getString(R.string.clone_start_button) },
                    onClick = {
                        val rawUrl = localUrl.trim()
                        val cleanedDir = normalizeRepoPath(localSaveDir)
                        if (rawUrl.isBlank()) {
                            urlError = if (useEnglish) "Please enter a repository URL." else "リポジトリ URL を入力してください。"
                            return@JjcPrimaryButton
                        }
                        if (cleanedDir.isBlank()) {
                            saveDirError = if (useEnglish) "Please enter or select a save directory path." else "保存先パスを入力または選択してください。"
                            return@JjcPrimaryButton
                        }
                        val normalizedUrl = if (!rawUrl.contains("://") && !rawUrl.startsWith("git@")) {
                            "https://$rawUrl"
                        } else {
                            rawUrl
                        }
                        val inferredAuth = if (normalizedUrl.startsWith("git@") || normalizedUrl.startsWith("ssh://")) {
                            AuthType.SSH_KEY
                        } else {
                            savedSettings.resolvedAuthType()
                        }
                        val userName = savedSettings.gitUserName.ifBlank { "jj-user" }
                        val userEmail = savedSettings.gitUserEmail.ifBlank { "jj-user@example.com" }

                        localUrl = normalizedUrl
                        localSaveDir = cleanedDir
                        viewModel.applyTemplate(
                            templateName = savedSettings.templateName,
                            userName = userName,
                            userEmail = userEmail,
                            repositoryUrl = normalizedUrl,
                            parentDirectoryPath = cleanedDir,
                            authType = inferredAuth,
                            sshPrivateKeyPath = savedSettings.sshPrivateKeyPath
                        )
                        scope.launch { viewModel.onStartCloneClicked() }
                    },
                    enabled = !uiState.isCloning
                )

                if (uiState.progressMessage.isNotBlank()) {
                    Text(
                        text = uiState.progressMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = JjcAccentBlue
                    )
                }

                if (uiState.generalError != null) {
                    JjcErrorCard(
                        title = if (useEnglish) "Clone Error" else "クローンエラー",
                        message = uiState.generalError!!
                    )
                }
            }
        }
    }
}

@Composable
private fun JjLineIcon(
    kind: String,
    tint: Color,
    modifier: Modifier = Modifier.size(16.dp)
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        when (kind) {
            "status" -> {
                drawCircle(color = tint, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(color = tint, radius = w * 0.10f, center = Offset(w * 0.5f, h * 0.5f))
            }
            "refresh" -> {
                drawArc(
                    color = tint,
                    startAngle = -60f,
                    sweepAngle = 250f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.76f, h * 0.16f), Offset(w * 0.84f, h * 0.34f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.64f, h * 0.34f), Offset(w * 0.84f, h * 0.34f), stroke.width, StrokeCap.Round)
            }
            "diff" -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.35f), Offset(w * 0.52f, h * 0.35f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.37f, h * 0.20f), Offset(w * 0.37f, h * 0.50f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.48f, h * 0.72f), Offset(w * 0.80f, h * 0.72f), stroke.width, StrokeCap.Round)
            }
            "save" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.18f, h * 0.16f),
                    size = Size(w * 0.64f, h * 0.68f),
                    cornerRadius = CornerRadius(3f, 3f),
                    style = stroke
                )
                drawRect(
                    color = tint,
                    topLeft = Offset(w * 0.32f, h * 0.16f),
                    size = Size(w * 0.36f, h * 0.24f),
                    style = stroke
                )
            }
            "describe" -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.30f), Offset(w * 0.78f, h * 0.30f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.52f), Offset(w * 0.66f, h * 0.52f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.74f), Offset(w * 0.52f, h * 0.74f), stroke.width, StrokeCap.Round)
            }
            "new" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.20f, h * 0.16f),
                    size = Size(w * 0.60f, h * 0.68f),
                    cornerRadius = CornerRadius(3f, 3f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.34f), Offset(w * 0.50f, h * 0.66f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.50f), Offset(w * 0.66f, h * 0.50f), stroke.width, StrokeCap.Round)
            }
            "fetch" -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.18f), Offset(w * 0.50f, h * 0.68f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.50f), Offset(w * 0.50f, h * 0.68f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.68f, h * 0.50f), Offset(w * 0.50f, h * 0.68f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.84f), Offset(w * 0.78f, h * 0.84f), stroke.width, StrokeCap.Round)
            }
            "push" -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.50f, h * 0.18f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.36f), Offset(w * 0.50f, h * 0.18f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.68f, h * 0.36f), Offset(w * 0.50f, h * 0.18f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.84f), Offset(w * 0.78f, h * 0.84f), stroke.width, StrokeCap.Round)
            }
            "bookmark" -> {
                drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.34f, h * 0.24f), style = stroke)
                drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.34f, h * 0.76f), style = stroke)
                drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.70f, h * 0.38f), style = stroke)
                drawLine(tint, Offset(w * 0.34f, h * 0.36f), Offset(w * 0.34f, h * 0.64f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.52f), Offset(w * 0.70f, h * 0.48f), stroke.width, StrokeCap.Round)
            }
            else -> {
                drawArc(
                    color = tint,
                    startAngle = -140f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(w * 0.20f, h * 0.24f),
                    size = Size(w * 0.62f, h * 0.60f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.20f, h * 0.24f), Offset(w * 0.38f, h * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.20f, h * 0.24f), Offset(w * 0.22f, h * 0.42f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun JjActionGridButton(
    title: String,
    commandHint: String,
    iconKind: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        primary && enabled -> JjcAccentBlue
        primary && !enabled -> JjcAccentBlue.copy(alpha = 0.45f)
        else -> JjcCardDark
    }
    val fgColor = when {
        primary -> JjcAccentTextDark
        enabled -> JjcTextPrimary
        else -> JjcTextSecondary.copy(alpha = 0.6f)
    }
    val subColor = when {
        primary -> JjcAccentTextDark.copy(alpha = 0.82f)
        enabled -> JjcTextSecondary
        else -> JjcTextSecondary.copy(alpha = 0.5f)
    }
    val borderColor = if (primary) JjcAccentBlue else JjcBorderDark

    Surface(
        modifier = modifier
            .height(54.dp)
            .clip(JjcShapeMedium)
            .border(1.dp, borderColor, JjcShapeMedium)
            .clickable(enabled = enabled, onClick = onClick),
        color = bgColor,
        shape = JjcShapeMedium
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            JjLineIcon(
                kind = iconKind,
                tint = fgColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = fgColor,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = commandHint,
                    color = subColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun InitialSettingsRoute(
    savedSettings: SavedAppSettings,
    isOnboarding: Boolean = false,
    selectedLanguage: AppLanguage = AppLanguage.SYSTEM,
    onSelectLanguage: (AppLanguage) -> Unit = {},
    onSaveSettings: (userName: String, userEmail: String, parentDir: String, authType: AuthType, sshKeyPath: String) -> Unit,
    onBack: () -> Unit
) {
    var userName by remember(savedSettings) { mutableStateOf(savedSettings.gitUserName) }
    var userEmail by remember(savedSettings) { mutableStateOf(savedSettings.gitUserEmail) }
    val defaultSavePath = remember(savedSettings) {
        savedSettings.parentDirectoryPath.ifBlank { "/data/data/com.termux/files/home/repos" }
    }
    var authType by remember(savedSettings) { mutableStateOf(savedSettings.resolvedAuthType()) }
    var sshKeyPath by remember(savedSettings) { mutableStateOf(savedSettings.sshPrivateKeyPath) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val useEnglish = resolveUseEnglish(selectedLanguage)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = JjcBgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            JjcScreenHeader(
                title = if (useEnglish) {
                    if (isOnboarding) "Initial Setup" else "Initial Settings"
                } else {
                    if (useEnglish) { if (isOnboarding) "Initial Setup" else "Initial Settings" } else { if (isOnboarding) "初期設定（初回セットアップ）" else "初期設定" }
                },
                subtitle = if (useEnglish) {
                    if (isOnboarding) {
                        "Configure Git / Jujutsu author info and auth method"
                    } else {
                        "Git / Jujutsu author info and auth settings"
                    }
                } else {
                    if (isOnboarding) {
                        if (useEnglish) "Configure Git / Jujutsu author info and auth method" else "Git / Jujutsu の作成者情報と認証方式を設定してください"
                    } else {
                        if (useEnglish) "Git / Jujutsu author info and auth settings" else "Git / Jujutsu 作成者情報と認証設定"
                    }
                },
                trailing = if (!isOnboarding) {
                    {
                        OutlinedButton(
                            onClick = onBack,
                            shape = JjcShapeSmall,
                            border = BorderStroke(1.dp, JjcBorderDark)
                        ) {
                            Text(if (useEnglish) "Back" else "戻る", color = JjcTextPrimary)
                        }
                    }
                } else {
                    null
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = JjcShapeMedium,
                    colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                    border = BorderStroke(1.dp, JjcBorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (useEnglish) "Display Language" else "表示言語",
                            color = JjcTextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLanguage.entries.forEach { langOption ->
                                val chipText = if (useEnglish) langOption.labelEn else langOption.labelJa
                                FilterChip(
                                    selected = selectedLanguage == langOption,
                                    onClick = { onSelectLanguage(langOption) },
                                    shape = JjcShapeSmall,
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = JjcCardDark,
                                        labelColor = JjcTextPrimary,
                                        selectedContainerColor = Color(0xFF133347),
                                        selectedLabelColor = JjcAccentBlue
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selectedLanguage == langOption,
                                        borderColor = JjcBorderDark,
                                        selectedBorderColor = JjcAccentBlue
                                    ),
                                    label = { Text(chipText, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                        HorizontalDivider(color = JjcBorderDark, thickness = 1.dp)
                                                Text(
                            text = if (useEnglish) "Author Info" else "作成者情報",
                            color = JjcTextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )

                        JjcInputField(
                            value = userName,
                            onValueChange = {
                                userName = it
                                errorMsg = null
                            },
                            label = if (useEnglish) "Git User Name" else "Git ユーザー名",
                            placeholder = if (useEnglish) "e.g. jj-user" else "例: jj-user"
                        )

                        JjcInputField(
                            value = userEmail,
                            onValueChange = {
                                userEmail = it
                                errorMsg = null
                            },
                            label = if (useEnglish) "Git Email" else "Git メールアドレス",
                            placeholder = if (useEnglish) "e.g. jj-user@example.com" else "例: jj-user@example.com"
                        )

                        JjcAuthTypeSelector(
                            selected = authType,
                            onSelect = { authType = it }
                        )

                        if (authType == AuthType.SSH_KEY) {
                            JjcInputField(
                                value = sshKeyPath,
                                onValueChange = { sshKeyPath = it },
                                label = if (useEnglish) "SSH Private Key Path" else "SSH 秘密鍵パス",
                                placeholder = "/data/data/com.termux/files/home/.ssh/id_ed25519",
                                monospace = true
                            )
                        }
                    }
                }

                if (errorMsg != null) {
                    JjcErrorCard(
                        title = if (useEnglish) "Input Error" else "入力エラー",
                        message = errorMsg!!
                    )
                }

                JjcPrimaryButton(
                    text = if (useEnglish) {
                        if (isOnboarding) "Save & Continue" else "Save Settings"
                    } else {
                        if (useEnglish) { if (isOnboarding) "Save & Continue" else "Save Settings" } else { if (isOnboarding) "初期設定を保存して次へ" else "初期設定を保存" }
                    },
                    onClick = {
                        val trimmedName = userName.trim()
                        val trimmedEmail = userEmail.trim()
                        if (trimmedName.isBlank() || trimmedEmail.isBlank()) {
                            errorMsg = if (useEnglish) {
                                "Please enter both Git user name and email."
                            } else {
                                if (useEnglish) "Please enter both Git user name and email." else "Git ユーザー名とメールアドレスを入力してください。"
                            }
                        } else {
                            onSaveSettings(trimmedName, trimmedEmail, defaultSavePath, authType, sshKeyPath.trim())
                        }
                    }
                )
            }
        }
    }
}

private fun normalizeRepoPath(raw: String): String {
    return raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")
        .replace(Regex("/+"), "/")
        .trimEnd('/')
}

private fun isSafeRepositoryDeletionPath(path: String): Boolean {
    val cleaned = normalizeRepoPath(path)
    if (cleaned.isEmpty()) return false
    val forbidden = setOf(
        "/",
        "/data",
        "/data/data",
        "/data/data/com.termux",
        "/data/data/com.termux/files",
        "/data/data/com.termux/files/home",
        "/data/data/com.termux/files/home/repos",
        "/data/data/com.termux/files/usr",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0"
    )
    if (cleaned in forbidden) return false
    return cleaned.length >= 12 && cleaned.contains("/")
}

private fun resolvePickedTreeUriToPath(uri: Uri): String {
    val decoded = Uri.decode(uri.toString())
    val treePart = decoded.substringAfter("/tree/", missingDelimiterValue = "").substringBefore("?")
    if (treePart.isNotBlank()) {
        if (treePart.startsWith("data/")) {
            return normalizeRepoPath("/$treePart")
        }
        if (treePart.startsWith("/data/")) {
            return normalizeRepoPath(treePart)
        }
        if (treePart.startsWith("primary:")) {
            val rel = treePart.removePrefix("primary:").trim('/')
            return if (rel.isBlank()) "/storage/emulated/0" else normalizeRepoPath("/storage/emulated/0/$rel")
        }
        if (treePart.contains(":")) {
            val vol = treePart.substringBefore(":")
            val rel = treePart.substringAfter(":").trim('/')
            if (vol.equals("home", ignoreCase = true)) {
                return if (rel.isBlank()) "/storage/emulated/0/Documents" else normalizeRepoPath("/storage/emulated/0/Documents/$rel")
            }
            return if (rel.isBlank()) "/storage/$vol" else normalizeRepoPath("/storage/$vol/$rel")
        }
    }
    if (decoded.startsWith("content:/") && !decoded.startsWith("content://")) {
        val afterTree = decoded.substringAfter("tree/", missingDelimiterValue = "")
        if (afterTree.isNotBlank()) {
            return normalizeRepoPath("/$afterTree")
        }
    }
    return normalizeRepoPath(decoded)
}

@Composable
fun RepositorySelectorRoute(
    currentRepoPath: String,
    prefsRepository: AppPreferencesRepository,
    jjRepository: JjRepository,
    useEnglish: Boolean = java.util.Locale.getDefault().language != "ja",
    onSelectRepository: (String) -> Unit,
    onBackToWorkspace: (String) -> Unit
) {
    val context = LocalContext.current
    val commandClient = remember(context) { TermuxCommandClient(context) }
    val cloneRepository = remember(commandClient) { GitCloneRepository(commandClient) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(prefsRepository.loadSettings()) }
    val normalizedCurrent = normalizeRepoPath(currentRepoPath)
    var selectedPath by remember(normalizedCurrent) { mutableStateOf(normalizedCurrent) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    var showRepoManagerDialog by remember { mutableStateOf(false) }
    var cloneUrlInput by remember { mutableStateOf("") }
    var cloneSavePathInput by remember(settings.parentDirectoryPath) { mutableStateOf(settings.parentDirectoryPath.ifBlank { "/data/data/com.termux/files/home/repos" }) }
    var showFolderPickerWarning by remember { mutableStateOf(false) }
    var isCloningInDialog by remember { mutableStateOf(false) }
    var clonePhaseMessage by remember { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    var pendingDeleteRepo by remember { mutableStateOf<String?>(null) }
    var isProcessingDelete by remember { mutableStateOf(false) }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            val resolved = resolvePickedTreeUriToPath(pickedUri)
            if (resolved.isNotBlank()) {
                cloneSavePathInput = resolved
                dialogMessage = null
            }
        }
    }

    val repos = remember(settings, selectedPath, normalizedCurrent) {
        buildList {
            if (normalizedCurrent.isNotBlank()) add(normalizedCurrent)
            if (selectedPath.isNotBlank()) add(selectedPath)
            settings.registeredRepositories.forEach { raw ->
                val cleaned = normalizeRepoPath(raw)
                if (cleaned.isNotBlank()) add(cleaned)
            }
        }.distinct().filter {
            it != "/data/data/com.termux/files/home/repos" && !it.startsWith("content:")
        }
    }

    fun applyRemoveFromList(targetRepo: String) {
        prefsRepository.removeRepository(targetRepo)
        settings = prefsRepository.loadSettings()
        if (selectedPath == targetRepo) {
            selectedPath = normalizeRepoPath(settings.activeRepositoryPath.orEmpty())
                .ifBlank { repos.firstOrNull { it != targetRepo } ?: "" }
        }
    }

    if (showFolderPickerWarning) {
        AlertDialog(
            onDismissRequest = { showFolderPickerWarning = false },
            containerColor = JjcCardDark,
            title = { Text(if (useEnglish) "Select Save Folder" else "保存先フォルダの選択", color = JjcTextPrimary) },
            text = {
                Text(
                    text = "端末のファイル一覧（フォルダ選択画面）から保存先を選択しますか？",
                    color = JjcTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFolderPickerWarning = false
                        openDocumentTreeLauncher.launch(null)
                    }
                ) {
                    Text("はい")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFolderPickerWarning = false }
                ) {
                    Text("いいえ", color = JjcTextSecondary)
                }
            }
        )
    }

    if (pendingDeleteRepo != null) {
        val targetRepo = pendingDeleteRepo!!
        AlertDialog(
            onDismissRequest = { if (!isProcessingDelete) pendingDeleteRepo = null },
            containerColor = JjcCardDark,
            title = { Text("リポジトリの除外確認", color = JjcTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "一覧から除外しますか？実フォルダの削除有無も選択できます。",
                        color = JjcTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = targetRepo,
                        color = JjcTextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isProcessingDelete,
                    onClick = {
                        if (!isSafeRepositoryDeletionPath(targetRepo)) {
                            pendingDeleteRepo = null
                            showRepoManagerDialog = false
                            statusBanner = "安全のためこのパスの実フォルダ削除はブロックされました: $targetRepo"
                            return@Button
                        }
                        scope.launch {
                            isProcessingDelete = true
                            val rmResult = commandClient.execute(
                                executablePath = "${TermuxCommandClient.TERMUX_BIN_PREFIX}/rm",
                                arguments = arrayOf("-rf", targetRepo),
                                timeoutMillis = 20_000L
                            )
                            val fallbackDeleted = if (rmResult.isFailure) {
                                runCatching { File(targetRepo).deleteRecursively() }.getOrDefault(false)
                            } else {
                                true
                            }
                            applyRemoveFromList(targetRepo)
                            isProcessingDelete = false
                            pendingDeleteRepo = null
                            showRepoManagerDialog = false
                            statusBanner = if (rmResult.isSuccess || fallbackDeleted) {
                                "一覧から除外し、実フォルダも削除しました: $targetRepo"
                            } else {
                                "一覧から除外しましたが、実フォルダ削除に失敗しました: ${rmResult.exceptionOrNull()?.message ?: targetRepo}"
                            }
                        }
                    }
                ) {
                    Text(if (isProcessingDelete) "削除中..." else "実フォルダも削除")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !isProcessingDelete,
                        onClick = {
                            applyRemoveFromList(targetRepo)
                            pendingDeleteRepo = null
                            showRepoManagerDialog = false
                            statusBanner = "一覧から除外しました（実フォルダは保持されます）: $targetRepo"
                        }
                    ) {
                        Text("除外のみ", color = JjcTextPrimary)
                    }
                    TextButton(
                        enabled = !isProcessingDelete,
                        onClick = { pendingDeleteRepo = null }
                    ) {
                        Text("キャンセル", color = JjcTextSecondary)
                    }
                }
            }
        )
    }

    if (showRepoManagerDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isProcessingDelete && !isCloningInDialog) {
                    showRepoManagerDialog = false
                    dialogMessage = null
                }
            },
            containerColor = JjcCardDark,
            title = { Text("Repo登録・管理", color = JjcTextPrimary) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    JjcInputField(
                        value = cloneUrlInput,
                        onValueChange = {
                            cloneUrlInput = it
                            dialogMessage = null
                        },
                        label = "クローンするリポジトリ (URL)",
                        placeholder = "https://github.com/owner/repo.git",
                        enabled = !isCloningInDialog
                    )

                    JjcInputField(
                        value = cloneSavePathInput,
                        onValueChange = {
                            cloneSavePathInput = it
                            dialogMessage = null
                        },
                        label = if (useEnglish) "Save Directory Path" else "保存先パス",
                        placeholder = "/data/data/com.termux/files/home/repos",
                        enabled = !isCloningInDialog,
                        monospace = true
                    )

                    OutlinedButton(
                        onClick = {
                            dialogMessage = null
                            showFolderPickerWarning = true
                        },
                        enabled = !isCloningInDialog,
                        shape = JjcShapeSmall,
                        border = BorderStroke(1.dp, JjcBorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("フォルダ選択画面から指定", color = JjcTextPrimary)
                    }

                    if (normalizeRepoPath(cloneSavePathInput).startsWith("/storage/")) {
                        JjcErrorText(
                            message = if (useEnglish) "Paths under /storage/ may fail with jj file-lock errors (os error 38). Termux /data/data/com.termux/files/home/repos is recommended." else "/storage/ 配下は jj のファイルロック非対応（os error 38）になる場合があります。Termux の /data/data/com.termux/files/home/repos を推奨します。",
                            useEnglish = useEnglish
                        )
                    }

                    if (isCloningInDialog && clonePhaseMessage.isNotBlank()) {
                        Text(
                            text = clonePhaseMessage,
                            color = JjcAccentBlue,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    JjcPrimaryButton(
                        text = if (isCloningInDialog) "クローン中..." else "クローンして登録 (jj git clone)",
                        enabled = !isCloningInDialog && !isProcessingDelete,
                        onClick = {
                            val trimmedUrl = cloneUrlInput.trim()
                            val trimmedParent = normalizeRepoPath(cloneSavePathInput)
                            if (trimmedUrl.isBlank()) {
                                dialogMessage = "クローンするリポジトリ (URL) を入力してください。"
                                return@JjcPrimaryButton
                            }
                            if (trimmedParent.isBlank()) {
                                dialogMessage = "保存先パスを選択してください。"
                                return@JjcPrimaryButton
                            }
                            val inferredAuth = if (trimmedUrl.startsWith("git@") || trimmedUrl.startsWith("ssh://")) {
                                AuthType.SSH_KEY
                            } else {
                                AuthType.HTTPS
                            }
                            val latestSettings = prefsRepository.loadSettings()
                            val userName = latestSettings.gitUserName.ifBlank { "jj-user" }
                            val userEmail = latestSettings.gitUserEmail.ifBlank { "jj-user@example.com" }

                            scope.launch {
                                isCloningInDialog = true
                                clonePhaseMessage = "jj git clone を実行中..."
                                dialogMessage = null
                                val result = cloneRepository.cloneAndConfigure(
                                    params = CloneParams(
                                        gitUserName = userName,
                                        gitUserEmail = userEmail,
                                        repositoryUrl = trimmedUrl,
                                        parentDirectoryPath = trimmedParent,
                                        authType = inferredAuth,
                                        sshPrivateKeyPath = latestSettings.sshPrivateKeyPath.ifBlank { null },
                                        overwriteExisting = false
                                    ),
                                    onPhaseChanged = { phase ->
                                        clonePhaseMessage = phase
                                    },
                                        useEnglish = useEnglish
                                    )
                                isCloningInDialog = false
                                clonePhaseMessage = ""
                                result.fold(
                                    onSuccess = { clonedPath ->
                                        val normCloned = normalizeRepoPath(clonedPath)
                                        prefsRepository.registerRepositoryPath(normCloned)
                                        settings = prefsRepository.loadSettings()
                                        selectedPath = normCloned
                                        cloneUrlInput = ""
                                        showRepoManagerDialog = false
                                        statusBanner = "クローンして登録しました: $normCloned"
                                    },
                                    onFailure = { err ->
                                        dialogMessage = err.message ?: "リポジトリのクローンに失敗しました。"
                                    }
                                )
                            }
                        }
                    )

                    if (dialogMessage != null) {
                        JjcErrorText(message = dialogMessage!!)
                    }

                    if (repos.isNotEmpty()) {
                        Text(
                            text = "登録済みリポジトリ",
                            color = JjcTextPrimary,
                            style = MaterialTheme.typography.titleSmall
                        )

                        repos.forEach { repo ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = repo,
                                    color = JjcTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    enabled = !isProcessingDelete && !isCloningInDialog && repos.size > 1,
                                    onClick = { pendingDeleteRepo = repo }
                                ) {
                                    Text("削除", color = JjcErrorText)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isCloningInDialog && !isProcessingDelete,
                    onClick = {
                        showRepoManagerDialog = false
                        dialogMessage = null
                    }
                ) {
                    Text(if (useEnglish) "Close" else "閉じる", color = JjcAccentBlue)
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = JjcBgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            JjcScreenHeader(
                title = "Jujutsu Controller",
                subtitle = if (useEnglish) "Android Edition · Select a repository (jj)" else "Android Edition · リポジトリ切替 (jj)",
                useEnglish = useEnglish,
                trailing = {
                    OutlinedButton(
                        onClick = {
                            val target = selectedPath.ifBlank { normalizedCurrent }
                            onBackToWorkspace(target)
                        },
                        shape = JjcShapeSmall,
                        border = BorderStroke(1.dp, JjcBorderDark)
                    ) {
                        Text(if (useEnglish) "Back" else "戻る", color = JjcTextPrimary)
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (useEnglish) "Select a repository" else "管理対象のリポジトリを選択",
                        color = JjcTextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(
                        onClick = {
                            dialogMessage = null
                            showRepoManagerDialog = true
                        }
                    ) {
                        Text(if (useEnglish) "Add Repo" else "Repo登録", color = JjcAccentBlue)
                    }
                }

                if (statusBanner != null) {
                    Text(
                        text = statusBanner!!,
                        color = JjcTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                repos.forEach { repo ->
                    val isSelected = repo == selectedPath
                    val repoTitle = repo.substringAfterLast('/').ifEmpty { repo }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedPath = repo
                                prefsRepository.setActiveRepository(repo)
                                scope.launch {
                                    runCatching { jjRepository.loadCommitHistory(repo) }
                                    onSelectRepository(repo)
                                }
                            },
                        shape = JjcShapeMedium,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF172334) else JjcCardDark
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) JjcAccentBlue else JjcBorderDark
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (isSelected) "> $repoTitle" else repoTitle,
                                color = JjcTextPrimary,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = repo,
                                color = JjcTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkspaceScreen(
    repoPath: String,
    jjRepository: JjRepository,
    useEnglish: Boolean = java.util.Locale.getDefault().language != "ja",
    onOpenRepositorySelector: () -> Unit,
    onOpenInitialSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var isBusy by remember { mutableStateOf(false) }
    var statusOutput by remember { mutableStateOf<JjCommandOutput?>(null) }
    var logText by remember { mutableStateOf<String?>(null) }
    var logErrorText by remember { mutableStateOf<String?>(null) }
    var selectedLogDetail by remember { mutableStateOf<String?>(null) }
    var inputDialogMode by remember { mutableStateOf<JjActionType?>(null) }
    var inputText by remember { mutableStateOf("") }

    val repoName = remember(repoPath) {
        repoPath.trimEnd('/').substringAfterLast('/').ifEmpty { repoPath }
    }

    fun refreshJjState() {
        if (repoPath.isBlank()) return
        scope.launch {
            isBusy = true
            statusOutput = runCatching {
                jjRepository.runAction(repoPath = repoPath, action = JjActionType.STATUS)
            }.getOrElse { err ->
                JjCommandOutput(
                    action = JjActionType.STATUS,
                    commandSummary = "jj status",
                    output = err.message ?: if (useEnglish) "Failed to load status." else "状態取得に失敗しました。",
                    isError = true
                )
            }

            val historyResult = runCatching { jjRepository.loadCommitHistory(repoPath) }
                .getOrElse { Result.failure(it) }
            historyResult.fold(
                onSuccess = { text ->
                    logText = text
                    logErrorText = null
                },
                onFailure = { err ->
                    logErrorText = err.message ?: if (useEnglish) "Failed to load commit log." else "履歴取得に失敗しました。"
                }
            )
            isBusy = false
        }
    }

    fun executeJjAction(action: JjActionType, argument: String = "") {
        if (repoPath.isBlank()) return
        scope.launch {
            isBusy = true
            val result = runCatching {
                jjRepository.runAction(
                    repoPath = repoPath,
                    action = action,
                    inputValue = argument.ifBlank { null }
                )
            }.getOrElse { err ->
                JjCommandOutput(
                    action = action,
                    commandSummary = action.label,
                    output = err.message ?: if (useEnglish) "Command execution error" else "コマンド実行エラー",
                    isError = true
                )
            }

            if (action == JjActionType.LOG) {
                if (result.isError) {
                    logErrorText = result.output
                } else {
                    logText = result.output
                    logErrorText = null
                }
            } else {
                statusOutput = result
                if (!result.isError && action != JjActionType.STATUS && action != JjActionType.DIFF) {
                    runCatching { jjRepository.loadCommitHistory(repoPath) }
                        .getOrNull()
                        ?.onSuccess { text ->
                            logText = text
                            logErrorText = null
                        }
                }
            }
            isBusy = false
        }
    }

    LaunchedEffect(repoPath) {
        refreshJjState()
    }

    DisposableEffect(lifecycleOwner, repoPath) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshJjState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (inputDialogMode != null) {
        val mode = inputDialogMode!!
        val dialogTitle = when (mode) {
            JjActionType.COMMIT -> if (useEnglish) "Commit Changes (jj commit)" else "変更を保存 (jj commit)"
            JjActionType.DESCRIBE -> if (useEnglish) "Edit Description (jj describe)" else "説明を編集 (jj describe)"
            JjActionType.NEW -> if (useEnglish) "New Change (jj new)" else "新規作成 (jj new)"
            JjActionType.BOOKMARK -> if (useEnglish) "Bookmark (jj bookmark)" else "ブックマーク (jj bookmark)"
            else -> if (useEnglish) "jj Action" else "jj 操作"
        }
        val dialogLabel = when (mode) {
            JjActionType.BOOKMARK -> if (useEnglish) "Bookmark name" else "ブックマーク名"
            else -> if (useEnglish) "Message / Description" else "メッセージ・説明"
        }
        AlertDialog(
            onDismissRequest = { inputDialogMode = null },
            containerColor = JjcCardDark,
            title = { Text(dialogTitle, color = JjcTextPrimary) },
            text = {
                JjcInputField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = dialogLabel
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val value = inputText.trim()
                        inputDialogMode = null
                        executeJjAction(mode, value)
                    }
                ) {
                    Text("実行")
                }
            },
            dismissButton = {
                TextButton(onClick = { inputDialogMode = null }) {
                    Text("キャンセル", color = JjcTextSecondary)
                }
            }
        )
    }

    if (selectedLogDetail != null) {
        AlertDialog(
            onDismissRequest = { selectedLogDetail = null },
            containerColor = JjcCardDark,
            title = { Text("コミット詳細 (jj log)", color = JjcAccentBlue) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = formatJjLogEntryAnnotated(selectedLogDetail!!),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLogDetail = null }) {
                    Text(if (useEnglish) "Close" else "閉じる", color = JjcAccentBlue)
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = JjcBgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            JjcScreenHeader(
                title = "Jujutsu Controller",
                subtitle = "Android Edition · $repoName",
                detail = repoPath,
                useEnglish = useEnglish,
                trailing = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenRepositorySelector,
                            shape = JjcShapeSmall,
                            border = BorderStroke(1.dp, JjcBorderDark),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(if (useEnglish) "Repositories" else "リポジトリ選択", color = JjcTextPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = onOpenInitialSettings,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(if (useEnglish) "Settings" else "初期設定", color = JjcAccentBlue, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val actionSpecs = if (useEnglish) {
                    listOf(
                        listOf(
                            Triple("Status", "(jj status)", "status"),
                            Triple("Refresh Log", "(jj log)", "refresh")
                        ),
                        listOf(
                            Triple("Working Diff", "(jj diff)", "diff"),
                            Triple("Commit", "(jj commit)", "save")
                        ),
                        listOf(
                            Triple("Describe", "(jj describe)", "describe"),
                            Triple("New Change", "(jj new)", "new")
                        ),
                        listOf(
                            Triple("Fetch", "(jj git fetch)", "fetch"),
                            Triple("Push", "(jj git push)", "push")
                        ),
                        listOf(
                            Triple("Bookmark", "(jj bookmark)", "bookmark"),
                            Triple("Undo", "(jj undo)", "undo")
                        )
                    )
                } else {
                    listOf(
                        listOf(
                            Triple("状態", "(jj status)", "status"),
                            Triple("履歴を更新", "(jj log)", "refresh")
                        ),
                        listOf(
                            Triple("作業コピー差分", "(jj diff)", "diff"),
                            Triple("変更を保存", "(jj commit)", "save")
                        ),
                        listOf(
                            Triple("説明を編集", "(jj describe)", "describe"),
                            Triple("新規作成", "(jj new)", "new")
                        ),
                        listOf(
                            Triple("取得", "(jj git fetch)", "fetch"),
                            Triple("送信", "(jj git push)", "push")
                        ),
                        listOf(
                            Triple("ブックマーク", "(jj bookmark)", "bookmark"),
                            Triple("取り消し", "(jj undo)", "undo")
                        )
                    )
                }

                actionSpecs.forEachIndexed { rowIdx, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEachIndexed { colIdx, spec ->
                            val (title, hint, icon) = spec
                            val isPrimary = rowIdx == 0 && colIdx == 0
                            JjActionGridButton(
                                title = title,
                                commandHint = hint,
                                iconKind = icon,
                                primary = isPrimary,
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    when (hint) {
                                        "(jj status)" -> executeJjAction(JjActionType.STATUS)
                                        "(jj log)" -> executeJjAction(JjActionType.LOG)
                                        "(jj diff)" -> executeJjAction(JjActionType.DIFF)
                                        "(jj commit)" -> {
                                            inputText = ""
                                            inputDialogMode = JjActionType.COMMIT
                                        }
                                        "(jj describe)" -> {
                                            inputText = ""
                                            inputDialogMode = JjActionType.DESCRIBE
                                        }
                                        "(jj new)" -> {
                                            inputText = ""
                                            inputDialogMode = JjActionType.NEW
                                        }
                                        "(jj git fetch)" -> executeJjAction(JjActionType.FETCH)
                                        "(jj git push)" -> executeJjAction(JjActionType.PUSH)
                                        "(jj bookmark)" -> {
                                            inputText = ""
                                            inputDialogMode = JjActionType.BOOKMARK
                                        }
                                        "(jj undo)" -> executeJjAction(JjActionType.UNDO)
                                    }
                                }
                            )
                        }
                    }
                }

                if (isBusy) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = JjcShapeMedium,
                        colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                        border = BorderStroke(1.dp, JjcBorderDark)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = JjcAccentBlue
                            )
                            Text(
                                text = if (useEnglish) "Running jj command..." else "jj コマンドを実行中...",
                                color = JjcTextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                val currentStatus = statusOutput
                if (currentStatus != null) {
                    val isError = currentStatus.isError
                    if (isError) {
                        JjcErrorCard(
                            title = if (useEnglish) "Output / Error (${currentStatus.commandSummary})" else "出力 / エラー (${currentStatus.commandSummary})",
                            message = currentStatus.output
                        )
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = JjcShapeMedium,
                            colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                            border = BorderStroke(1.dp, JjcBorderDark)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (useEnglish) "Status (${currentStatus.commandSummary})" else "状態 (${currentStatus.commandSummary})",
                                    color = JjcAccentBlue,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = formatJjStatusAnnotated(currentStatus.output.ifBlank { if (useEnglish) "(No output)" else "(出力なし)" }),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Text(
                    text = if (useEnglish) "Commit Log (jj log)" else "コミット履歴 (jj log)",
                    color = JjcTextPrimary,
                    style = MaterialTheme.typography.titleMedium
                )

                if (logText == null && logErrorText == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = JjcShapeMedium,
                        colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                        border = BorderStroke(1.dp, JjcBorderDark)
                    ) {
                        Text(
                            text = if (useEnglish) "Loading commit history..." else "履歴を読み込んでいます...",
                            color = JjcTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else if (logErrorText != null && logText.isNullOrBlank()) {
                    JjcErrorCard(
                        title = if (useEnglish) "Log Error (jj log)" else "履歴取得エラー (jj log)",
                        message = logErrorText!!
                    )
                } else {
                    val entries = logText.orEmpty()
                        .split("\n\n")
                        .flatMap { block ->
                            val trimmed = block.trim()
                            if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
                        }

                    if (entries.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = JjcShapeMedium,
                            colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                            border = BorderStroke(1.dp, JjcBorderDark)
                        ) {
                            Text(
                                text = if (useEnglish) "Commit history (0 entries)" else "コミット履歴 (0件)",
                                color = JjcTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        entries.forEach { entry ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedLogDetail = entry },
                                shape = JjcShapeMedium,
                                colors = CardDefaults.cardColors(containerColor = JjcCardDark),
                                border = BorderStroke(1.dp, JjcBorderDark)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = formatJjLogEntryAnnotated(entry),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = if (useEnglish) "Tap to view details" else "タップして詳細を表示",
                                        color = JjcAccentBlue,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val JjcAddedGreen = Color(0xFF5CE08A)
private val JjcModifiedAmber = Color(0xFFFFC857)
private val JjcDeletedRed = Color(0xFFFF7B7B)
private val JjcCommitIdViolet = Color(0xFFB39DDB)
private val JjcBookmarkTeal = Color(0xFF4DD0E1)

private fun formatJjStatusAnnotated(rawText: String): androidx.compose.ui.text.AnnotatedString {
    val idRegex = Regex("""\b([k-z]{8,12})\s+([0-9a-f]{7,12})\b""")
    return androidx.compose.ui.text.buildAnnotatedString {
        val lines = rawText.split("\n")
        lines.forEachIndexed { idx, line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("A ") || trimmed.startsWith("Added ") -> {
                    withStyle(SpanStyle(color = JjcAddedGreen, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) {
                        append(line)
                    }
                }
                trimmed.startsWith("M ") || trimmed.startsWith("Modified ") || trimmed.startsWith("R ") || trimmed.startsWith("Renamed ") -> {
                    withStyle(SpanStyle(color = JjcModifiedAmber)) {
                        append(line)
                    }
                }
                trimmed.startsWith("D ") || trimmed.startsWith("Deleted ") -> {
                    withStyle(SpanStyle(color = JjcDeletedRed)) {
                        append(line)
                    }
                }
                line.contains("conflict", ignoreCase = true) || line.contains("error", ignoreCase = true) -> {
                    withStyle(SpanStyle(color = JjcErrorText)) {
                        append(line)
                    }
                }
                trimmed.startsWith("Working copy") || trimmed.startsWith("Parent commit") -> {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val labelPart = line.substring(0, colonIdx + 1)
                        val bodyPart = line.substring(colonIdx + 1)
                        val isWorkingCopy = trimmed.startsWith("Working copy")
                        withStyle(
                            SpanStyle(
                                color = if (isWorkingCopy) JjcAccentBlue else JjcTextPrimary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        ) {
                            append(labelPart)
                        }
                        val idMatch = idRegex.find(bodyPart)
                        if (idMatch != null) {
                            append(bodyPart.substring(0, idMatch.range.first))
                            withStyle(SpanStyle(color = JjcAccentBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) {
                                append(idMatch.groupValues[1])
                            }
                            append(" ")
                            withStyle(SpanStyle(color = JjcCommitIdViolet)) {
                                append(idMatch.groupValues[2])
                            }
                            val rest = bodyPart.substring(idMatch.range.last + 1)
                            withStyle(SpanStyle(color = JjcTextSecondary)) {
                                append(rest)
                            }
                        } else {
                            withStyle(SpanStyle(color = JjcTextSecondary)) {
                                append(bodyPart)
                            }
                        }
                    } else {
                        withStyle(SpanStyle(color = JjcTextPrimary)) { append(line) }
                    }
                }
                else -> {
                    withStyle(SpanStyle(color = JjcTextSecondary)) {
                        append(line)
                    }
                }
            }
            if (idx < lines.lastIndex) append("\n")
        }
    }
}

private fun formatJjLogEntryAnnotated(entry: String): androidx.compose.ui.text.AnnotatedString {
    val headerLineRegex = Regex("""^([@○◆×◉│├└─\s]*)([k-z]{8,12})\s+(\S+@\S+)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})(?:\s+(.*))?$""")
    val commitHashRegex = Regex("""\b[0-9a-f]{7,12}\b""")
    val metaTagRegex = Regex("""\(empty\)|\(no description set\)""")
    return androidx.compose.ui.text.buildAnnotatedString {
        val lines = entry.split("\n")
        lines.forEachIndexed { lineIdx, line ->
            val trimmed = line.trim()
            val headerMatch = headerLineRegex.matchEntire(line)
            when {
                headerMatch != null -> {
                    val graphPrefix = headerMatch.groupValues[1]
                    val changeId = headerMatch.groupValues[2]
                    val email = headerMatch.groupValues[3]
                    val timestamp = headerMatch.groupValues[4]
                    val trailingMeta = headerMatch.groupValues[5]
                    if (graphPrefix.isNotEmpty()) {
                        withStyle(SpanStyle(color = JjcAccentBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                            append(graphPrefix)
                        }
                    }
                    withStyle(SpanStyle(color = JjcAccentBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                        append(changeId)
                    }
                    append(" ")
                    withStyle(SpanStyle(color = JjcTextSecondary)) {
                        append("$email $timestamp")
                    }
                    if (trailingMeta.isNotBlank()) {
                        append(" ")
                        trailingMeta.split(Regex("""\s+""")).forEachIndexed { tIdx, tok ->
                            if (tIdx > 0) append(" ")
                            when {
                                commitHashRegex.matches(tok) -> {
                                    withStyle(SpanStyle(color = JjcCommitIdViolet)) { append(tok) }
                                }
                                tok == "(empty)" || tok == "(no description set)" -> {
                                    withStyle(SpanStyle(color = JjcTextSecondary)) { append(tok) }
                                }
                                else -> {
                                    withStyle(SpanStyle(color = JjcBookmarkTeal, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) {
                                        append(tok)
                                    }
                                }
                            }
                        }
                    }
                }
                commitHashRegex.matches(trimmed) -> {
                    withStyle(SpanStyle(color = JjcCommitIdViolet)) {
                        append(line)
                    }
                }
                metaTagRegex.containsMatchIn(trimmed) -> {
                    var cursor = 0
                    metaTagRegex.findAll(line).forEach { m ->
                        if (m.range.first > cursor) {
                            withStyle(SpanStyle(color = JjcTextPrimary)) {
                                append(line.substring(cursor, m.range.first))
                            }
                        }
                        withStyle(SpanStyle(color = JjcTextSecondary)) {
                            append(m.value)
                        }
                        cursor = m.range.last + 1
                    }
                    if (cursor < line.length) {
                        withStyle(SpanStyle(color = JjcTextPrimary)) {
                            append(line.substring(cursor))
                        }
                    }
                }
                Regex("""^[0-9a-f]{7,12}\b""").containsMatchIn(trimmed) || Regex("""^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}""").containsMatchIn(trimmed) -> {
                    line.split(Regex("""(\s+)""")).forEach { part ->
                        val p = part.trim()
                        when {
                            p.isEmpty() -> append(part)
                            commitHashRegex.matches(p) -> withStyle(SpanStyle(color = JjcCommitIdViolet)) { append(part) }
                            Regex("""^\d{4}-\d{2}-\d{2}$""").matches(p) || Regex("""^\d{2}:\d{2}:\d{2}$""").matches(p) ->
                                withStyle(SpanStyle(color = JjcTextSecondary)) { append(part) }
                            else -> withStyle(SpanStyle(color = JjcBookmarkTeal, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(part) }
                        }
                    }
                }
                trimmed.contains("@") && !trimmed.contains(" ") -> {
                    withStyle(SpanStyle(color = JjcTextSecondary)) {
                        append(line)
                    }
                }
                else -> {
                    withStyle(SpanStyle(color = JjcTextPrimary)) {
                        append(line)
                    }
                }
            }
            if (lineIdx < lines.lastIndex) append("\n")
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = JjcTextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = JjcTextPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

















