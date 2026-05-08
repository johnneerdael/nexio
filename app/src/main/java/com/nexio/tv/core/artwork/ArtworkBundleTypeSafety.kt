package com.nexio.tv.core.artwork

fun ArtworkDisplayRef?.takeIfImageType(expectedType: ArtworkType): ArtworkDisplayRef? =
    this?.takeIf { ref -> ref.imageType == expectedType }

fun ArtworkBundle.enforceArtworkTypeBoundaries(): ArtworkBundle =
    ArtworkBundle(
        poster = poster.takeIfImageType(ArtworkType.POSTER),
        backdrop = backdrop.takeIfImageType(ArtworkType.BACKDROP),
        logo = logo.takeIfImageType(ArtworkType.LOGO),
        thumbnail = thumbnail.takeIfImageType(ArtworkType.THUMBNAIL)
    )

fun ArtworkBundle.emptyOrNull(): ArtworkBundle? =
    takeUnless { bundle ->
        bundle.poster == null &&
            bundle.backdrop == null &&
            bundle.logo == null &&
            bundle.thumbnail == null
    }
