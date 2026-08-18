package de.matthiasennen.transcript.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.exportTranscript
import de.matthiasennen.transcript.ai.AiModel
import kotlinx.coroutines.launch

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
    var pendingVadModelDownload by remember { mutableStateOf(false) }
    var pendingRecording by remember { mutableStateOf(false) }
    var pendingRecordingAfterFolderSelection by remember { mutableStateOf(false) }
    var pendingTranscription by remember { mutableStateOf(false) }
    var showRecordingFolderPrompt by remember { mutableStateOf(false) }
    val recordingFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val stored = viewModel.saveRecordingFolder(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (!stored) pendingRecordingAfterFolderSelection = false
        } else if (pendingRecordingAfterFolderSelection) {
            pendingRecordingAfterFolderSelection = false
            viewModel.reportRecordingFolderSelectionCancelled()
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingModelDownload?.let(viewModel::downloadModel)
        pendingAiModelDownload?.let(viewModel::downloadAiModel)
        if (pendingVadModelDownload) viewModel.downloadVadModel()
        if (pendingRecording) {
            if (granted) viewModel.startRecording()
            else viewModel.reportRecordingNotificationPermissionDenied()
        }
        if (pendingTranscription) viewModel.transcribe()
        pendingModelDownload = null
        pendingAiModelDownload = null
        pendingVadModelDownload = false
        pendingRecording = false
        pendingTranscription = false
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.reportMicrophonePermissionDenied()
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecording = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startRecording()
        }
    }
    LaunchedEffect(state.recordingFolderName, pendingRecordingAfterFolderSelection) {
        if (!pendingRecordingAfterFolderSelection || state.recordingFolderName == null) return@LaunchedEffect
        pendingRecordingAfterFolderSelection = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecording = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startRecording()
        }
    }
    val textExporter = rememberExporter(context, state, ExportFormat.TEXT)
    val srtExporter = rememberExporter(context, state, ExportFormat.SUBRIP)
    val jsonExporter = rememberExporter(context, state, ExportFormat.JSON)

    LaunchedEffect(state.pendingSharedMediaImport, state.isSharedMediaImporting) {
        if (state.pendingSharedMediaImport != null || state.isSharedMediaImporting) {
            page = AppPage.MAIN
        }
    }

    state.pendingSharedMediaImport?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::cancelSharedMediaImport,
            title = { Text("Aktuellen Vorgang ersetzen?") },
            text = {
                Text(
                    "Die geteilte Datei „${request.fileName}“ ersetzt die aktuell ausgewählte " +
                        "Datei und das vorhandene Transkript. Möchtest du fortfahren?"
                )
            },
            confirmButton = {
                Button(onClick = viewModel::confirmSharedMediaImport) {
                    Text("Ersetzen und importieren")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = viewModel::cancelSharedMediaImport) {
                    Text("Abbrechen")
                }
            }
        )
    }

    if (showRecordingFolderPrompt) {
        RecordingFolderRequiredDialog(
            state = state,
            onDismiss = { showRecordingFolderPrompt = false },
            onChooseFolder = {
                showRecordingFolderPrompt = false
                pendingRecordingAfterFolderSelection = true
                recordingFolderPicker.launch(null)
            }
        )
    }

    state.downloadStorageIssue?.let { issue ->
        DownloadStorageRequiredDialog(
            state = state,
            issue = issue,
            onConfirm = viewModel::dismissDownloadStorageIssue
        )
    }

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
                onEnter = viewModel::announceSettingsPerformance,
                onOpenAiDiagnostics = { page = AppPage.AI_DIAGNOSTICS },
                onOpenAiPerformance = { page = AppPage.AI_PERFORMANCE },
                onOpenWhisperSettings = { page = AppPage.WHISPER_SETTINGS },
                onOpenVadSettings = { page = AppPage.VAD_SETTINGS },
                onSelectModel = viewModel::selectModel,
                onDeleteModel = viewModel::deleteModel,
                onDeleteAllModels = viewModel::deleteAllModels,
                onDownloadVadModel = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingVadModelDownload = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else viewModel.downloadVadModel()
                },
                onDeleteVadModel = viewModel::deleteVadModel,
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
                onDeleteAllAiModels = viewModel::deleteAllAiModels,
                onChooseRecordingFolder = {
                    pendingRecordingAfterFolderSelection = false
                    recordingFolderPicker.launch(null)
                },
                onRefreshDeviceStorage = viewModel::refreshDeviceStorage,
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.ABOUT -> AboutScreen(
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.AI_DIAGNOSTICS -> AiDiagnosticsScreen(
                state = state,
                onEnter = viewModel::prepareAiDiagnostics,
                onPromptChange = viewModel::updateAiTestPrompt,
                onStart = viewModel::startAiSelfTest,
                onResetConversation = viewModel::resetAiTestConversation,
                onRefreshThermalStatus = viewModel::refreshAiDiagnosticsThermalStatus,
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.AI_PERFORMANCE -> AiPerformanceScreen(
                state = state,
                onSelectProfileModel = viewModel::selectPerformanceProfileModel,
                onConfigurationChanged = viewModel::updateAiPerformanceConfiguration,
                onRefreshHardware = viewModel::refreshAiHardware,
                onStartBenchmark = viewModel::startAiPerformanceBenchmark,
                onCancelBenchmark = viewModel::cancelAiPerformanceBenchmark,
                onResetConfiguration = viewModel::resetAiPerformanceConfiguration,
                onCopyConfiguration = viewModel::copyAiPerformanceConfiguration,
                onExportConfiguration = viewModel::exportAiPerformanceConfiguration,
                onJsonChanged = viewModel::updateAiPerformanceJson,
                onImportConfiguration = viewModel::importAiPerformanceConfiguration,
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.WHISPER_SETTINGS -> WhisperSettingsScreen(
                state = state,
                onLanguageChanged = {
                    viewModel.setLanguage(it, WhisperSettingsPage.WHISPER)
                },
                onSettingsChanged = {
                    viewModel.updateWhisperSettings(it, WhisperSettingsPage.WHISPER)
                },
                onResetGroup = {
                    viewModel.resetWhisperSettings(it, WhisperSettingsPage.WHISPER)
                },
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.VAD_SETTINGS -> VadSettingsScreen(
                state = state,
                onSettingsChanged = {
                    viewModel.updateWhisperSettings(it, WhisperSettingsPage.VAD)
                },
                onReset = {
                    viewModel.resetWhisperSettings(
                        WhisperSettingsGroup.VAD,
                        WhisperSettingsPage.VAD
                    )
                },
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
                    if (state.isRecordingStopping) {
                        Unit
                    } else if (state.isRecording) {
                        viewModel.stopRecording()
                    } else if (viewModel.recordingFolder() == null) {
                        showRecordingFolderPrompt = true
                    } else if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingRecording = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startRecording()
                        }
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                requestTranscription = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingTranscription = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.transcribe()
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

@Composable
private fun MainContent(
    viewModel: MainScreenViewModel,
    state: TranscriptUiState,
    openSettings: () -> Unit,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    audioPicker: () -> Unit,
    requestModelDownload: () -> Unit,
    requestRecording: () -> Unit,
    requestTranscription: () -> Unit,
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
        var transcriptHeadingBottomPx by remember { mutableStateOf<Float?>(null) }
        var exportActionsTopPx by remember { mutableStateOf<Float?>(null) }
        var exportActionsBottomPx by remember { mutableStateOf<Float?>(null) }
        var viewportTopPx by remember { mutableStateOf<Float?>(null) }
        var viewportBottomPx by remember { mutableStateOf<Float?>(null) }
        val hasCompletedTranscript = state.completedModel != null && state.segments.isNotEmpty()
        val activeSegment = if (hasCompletedTranscript) {
            activeTranscriptSegment(state.segments, state.playbackPositionMs)
        } else {
            null
        }
        val showFloatingTranscriptControls = shouldShowFloatingTranscriptControls(
            hasCompletedTranscript = hasCompletedTranscript,
            transcriptHeadingBottomPx = transcriptHeadingBottomPx,
            exportActionsTopPx = exportActionsTopPx,
            exportActionsBottomPx = exportActionsBottomPx,
            viewportTopPx = viewportTopPx,
            viewportBottomPx = viewportBottomPx
        )

        LaunchedEffect(state.isTranscribing) {
            if (!state.isTranscribing) confirmTranscriptionCancellation = false
        }

        LaunchedEffect(state.selectedAudio) {
            scrollState.scrollTo(0)
            transcriptHeadingBottomPx = null
            exportActionsTopPx = null
            exportActionsBottomPx = null
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
                                PendingTranscriptAction.TRANSCRIBE -> requestTranscription()
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
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    viewportTopPx = bounds.top
                    viewportBottomPx = bounds.bottom
                }
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
                onPreviousSegmentClick = viewModel::skipToPreviousTranscriptSegment,
                onNextSegmentClick = viewModel::skipToNextTranscriptSegment,
                onSegmentRepeatClick = viewModel::toggleSegmentRepeat,
                onSeek = viewModel::seekPlayback
            )

            LanguageSelector(
                selected = state.language,
                enabled = !state.isBusy && !state.isRecording,
                onSelected = { viewModel.setLanguage(it) }
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
                        requestTranscription()
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
            state.latestAiCorrectionTrace?.let { AiCorrectionTraceCard(it) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (state.segments.isNotEmpty()) {
                TranscriptResultSummary(state)
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
                        Text(
                            "Transkript",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                transcriptHeadingBottomPx =
                                    coordinates.positionInRoot().y + coordinates.size.height
                            }
                        )
                        TranscriptList(
                            state = state,
                            segments = if (state.isEditingTranscript) {
                                state.draftSegments
                            } else {
                                state.segments
                            },
                            rawWhisperSegments = state.rawWhisperSegments,
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
                            onApplyEdits = viewModel::applyTranscriptEdits,
                            activeSegmentIndex = activeSegment?.index,
                            activeSegmentProgress = activeSegment?.progress ?: 0f
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    val top = coordinates.positionInRoot().y
                                    exportActionsTopPx = top
                                    exportActionsBottomPx = top + coordinates.size.height
                                }
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
                visible = showFloatingTranscriptControls,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            ) {
                FloatingTranscriptControls(
                    isPlaying = state.isPlaying,
                    isSegmentRepeatEnabled = state.isSegmentRepeatEnabled,
                    playbackEnabled = hasCompletedTranscript &&
                        !state.isRecording && !state.isBusy,
                    onPreviousSegmentClick = viewModel::skipToPreviousTranscriptSegment,
                    onPlayPauseClick = viewModel::togglePlayback,
                    onNextSegmentClick = viewModel::skipToNextTranscriptSegment,
                    onSegmentRepeatClick = viewModel::toggleSegmentRepeat,
                    onScrollToTopClick = {
                        scrollScope.launch { scrollState.animateScrollTo(0) }
                    }
                )
            }
        }
    }
