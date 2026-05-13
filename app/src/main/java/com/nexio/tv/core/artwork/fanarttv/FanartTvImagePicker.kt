package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage

class FanartTvImagePicker {

    fun pickFor(
        doc: FanartTvDocument,
        callType: FanartTvCallId.Type,
        artworkType: ArtworkType
    ): String? = when (callType) {
        FanartTvCallId.Type.MOVIE -> when (artworkType) {
            ArtworkType.LOGO -> pickEnglishHighest(doc.hdMovieLogo)
            ArtworkType.BACKDROP -> pickHighestAnyLang(doc.movieBackground)
            ArtworkType.POSTER -> pickEnglishHighest(doc.moviePoster)
            ArtworkType.THUMBNAIL -> null
        }
        FanartTvCallId.Type.TV -> when (artworkType) {
            ArtworkType.LOGO -> pickEnglishHighest(doc.hdTvLogo)
            ArtworkType.BACKDROP -> pickHighestAnyLang(doc.showBackground)
            ArtworkType.POSTER -> pickEnglishHighest(doc.tvPoster)
            ArtworkType.THUMBNAIL -> null
        }
    }

    private fun pickEnglishHighest(images: List<FanartTvImage>?): String? =
        images
            ?.asSequence()
            ?.filter { it.url?.isNotBlank() == true }
            ?.filter { it.lang?.equals("en", ignoreCase = true) == true }
            ?.sortedWith(compareByDescending<FanartTvImage> { it.likesAsInt() }.thenBy { it.idAsLongOrMax() })
            ?.firstOrNull()
            ?.url

    private fun pickHighestAnyLang(images: List<FanartTvImage>?): String? =
        images
            ?.asSequence()
            ?.filter { it.url?.isNotBlank() == true }
            ?.sortedWith(compareByDescending<FanartTvImage> { it.likesAsInt() }.thenBy { it.idAsLongOrMax() })
            ?.firstOrNull()
            ?.url

    private fun FanartTvImage.likesAsInt(): Int = likes?.toIntOrNull() ?: 0
    private fun FanartTvImage.idAsLongOrMax(): Long = id?.toLongOrNull() ?: Long.MAX_VALUE
}
