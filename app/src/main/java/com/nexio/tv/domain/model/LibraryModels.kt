package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LibraryEntry(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: PosterShape = PosterShape.POSTER,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val addonBaseUrl: String?,
    val listKeys: Set<String> = emptySet(),
    val listedAt: Long = 0L,
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    val directPlaybackUrl: String? = null,
    val playbackHeaders: Map<String, String>? = null,
    val playbackStreamName: String? = null,
    val playbackFilename: String? = null,
    val playbackSizeBytes: Long? = null
) {
    val displayPoster: String?
        get() = toMetaPreview().displayPoster

    val displayBackground: String?
        get() = toMetaPreview().displayBackground

    val displayLogo: String?
        get() = toMetaPreview().displayLogo

    fun toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.fromString(type),
            rawType = type,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres
        )
    }
}

enum class LibrarySourceMode {
    LOCAL,
    TRAKT,
    SIMKL,
    DEBRID
}

enum class LibraryProviderSelection(
    val label: String
) {
    UNIFIED("Unified"),
    TRAKT("Trakt"),
    SIMKL("SIMKL"),
    MDBLIST("MDBList"),
    REAL_DEBRID("Real-Debrid"),
    PREMIUMIZE("Premiumize"),
    TORBOX("TorBox"),
    EASY_DEBRID("EasyDebrid");

    val isTracker: Boolean
        get() = this == TRAKT || this == SIMKL || this == MDBLIST

    val isDebrid: Boolean
        get() = this == REAL_DEBRID || this == PREMIUMIZE || this == TORBOX || this == EASY_DEBRID
}

@Immutable
data class LibraryProviderOption(
    val provider: LibraryProviderSelection,
    val label: String = provider.label
)

enum class LibraryListManagementMode {
    NONE,
    TRAKT_PERSONAL,
    SIMKL_STATUS,
    MDBLIST_STATIC
}

enum class LibraryEmptyReason {
    NONE,
    UNIFIED_NEEDS_TRACKER_AUTH,
    PROVIDER_EMPTY,
    PROVIDER_UNAVAILABLE
}

enum class TraktListPrivacy(val apiValue: String) {
    PRIVATE("private"),
    LINK("link"),
    FRIENDS("friends"),
    PUBLIC("public");

    companion object {
        fun fromApi(value: String?): TraktListPrivacy {
            return entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: PRIVATE
        }
    }
}

@Immutable
data class LibraryListTab(
    val key: String,
    val title: String,
    val type: Type,
    val traktListId: Long? = null,
    val slug: String? = null,
    val description: String? = null,
    val privacy: TraktListPrivacy? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
    val mdbListId: Long? = null,
    val mdbListSlug: String? = null,
    val mdbListType: String? = null,
    val isMutableStaticList: Boolean = false
) {
    enum class Type {
        WATCHLIST,
        PERSONAL,
        SERVICE
    }
}

@Immutable
data class LibraryProviderSnapshot(
    val provider: LibraryProviderSelection,
    val sourceMode: LibrarySourceMode,
    val items: List<LibraryEntry> = emptyList(),
    val listTabs: List<LibraryListTab> = emptyList(),
    val selectedListKey: String? = null,
    val supportsLists: Boolean = false,
    val supportsListManagement: Boolean = false,
    val listManagementMode: LibraryListManagementMode = LibraryListManagementMode.NONE,
    val emptyReason: LibraryEmptyReason = LibraryEmptyReason.NONE,
    val listSelectorLabel: String = "N/A"
)

@Immutable
data class ListMembershipSnapshot(
    val listMembership: Map<String, Boolean> = emptyMap()
)

@Immutable
data class ListMembershipChanges(
    val desiredMembership: Map<String, Boolean>
)

@Immutable
data class LibraryEntryInput(
    val itemId: String,
    val itemType: String,
    val title: String,
    val year: Int? = null,
    val traktId: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val poster: String? = null,
    val posterShape: PosterShape = PosterShape.POSTER,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null
)
