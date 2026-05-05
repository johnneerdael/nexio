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
}
