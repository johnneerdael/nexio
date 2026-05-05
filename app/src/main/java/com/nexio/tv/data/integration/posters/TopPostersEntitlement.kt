package com.nexio.tv.data.integration.posters

import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import org.json.JSONObject

object TopPostersEntitlementParser {
    fun parse(
        body: String,
        verifiedAtMs: Long,
        ttlMs: Long
    ): TopPostersEntitlementSnapshot {
        val json = JSONObject(body)
        val features = json.optJSONObject("tier_info")
            ?.optJSONObject("features")

        return TopPostersEntitlementSnapshot(
            valid = json.optBoolean("valid", false),
            isActive = json.optBoolean("is_active", false),
            tier = json.optInt("tier", 0),
            tierName = json.optString("tier_name", ""),
            episodeThumbnails = features?.optBoolean("episode_thumbnails", false) ?: false,
            verifiedAtMs = verifiedAtMs,
            expiresAtMs = verifiedAtMs + ttlMs
        )
    }

    fun serialize(snapshot: TopPostersEntitlementSnapshot): String =
        JSONObject()
            .put("valid", snapshot.valid)
            .put("is_active", snapshot.isActive)
            .put("tier", snapshot.tier)
            .put("tier_name", snapshot.tierName)
            .put("episode_thumbnails", snapshot.episodeThumbnails)
            .put("verified_at_ms", snapshot.verifiedAtMs)
            .put("expires_at_ms", snapshot.expiresAtMs)
            .toString()

    fun parseCachedSnapshot(body: String): TopPostersEntitlementSnapshot? =
        runCatching {
            val json = JSONObject(body)
            TopPostersEntitlementSnapshot(
                valid = json.optBoolean("valid", false),
                isActive = json.optBoolean("is_active", false),
                tier = json.optInt("tier", 0),
                tierName = json.optString("tier_name", ""),
                episodeThumbnails = json.optBoolean("episode_thumbnails", false),
                verifiedAtMs = json.getLong("verified_at_ms"),
                expiresAtMs = json.getLong("expires_at_ms")
            )
        }.getOrNull()
}
