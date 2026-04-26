package com.nexio.tv.core.integration

object RailKeyFactory {
    fun homeCatalog(profileId: Int, catalogId: String): String =
        "profile:$profileId:home:catalog:${catalogId.trim()}"

    fun homeCatalogNamespace(profileId: Int): String =
        "profile:$profileId:home:catalog:"

    fun continueWatching(profileId: Int): String =
        "profile:$profileId:home:continue_watching"

    fun traktLibrary(profileId: Int, listKey: String): String =
        "profile:$profileId:library:trakt:${listKey.trim()}"

    fun traktLibraryNamespace(profileId: Int): String =
        "profile:$profileId:library:trakt:"

    fun simklLibrary(profileId: Int, listKey: String): String =
        "profile:$profileId:library:simkl:${listKey.trim()}"

    fun simklLibraryNamespace(profileId: Int): String =
        "profile:$profileId:library:simkl:"
}
