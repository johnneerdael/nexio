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
        secondary: List<MetadataCandidate>,
        requestContentId: String? = null
    ): ResolvedMetadataDocument = resolveInternal(
        preview = null,
        primary = primary,
        secondary = secondary,
        requestContentId = requestContentId
    )

    fun resolveWithPreview(
        preview: MetadataCandidate?,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>,
        requestContentId: String? = null
    ): ResolvedMetadataDocument {
        return resolvePreviewFirst(
            preview = preview,
            primary = primary,
            secondary = secondary,
            requestContentId = requestContentId
        )
    }

    private fun resolvePreviewFirst(
        preview: MetadataCandidate?,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>,
        requestContentId: String? = null
    ): ResolvedMetadataDocument {
        val fields = linkedMapOf<ResolvedField, Any>()
        val owners = linkedMapOf<ResolvedField, FieldOwner>()
        val providers = linkedMapOf<ResolvedField, String>()
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
                providers = providers,
                sourceRoles = sourceRoles,
                sourceProviders = sourceProviders,
                localization = localization,
                ignoredOverwrites = ignoredOverwrites,
                rejectedByField = rejectedByField
            )
        }

        primary?.fields?.forEach { (field, fieldValue) ->
            val previewSourceRole = sourceRoles[field]
            if (previewSourceRole.isPreviewRole()) {
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
                        "sourceRole" to previewSourceRole?.name,
                        "reason" to "primary canonical field available"
                    )
                )
            }
            selectField(
                fields = fields,
                owners = owners,
                providers = providers,
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
                providers = providers,
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
            traceContentId = requestContentId ?: primary?.provider?.name ?: preview?.provider?.name ?: "UNKNOWN",
            fallbackSourceProvider = primary?.sourceProvider ?: preview?.sourceProvider ?: "UNKNOWN"
        )
    }

    private fun resolveInternal(
        preview: MetadataCandidate?,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>,
        requestContentId: String? = null
    ): ResolvedMetadataDocument {
        val fields = linkedMapOf<ResolvedField, Any>()
        val owners = linkedMapOf<ResolvedField, FieldOwner>()
        val providers = linkedMapOf<ResolvedField, String>()
        val sourceRoles = linkedMapOf<ResolvedField, SourceRole>()
        val sourceProviders = linkedMapOf<ResolvedField, String>()
        val localization = linkedMapOf<ResolvedField, MetadataLocalizationFieldTrace>()
        val ignoredOverwrites = mutableListOf<IgnoredFieldOverwrite>()
        val rejectedByField = linkedMapOf<ResolvedField, MutableList<Map<String, Any?>>>()

        primary?.fields?.forEach { (field, fieldValue) ->
            selectField(
                fields = fields,
                owners = owners,
                providers = providers,
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
                providers = providers,
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
                providers = providers,
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
            traceContentId = requestContentId ?: primary?.provider?.name ?: preview?.provider?.name ?: "UNKNOWN",
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
            val sourceRole = sourceRoles[field] ?: defaultSourceRole(owner)
            val replacedPreview = rejectedByField[field]
                ?.firstOrNull { it["reason"] == "primary canonical field available" }
            val resolverReplacedRailPreview = rejectedByField[field]
                ?.any { it["reason"] == "dedicated resolver field replaces rail preview" } == true
            val rule = if (replacedPreview != null) {
                "primary canonical field replaces ${previewRoleLabel(replacedPreview["sourceRole"] as? String)}"
            } else if (resolverReplacedRailPreview) {
                "dedicated resolver field replaces rail preview"
            } else if (sourceRole == SourceRole.RAIL_PREVIEW) {
                "rail preview fills field before canonical hydration"
            } else if (sourceRole == SourceRole.ADDON_PREVIEW) {
                "addon preview fills until canonical arrives"
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

        @Suppress("UNCHECKED_CAST")
        return ResolvedMetadataDocument(
            canonicalId = fields[ResolvedField.CANONICAL_ID] as? String,
            title = fields[ResolvedField.TITLE] as? String,
            overview = fields[ResolvedField.OVERVIEW] as? String,
            poster = fields[ResolvedField.POSTER] as? String,
            backdrop = fields[ResolvedField.BACKDROP] as? String,
            logo = fields[ResolvedField.LOGO] as? String,
            rating = fields[ResolvedField.RATING],
            runtimeMinutes = fields[ResolvedField.RUNTIME] as? Int,
            genres = (fields[ResolvedField.GENRES] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            releaseDate = fields[ResolvedField.RELEASE_DATE] as? String,
            ageRating = fields[ResolvedField.AGE_RATING] as? String,
            countries = (fields[ResolvedField.COUNTRIES] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            language = fields[ResolvedField.LANGUAGE] as? String,
            castMembers = (fields[ResolvedField.CAST] as? List<*>)
                ?.filterIsInstance<com.nexio.tv.domain.model.MetaCastMember>() ?: emptyList(),
            productionCompanies = (fields[ResolvedField.ORGANIZATION_LIST] as? List<*>)
                ?.filterIsInstance<com.nexio.tv.domain.model.MetaCompany>()
                ?.filter { it.kind == com.nexio.tv.domain.model.MetaCompanyKind.COMPANY } ?: emptyList(),
            networks = (fields[ResolvedField.ORGANIZATION_LIST] as? List<*>)
                ?.filterIsInstance<com.nexio.tv.domain.model.MetaCompany>()
                ?.filter { it.kind == com.nexio.tv.domain.model.MetaCompanyKind.NETWORK } ?: emptyList(),
            airsTime = fields[ResolvedField.AIRS_TIME] as? String,
            originalCountry = fields[ResolvedField.ORIGINAL_COUNTRY] as? String,
            originalNetwork = fields[ResolvedField.ORIGINAL_NETWORK] as? String,
            latestNetwork = fields[ResolvedField.LATEST_NETWORK] as? String,
            platformName = fields[ResolvedField.PLATFORM_NAME] as? String,
            fieldOwners = owners,
            ignoredOverwrites = ignoredOverwrites,
            localization = localization,
            sourceRoles = sourceRoles,
            sourceProviders = sourceProviders
        )
    }

    private fun SourceRole?.isPreviewRole(): Boolean =
        this == SourceRole.RAIL_PREVIEW || this == SourceRole.ADDON_PREVIEW

    private fun previewRoleLabel(sourceRole: String?): String =
        when (sourceRole) {
            SourceRole.RAIL_PREVIEW.name -> "rail preview"
            SourceRole.ADDON_PREVIEW.name -> "addon preview"
            else -> "preview"
        }

    private fun defaultSourceRole(owner: FieldOwner): SourceRole =
        when (owner) {
            FieldOwner.PRIMARY -> SourceRole.PRIMARY
            FieldOwner.ARTWORK -> SourceRole.ARTWORK
            FieldOwner.RATING -> SourceRole.RATING
            FieldOwner.TRACKING -> SourceRole.TRACKING
            FieldOwner.REVIEWS,
            FieldOwner.TRAILERS,
            FieldOwner.RECOMMENDATIONS,
            FieldOwner.ORGANIZATION_PERSON -> SourceRole.SECONDARY
        }

    private fun applyMissingCandidate(
        candidate: MetadataCandidate,
        fields: MutableMap<ResolvedField, Any>,
        owners: MutableMap<ResolvedField, FieldOwner>,
        providers: MutableMap<ResolvedField, String>,
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
                    providers = providers,
                    sourceRoles = sourceRoles,
                    sourceProviders = sourceProviders,
                    localization = localization,
                    candidate = candidate,
                    field = field,
                    fieldValue = fieldValue,
                    selectedOwner = fieldValue.owner
                )
            } else if (canReplaceRailPreview(field, sourceRoles[field], fieldValue)) {
                ignoredOverwrites += IgnoredFieldOverwrite(
                    field = field,
                    existingOwner = existingOwner,
                    attemptedOwner = existingOwner,
                    attemptedValue = fields[field] ?: fieldValue.value
                )
                rejectedByField.getOrPut(field) { mutableListOf() }.add(
                    mapOf(
                        "provider" to (providers[field] ?: sourceProviders[field]),
                        "sourceProvider" to sourceProviders[field],
                        "sourceRole" to SourceRole.RAIL_PREVIEW.name,
                        "reason" to "dedicated resolver field replaces rail preview"
                    )
                )
                selectField(
                    fields = fields,
                    owners = owners,
                    providers = providers,
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

    private fun canReplaceRailPreview(
        field: ResolvedField,
        existingSourceRole: SourceRole?,
        fieldValue: FieldValue
    ): Boolean {
        if (existingSourceRole != SourceRole.RAIL_PREVIEW) return false

        return when (field) {
            ResolvedField.POSTER,
            ResolvedField.BACKDROP,
            ResolvedField.LOGO -> fieldValue.owner == FieldOwner.ARTWORK
            ResolvedField.RATING -> fieldValue.owner == FieldOwner.RATING
            else -> false
        }
    }

    private fun selectField(
        fields: MutableMap<ResolvedField, Any>,
        owners: MutableMap<ResolvedField, FieldOwner>,
        providers: MutableMap<ResolvedField, String>,
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
        providers[field] = candidate.provider.name
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
            fieldValue.sourceRole == defaultSourceRole(fieldValue.owner)
        ) {
            candidate.sourceRole
        } else {
            fieldValue.sourceRole
        }
    }
}
