package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FieldResolver @Inject constructor(
    private val traceEvents: TraceMetadataEvents
) {
    constructor() : this(
        TraceMetadataEvents(
            sink = NoopRuntimeTraceSink,
            sessionId = { null }
        )
    )

    fun resolve(
        primary: MetadataCandidate,
        secondary: List<MetadataCandidate>
    ): ResolvedMetadataDocument = resolveInternal(
        preview = null,
        primary = primary,
        secondary = secondary
    )

    fun resolveWithPreview(
        preview: MetadataCandidate?,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>
    ): ResolvedMetadataDocument {
        return resolvePreviewFirst(
            preview = preview,
            primary = primary,
            secondary = secondary
        )
    }

    private fun resolvePreviewFirst(
        preview: MetadataCandidate?,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>
    ): ResolvedMetadataDocument {
        val fields = linkedMapOf<ResolvedField, Any>()
        val owners = linkedMapOf<ResolvedField, FieldOwner>()
        val sourceRoles = linkedMapOf<ResolvedField, SourceRole>()
        val sourceProviders = linkedMapOf<ResolvedField, String>()
        val localization = linkedMapOf<ResolvedField, MetadataLocalizationFieldTrace>()
        val ignoredOverwrites = mutableListOf<IgnoredFieldOverwrite>()
        val rejectedByField = linkedMapOf<ResolvedField, MutableList<Map<String, Any?>>>()

        preview?.let { candidate ->
            applyMissingCandidate(
                candidate = candidate,
                fields = fields,
                owners = owners,
                sourceRoles = sourceRoles,
                sourceProviders = sourceProviders,
                localization = localization,
                ignoredOverwrites = ignoredOverwrites,
                rejectedByField = rejectedByField
            )
        }

        primary?.fields?.forEach { (field, fieldValue) ->
            if (sourceRoles[field] == SourceRole.RAIL_PREVIEW) {
                val previewValue = fields[field]
                ignoredOverwrites += IgnoredFieldOverwrite(
                    field = field,
                    existingOwner = FieldOwner.PRIMARY,
                    attemptedOwner = owners[field] ?: fieldValue.owner,
                    attemptedValue = previewValue ?: fieldValue.value
                )
                rejectedByField.getOrPut(field) { mutableListOf() }.add(
                    mapOf(
                        "provider" to (preview?.provider?.name ?: sourceProviders[field]),
                        "sourceProvider" to sourceProviders[field],
                        "sourceRole" to SourceRole.RAIL_PREVIEW.name,
                        "reason" to "primary canonical field available"
                    )
                )
            }
            selectField(
                fields = fields,
                owners = owners,
                sourceRoles = sourceRoles,
                sourceProviders = sourceProviders,
                localization = localization,
                candidate = primary,
                field = field,
                fieldValue = fieldValue,
                selectedOwner = FieldOwner.PRIMARY
            )
        }

        secondary.forEach { candidate ->
            applyMissingCandidate(
                candidate = candidate,
                fields = fields,
                owners = owners,
                sourceRoles = sourceRoles,
                sourceProviders = sourceProviders,
                localization = localization,
                ignoredOverwrites = ignoredOverwrites,
                rejectedByField = rejectedByField
            )
        }

        return buildDocument(
            fields = fields,
            owners = owners,
            sourceRoles = sourceRoles,
            sourceProviders = sourceProviders,
            localization = localization,
            ignoredOverwrites = ignoredOverwrites,
            rejectedByField = rejectedByField,
            traceContentId = primary?.provider?.name ?: preview?.provider?.name ?: "UNKNOWN",
            fallbackSourceProvider = primary?.sourceProvider ?: preview?.sourceProvider ?: "UNKNOWN"
        )
    }

    private fun resolveInternal(
        preview: MetadataCandidate?,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>
    ): ResolvedMetadataDocument {
        val fields = linkedMapOf<ResolvedField, Any>()
        val owners = linkedMapOf<ResolvedField, FieldOwner>()
        val sourceRoles = linkedMapOf<ResolvedField, SourceRole>()
        val sourceProviders = linkedMapOf<ResolvedField, String>()
        val localization = linkedMapOf<ResolvedField, MetadataLocalizationFieldTrace>()
        val ignoredOverwrites = mutableListOf<IgnoredFieldOverwrite>()
        val rejectedByField = linkedMapOf<ResolvedField, MutableList<Map<String, Any?>>>()

        primary?.fields?.forEach { (field, fieldValue) ->
            selectField(
                fields = fields,
                owners = owners,
                sourceRoles = sourceRoles,
                sourceProviders = sourceProviders,
                localization = localization,
                candidate = primary,
                field = field,
                fieldValue = fieldValue,
                selectedOwner = FieldOwner.PRIMARY
            )
        }

        preview?.let { candidate ->
            applyMissingCandidate(
                candidate = candidate,
                fields = fields,
                owners = owners,
                sourceRoles = sourceRoles,
                sourceProviders = sourceProviders,
                localization = localization,
                ignoredOverwrites = ignoredOverwrites,
                rejectedByField = rejectedByField
            )
        }

        secondary.forEach { candidate ->
            applyMissingCandidate(
                candidate = candidate,
                fields = fields,
                owners = owners,
                sourceRoles = sourceRoles,
                sourceProviders = sourceProviders,
                localization = localization,
                ignoredOverwrites = ignoredOverwrites,
                rejectedByField = rejectedByField
            )
        }

        return buildDocument(
            fields = fields,
            owners = owners,
            sourceRoles = sourceRoles,
            sourceProviders = sourceProviders,
            localization = localization,
            ignoredOverwrites = ignoredOverwrites,
            rejectedByField = rejectedByField,
            traceContentId = primary?.provider?.name ?: preview?.provider?.name ?: "UNKNOWN",
            fallbackSourceProvider = primary?.sourceProvider ?: preview?.sourceProvider ?: "UNKNOWN"
        )
    }

    private fun buildDocument(
        fields: Map<ResolvedField, Any>,
        owners: Map<ResolvedField, FieldOwner>,
        sourceRoles: Map<ResolvedField, SourceRole>,
        sourceProviders: Map<ResolvedField, String>,
        localization: Map<ResolvedField, MetadataLocalizationFieldTrace>,
        ignoredOverwrites: List<IgnoredFieldOverwrite>,
        rejectedByField: Map<ResolvedField, List<Map<String, Any?>>>,
        traceContentId: String,
        fallbackSourceProvider: String
    ): ResolvedMetadataDocument {
        fields.forEach { (field, value) ->
            val owner = owners[field] ?: FieldOwner.PRIMARY
            val selectedProvider = sourceProviders[field] ?: fallbackSourceProvider
            val sourceRole = sourceRoles[field] ?: owner.defaultSourceRole()
            val replacedRailPreview = rejectedByField[field]
                ?.any { it["reason"] == "primary canonical field available" } == true
            val rule = if (replacedRailPreview) {
                "primary canonical field replaces rail preview"
            } else if (sourceRole == SourceRole.RAIL_PREVIEW) {
                "rail preview fills field before canonical hydration"
            } else if (owner == FieldOwner.PRIMARY) {
                "primary always wins"
            } else {
                "secondary fills missing field"
            }
            val valueStr = value.toString()
            val preview = if (valueStr.length > 80) valueStr.substring(0, 80) + "…" else valueStr

            traceEvents.emitFieldSelected(
                contentId = traceContentId,
                field = field.name,
                selectedProvider = selectedProvider,
                sourceRole = sourceRole.name,
                valuePreview = preview,
                ownershipRule = rule,
                rejectedCandidates = rejectedByField[field] ?: emptyList()
            )
        }

        return ResolvedMetadataDocument(
            canonicalId = fields[ResolvedField.CANONICAL_ID] as? String,
            title = fields[ResolvedField.TITLE] as? String,
            overview = fields[ResolvedField.OVERVIEW] as? String,
            poster = fields[ResolvedField.POSTER] as? String,
            backdrop = fields[ResolvedField.BACKDROP] as? String,
            logo = fields[ResolvedField.LOGO] as? String,
            rating = fields[ResolvedField.RATING],
            runtimeMinutes = fields[ResolvedField.RUNTIME] as? Int,
            fieldOwners = owners,
            ignoredOverwrites = ignoredOverwrites,
            localization = localization,
            sourceRoles = sourceRoles,
            sourceProviders = sourceProviders
        )
    }

    private fun applyMissingCandidate(
        candidate: MetadataCandidate,
        fields: MutableMap<ResolvedField, Any>,
        owners: MutableMap<ResolvedField, FieldOwner>,
        sourceRoles: MutableMap<ResolvedField, SourceRole>,
        sourceProviders: MutableMap<ResolvedField, String>,
        localization: MutableMap<ResolvedField, MetadataLocalizationFieldTrace>,
        ignoredOverwrites: MutableList<IgnoredFieldOverwrite>,
        rejectedByField: MutableMap<ResolvedField, MutableList<Map<String, Any?>>>
    ) {
        candidate.fields.forEach { (field, fieldValue) ->
            val existingOwner = owners[field]
            if (existingOwner == null) {
                selectField(
                    fields = fields,
                    owners = owners,
                    sourceRoles = sourceRoles,
                    sourceProviders = sourceProviders,
                    localization = localization,
                    candidate = candidate,
                    field = field,
                    fieldValue = fieldValue,
                    selectedOwner = fieldValue.owner
                )
            } else {
                ignoredOverwrites += IgnoredFieldOverwrite(
                    field = field,
                    existingOwner = existingOwner,
                    attemptedOwner = fieldValue.owner,
                    attemptedValue = fieldValue.value
                )
                rejectedByField.getOrPut(field) { mutableListOf() }.add(
                    mapOf(
                        "provider" to candidate.provider.name,
                        "sourceProvider" to candidate.sourceProvider,
                        "sourceRole" to effectiveSourceRole(candidate, fieldValue).name,
                        "reason" to "field already filled"
                    )
                )
            }
        }
    }

    private fun selectField(
        fields: MutableMap<ResolvedField, Any>,
        owners: MutableMap<ResolvedField, FieldOwner>,
        sourceRoles: MutableMap<ResolvedField, SourceRole>,
        sourceProviders: MutableMap<ResolvedField, String>,
        localization: MutableMap<ResolvedField, MetadataLocalizationFieldTrace>,
        candidate: MetadataCandidate,
        field: ResolvedField,
        fieldValue: FieldValue,
        selectedOwner: FieldOwner
    ) {
        fields[field] = fieldValue.value
        owners[field] = selectedOwner
        sourceRoles[field] = effectiveSourceRole(candidate, fieldValue)
        sourceProviders[field] = candidate.sourceProvider
        candidate.localization[field]?.let { localization[field] = it }
    }

    private fun effectiveSourceRole(
        candidate: MetadataCandidate,
        fieldValue: FieldValue
    ): SourceRole {
        return if (
            candidate.sourceRole != SourceRole.PRIMARY &&
            fieldValue.sourceRole == fieldValue.owner.defaultSourceRole()
        ) {
            candidate.sourceRole
        } else {
            fieldValue.sourceRole
        }
    }
}
