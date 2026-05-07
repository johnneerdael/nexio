package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.AddonParserPreset

private val NEXIO_TORII_IDS = setOf("org.community.nexiotorii")
private val NEXIO_NAGARE_IDS = setOf("org.community.nexionagare")

fun resolveAutoPreset(manifestId: String?, userPick: AddonParserPreset): AddonParserPreset {
    if (userPick != AddonParserPreset.GENERIC) return userPick
    val id = manifestId?.lowercase() ?: return AddonParserPreset.GENERIC
    return when {
        id in NEXIO_TORII_IDS -> AddonParserPreset.NEXIO_TORII
        id in NEXIO_NAGARE_IDS -> AddonParserPreset.NEXIO_NAGARE
        else -> AddonParserPreset.GENERIC
    }
}
