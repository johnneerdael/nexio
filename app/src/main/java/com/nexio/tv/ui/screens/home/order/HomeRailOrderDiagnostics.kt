package com.nexio.tv.ui.screens.home.order

import javax.inject.Inject
import javax.inject.Singleton

data class HomeRailOrderDiagnosticEvent(
    val eventType: String,
    val payload: String,
)

interface HomeRailOrderDiagnosticsSink {
    fun emitReconciled(
        savedGlobalOrder: List<HomeRailKey>,
        providerOrders: Map<RailFamily, List<HomeRailKey>>,
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitionOrder: List<HomeRailKey>,
        effectiveOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        newlyDiscoveredKeys: List<HomeRailKey>,
        ignoredOrderSources: List<String>,
        mutationSource: RailOrderMutationSource,
    ) {
    }

    fun emitMutation(
        source: RailOrderMutationSource,
        before: List<HomeRailKey>,
        after: List<HomeRailKey>,
    ) {
    }

    fun emitEnabledChanged(
        key: HomeRailKey,
        enabled: Boolean,
        source: RailOrderMutationSource,
    ) {
    }

    fun emitHiddenDueToDisabled(key: HomeRailKey) {
    }

    fun emitAddedFromMissingDefault(key: HomeRailKey) {
    }

    fun emitPersistedSyntheticUsedAsContentOnly(key: HomeRailKey) {
    }
}

@Singleton
class LoggingHomeRailOrderDiagnosticsSink @Inject constructor() : HomeRailOrderDiagnosticsSink {
    override fun emitReconciled(
        savedGlobalOrder: List<HomeRailKey>,
        providerOrders: Map<RailFamily, List<HomeRailKey>>,
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitionOrder: List<HomeRailKey>,
        effectiveOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        newlyDiscoveredKeys: List<HomeRailKey>,
        ignoredOrderSources: List<String>,
        mutationSource: RailOrderMutationSource,
    ) {
        android.util.Log.i(
            "HomeRailOrder",
            "rail_order_reconciled saved=${savedGlobalOrder.size} effective=${effectiveOrder.size} " +
                "newlyDiscovered=${newlyDiscoveredKeys.size} disabled=${disabledKeys.size} " +
                "ignored=$ignoredOrderSources source=$mutationSource",
        )
    }

    override fun emitMutation(
        source: RailOrderMutationSource,
        before: List<HomeRailKey>,
        after: List<HomeRailKey>,
    ) {
        if (com.nexio.tv.BuildConfig.DEBUG) {
            android.util.Log.d(
                "HomeRailOrder",
                "rail_order_mutation source=$source beforeSize=${before.size} afterSize=${after.size}",
            )
        }
    }

    override fun emitEnabledChanged(key: HomeRailKey, enabled: Boolean, source: RailOrderMutationSource) {
        if (com.nexio.tv.BuildConfig.DEBUG) {
            android.util.Log.d("HomeRailOrder", "rail_enabled_changed key=${key.value} enabled=$enabled source=$source")
        }
    }

    override fun emitHiddenDueToDisabled(key: HomeRailKey) {
        if (com.nexio.tv.BuildConfig.DEBUG) {
            android.util.Log.d("HomeRailOrder", "rail_hidden_due_to_disabled key=${key.value}")
        }
    }

    override fun emitAddedFromMissingDefault(key: HomeRailKey) {
        if (com.nexio.tv.BuildConfig.DEBUG) {
            android.util.Log.d("HomeRailOrder", "rail_added_from_missing_default key=${key.value}")
        }
    }

    override fun emitPersistedSyntheticUsedAsContentOnly(key: HomeRailKey) {
        if (com.nexio.tv.BuildConfig.DEBUG) {
            android.util.Log.d("HomeRailOrder", "persisted_synthetic_used_as_content_only key=${key.value}")
        }
    }
}

class CapturingHomeRailOrderDiagnosticsSink : HomeRailOrderDiagnosticsSink {
    val events = mutableListOf<HomeRailOrderDiagnosticEvent>()

    override fun emitReconciled(
        savedGlobalOrder: List<HomeRailKey>,
        providerOrders: Map<RailFamily, List<HomeRailKey>>,
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitionOrder: List<HomeRailKey>,
        effectiveOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        newlyDiscoveredKeys: List<HomeRailKey>,
        ignoredOrderSources: List<String>,
        mutationSource: RailOrderMutationSource,
    ) {
        val payload = listOf(
            "savedGlobalOrder=$savedGlobalOrder",
            "providerOrders=$providerOrders",
            "persistedSyntheticOrder=$persistedSyntheticOrder",
            "liveDefinitionOrder=$liveDefinitionOrder",
            "effectiveOrder=$effectiveOrder",
            "disabledKeys=$disabledKeys",
            "newlyDiscoveredKeys=$newlyDiscoveredKeys",
            "ignoredOrderSources=$ignoredOrderSources",
            "mutationSource=$mutationSource",
        ).joinToString(", ")
        events += HomeRailOrderDiagnosticEvent("home.rail_order_reconciled", payload)
    }

    override fun emitMutation(
        source: RailOrderMutationSource,
        before: List<HomeRailKey>,
        after: List<HomeRailKey>,
    ) {
        events += HomeRailOrderDiagnosticEvent(
            "home.rail_order_mutation",
            "source=$source before=$before after=$after",
        )
    }

    override fun emitEnabledChanged(key: HomeRailKey, enabled: Boolean, source: RailOrderMutationSource) {
        events += HomeRailOrderDiagnosticEvent(
            "home.rail_enabled_changed",
            "key=${key.value} enabled=$enabled source=$source",
        )
    }

    override fun emitHiddenDueToDisabled(key: HomeRailKey) {
        events += HomeRailOrderDiagnosticEvent(
            "home.rail_hidden_due_to_disabled",
            "key=${key.value}",
        )
    }

    override fun emitAddedFromMissingDefault(key: HomeRailKey) {
        events += HomeRailOrderDiagnosticEvent(
            "home.rail_added_from_missing_default",
            "key=${key.value}",
        )
    }

    override fun emitPersistedSyntheticUsedAsContentOnly(key: HomeRailKey) {
        events += HomeRailOrderDiagnosticEvent(
            "home.persisted_synthetic_used_as_content_only",
            "key=${key.value}",
        )
    }
}
