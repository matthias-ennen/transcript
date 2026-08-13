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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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

@Composable
internal fun TranscriptList(
    state: TranscriptUiState,
    segments: List<WhisperSegment>,
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

    val groups = remember(segments) { groupTranscriptSegments(segments) }
    val expandedGroups = remember(state.selectedAudio) {
        mutableStateMapOf<Long, Boolean>().apply {
            groups.firstOrNull()?.let { firstGroup -> put(firstGroup.startMs, true) }
        }
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
            val isEditingGroup = state.editingTranscriptGroupStartMs == group.startMs
            val expanded = isEditingGroup || expandedGroups[group.startMs] == true

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (!isEditingGroup) expandedGroups[group.startMs] = !expanded
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
                        TranscriptSegmentCard(
                            number = indexedSegment.originalIndex + 1,
                            segment = indexedSegment.segment,
                            isEditing = isEditingGroup,
                            editingEnabled = !state.isAiPostProcessing,
                            isPlaybackActive = indexedSegment.originalIndex == activeSegmentIndex,
                            playbackProgress = if (indexedSegment.originalIndex == activeSegmentIndex) {
                                activeSegmentProgress
                            } else {
                                0f
                            },
                            onTextChanged = {
                                onTextChanged(indexedSegment.originalIndex, it)
                            }
                        )
                    }
                    TranscriptGroupEditorActions(
                        state = state,
                        groupStartMs = group.startMs,
                        onAiEdit = { onAiEditGroup(group.startMs) },
                        onEdit = { onEditGroup(group.startMs) },
                        onCancel = onCancelEditing,
                        onApply = onApplyEdits
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptSegmentCard(
    number: Int,
    segment: WhisperSegment,
    isEditing: Boolean,
    editingEnabled: Boolean,
    isPlaybackActive: Boolean,
    playbackProgress: Float,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, end = 4.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
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
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 8.dp, y = (-8).dp)
                .width(52.dp)
                .height(32.dp)
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
        val overlayColor = Color.White.copy(alpha = 0.15f)
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
                        Modifier.border(1.dp, activeBorderColor, textAreaShape)
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
    onAiEdit: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val isEditingGroup = state.editingTranscriptGroupStartMs == groupStartMs
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
                enabled = !state.isBusy && !state.isEditingTranscript,
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
        "Nur diese Fünf-Minuten-Gruppe ist editierbar. Die Zeitstempel bleiben unverändert.",
        style = MaterialTheme.typography.bodySmall
    )
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
                text = "Änderungen übernehmen",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
