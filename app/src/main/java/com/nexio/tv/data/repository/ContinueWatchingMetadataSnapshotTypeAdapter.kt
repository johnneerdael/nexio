package com.nexio.tv.data.repository

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.ui.screens.home.emptySlotsAt
import com.nexio.tv.ui.screens.home.toResolvedFieldSlots
import java.lang.reflect.Type

/**
 * Phase 3.8 full — detects v1 vs v2 ContinueWatchingMetadataSnapshot records
 * by field presence and projects v1 -> v2 at FIRST_PAINT rank.
 *
 * v1 records have clickTimeDisplayMetadata: HomeDisplayMetadata (legacy
 * carrier). v2 records have clickTimeSlots: ResolvedDisplayFieldSlots
 * (typed carrier). Future writes always emit v2 via default gson reflection
 * (the data class declaration is the v2 shape).
 *
 * After every user has upgraded once and re-flushed their CW snapshot file,
 * the v1 detection branch is dead code that can be deleted in a follow-up
 * cleanup. Until then, this adapter is the safe-upgrade migration shim.
 */
class ContinueWatchingMetadataSnapshotTypeAdapter : JsonDeserializer<ContinueWatchingMetadataSnapshot> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): ContinueWatchingMetadataSnapshot {
        val obj = json.asJsonObject
        val nowMs = System.currentTimeMillis()
        val clickTimeSlots: ResolvedDisplayFieldSlots = when {
            obj.has("clickTimeSlots") && !obj.get("clickTimeSlots").isJsonNull -> {
                context.deserialize(obj.get("clickTimeSlots"), ResolvedDisplayFieldSlots::class.java)
            }
            obj.has("clickTimeDisplayMetadata") && !obj.get("clickTimeDisplayMetadata").isJsonNull -> {
                runCatching {
                    val legacy: HomeDisplayMetadata = context.deserialize(
                        obj.get("clickTimeDisplayMetadata"),
                        HomeDisplayMetadata::class.java
                    )
                    legacy.toResolvedFieldSlots(
                        nowMs = nowMs,
                        rank = DisplaySourceRank.FIRST_PAINT,
                    )
                }.getOrDefault(emptySlotsAt(nowMs))
            }
            else -> emptySlotsAt(nowMs)
        }
        return ContinueWatchingMetadataSnapshot(
            routingVersion = obj.get("routingVersion").asInt,
            parentId = obj.get("parentId").asString,
            primaryProvider = context.deserialize(
                obj.get("primaryProvider"),
                MetadataPrimaryProvider::class.java
            ),
            decisionReason = context.deserialize(
                obj.get("decisionReason"),
                MetadataDecisionReason::class.java
            ),
            clickTimeSlots = clickTimeSlots
        )
    }
}
