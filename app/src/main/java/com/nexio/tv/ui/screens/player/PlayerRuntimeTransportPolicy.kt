package com.nexio.tv.ui.screens.player

internal fun effectiveRuntimeTransportPolicy(
    basePolicy: TransportPolicy?,
    enabled: Boolean,
    activeServiceKey: String?,
    runtimeHints: com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2?,
    observation: RuntimeTransportObservation?
): TransportPolicy? {
    val policy = basePolicy ?: return null
    val specialization = resolveRuntimeTransportSpecialization(
        enabled = enabled,
        activeServiceKey = activeServiceKey,
        activeHostScope = observation?.hostScope,
        activeTransportClass = observation?.transportClass,
        runtimeHints = runtimeHints
    )
    return policy.applyRuntimeTransportSpecialization(
        specialization = specialization,
        runtimeHints = runtimeHints
    )
}
