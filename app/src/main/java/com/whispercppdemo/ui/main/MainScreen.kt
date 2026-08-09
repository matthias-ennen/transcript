package de.matthiasennen.transcript.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.exportTranscript
import de.matthiasennen.transcript.ai.AiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val languages = listOf(
    "auto" to "Automatisch – empfohlen",
    "en" to "Englisch",
    "de" to "Deutsch"
)

private enum class PendingTranscriptAction {
    SELECT_AUDIO,
    START_RECORDING,
    TRANSCRIBE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    val state = viewModel.uiState
    val context = LocalContext.current
    var page by remember { mutableStateOf(AppPage.MAIN) }
    var appLanguage by remember {
        mutableStateOf(AppLanguagePreference.load(context))
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::selectAudio)
    }
    var pendingModelDownload by remember { mutableStateOf<WhisperModel?>(null) }
    var pendingAiModelDownload by remember { mutableStateOf<AiModel?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingModelDownload?.let(viewModel::downloadModel)
        pendingAiModelDownload?.let(viewModel::downloadAiModel)
        pendingModelDownload = null
        pendingAiModelDownload = null
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording() else viewModel.reportMicrophonePermissionDenied()
    }
    val textExporter = rememberExporter(context, state, ExportFormat.TEXT)
    val srtExporter = rememberExporter(context, state, ExportFormat.SUBRIP)
    val jsonExporter = rememberExporter(context, state, ExportFormat.JSON)

    Scaffold(
        topBar = {
            TranscriptTopBar(
                page = page,
                appLanguage = appLanguage,
                onAppLanguageSelected = { language ->
                    appLanguage = language
                    AppLanguagePreference.save(context, language)
                },
                onNavigate = { page = it }
            )
        }
    ) { innerPadding ->
        when (page) {
            AppPage.SETTINGS -> SettingsScreen(
                state = state,
                onDeleteModel = viewModel::deleteModel,
                onDeleteAllModels = viewModel::deleteAllModels,
                onAiEnabledChanged = viewModel::setAiPostProcessingEnabled,
                onAiAutomaticChanged = viewModel::setAutomaticAiPostProcessingEnabled,
                onSelectAiModel = viewModel::selectAiModel,
                onDownloadAiModel = { model ->
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingAiModelDownload = model
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.downloadAiModel(model)
                    }
                },
                onDeleteAiModel = viewModel::deleteAiModel,
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.ABOUT -> AboutScreen(
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.MAIN -> MainContent(
                viewModel = viewModel,
                state = state,
                openSettings = { page = AppPage.SETTINGS },
                innerPadding = innerPadding,
                audioPicker = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                requestModelDownload = {
                    val model = state.selectedModel
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingModelDownload = model
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.downloadModel(model)
                    }
                },
                requestRecording = {
                    if (state.isRecording) {
                        viewModel.stopRecording()
                    } else if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.startRecording()
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                textExporter = {
                    textExporter.launch(transcriptExportFileName(state, ExportFormat.TEXT))
                },
                srtExporter = {
                    srtExporter.launch(transcriptExportFileName(state, ExportFormat.SUBRIP))
                },
                jsonExporter = {
                    jsonExporter.launch(transcriptExportFileName(state, ExportFormat.JSON))
                }
            )
        }
    }
}

private enum class AppPage { MAIN, SETTINGS, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptTopBar(
    page: AppPage,
    appLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    onNavigate: (AppPage) -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (page == AppPage.MAIN) "Simple Transcript" else page.title)
            }
        },
        navigationIcon = {
            if (page != AppPage.MAIN) {
                IconButton(onClick = { onNavigate(AppPage.MAIN) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                }
            }
        },
        actions = {
            if (page == AppPage.MAIN) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    AppLanguageSelector(
                        selected = appLanguage,
                        onSelected = onAppLanguageSelected
                    )
                    IconButton(
                        onClick = { onNavigate(AppPage.SETTINGS) },
                        modifier = Modifier
                            .width(42.dp)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                    IconButton(
                        onClick = { onNavigate(AppPage.ABOUT) },
                        modifier = Modifier
                            .width(42.dp)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Über die App")
                    }
                }
            }
        }
    )
}

@Composable
private fun AppLanguageSelector(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            border = null,
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier
                .width(48.dp)
                .height(44.dp)
        ) {
            Text(selected.flag)
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "GUI-Sprache auswählen"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text("${language.flag}  ${language.displayName}") },
                    onClick = {
                        onSelected(language)
                        expanded = false
                    }
                )
            }
        }
    }
}

private val AppPage.title: String
    get() = when (this) {
        AppPage.MAIN -> "Simple Transcript"
        AppPage.SETTINGS -> "Einstellungen"
        AppPage.ABOUT -> "Über die App"
    }

@Composable
private fun MainContent(
    viewModel: MainScreenViewModel,
    state: TranscriptUiState,
    openSettings: () -> Unit,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    audioPicker: () -> Unit,
    requestModelDownload: () -> Unit,
    requestRecording: () -> Unit,
    textExporter: () -> Unit,
    srtExporter: () -> Unit,
    jsonExporter: () -> Unit
) {
        val context = LocalContext.current
        var pendingTranscriptAction by remember {
            mutableStateOf<PendingTranscriptAction?>(null)
        }
        var confirmTranscriptionCancellation by remember { mutableStateOf(false) }
        var showTranscriptShareDialog by remember { mutableStateOf(false) }
        var showMissingAiModelDialog by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        val scrollScope = rememberCoroutineScope()
        val density = LocalDensity.current
        val scrollToTopThresholdPx = remember(density) {
            with(density) { 720.dp.roundToPx() }
        }
        val showScrollToTop by remember(state.segments.size, scrollToTopThresholdPx) {
            derivedStateOf {
                shouldShowScrollToTop(
                    segmentCount = state.segments.size,
                    scrollOffsetPx = scrollState.value,
                    thresholdPx = scrollToTopThresholdPx
                )
            }
        }

        LaunchedEffect(state.isTranscribing) {
            if (!state.isTranscribing) confirmTranscriptionCancellation = false
        }

        LaunchedEffect(state.selectedAudio) {
            scrollState.scrollTo(0)
        }

        if (confirmTranscriptionCancellation) {
            CancelTranscriptionDialog(
                state = state,
                onContinue = { confirmTranscriptionCancellation = false },
                onConfirmCancellation = {
                    confirmTranscriptionCancellation = false
                    viewModel.cancelTranscription()
                }
            )
        }

        if (showTranscriptShareDialog) {
            TranscriptShareDialog(
                onDismiss = { showTranscriptShareDialog = false },
                onShare = { formats -> shareTranscript(context, state, formats) }
            )
        }

        if (showMissingAiModelDialog) {
            AlertDialog(
                onDismissRequest = { showMissingAiModelDialog = false },
                title = { Text("KI-Modell fehlt") },
                text = {
                    Text(
                        "Für die lokale KI-Nachbearbeitung muss zuerst in den Einstellungen " +
                            "ein KI-Modell heruntergeladen und ausgewählt werden."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showMissingAiModelDialog = false
                        openSettings()
                    }) { Text("Zu den Einstellungen") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showMissingAiModelDialog = false }) {
                        Text("Abbrechen")
                    }
                }
            )
        }

        pendingTranscriptAction?.let { pendingAction ->
            AlertDialog(
                onDismissRequest = { pendingTranscriptAction = null },
                title = { Text("Änderungen noch nicht übernommen") },
                text = {
                    Text(
                        "Die Textkorrekturen würden bei dieser Aktion verworfen. " +
                            "Möchtest du trotzdem fortfahren?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingTranscriptAction = null
                            when (pendingAction) {
                                PendingTranscriptAction.SELECT_AUDIO -> audioPicker()
                                PendingTranscriptAction.START_RECORDING -> requestRecording()
                                PendingTranscriptAction.TRANSCRIBE -> viewModel.transcribe()
                            }
                        }
                    ) {
                        Text("Verwerfen und fortfahren")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { pendingTranscriptAction = null }) {
                        Text("Zurück")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Text(
                "Audio- und Videodateien vollständig offline mit Whisper transkribieren.",
                style = MaterialTheme.typography.bodyMedium
            )

            ModelSelector(
                selected = state.selectedModel,
                installations = state.modelInstallations,
                enabled = state.isModelSelectionEnabled,
                onSelected = viewModel::selectModel
            )

            ModelManagerCard(
                state = state,
                onDownload = requestModelDownload
            )

            OutlinedButton(
                onClick = {
                    if (state.hasUnsavedTranscriptChanges) {
                        pendingTranscriptAction = PendingTranscriptAction.SELECT_AUDIO
                    } else {
                        audioPicker()
                    }
                },
                enabled = !state.isBusy && !state.isRecording,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text("Datei", style = MaterialTheme.typography.labelSmall)
                    Text(
                        state.selectedFileName ?: "Audio- oder Videodatei auswählen",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            AudioControls(
                state = state,
                onRecordClick = {
                    if (!state.isRecording && state.hasUnsavedTranscriptChanges) {
                        pendingTranscriptAction = PendingTranscriptAction.START_RECORDING
                    } else {
                        requestRecording()
                    }
                },
                onPlayPauseClick = viewModel::togglePlayback,
                onSeek = viewModel::seekPlayback
            )

            LanguageSelector(
                selected = state.language,
                enabled = !state.isBusy && !state.isRecording,
                onSelected = viewModel::setLanguage
            )

            Button(
                onClick = {
                    if (state.isTranscribing) {
                        if (state.selectedModel.requiresCancellationConfirmation) {
                            confirmTranscriptionCancellation = true
                        } else {
                            viewModel.cancelTranscription()
                        }
                    } else if (state.hasUnsavedTranscriptChanges) {
                        pendingTranscriptAction = PendingTranscriptAction.TRANSCRIBE
                    } else {
                        viewModel.transcribe()
                    }
                },
                enabled = if (state.isTranscribing) {
                    !state.isCancellationRequested
                } else {
                    state.modelReady && state.selectedAudio != null &&
                        !state.isBusy && !state.isRecording
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        state.isCancellationRequested -> "Wird abgebrochen …"
                        state.isTranscribing -> "Abbrechen"
                        else -> "Transkribieren"
                    }
                )
            }

            if (state.isBusy && state.downloadingModel == null) {
                state.progress?.let {
                    LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LiveStatusLine(state)
            AiSelfTestCard(
                state = state,
                onPromptChange = viewModel::updateAiTestPrompt,
                onStart = viewModel::startAiSelfTest
            )
            state.latestAiCorrectionTrace?.let { AiCorrectionTraceCard(it) }
            if (state.isTranscribing) {
                Text(
                    transcriptionRuntimeDisplay(
                        elapsedSeconds = state.elapsedSeconds,
                        estimateSeconds = state.transcriptionEstimateSeconds
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (state.segments.isNotEmpty()) {
                TranscriptResultSummary(state)
            }

            if (state.diagnostics.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Diagnose", style = MaterialTheme.typography.titleSmall)
                        state.diagnostics.forEach { entry ->
                            Text(entry, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (state.segments.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Transkript", style = MaterialTheme.typography.titleSmall)
                        TranscriptList(
                            state = state,
                            segments = if (state.isEditingTranscript) {
                                state.draftSegments
                            } else {
                                state.segments
                            },
                            onTextChanged = viewModel::updateTranscriptText,
                            onAiEditGroup = { groupStartMs ->
                                if (state.selectedAiModelInstalled) {
                                    viewModel.startAiTranscriptEditing(groupStartMs)
                                } else {
                                    showMissingAiModelDialog = true
                                }
                            },
                            onEditGroup = viewModel::startTranscriptEditing,
                            onCancelEditing = viewModel::cancelTranscriptEditing,
                            onApplyEdits = viewModel::applyTranscriptEdits
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = textExporter,
                                enabled = !state.isEditingTranscript,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "TXT",
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    softWrap = false,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Button(
                                onClick = srtExporter,
                                enabled = !state.isEditingTranscript,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "SRT",
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    softWrap = false,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Button(
                                onClick = jsonExporter,
                                enabled = !state.isEditingTranscript,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "JSON",
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    softWrap = false,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Button(
                                onClick = { showTranscriptShareDialog = true },
                                enabled = !state.isEditingTranscript,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Transkript teilen"
                                )
                            }
                        }
                    }
                }
            }
            }

            AnimatedVisibility(
                visible = showScrollToTop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 20.dp)
            ) {
                Button(
                    onClick = {
                        scrollScope.launch { scrollState.animateScrollTo(0) }
                    },
                    modifier = Modifier
                        .width(58.dp)
                        .height(44.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.62f),
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Zum Anfang der App"
                    )
                }
            }
        }
    }

internal fun shouldShowScrollToTop(
    segmentCount: Int,
    scrollOffsetPx: Int,
    thresholdPx: Int
): Boolean = segmentCount >= 20 && scrollOffsetPx >= thresholdPx

@Composable
internal fun ModelSelector(
    selected: WhisperModel,
    installations: List<ModelInstallation>,
    enabled: Boolean,
    onSelected: (WhisperModel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val selectorWidth = maxWidth
        LabeledSelectorButton(
            label = "Qualitätsstufe",
            value = selected.qualityLabel,
            enabled = enabled,
            onClick = { expanded = true },
            contentDescription = if (expanded) "Modellliste schließen" else "Modellliste öffnen"
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(selectorWidth)
        ) {
            installations.forEach { installation ->
                val model = installation.model
                DropdownMenuItem(
                    text = { Text(model.qualityLabel) },
                    onClick = {
                        onSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CancelTranscriptionDialog(
    state: TranscriptUiState,
    onContinue: () -> Unit,
    onConfirmCancellation: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "cancel-question-pulse")
    val alpha = transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cancel-question-alpha"
    ).value

    AlertDialog(
        onDismissRequest = onContinue,
        shape = RoundedCornerShape(28.dp),
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CannaBotStatusAnimation(state)
                Text(
                    text = "Möchtest du die Transkription wirklich abbrechen?",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmCancellation,
                shape = RoundedCornerShape(50)
            ) {
                Text("Abbrechen")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onContinue,
                shape = RoundedCornerShape(50)
            ) {
                Text("Weiter")
            }
        }
    )
}

internal val TranscriptUiState.isModelSelectionEnabled: Boolean
    get() = !isBusy && !isRecording

@Composable
private fun LiveStatusLine(state: TranscriptUiState) {
    val estimateStatus = transcriptionEstimateStatus(state.transcriptionEstimateSeconds)
    val alternatesReadyStatus =
        state.cannaBotMode == CannaBotMode.REVIEW &&
            state.selectedAudio != null &&
            state.segments.isEmpty() &&
            state.error == null &&
            estimateStatus != null
    val announcesChangedModelEstimate =
        state.runtimeEstimateAnnouncementId > 0L &&
            state.modelReady &&
            state.selectedAudio != null &&
            !state.isBusy &&
            !state.isRecording &&
            !state.isTranscribing &&
            state.error == null &&
            estimateStatus != null
    val isActiveOperation = state.isBusy || state.isRecording || state.isPlaying ||
        state.isWaveformLoading
    val primaryStatus = if (alternatesReadyStatus) {
        state.mediaReadyStatus ?: state.status
    } else {
        state.status
    }
    val activityStatus = state.activityDetail
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != primaryStatus }
    val alternateStatus = when {
        announcesChangedModelEstimate -> estimateStatus
        alternatesReadyStatus -> estimateStatus
        isActiveOperation -> activityStatus
        else -> null
    }
    var showAlternate by remember { mutableStateOf(false) }
    var handledEstimateAnnouncementId by remember { mutableStateOf(0L) }
    LaunchedEffect(
        primaryStatus,
        alternateStatus,
        announcesChangedModelEstimate,
        state.runtimeEstimateAnnouncementId
    ) {
        if (alternateStatus == null) {
            showAlternate = false
            return@LaunchedEffect
        }
        if (
            announcesChangedModelEstimate &&
            state.runtimeEstimateAnnouncementId != handledEstimateAnnouncementId
        ) {
            handledEstimateAnnouncementId = state.runtimeEstimateAnnouncementId
            showAlternate = true
        }
        while (true) {
            delay(3_600L)
            showAlternate = !showAlternate
        }
    }
    val isActive = isActiveOperation || alternatesReadyStatus || announcesChangedModelEstimate
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha = if (isActive) {
        transition.animateFloat(
            initialValue = 0.20f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "status-alpha"
        ).value
    } else {
        1f
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CannaBotStatusAnimation(state)
        Text(
            text = if (showAlternate) alternateStatus.orEmpty() else primaryStatus,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
    }
}

@Composable
private fun AiSelfTestCard(
    state: TranscriptUiState,
    onPromptChange: (String) -> Unit,
    onStart: () -> Unit
) {
    var responseExpanded by remember { mutableStateOf(false) }
    val modelInstalled = state.selectedAiModelInstalled
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("KI-Testbereich", style = MaterialTheme.typography.titleSmall)
            Text(
                "Hier kannst du dem ausgewählten lokalen KI-Modell eine eigene Frage oder Aufgabe geben.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = state.aiTestPrompt,
                onValueChange = onPromptChange,
                label = { Text("Frage oder Aufgabe") },
                placeholder = { Text("Eigene Eingabe für die KI …") },
                minLines = 3,
                maxLines = 8,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onStart,
                enabled = modelInstalled && !state.isBusy && state.aiTestPrompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isAiSelfTest) "Anfrage läuft …" else "Anfrage an KI senden")
            }
            if (!modelInstalled) {
                Text(
                    "Bitte zuerst das ausgewählte KI-Modell in den Einstellungen herunterladen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.aiSelfTestResponse?.let { response ->
                OutlinedButton(
                    onClick = { responseExpanded = !responseExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (responseExpanded) "KI-Antwort ausblenden" else "KI-Antwort anzeigen")
                }
                if (responseExpanded) {
                    Text(response, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AiCorrectionTraceCard(trace: de.matthiasennen.transcript.ai.AiCorrectionTrace) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Letzte KI-Korrektur · Segment ${trace.segmentNumber}", style = MaterialTheme.typography.titleSmall)
            Text(trace.decision, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (expanded) "Interaktion ausblenden" else "Interaktion anzeigen")
            }
            if (expanded) {
                Text("Whisper-Original", style = MaterialTheme.typography.labelLarge)
                Text(trace.originalText, style = MaterialTheme.typography.bodyMedium)
                Text("KI-Rohantwort", style = MaterialTheme.typography.labelLarge)
                Text(trace.rawResponse, style = MaterialTheme.typography.bodyMedium)
                Text("Von der App verwendetes Ergebnis", style = MaterialTheme.typography.labelLarge)
                Text(trace.resultText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ModelManagerCard(
    state: TranscriptUiState,
    onDownload: () -> Unit
) {
    val model = state.selectedModel
    val installation = state.modelInstallations.firstOrNull { it.model == model }
    val installed = installation?.isInstalled == true
    val isDownloading = state.downloadingModel == model

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(model.qualityLabel, style = MaterialTheme.typography.titleMedium)
            Text(model.modelLabel, style = MaterialTheme.typography.bodyMedium)
            Text(model.description, style = MaterialTheme.typography.bodySmall)

            when {
                isDownloading -> {
                    val downloaded = formatDownloadSize(state.downloadedBytes)
                    val total = state.downloadTotalBytes.takeIf { it > 0L }
                        ?.let(::formatDownloadSize)
                        ?: model.downloadSizeLabel
                    Text("Download: $downloaded von $total")
                    state.progress?.let {
                        LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth())
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                installed -> {
                    Text(
                        "Aktiv · Installiert (${formatDownloadSize(installation?.installedBytes ?: 0L)})",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Text("Noch nicht installiert · Download ${model.downloadSizeLabel}")
                    Button(
                        onClick = onDownload,
                        enabled = !state.isBusy && !state.isRecording,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Modell herunterladen")
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptResultSummary(state: TranscriptUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Ergebnis", style = MaterialTheme.typography.titleSmall)
            state.completedModel?.let { Text("Modell: ${it.modelLabel}") }
            state.detectedLanguage?.let {
                Text("Erkannte Sprache: ${whisperLanguageDisplayName(it)}")
            }
            state.transcriptionDurationSeconds?.let { Text("Transkriptionszeit: ${formatClock(it)}") }
            Text("Textabschnitte: ${state.segments.size}")
        }
    }
}

@Composable
private fun LanguageSelector(
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = languages.firstOrNull { it.first == selected }?.second ?: selected

    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val selectorWidth = maxWidth
        LabeledSelectorButton(
            label = "Sprache",
            value = label,
            enabled = enabled,
            onClick = { expanded = true },
            contentDescription = if (expanded) "Sprachliste schließen" else "Sprachliste öffnen"
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(selectorWidth)
        ) {
            languages.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LabeledSelectorButton(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun rememberExporter(
    context: Context,
    state: TranscriptUiState,
    format: ExportFormat
) = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri: Uri? ->
    uri?.let {
        context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer ->
            writer.write(
                exportTranscript(
                    segments = state.segments,
                    format = format,
                    metadata = state.exportMetadata()
                )
            )
        }
    }
}

internal fun formatDownloadSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}
