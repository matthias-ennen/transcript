from pathlib import Path
import re

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one occurrence, found {count}: {old[:80]!r}')
    write(path, text.replace(old, new, 1))


def regex_once(path, pattern, replacement):
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f'{path}: regex expected one occurrence, found {count}: {pattern[:100]!r}')
    write(path, updated)


# ---------------------------------------------------------------------------
# Provenance and view model shared helpers
# ---------------------------------------------------------------------------
write('app/src/main/java/com/whispercppdemo/ui/main/TranscriptProvenance.kt', '''package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

enum class TranscriptSegmentOrigin { ORIGINAL, MANUAL, AI }

enum class TranscriptViewMode { ORIGINAL, EDITED }

internal fun defaultTranscriptOrigins(
    segments: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>
): Map<Long, TranscriptSegmentOrigin> = buildMap {
    segments.forEachIndexed { index, segment ->
        put(
            stableTranscriptSegmentId(index, segment),
            if (
                rawWhisperSegments.isEmpty() ||
                segment.text == originalTranscriptText(segment, rawWhisperSegments)
            ) {
                TranscriptSegmentOrigin.ORIGINAL
            } else {
                TranscriptSegmentOrigin.MANUAL
            }
        )
    }
}

internal fun acceptedTranscriptOrigin(
    index: Int,
    segment: WhisperSegment,
    rawWhisperSegments: List<WhisperSegment>,
    origins: Map<Long, TranscriptSegmentOrigin>
): TranscriptSegmentOrigin {
    if (rawWhisperSegments.isEmpty()) return TranscriptSegmentOrigin.ORIGINAL
    val originalText = originalTranscriptText(segment, rawWhisperSegments)
    if (segment.text == originalText) return TranscriptSegmentOrigin.ORIGINAL
    return origins[stableTranscriptSegmentId(index, segment)]
        ?.takeUnless { it == TranscriptSegmentOrigin.ORIGINAL }
        ?: TranscriptSegmentOrigin.MANUAL
}

internal fun TranscriptUiState.acceptedTranscriptOrigin(index: Int): TranscriptSegmentOrigin =
    segments.getOrNull(index)?.let { segment ->
        acceptedTranscriptOrigin(index, segment, rawWhisperSegments, segmentOrigins)
    } ?: TranscriptSegmentOrigin.ORIGINAL

internal fun updateTranscriptOrigins(
    previousSegments: List<WhisperSegment>,
    updatedSegments: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>,
    existingOrigins: Map<Long, TranscriptSegmentOrigin>,
    changedOrigin: TranscriptSegmentOrigin
): Map<Long, TranscriptSegmentOrigin> = buildMap {
    updatedSegments.forEachIndexed { index, segment ->
        val id = stableTranscriptSegmentId(index, segment)
        val originalText = originalTranscriptText(segment, rawWhisperSegments)
        val origin = when {
            rawWhisperSegments.isEmpty() || segment.text == originalText ->
                TranscriptSegmentOrigin.ORIGINAL
            previousSegments.getOrNull(index)?.text != segment.text -> changedOrigin
            else -> existingOrigins[id]
                ?.takeUnless { it == TranscriptSegmentOrigin.ORIGINAL }
                ?: TranscriptSegmentOrigin.MANUAL
        }
        put(id, origin)
    }
}

internal fun reconcileTranscriptOrigins(
    segments: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>,
    existingOrigins: Map<Long, TranscriptSegmentOrigin>
): Map<Long, TranscriptSegmentOrigin> = buildMap {
    segments.forEachIndexed { index, segment ->
        val id = stableTranscriptSegmentId(index, segment)
        put(
            id,
            acceptedTranscriptOrigin(index, segment, rawWhisperSegments, existingOrigins)
        )
    }
}

internal fun TranscriptUiState.originalTranscriptSegments(): List<WhisperSegment> {
    if (rawWhisperSegments.isEmpty()) return segments
    return segments.map { segment ->
        segment.copy(text = originalTranscriptText(segment, rawWhisperSegments))
    }
}

internal fun TranscriptUiState.transcriptSegmentsForSelectedView(): List<WhisperSegment> = when {
    isEditingTranscript && draftSegments.size == segments.size -> draftSegments
    transcriptView == TranscriptViewMode.ORIGINAL && rawWhisperSegments.isNotEmpty() ->
        originalTranscriptSegments()
    else -> segments
}

internal fun TranscriptUiState.exportSegmentsForSelectedView(): List<WhisperSegment> =
    if (transcriptView == TranscriptViewMode.ORIGINAL && rawWhisperSegments.isNotEmpty()) {
        originalTranscriptSegments()
    } else {
        segments
    }

internal val TranscriptViewMode.displayLabel: String
    get() = when (this) {
        TranscriptViewMode.ORIGINAL -> "Whisper-Original"
        TranscriptViewMode.EDITED -> "Nachbearbeitet"
    }
''')

# ---------------------------------------------------------------------------
# UI state
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/whispercppdemo/ui/main/TranscriptUiState.kt',
    '''    val rawWhisperSegments: List<WhisperSegment> = emptyList(),
    val segments: List<WhisperSegment> = emptyList(),
    val transcriptSectionMinutes: Int? = null,
    val isEditingTranscript: Boolean = false,''',
    '''    val rawWhisperSegments: List<WhisperSegment> = emptyList(),
    val segments: List<WhisperSegment> = emptyList(),
    val transcriptSectionMinutes: Int? = null,
    val segmentOrigins: Map<Long, TranscriptSegmentOrigin> = emptyMap(),
    val transcriptView: TranscriptViewMode = TranscriptViewMode.EDITED,
    val editingTranscriptOrigin: TranscriptSegmentOrigin = TranscriptSegmentOrigin.MANUAL,
    val aiBaselineSegments: List<WhisperSegment> = emptyList(),
    val aiBaselineOrigins: Map<Long, TranscriptSegmentOrigin> = emptyMap(),
    val isEditingTranscript: Boolean = false,'''
)

# ---------------------------------------------------------------------------
# Persist status provenance and selected global view (v4, backwards compatible)
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/transcription/TranscriptResultStore.kt'
replace_once(
    path,
    '''import de.matthiasennen.transcript.ui.main.DEFAULT_TRANSCRIPT_GROUP_MINUTES
import de.matthiasennen.transcript.ui.main.TranscriptGroupingRuntime''',
    '''import de.matthiasennen.transcript.ui.main.DEFAULT_TRANSCRIPT_GROUP_MINUTES
import de.matthiasennen.transcript.ui.main.TranscriptGroupingRuntime
import de.matthiasennen.transcript.ui.main.TranscriptSegmentOrigin
import de.matthiasennen.transcript.ui.main.TranscriptViewMode'''
)
replace_once(path, 'private const val RESULT_VERSION = 3', 'private const val RESULT_VERSION = 4')
replace_once(
    path,
    '''    val displayedSegments: List<WhisperSegment>,
    val vadSummary: VadProcessingSummary? = null,
    val sectionMinutes: Int = DEFAULT_TRANSCRIPT_GROUP_MINUTES
)''',
    '''    val displayedSegments: List<WhisperSegment>,
    val vadSummary: VadProcessingSummary? = null,
    val sectionMinutes: Int = DEFAULT_TRANSCRIPT_GROUP_MINUTES,
    val segmentOrigins: Map<Long, TranscriptSegmentOrigin> = emptyMap(),
    val transcriptView: TranscriptViewMode = TranscriptViewMode.EDITED
)'''
)
replace_once(
    path,
    '''            val sectionMinutes = if (version >= 3) {
                input.readInt().coerceIn(1, 5)
            } else {
                DEFAULT_TRANSCRIPT_GROUP_MINUTES
            }
            StoredTranscriptResult(''',
    '''            val sectionMinutes = if (version >= 3) {
                input.readInt().coerceIn(1, 5)
            } else {
                DEFAULT_TRANSCRIPT_GROUP_MINUTES
            }
            val segmentOrigins = if (version >= 4) input.readSegmentOrigins() else emptyMap()
            val transcriptView = if (version >= 4) {
                TranscriptViewMode.valueOf(input.readResultString())
            } else {
                TranscriptViewMode.EDITED
            }
            StoredTranscriptResult('''
)
replace_once(
    path,
    '''                displayedSegments = displayedSegments,
                vadSummary = vadSummary,
                sectionMinutes = sectionMinutes
            )''',
    '''                displayedSegments = displayedSegments,
                vadSummary = vadSummary,
                sectionMinutes = sectionMinutes,
                segmentOrigins = segmentOrigins,
                transcriptView = transcriptView
            )'''
)
replace_once(
    path,
    '''            output.writeVadSummary(result.vadSummary)
            output.writeInt(result.sectionMinutes.coerceIn(1, 5))''',
    '''            output.writeVadSummary(result.vadSummary)
            output.writeInt(result.sectionMinutes.coerceIn(1, 5))
            output.writeSegmentOrigins(result.segmentOrigins)
            output.writeResultString(result.transcriptView.name)'''
)
replace_once(
    path,
    '''private fun DataOutputStream.writeSegments(segments: List<WhisperSegment>) {''',
    '''private fun DataOutputStream.writeSegmentOrigins(origins: Map<Long, TranscriptSegmentOrigin>) {
    require(origins.size <= MAX_RESULT_SEGMENTS) { "Zu viele Herkunftseinträge." }
    writeInt(origins.size)
    origins.toSortedMap().forEach { (segmentId, origin) ->
        writeLong(segmentId)
        writeResultString(origin.name)
    }
}

private fun DataInputStream.readSegmentOrigins(): Map<Long, TranscriptSegmentOrigin> {
    val count = readInt()
    check(count in 0..MAX_RESULT_SEGMENTS) { "Ungültige Herkunftsanzahl." }
    val result = LinkedHashMap<Long, TranscriptSegmentOrigin>(count)
    repeat(count) {
        result[readLong()] = TranscriptSegmentOrigin.valueOf(readResultString())
    }
    return result
}

private fun DataOutputStream.writeSegments(segments: List<WhisperSegment>) {'''
)

# ---------------------------------------------------------------------------
# Transcript session
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/ui/main/TranscriptSession.kt'
replace_once(
    path,
    '''        if (state.isBusy || state.segments.isEmpty()) return null''',
    '''        if (
            state.isBusy || state.segments.isEmpty() ||
            state.transcriptView != TranscriptViewMode.EDITED
        ) return null'''
)
replace_once(
    path,
    '''            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = state.segments''',
    '''            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = state.segments,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL'''
)
replace_once(
    path,
    '''            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList()
        )''',
    '''            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL
        )'''
)
replace_once(
    path,
    '''                displayedSegments = displayedSegments,
                vadSummary = state.vadProcessingSummary,
                sectionMinutes = sectionMinutes
            )''',
    '''                displayedSegments = displayedSegments,
                vadSummary = state.vadProcessingSummary,
                sectionMinutes = sectionMinutes,
                segmentOrigins = state.segmentOrigins,
                transcriptView = state.transcriptView
            )'''
)
replace_once(
    path,
    '''    fun restoreWithoutSource(state: TranscriptUiState, stored: StoredTranscriptResult): TranscriptUiState {
        val sectionMinutes = TranscriptGroupingRuntime.use(stored.sectionMinutes)
        return state.copy(''',
    '''    fun restoreWithoutSource(state: TranscriptUiState, stored: StoredTranscriptResult): TranscriptUiState {
        val sectionMinutes = TranscriptGroupingRuntime.use(stored.sectionMinutes)
        val origins = stored.segmentOrigins.takeIf { it.isNotEmpty() }
            ?: defaultTranscriptOrigins(stored.displayedSegments, stored.rawWhisperSegments)
        return state.copy('''
)
replace_once(
    path,
    '''            segments = stored.displayedSegments,
            transcriptSectionMinutes = sectionMinutes,
            detectedLanguage = stored.detectedLanguage,''',
    '''            segments = stored.displayedSegments,
            transcriptSectionMinutes = sectionMinutes,
            segmentOrigins = origins,
            transcriptView = stored.transcriptView,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
            aiBaselineSegments = emptyList(),
            aiBaselineOrigins = emptyMap(),
            detectedLanguage = stored.detectedLanguage,'''
)

# ---------------------------------------------------------------------------
# Live grouping is captured before completion; completed Whisper starts ORIGINAL.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/ui/main/TranscriptionStatePresentation.kt'
replace_once(
    path,
    '''    diagnostics = state.diagnostics,
    rawWhisperSegments = emptyList(),
    segments = state.committedSegments,
    detectedLanguage = state.detectedLanguage,''',
    '''    diagnostics = state.diagnostics,
    rawWhisperSegments = emptyList(),
    segments = state.committedSegments,
    transcriptSectionMinutes = whisperSettings.sectionMinutes.coerceIn(1, 5),
    segmentOrigins = emptyMap(),
    transcriptView = TranscriptViewMode.EDITED,
    detectedLanguage = state.detectedLanguage,'''
)
replace_once(
    path,
    '''    val capturedSectionMinutes = whisperSettings.sectionMinutes.coerceIn(1, 5)''',
    '''    val capturedSectionMinutes = transcriptSectionMinutes
        ?.coerceIn(1, 5)
        ?: whisperSettings.sectionMinutes.coerceIn(1, 5)'''
)
replace_once(
    path,
    '''            rawWhisperSegments = completed.segments,
            segments = timelineSegments,
            transcriptSectionMinutes = capturedSectionMinutes,
            isEditingTranscript = false,''',
    '''            rawWhisperSegments = completed.segments,
            segments = timelineSegments,
            transcriptSectionMinutes = capturedSectionMinutes,
            segmentOrigins = defaultTranscriptOrigins(timelineSegments, completed.segments),
            transcriptView = TranscriptViewMode.EDITED,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
            aiBaselineSegments = emptyList(),
            aiBaselineOrigins = emptyMap(),
            isEditingTranscript = false,'''
)
replace_once(
    path,
    '''    transcriptSectionMinutes = null,
    isEditingTranscript = false,''',
    '''    transcriptSectionMinutes = null,
    segmentOrigins = emptyMap(),
    transcriptView = TranscriptViewMode.EDITED,
    editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
    aiBaselineSegments = emptyList(),
    aiBaselineOrigins = emptyMap(),
    isEditingTranscript = false,'''
)
# Same reset occurs in failed state.
replace_once(
    path,
    '''    transcriptSectionMinutes = null,
    isEditingTranscript = false,''',
    '''    transcriptSectionMinutes = null,
    segmentOrigins = emptyMap(),
    transcriptView = TranscriptViewMode.EDITED,
    editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
    aiBaselineSegments = emptyList(),
    aiBaselineOrigins = emptyMap(),
    isEditingTranscript = false,'''
)

# ---------------------------------------------------------------------------
# One canonical pulsing KannaBot question bubble for confirmations.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/ui/main/MainScreenResultComponents.kt'
replace_once(
    path,
    '''@Composable
internal fun CancelTranscriptionDialog(''',
    '''@Composable
internal fun CannaBotQuestionDialog(
    state: TranscriptUiState,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "cannabot-question-pulse")
    val alpha = transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cannabot-question-alpha"
    ).value
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CannaBotStatusAnimation(state)
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(50)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(50)) {
                Text(dismissLabel)
            }
        }
    )
}

@Composable
internal fun CancelTranscriptionDialog('''
)

# ---------------------------------------------------------------------------
# Transcript list: global view selector + passive status symbols.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/ui/main/TranscriptGroups.kt'
replace_once(path, 'import androidx.compose.foundation.layout.padding\n', 'import androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.size\n')
replace_once(path, 'import androidx.compose.material3.AlertDialog\n', '')
replace_once(path, 'import androidx.compose.ui.text.style.TextAlign\n', 'import androidx.compose.ui.res.painterResource\nimport androidx.compose.ui.text.style.TextAlign\n')
replace_once(path, 'import com.whispercpp.whisper.WhisperSegment\n', 'import com.whispercpp.whisper.WhisperSegment\nimport de.matthiasennen.transcript.R\n')
replace_once(
    path,
    '''    onCancelEditing: () -> Unit,
    onApplyEdits: () -> Unit,
    activeSegmentIndex: Int?,''',
    '''    onCancelEditing: () -> Unit,
    onApplyEdits: () -> Unit,
    onViewChanged: (TranscriptViewMode) -> Unit,
    activeSegmentIndex: Int?,'''
)
replace_once(path, 'TranscriptEditQuestionDialog(', 'CannaBotQuestionDialog(')
replace_once(path, 'TranscriptEditQuestionDialog(', 'CannaBotQuestionDialog(')
replace_once(
    path,
    '''    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(''',
    '''    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TranscriptViewSelector(
            view = state.transcriptView,
            enabled = !state.isEditingTranscript && !state.isAiPostProcessing &&
                state.rawWhisperSegments.isNotEmpty(),
            onViewChanged = onViewChanged
        )
        Text(
            text = "Export und Teilen verwenden: ${state.transcriptView.displayLabel}",
            style = MaterialTheme.typography.bodySmall
        )
        Row('''
)
replace_once(
    path,
    '''                        TranscriptSegmentCard(
                            number = segmentNumbers.getOrNull(index),
                            segment = indexedSegment.segment,
                            isEditing = isEditingGroup || isEditingSingle,''',
    '''                        TranscriptSegmentCard(
                            number = segmentNumbers.getOrNull(index),
                            segment = indexedSegment.segment,
                            origin = if (
                                state.transcriptView == TranscriptViewMode.EDITED &&
                                !state.isAiPostProcessing
                            ) state.acceptedTranscriptOrigin(index) else null,
                            isEditing = isEditingGroup || isEditingSingle,'''
)
replace_once(
    path,
    '''                                else -> TranscriptSegmentEditControls.Start(
                                    enabled = !state.isBusy && !state.isAiPostProcessing,''',
    '''                                else -> TranscriptSegmentEditControls.Start(
                                    enabled = state.transcriptView == TranscriptViewMode.EDITED &&
                                        !state.isBusy && !state.isAiPostProcessing,'''
)
replace_once(
    path,
    '''private sealed interface TranscriptSegmentEditControls {''',
    '''@Composable
private fun TranscriptViewSelector(
    view: TranscriptViewMode,
    enabled: Boolean,
    onViewChanged: (TranscriptViewMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val originalModifier = Modifier.weight(1f)
        if (view == TranscriptViewMode.ORIGINAL) {
            Button(
                onClick = { onViewChanged(TranscriptViewMode.ORIGINAL) },
                enabled = enabled,
                modifier = originalModifier,
                shape = RoundedCornerShape(50)
            ) { Text("Whisper-Original", maxLines = 1, softWrap = false) }
        } else {
            OutlinedButton(
                onClick = { onViewChanged(TranscriptViewMode.ORIGINAL) },
                enabled = enabled,
                modifier = originalModifier,
                shape = RoundedCornerShape(50)
            ) { Text("Whisper-Original", maxLines = 1, softWrap = false) }
        }

        val editedModifier = Modifier.weight(1f)
        if (view == TranscriptViewMode.EDITED) {
            Button(
                onClick = { onViewChanged(TranscriptViewMode.EDITED) },
                enabled = enabled,
                modifier = editedModifier,
                shape = RoundedCornerShape(50)
            ) { Text("Nachbearbeitet", maxLines = 1, softWrap = false) }
        } else {
            OutlinedButton(
                onClick = { onViewChanged(TranscriptViewMode.EDITED) },
                enabled = enabled,
                modifier = editedModifier,
                shape = RoundedCornerShape(50)
            ) { Text("Nachbearbeitet", maxLines = 1, softWrap = false) }
        }
    }
}

private sealed interface TranscriptSegmentEditControls {'''
)
replace_once(
    path,
    '''private fun TranscriptSegmentCard(
    number: Int?,
    segment: WhisperSegment,
    isEditing: Boolean,''',
    '''private fun TranscriptSegmentCard(
    number: Int?,
    segment: WhisperSegment,
    origin: TranscriptSegmentOrigin?,
    isEditing: Boolean,'''
)
replace_once(
    path,
    '''        if (number != null) {
            Box(''',
    '''        if (number != null && origin != null) {
            val (iconRes, description) = when (origin) {
                TranscriptSegmentOrigin.ORIGINAL ->
                    R.drawable.ic_transcript_status_original to "Unverändertes Whisper-Original"
                TranscriptSegmentOrigin.MANUAL ->
                    R.drawable.ic_transcript_status_manual to "Manuell bearbeitet"
                TranscriptSegmentOrigin.AI ->
                    R.drawable.ic_transcript_status_ai to "Mit KI bearbeitet"
            }
            Icon(
                painter = painterResource(iconRes),
                contentDescription = description,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-48).dp, y = (-8).dp)
                    .size(32.dp),
                tint = Color.Unspecified
            )
        }

        if (number != null) {
            Box('''
)
replace_once(
    path,
    '''                enabled = !state.isBusy && !state.isEditingTranscript && state.completedModel != null && state.selectedAiModelInstalled,''',
    '''                enabled = state.transcriptView == TranscriptViewMode.EDITED &&
                    !state.isBusy && !state.isEditingTranscript &&
                    state.completedModel != null && state.selectedAiModelInstalled,'''
)
replace_once(
    path,
    '''                enabled = !state.isBusy && !state.isAiPostProcessing,''',
    '''                enabled = state.transcriptView == TranscriptViewMode.EDITED &&
                    !state.isBusy && !state.isAiPostProcessing,'''
)
regex_once(
    path,
    r'''\n@Composable\nprivate fun TranscriptEditQuestionDialog\(.*?\n\}\n\ninternal val TRANSCRIPT_NUMBER_CAPSULE_WIDTH''',
    '''\ninternal val TRANSCRIPT_NUMBER_CAPSULE_WIDTH'''
)

# ---------------------------------------------------------------------------
# Main screen: selected view feeds list/export, normal destructive dialogs -> KannaBot.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/ui/main/MainScreen.kt'
regex_once(
    path,
    r'''    state\.pendingSharedMediaImport\?\.let \{ request ->\n        AlertDialog\(.*?\n    \}\n\n    if \(showRecordingFolderPrompt\)''',
    '''    state.pendingSharedMediaImport?.let { request ->
        CannaBotQuestionDialog(
            state = state,
            message = "Die geteilte Datei „${request.fileName}“ ersetzt die aktuelle Datei und das vorhandene Transkript. Möchtest du fortfahren?",
            confirmLabel = "Ersetzen",
            dismissLabel = "Abbrechen",
            onConfirm = viewModel::confirmSharedMediaImport,
            onDismiss = viewModel::cancelSharedMediaImport
        )
    }

    if (showRecordingFolderPrompt)'''
)
regex_once(
    path,
    r'''        pendingTranscriptAction\?\.let \{ pendingAction ->\n            AlertDialog\(.*?\n        \}\n\n        Box\(''',
    '''        pendingTranscriptAction?.let { pendingAction ->
            val question = when (pendingAction) {
                PendingTranscriptAction.SELECT_AUDIO ->
                    "Du hast Änderungen am Transkript noch nicht übernommen. Möchtest du sie verwerfen und eine andere Datei auswählen?"
                PendingTranscriptAction.START_RECORDING ->
                    "Du hast Änderungen am Transkript noch nicht übernommen. Möchtest du sie verwerfen und eine neue Aufnahme starten?"
                PendingTranscriptAction.TRANSCRIBE ->
                    "Du hast Änderungen am Transkript noch nicht übernommen. Möchtest du sie verwerfen und die Transkription neu starten?"
            }
            CannaBotQuestionDialog(
                state = state,
                message = question,
                confirmLabel = "Verwerfen",
                dismissLabel = "Zurück",
                onConfirm = {
                    pendingTranscriptAction = null
                    viewModel.cancelTranscriptEditing()
                    when (pendingAction) {
                        PendingTranscriptAction.SELECT_AUDIO -> audioPicker()
                        PendingTranscriptAction.START_RECORDING -> requestRecording()
                        PendingTranscriptAction.TRANSCRIBE -> requestTranscription()
                    }
                },
                onDismiss = { pendingTranscriptAction = null }
            )
        }

        Box('''
)
replace_once(
    path,
    '''                            segments = if (state.isEditingTranscript) {
                                state.draftSegments
                            } else {
                                state.segments
                            },''',
    '''                            segments = state.transcriptSegmentsForSelectedView(),'''
)
replace_once(
    path,
    '''                            onCancelEditing = viewModel::cancelTranscriptEditing,
                            onApplyEdits = viewModel::applyTranscriptEdits,
                            activeSegmentIndex = activeSegment?.index,''',
    '''                            onCancelEditing = viewModel::cancelTranscriptEditing,
                            onApplyEdits = viewModel::applyTranscriptEdits,
                            onViewChanged = viewModel::setTranscriptView,
                            activeSegmentIndex = activeSegment?.index,'''
)

# ---------------------------------------------------------------------------
# Export and sharing follow the explicitly selected global view.
# JSON schema remains stable; provenance stays internal/UI in this issue.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/ui/main/TranscriptShare.kt'
replace_once(path, 'segments = state.segments,', 'segments = state.exportSegmentsForSelectedView(),')
replace_once(path, 'segments = state.segments,', 'segments = state.exportSegmentsForSelectedView(),')

# ---------------------------------------------------------------------------
# ViewModel: capture live grouping, provenance transitions, persistence/view switching.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/whispercppdemo/ui/main/MainScreenViewModel.kt'
replace_once(
    path,
    '''            rawWhisperSegments = restoredTranscript?.rawWhisperSegments.orEmpty(),
            segments = restoredTranscript?.displayedSegments.orEmpty(),
            isEditingTranscript = false,''',
    '''            rawWhisperSegments = restoredTranscript?.rawWhisperSegments.orEmpty(),
            segments = restoredTranscript?.displayedSegments.orEmpty(),
            transcriptSectionMinutes = restoredTranscript?.sectionMinutes,
            segmentOrigins = restoredTranscript?.let { stored ->
                stored.segmentOrigins.takeIf { it.isNotEmpty() }
                    ?: defaultTranscriptOrigins(stored.displayedSegments, stored.rawWhisperSegments)
            }.orEmpty(),
            transcriptView = restoredTranscript?.transcriptView ?: TranscriptViewMode.EDITED,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
            aiBaselineSegments = emptyList(),
            aiBaselineOrigins = emptyMap(),
            isEditingTranscript = false,'''
)
replace_once(
    path,
    '''    fun startAiTranscriptEditing(groupStartMs: Long) {
        if (uiState.isBusy || uiState.completedModel == null || uiState.segments.isEmpty() || !uiState.selectedAiModelInstalled) return
        if (uiState.segments.none { transcriptGroupStartMs(it.startMs) == groupStartMs }) return''',
    '''    fun startAiTranscriptEditing(groupStartMs: Long) {
        if (
            uiState.isBusy || uiState.completedModel == null || uiState.segments.isEmpty() ||
            !uiState.selectedAiModelInstalled || uiState.transcriptView != TranscriptViewMode.EDITED
        ) return
        val sectionMinutes = uiState.effectiveTranscriptSectionMinutes()
        if (uiState.segments.none {
                transcriptGroupStartMs(it.startMs, sectionMinutes) == groupStartMs
            }
        ) return'''
)
replace_once(
    path,
    '''            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = uiState.segments,
            isBusy = true,''',
    '''            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = uiState.segments,
            editingTranscriptOrigin = TranscriptSegmentOrigin.AI,
            transcriptView = TranscriptViewMode.EDITED,
            isBusy = true,'''
)
replace_once(
    path,
    '''    fun applyTranscriptEdits() {
        val appliedSegments = transcriptSession.applyEdits(uiState) ?: return
        uiState = uiState.copy(
            segments = appliedSegments,
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),''',
    '''    fun applyTranscriptEdits() {
        val appliedSegments = transcriptSession.applyEdits(uiState) ?: return
        val appliedOrigins = updateTranscriptOrigins(
            previousSegments = uiState.segments,
            updatedSegments = appliedSegments,
            rawWhisperSegments = uiState.rawWhisperSegments,
            existingOrigins = uiState.segmentOrigins,
            changedOrigin = uiState.editingTranscriptOrigin
        )
        uiState = uiState.copy(
            segments = appliedSegments,
            segmentOrigins = appliedOrigins,
            transcriptView = TranscriptViewMode.EDITED,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),'''
)
replace_once(
    path,
    '''        cue(CannaBotCue.WAVING)
    }

    fun setAiPostProcessingEnabled''',
    '''        cue(CannaBotCue.WAVING)
    }

    fun setTranscriptView(view: TranscriptViewMode) {
        if (
            uiState.isEditingTranscript || uiState.isAiPostProcessing ||
            uiState.rawWhisperSegments.isEmpty() || uiState.transcriptView == view
        ) return
        uiState = uiState.copy(transcriptView = view)
        persistCurrentTranscript(uiState.segments)
    }

    fun setAiPostProcessingEnabled'''
)
replace_once(
    path,
    '''    fun transcribe() {
        val uri = uiState.selectedAudio ?: return
        if (!uiState.modelReady || uiState.isBusy) return
        stopPlayback(release = false)
        transcriptResultPersistence.clear()
        uiState = uiState.withRecalculatedTranscriptionEstimate()''',
    '''    fun transcribe() {
        val uri = uiState.selectedAudio ?: return
        if (!uiState.modelReady || uiState.isBusy) return
        stopPlayback(release = false)
        val capturedSectionMinutes = uiState.whisperSettings.sectionMinutes.coerceIn(1, 5)
        TranscriptGroupingRuntime.use(capturedSectionMinutes)
        transcriptResultPersistence.clear()
        uiState = uiState.withRecalculatedTranscriptionEstimate()'''
)
replace_once(
    path,
    '''            rawWhisperSegments = emptyList(),
            segments = emptyList(),
            detectedLanguage = null,''',
    '''            rawWhisperSegments = emptyList(),
            segments = emptyList(),
            transcriptSectionMinutes = capturedSectionMinutes,
            segmentOrigins = emptyMap(),
            transcriptView = TranscriptViewMode.EDITED,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
            aiBaselineSegments = emptyList(),
            aiBaselineOrigins = emptyMap(),
            detectedLanguage = null,'''
)
replace_once(
    path,
    '''            is AiPostProcessingState.Starting -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isAiPostProcessing = true,''',
    '''            is AiPostProcessingState.Starting -> {
                uiState = uiState.copy(
                    aiBaselineSegments = uiState.segments,
                    aiBaselineOrigins = uiState.segmentOrigins,
                    transcriptView = TranscriptViewMode.EDITED,
                    isBusy = true,
                    isAiPostProcessing = true,'''
)
replace_once(
    path,
    '''            is AiPostProcessingState.Completed -> {
                val manual = state.mode == AiPostProcessingMode.MANUAL_GROUP
                uiState = uiState.copy(''',
    '''            is AiPostProcessingState.Completed -> {
                val manual = state.mode == AiPostProcessingMode.MANUAL_GROUP
                val nextOrigins = if (manual) {
                    uiState.segmentOrigins
                } else {
                    updateTranscriptOrigins(
                        previousSegments = uiState.aiBaselineSegments.takeIf {
                            it.size == state.segments.size
                        } ?: uiState.segments,
                        updatedSegments = state.segments,
                        rawWhisperSegments = uiState.rawWhisperSegments,
                        existingOrigins = uiState.aiBaselineOrigins.takeIf { it.isNotEmpty() }
                            ?: uiState.segmentOrigins,
                        changedOrigin = TranscriptSegmentOrigin.AI
                    )
                }
                uiState = uiState.copy('''
)
replace_once(
    path,
    '''                    segments = if (manual) uiState.segments else state.segments,
                    draftSegments = if (manual) state.segments else emptyList(),
                    isEditingTranscript = manual,''',
    '''                    segments = if (manual) uiState.segments else state.segments,
                    segmentOrigins = nextOrigins,
                    transcriptView = TranscriptViewMode.EDITED,
                    draftSegments = if (manual) state.segments else emptyList(),
                    editingTranscriptOrigin = if (manual) {
                        TranscriptSegmentOrigin.AI
                    } else {
                        TranscriptSegmentOrigin.MANUAL
                    },
                    aiBaselineSegments = emptyList(),
                    aiBaselineOrigins = emptyMap(),
                    isEditingTranscript = manual,'''
)
replace_once(
    path,
    '''            is AiPostProcessingState.Failed -> {
                val manual = state.mode == AiPostProcessingMode.MANUAL_GROUP
                uiState = uiState.copy(''',
    '''            is AiPostProcessingState.Failed -> {
                val manual = state.mode == AiPostProcessingMode.MANUAL_GROUP
                val restoredOrigins = if (manual) {
                    uiState.segmentOrigins
                } else {
                    uiState.aiBaselineOrigins.takeIf { it.isNotEmpty() }
                        ?: defaultTranscriptOrigins(state.originalSegments, uiState.rawWhisperSegments)
                }
                uiState = uiState.copy('''
)
replace_once(
    path,
    '''                    segments = state.originalSegments,
                    draftSegments = if (manual) state.originalSegments else emptyList(),
                    isEditingTranscript = manual,''',
    '''                    segments = state.originalSegments,
                    segmentOrigins = restoredOrigins,
                    transcriptView = TranscriptViewMode.EDITED,
                    draftSegments = if (manual) state.originalSegments else emptyList(),
                    editingTranscriptOrigin = if (manual) {
                        TranscriptSegmentOrigin.AI
                    } else {
                        TranscriptSegmentOrigin.MANUAL
                    },
                    aiBaselineSegments = emptyList(),
                    aiBaselineOrigins = emptyMap(),
                    isEditingTranscript = manual,'''
)

# ---------------------------------------------------------------------------
# Tests for provenance rules and selected global view.
# ---------------------------------------------------------------------------
write('app/src/test/java/com/whispercppdemo/TranscriptProvenanceTest.kt', '''package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptProvenanceTest {
    private val raw = listOf(
        WhisperSegment(0L, 1_000L, "Alpha"),
        WhisperSegment(1_000L, 2_000L, "Beta")
    )

    @Test
    fun `manual ai and return to original follow accepted text`() {
        val originalOrigins = defaultTranscriptOrigins(raw, raw)
        val manualSegments = raw.withUpdatedTranscriptText(0, "Alpha korrigiert")
        val manualOrigins = updateTranscriptOrigins(
            previousSegments = raw,
            updatedSegments = manualSegments,
            rawWhisperSegments = raw,
            existingOrigins = originalOrigins,
            changedOrigin = TranscriptSegmentOrigin.MANUAL
        )
        assertEquals(TranscriptSegmentOrigin.MANUAL, acceptedTranscriptOrigin(0, manualSegments[0], raw, manualOrigins))
        assertEquals(TranscriptSegmentOrigin.ORIGINAL, acceptedTranscriptOrigin(1, manualSegments[1], raw, manualOrigins))

        val aiSegments = manualSegments.withUpdatedTranscriptText(1, "Beta KI")
        val aiOrigins = updateTranscriptOrigins(
            previousSegments = manualSegments,
            updatedSegments = aiSegments,
            rawWhisperSegments = raw,
            existingOrigins = manualOrigins,
            changedOrigin = TranscriptSegmentOrigin.AI
        )
        assertEquals(TranscriptSegmentOrigin.MANUAL, acceptedTranscriptOrigin(0, aiSegments[0], raw, aiOrigins))
        assertEquals(TranscriptSegmentOrigin.AI, acceptedTranscriptOrigin(1, aiSegments[1], raw, aiOrigins))

        val restoredOriginal = aiSegments.withUpdatedTranscriptText(0, "Alpha")
        val restoredOrigins = updateTranscriptOrigins(
            previousSegments = aiSegments,
            updatedSegments = restoredOriginal,
            rawWhisperSegments = raw,
            existingOrigins = aiOrigins,
            changedOrigin = TranscriptSegmentOrigin.MANUAL
        )
        assertEquals(TranscriptSegmentOrigin.ORIGINAL, acceptedTranscriptOrigin(0, restoredOriginal[0], raw, restoredOrigins))
    }

    @Test
    fun `global original view never changes accepted edited text`() {
        val edited = raw.withUpdatedTranscriptText(0, "Bearbeitet")
        val state = TranscriptUiState(
            rawWhisperSegments = raw,
            segments = edited,
            transcriptView = TranscriptViewMode.ORIGINAL
        )

        assertEquals("Alpha", state.transcriptSegmentsForSelectedView()[0].text)
        assertEquals("Bearbeitet", state.segments[0].text)
        assertEquals("Alpha", state.exportSegmentsForSelectedView()[0].text)
    }

    @Test
    fun `live whisper segments without raw source are treated as original`() {
        val segment = WhisperSegment(0L, 1_000L, "Live")
        assertEquals(
            TranscriptSegmentOrigin.ORIGINAL,
            acceptedTranscriptOrigin(0, segment, emptyList(), emptyMap())
        )
    }
}
''')

print('Issue #31 patch applied successfully.')
