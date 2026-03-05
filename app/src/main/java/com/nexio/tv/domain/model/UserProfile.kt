package com.nexio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false
) {
    val isPrimary: Boolean get() = id == 1
}
