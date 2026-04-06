package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import com.nexio.tv.data.repository.benchmark.normalizedBenchmarkServiceKey

internal enum class RuntimeTransportSpecializationStatus {
    BASELINE,
    MISMATCH,
    CONFIRMED
}

internal data class RuntimeTransportSpecializationEvent(
    val type: String,
    val detail: String
)

internal data class RuntimeTransportSpecializationTransition(
    val specialization: RuntimeTransportSpecialization,
    val status: RuntimeTransportSpecializationStatus,
    val events: List<RuntimeTransportSpecializationEvent>
)

internal fun nextRuntimeTransportSpecializationTransition(
    previousStatus: RuntimeTransportSpecializationStatus?,
    enabled: Boolean,
    activeServiceKey: String?,
    runtimeHints: RuntimeTransportHintsV2?,
    observation: RuntimeTransportObservation?
): RuntimeTransportSpecializationTransition {
    val specialization = resolveRuntimeTransportSpecialization(
        enabled = enabled,
        activeServiceKey = activeServiceKey,
        activeHostScope = observation?.hostScope,
        activeTransportClass = observation?.transportClass,
        runtimeHints = runtimeHints
    )

    val hintServiceKey = runtimeHints?.serviceKey
    val normalizedActiveServiceKey = normalizedBenchmarkServiceKey(activeServiceKey)
    val normalizedHintServiceKey = normalizedBenchmarkServiceKey(hintServiceKey)
    val serviceKeyMatches = normalizedActiveServiceKey != null &&
        normalizedActiveServiceKey == normalizedHintServiceKey
    val observationMatches = observation != null &&
        observation.hostScope == runtimeHints?.observedHostScope &&
        observation.transportClass == runtimeHints?.observedTransportClass

    val status = when {
        !enabled || runtimeHints == null -> RuntimeTransportSpecializationStatus.BASELINE
        !serviceKeyMatches -> RuntimeTransportSpecializationStatus.MISMATCH
        observation == null -> RuntimeTransportSpecializationStatus.BASELINE
        observationMatches -> RuntimeTransportSpecializationStatus.CONFIRMED
        else -> RuntimeTransportSpecializationStatus.MISMATCH
    }

    val details = linkedMapOf(
        "streamServiceKey" to (normalizedActiveServiceKey ?: "unknown"),
        "hintServiceKey" to (normalizedHintServiceKey ?: "unknown"),
        "hostScope" to (observation?.hostScope ?: "unknown"),
        "transportClass" to (observation?.transportClass ?: "unknown"),
        "allowUrgentChunkAbove8MiB" to specialization.allowUrgentChunkAbove8MiB.toString(),
        "connectionBudgetHint" to (specialization.connectionBudgetHint?.toString() ?: "null"),
        "retryMode" to specialization.retryMode.name
    )
    val reason = when {
        !enabled -> "feature_disabled"
        runtimeHints == null -> "no_runtime_hints"
        !serviceKeyMatches -> "service_key_mismatch"
        observation == null -> "awaiting_runtime_observation"
        observationMatches -> "confirmed"
        else -> "confirmation_lost"
    }
    details["reason"] = reason
    val detailString = details.entries.joinToString(" ") { (key, value) -> "$key=$value" }

    val events = mutableListOf<RuntimeTransportSpecializationEvent>()
    if (status == RuntimeTransportSpecializationStatus.CONFIRMED &&
        previousStatus != RuntimeTransportSpecializationStatus.CONFIRMED
    ) {
        events += RuntimeTransportSpecializationEvent(
            type = "transport_specialization_confirmed",
            detail = detailString
        )
    }
    if (status == RuntimeTransportSpecializationStatus.MISMATCH &&
        previousStatus != RuntimeTransportSpecializationStatus.MISMATCH
    ) {
        events += RuntimeTransportSpecializationEvent(
            type = "transport_specialization_mismatch",
            detail = detailString
        )
    }
    if (previousStatus == RuntimeTransportSpecializationStatus.CONFIRMED &&
        status != RuntimeTransportSpecializationStatus.CONFIRMED
    ) {
        events += RuntimeTransportSpecializationEvent(
            type = "transport_specialization_revoked",
            detail = detailString
        )
    }

    return RuntimeTransportSpecializationTransition(
        specialization = specialization,
        status = status,
        events = events
    )
}
