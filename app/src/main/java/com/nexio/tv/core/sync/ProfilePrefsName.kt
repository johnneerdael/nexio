package com.nexio.tv.core.sync

fun profilePrefsName(baseName: String, profileId: Int): String =
    if (profileId == 1) baseName else "${baseName}_p${profileId}"
