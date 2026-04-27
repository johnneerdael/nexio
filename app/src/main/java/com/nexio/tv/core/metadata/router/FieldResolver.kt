package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FieldResolver @Inject constructor() {
    fun resolve(
        primary: MetadataCandidate,
        secondary: List<MetadataCandidate>
    ): ResolvedMetadataDocument {
        val fields = linkedMapOf<ResolvedField, Any>()
        val owners = linkedMapOf<ResolvedField, FieldOwner>()
        val localization = linkedMapOf<ResolvedField, MetadataLocalizationFieldTrace>()
        val ignoredOverwrites = mutableListOf<IgnoredFieldOverwrite>()

        primary.fields.forEach { (field, fieldValue) ->
            fields[field] = fieldValue.value
            owners[field] = FieldOwner.PRIMARY
            primary.localization[field]?.let { localization[field] = it }
        }

        secondary.forEach { candidate ->
            candidate.fields.forEach { (field, fieldValue) ->
                val existingOwner = owners[field]
                if (existingOwner == null) {
                    fields[field] = fieldValue.value
                    owners[field] = fieldValue.owner
                    candidate.localization[field]?.let { localization[field] = it }
                } else {
                    ignoredOverwrites += IgnoredFieldOverwrite(
                        field = field,
                        existingOwner = existingOwner,
                        attemptedOwner = fieldValue.owner,
                        attemptedValue = fieldValue.value
                    )
                }
            }
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
            localization = localization
        )
    }
}
