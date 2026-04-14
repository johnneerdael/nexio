package com.nexio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val avatarUrl: String? = null,
    val avatarId: String? = null,       // D-02: Supabase avatar catalog ref
    val pinEnabled: Boolean = false     // D-03: server-side PIN lock state
) {
    val isPrimary: Boolean get() = id == 1
}
