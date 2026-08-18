package de.matthiasennen.transcript.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadStorageIssue(
    val modelLabel: String,
    val requiredFreeBytes: Long,
    val availableBytes: Long
)

object DownloadStorageIssueCoordinator {
    private val mutableIssue = MutableStateFlow<DownloadStorageIssue?>(null)
    val issue = mutableIssue.asStateFlow()

    fun show(requirement: DownloadStorageRequirement) {
        mutableIssue.value = DownloadStorageIssue(
            modelLabel = requirement.modelLabel,
            requiredFreeBytes = requirement.requiredFreeBytes,
            availableBytes = requirement.availableBytes
        )
    }

    fun clear() {
        mutableIssue.value = null
    }
}
