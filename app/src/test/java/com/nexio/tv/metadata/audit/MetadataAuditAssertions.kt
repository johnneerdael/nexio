package com.nexio.tv.metadata.audit

object MetadataAuditAssertions {
    fun assertPreviewIsAddonOnly(report: MetadataExecutionReport) {
        report.items.forEach { item ->
            check(!item.firstPaint.routerExecuted) {
                "PREVIEW routed item ${item.itemId}"
            }
            check(!item.firstPaint.networkExecuted) {
                "PREVIEW performed network for item ${item.itemId}"
            }
            check(item.routing == null) {
                "PREVIEW produced route for item ${item.itemId}"
            }
            check(item.runtimeCalls.isEmpty()) {
                "PREVIEW produced runtime calls for item ${item.itemId}"
            }
        }
    }

    fun assertNoLegacyExecution(events: List<AuditEvent>) {
        val violations = events.filterIsInstance<AuditEvent.PolicyViolation>()
            .filter {
                it.event.code in setOf(
                    "LEGACY_PROVIDER_ROUTER_USED",
                    "DIRECT_PROVIDER_SERVICE_USED",
                    "RETROFIT_BYPASS",
                    "FIELD_RESOLVER_BYPASSED"
                )
            }

        check(violations.isEmpty()) {
            "Legacy execution detected: $violations"
        }
    }

    fun assertFacadeOwnsOutput(report: MetadataExecutionReport) {
        report.items.forEach { item ->
            check(item.selectedFields.isNotEmpty()) {
                "No FieldResolver-selected fields for ${item.itemId}"
            }
        }
    }

    fun assertEveryRuntimeCallHasApiShape(report: MetadataExecutionReport) {
        report.items.flatMap { it.runtimeCalls }.forEach { call ->
            check(call.apiShapeId.isNotBlank()) {
                "Runtime call missing apiShapeId: $call"
            }
        }
    }

    fun assertNoCatalogHintsUsedForRouting(report: MetadataExecutionReport) {
        report.items.mapNotNull { it.routing }.forEach { route ->
            val forbidden = route.usedInputs.intersect(
                setOf(
                    "catalog.type",
                    "catalog.id",
                    "addon.name",
                    "source.name",
                    "genre",
                    "animeType",
                    "links",
                    "trend",
                    "popularity"
                )
            )

            check(forbidden.isEmpty()) {
                "Routing used forbidden inputs for ${route.itemId}: $forbidden"
            }
        }
    }
}
