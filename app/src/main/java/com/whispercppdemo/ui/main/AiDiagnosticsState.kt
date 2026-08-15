package de.matthiasennen.transcript.ui.main

internal const val AI_DIAGNOSTICS_WELCOME_MESSAGE =
    "Ich bin bereit. Was möchtest du ausprobieren?"

internal enum class AiDiagnosticsPreloadDecision {
    START,
    ALREADY_LOADED,
    MODEL_MISSING,
    OPERATION_ACTIVE
}

internal fun aiDiagnosticsPreloadDecision(
    modelInstalled: Boolean,
    operationActive: Boolean,
    matchingSessionLoaded: Boolean
): AiDiagnosticsPreloadDecision = when {
    !modelInstalled -> AiDiagnosticsPreloadDecision.MODEL_MISSING
    operationActive -> AiDiagnosticsPreloadDecision.OPERATION_ACTIVE
    matchingSessionLoaded -> AiDiagnosticsPreloadDecision.ALREADY_LOADED
    else -> AiDiagnosticsPreloadDecision.START
}

internal fun aiDiagnosticsResponseText(
    showWelcome: Boolean,
    modelResponse: String?
): String = if (showWelcome) AI_DIAGNOSTICS_WELCOME_MESSAGE else modelResponse.orEmpty()

internal fun canSendAiDiagnosticsRequest(
    modelInstalled: Boolean,
    modelReady: Boolean,
    modelPreloading: Boolean,
    operationActive: Boolean,
    prompt: String
): Boolean = modelInstalled && modelReady && !modelPreloading && !operationActive &&
    prompt.isNotBlank()
