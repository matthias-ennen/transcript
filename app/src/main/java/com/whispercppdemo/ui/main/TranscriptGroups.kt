package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.export.formatTimestamp

private sealed interface TranscriptEditTarget {
    val groupStartMs: Long

    data class Segment(
        val index: Int,
        override val groupStartMs: Long
    ) : TranscriptEditTarget

    data class Group(override val groupStartMs: Long) : TranscriptEditTarget
}

@Composable
internal fun TranscriptList(
    state: TranscriptUiState,
    segments: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>,
    onTextChanged: (Int, String) -> Unit,
    onAiEditGroup: (Long) -> Unit,
    onEditGroup: (Long) -> Unit,
    onCancelEditing: () -> Unit,
    onApplyEdits: () -> Unit,
    activeSegmentIndex: Int?,
    activeSegmentProgress: Float,
    modifier: Modifier = Modifier
) {
    if (segments.isEmpty()) return

    val sectionMinutes = state.effectiveTranscriptSectionMinutes()
    val groups = remember(segments, sectionMinutes) {
        groupTranscriptSegments(segments, sectionMinutes)
    }
    val segmentNumbers = remember(segments, rawWhisperSegments) {
        transcriptNumbers(segments, rawWhisperSegments)
    }
    val expandedGroups = remember(state.selectedAudio) {
        mutableStateMapOf<Long, Boolean>().apply {
            groups.firstOrNull()?.let { firstGroup -> put(firstGroup.startMs, true) }
        }
    }
    val editedTextByIndex = remember(state.selectedAudio) { mutableStateMapOf<Int, String>() }
    val showingOriginalByIndex = remember(state.selectedAudio) { mutableStateMapOf<Int, Boolean>() }
    var singleEditIndex by remember(state.selectedAudio) { mutableStateOf<Int?>(null) }
    var pendingEditTarget by remember(state.selectedAudio) { mutableStateOf<TranscriptEditTarget?>(null) }
    var confirmBlankApply by remember(state.selectedAudio) { mutableStateOf(false) }

    fun originalText(index: Int): String = segments.getOrNull(index)?.let { segment ->
        originalTranscriptText(segment, rawWhisperSegments)
    }.orEmpty()

    fun acceptedText(index: Int): String = state.segments.getOrNull(index)?.text.orEmpty()

    fun prepareEditDraft(target: TranscriptEditTarget) {
        editedTextByIndex.clear()
        showingOriginalByIndex.clear()
        state.segments.forEachIndexed { index, segment ->
            editedTextByIndex[index] = segment.text
            showingOriginalByIndex[index] = segment.text == originalTranscriptText(
                segment,
                rawWhisperSegments
            )
        }
        singleEditIndex = (target as? TranscriptEditTarget.Segment)?.index
        expandedGroups[target.groupStartMs] = true
        onEditGroup(target.groupStartMs)
    }

    fun requestEdit(target: TranscriptEditTarget) {
        if (!state.isEditingTranscript) {
            prepareEditDraft(target)
            return
        }
        val sameTarget = when (target) {
            is TranscriptEditTarget.Segment -> singleEditIndex == target.index
            is TranscriptEditTarget.Group -> singleEditIndex == null &&
                state.editingTranscriptGroupStartMs == target.groupStartMs
        }
        if (sameTarget) return
        if (state.hasUnsavedTranscriptChanges) {
            pendingEditTarget = target
        } else {
            onCancelEditing()
            prepareEditDraft(target)
        }
    }

    fun finishEditing() {
        onApplyEdits()
        singleEditIndex = null
        editedTextByIndex.clear()
        showingOriginalByIndex.clear()
        confirmBlankApply = false
    }

    fun requestApply() {
        if (state.hasNewlyBlankTranscriptDraft) {
            confirmBlankApply = true
        } else {
            finishEditing()
        }
    }

    fun showOriginal(index: Int) {
        if (showingOriginalByIndex[index] != true) {
            editedTextByIndex[index] = state.draftSegments.getOrNull(index)?.text
                ?: segments.getOrNull(index)?.text
                ?: acceptedText(index)
        }
        showingOriginalByIndex[index] = true
        onTextChanged(index, originalText(index))
    }

    fun showEdited(index: Int) {
        showingOriginalByIndex[index] = false
        onTextChanged(index, editedTextByIndex[index] ?: acceptedText(index))
    }

    fun editText(index: Int, text: String) {
        editedTextByIndex[index] = text
        showingOriginalByIndex[index] = false
        onTextChanged(index, text)
    }

    fun groupIndices(group: TranscriptGroup): List<Int> =
        group.segments.map { it.originalIndex }

    fun showGroupOriginal(group: TranscriptGroup) {
        groupIndices(group).forEach(::showOriginal)
    }

    fun showGroupEdited(group: TranscriptGroup) {
        groupIndices(group).forEach(::showEdited)
    }

    LaunchedEffect(
        state.isEditingTranscript,
        state.editingTranscriptGroupStartMs,
        state.isAiPostProcessing
    ) {
        if (!state.isEditingTranscript) {
            singleEditIndex = null
            editedTextByIndex.clear()
            showingOriginalByIndex.clear()
        } else {
            val aiDraftCompleted = !state.isAiPostProcessing &&
                singleEditIndex == null &&
                state.draftSegments.size == state.segments.size &&
                state.draftSegments != state.segments
            if (editedTextByIndex.isEmpty() || aiDraftCompleted) {
                editedTextByIndex.clear()
                showingOriginalByIndex.clear()
                val source = state.draftSegments.takeIf { it.size == state.segments.size }
                    ?: state.segments
                source.forEachIndexed { index, segment ->
                    editedTextByIndex[index] = segment.text
                    showingOriginalByIndex[index] = segment.text == originalTranscriptText(
                        state.segments.getOrNull(index) ?: segment,
                        rawWhisperSegments
                    )
                }
            }
        }
    }

    pendingEditTarget?.let { target ->
        TranscriptEditQuestionDialog(
            state = state,
            message = "Es gibt noch nicht übernommene Änderungen. Möchtest du sie verwerfen und die andere Bearbeitung öffnen?",
            confirmLabel = "Verwerfen",
            dismissLabel = "Zurück",
            onConfirm = {
                pendingEditTarget = null
                onCancelEditing()
                prepareEditDraft(target)
            },
            onDismiss = { pendingEditTarget = null }
        )
    }

    if (confirmBlankApply) {
        TranscriptEditQuestionDialog(
            state = state,
            message = "Mindestens ein Textabschnitt ist leer. Möchtest du das wirklich übernehmen?",
            confirmLabel = "Übernehmen",
            dismissLabel = "Abbrechen",
            onConfirm = ::finishEditing,
            onDismiss = { confirmBlankApply = false }
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { groups.forEach { expandedGroups[it.startMs] = false } },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Alle einklappen",
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = { groups.forEach { expandedGroups[it.startMs] = true } },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Alle ausklappen",
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center
                )
            }
        }

        groups.forEach { group ->
            val activeEditGroup = state.editingTranscriptGroupStartMs == group.startMs &&
                state.isEditingTranscript
            val isEditingGroup = activeEditGroup && singleEditIndex == null
            val expanded = activeEditGroup || expandedGroups[group.startMs] == true
            val indices = groupIndices(group)
            val allOriginal = indices.isNotEmpty() && indices.all { showingOriginalByIndex[it] == true }
            val allEdited = indices.isNotEmpty() && indices.all { showingOriginalByIndex[it] != true }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (!activeEditGroup) expandedGroups[group.startMs] = !expanded
                    },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${formatClock(group.startMs / 1_000L)}–" +
                            formatClock(group.endMs / 1_000L),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) {
                            "Gruppe einklappen"
                        } else {
                            "Gruppe ausklappen"
                        }
                    )
                }

                if (expanded) {
                    group.segments.forEach { indexedSegment ->
                        val index = indexedSegment.originalIndex
                        val isEditingSingle = singleEditIndex == index && activeEditGroup
                        TranscriptSegmentCard(
                            number = segmentNumbers.getOrNull(index),
                            segment = indexedSegment.segment,
                            isEditing = isEditingGroup || isEditingSingle,
                            editingEnabled = !state.isAiPostProcessing,
                            isPlaybackActive = index == activeSegmentIndex,
                            playbackProgress = if (index == activeSegmentIndex) {
                                activeSegmentProgress
                            } else {
                                0f
                            },
                            onTextChanged = { editText(index, it) },
                            editControls = when {
                                isEditingGroup -> TranscriptSegmentEditControls.Group(
                                    showingOriginal = showingOriginalByIndex[index] == true,
                                    enabled = !state.isAiPostProcessing,
                                    onOriginal = { showOriginal(index) },
                                    onEdited = { showEdited(index) }
                                )
                                isEditingSingle -> TranscriptSegmentEditControls.Single(
                                    showingOriginal = showingOriginalByIndex[index] == true,
                                    onCancel = {
                                        onCancelEditing()
                                        singleEditIndex = null
                                        editedTextByIndex.clear()
                                        showingOriginalByIndex.clear()
                                    },
                                    onApply = ::requestApply,
                                    onOriginal = { showOriginal(index) },
                                    onEdited = { showEdited(index) }
                                )
                                else -> TranscriptSegmentEditControls.Start(
                                    enabled = !state.isBusy && !state.isAiPostProcessing,
                                    onEdit = {
                                        requestEdit(
                                            TranscriptEditTarget.Segment(
                                                index = index,
                                                groupStartMs = group.startMs
                                            )
                                        )
                                    }
                                )
                            }
                        )
                    }
                    TranscriptGroupEditorActions(
                        state = state,
                        groupStartMs = group.startMs,
                        sectionMinutes = sectionMinutes,
                        isEditingGroup = isEditingGroup,
                        allOriginal = allOriginal,
                        allEdited = allEdited,
                        onAiEdit = { onAiEditGroup(group.startMs) },
                        onEdit = { requestEdit(TranscriptEditTarget.Group(group.startMs)) },
                        onOriginal = { showGroupOriginal(group) },
                        onEdited = { showGroupEdited(group) },
                        onCancel = {
                            onCancelEditing()
                            singleEditIndex = null
                            editedTextByIndex.clear()
                            showingOriginalByIndex.clear()
                        },
                        onApply = ::requestApply
                    )
                }
            }
        }
    }
}

private sealed interface TranscriptSegmentEditControls {
    data class Start(
        val enabled: Boolean,
        val onEdit: () -> Unit
    ) : TranscriptSegmentEditControls

    data class Single(
        val showingOriginal: Boolean,
        val onCancel: () -> Unit,
        val onApply: () -> Unit,
        val onOriginal: () -> Unit,
        val onEdited: () -> Unit
    ) : TranscriptSegmentEditControls

    data class Group(
        val showingOriginal: Boolean,
        val enabled: Boolean,
        val onOriginal: () -> Unit,
        val onEdited: () -> Unit
    ) : TranscriptSegmentEditControls
}

@Composable
private fun TranscriptSegmentCard(
    number: Int?,
    segment: WhisperSegment,
    isEditing: Boolean,
    editingEnabled: Boolean,
    isPlaybackActive: Boolean,
    playbackProgress: Float,
    onTextChanged: (String) -> Unit,
    editControls: TranscriptSegmentEditControls,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, end = 4.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                if (isEditing) {
                    TranscriptSegmentBody(
                        segment = segment,
                        isEditing = true,
                        editingEnabled = editingEnabled,
                        isPlaybackActive = isPlaybackActive,
                        playbackProgress = playbackProgress,
                        onTextChanged = onTextChanged
                    )
                } else {
                    SelectionContainer {
                        TranscriptSegmentBody(
                            segment = segment,
                            isPlaybackActive = isPlaybackActive,
                            playbackProgress = playbackProgress
                        )
                    }
                }
                SegmentCapsules(editControls)
            }
        }

        if (number != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .width(TRANSCRIPT_NUMBER_CAPSULE_WIDTH)
                    .height(TRANSCRIPT_NUMBER_CAPSULE_HEIGHT)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = MaterialTheme.typography.labelMedium.fontSize * 1.2f
                    )
                )
            }
        }
    }
}

@Composable
private fun SegmentCapsules(controls: TranscriptSegmentEditControls) {
    Row(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (controls) {
            is TranscriptSegmentEditControls.Start -> {
                TranscriptIconCapsule(
                    onClick = controls.onEdit,
                    enabled = controls.enabled
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Textabschnitt bearbeiten")
                }
            }
            is TranscriptSegmentEditControls.Single -> {
                TranscriptIconCapsule(
                    onClick = controls.onCancel,
                    enabled = true
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Bearbeitung abbrechen")
                }
                TranscriptIconCapsule(
                    onClick = controls.onApply,
                    enabled = true
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Änderung übernehmen")
                }
                TranscriptTextCapsule(
                    text = "Original",
                    selected = controls.showingOriginal,
                    onClick = controls.onOriginal
                )
                TranscriptTextCapsule(
                    text = "Editiert",
                    selected = !controls.showingOriginal,
                    onClick = controls.onEdited
                )
            }
            is TranscriptSegmentEditControls.Group -> {
                TranscriptTextCapsule(
                    text = "Original",
                    selected = controls.showingOriginal,
                    enabled = controls.enabled,
                    onClick = controls.onOriginal
                )
                TranscriptTextCapsule(
                    text = "Editiert",
                    selected = !controls.showingOriginal,
                    enabled = controls.enabled,
                    onClick = controls.onEdited
                )
            }
        }
    }
}

@Composable
private fun TranscriptIconCapsule(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(TRANSCRIPT_NUMBER_CAPSULE_WIDTH)
            .height(TRANSCRIPT_NUMBER_CAPSULE_HEIGHT),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(
                alpha = FLOATING_TRANSCRIPT_CONTROL_ALPHA
            ),
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun TranscriptTextCapsule(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(TRANSCRIPT_NUMBER_CAPSULE_HEIGHT),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(
                alpha = if (selected) 1f else FLOATING_TRANSCRIPT_CONTROL_ALPHA
            ),
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun TranscriptSegmentBody(
    segment: WhisperSegment,
    isEditing: Boolean = false,
    editingEnabled: Boolean = true,
    isPlaybackActive: Boolean = false,
    playbackProgress: Float = 0f,
    onTextChanged: (String) -> Unit = {}
) {
    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            "${formatTimestamp(segment.startMs)} – ${formatTimestamp(segment.endMs)}",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(4.dp))
        val textAreaShape = RoundedCornerShape(8.dp)
        val activeBorderColor = Color.White
        val overlayColor = Color.White.copy(alpha = FLOATING_TRANSCRIPT_CONTROL_ALPHA)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(textAreaShape)
                .drawBehind {
                    if (isPlaybackActive) {
                        drawRect(
                            color = overlayColor,
                            size = Size(
                                width = size.width * playbackProgress.coerceIn(0f, 1f),
                                height = size.height
                            )
                        )
                    }
                }
                .then(
                    if (isPlaybackActive) {
                        Modifier.border(2.dp, activeBorderColor, textAreaShape)
                    } else {
                        Modifier
                    }
                )
                .padding(8.dp)
        ) {
            if (isEditing) {
                OutlinedTextField(
                    value = segment.text,
                    onValueChange = onTextChanged,
                    enabled = editingEnabled,
                    label = { Text("Text") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(segment.text)
            }
        }
    }
}

@Composable
private fun TranscriptGroupEditorActions(
    state: TranscriptUiState,
    groupStartMs: Long,
    sectionMinutes: Int,
    isEditingGroup: Boolean,
    allOriginal: Boolean,
    allEdited: Boolean,
    onAiEdit: () -> Unit,
    onEdit: () -> Unit,
    onOriginal: () -> Unit,
    onEdited: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    if (!isEditingGroup) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            OutlinedButton(
                onClick = onAiEdit,
                enabled = !state.isBusy && !state.isEditingTranscript && state.completedModel != null && state.selectedAiModelInstalled,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "KI-Nachbearbeitung",
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            OutlinedButton(
                onClick = onEdit,
                enabled = !state.isBusy && !state.isAiPostProcessing,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Text("Bearbeiten")
            }
        }
        return
    }

    Text(
        "Nur diese $sectionMinutes-Minuten-Gruppe ist editierbar. Die Zeitstempel bleiben unverändert.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TranscriptTextCapsule(
            text = "Original",
            selected = allOriginal,
            enabled = !state.isAiPostProcessing,
            onClick = onOriginal
        )
        TranscriptTextCapsule(
            text = "Editiert",
            selected = allEdited,
            enabled = !state.isAiPostProcessing,
            onClick = onEdited
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        OutlinedButton(
            onClick = onCancel,
            enabled = !state.isAiPostProcessing,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Abbrechen",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick = onApply,
            enabled = !state.isAiPostProcessing && state.hasUnsavedChangesInGroup(groupStartMs),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Übernehmen",
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TranscriptEditQuestionDialog(
    state: TranscriptUiState,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
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
                    style = MaterialTheme.typography.bodyLarge
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

internal val TRANSCRIPT_NUMBER_CAPSULE_WIDTH = 52.dp
internal val TRANSCRIPT_NUMBER_CAPSULE_HEIGHT = 32.dp
