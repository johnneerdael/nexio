package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.R
import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.WyzieSource

/**
 * Maps a [WyzieSubtitleDto] to the domain [Subtitle] type.
 *
 * - `addonName`  = "Wyzie" or "Wyzie · <displayName|raw>"
 * - `addonLogo`  = `android.resource://com.nexio.tv/<drawable-id>` for the matched source,
 *                  or the generic Wyzie icon otherwise.
 * - `id`         = `wyzie:<dto.id>` (namespaced to avoid collision with addon ids).
 *
 * The mapper is stateless; resource ids are looked up by [WyzieSource], so there is no
 * Context/Resources dependency at unit-test time.
 */
object WyzieResultMapper {

    private const val PACKAGE = "com.nexio.tv"

    private fun resourceUri(resId: Int): String =
        "android.resource://$PACKAGE/$resId"

    private fun logoFor(source: WyzieSource?): String = when (source) {
        WyzieSource.OPENSUBTITLES -> resourceUri(R.drawable.ic_wyzie_opensubtitles)
        WyzieSource.SUBDL -> resourceUri(R.drawable.ic_wyzie_subdl)
        WyzieSource.TVSUBTITLES -> resourceUri(R.drawable.ic_wyzie)
        WyzieSource.SUBF2M -> resourceUri(R.drawable.ic_wyzie_subf2m)
        WyzieSource.PODNAPISI -> resourceUri(R.drawable.ic_wyzie_podnapisi)
        WyzieSource.GESTDOWN -> resourceUri(R.drawable.ic_wyzie_gestdown)
        WyzieSource.ANIMETOSHO -> resourceUri(R.drawable.ic_wyzie_animetosho)
        WyzieSource.JIMAKU -> resourceUri(R.drawable.ic_wyzie_jimaku)
        WyzieSource.KITSUNEKKO -> resourceUri(R.drawable.ic_wyzie_kitsunekko)
        WyzieSource.AJATTTOOLS -> resourceUri(R.drawable.ic_wyzie_ajatttools)
        null -> resourceUri(R.drawable.ic_wyzie)
    }

    fun map(dto: WyzieSubtitleDto): Subtitle {
        val matched = WyzieSource.fromApiNameOrNull(dto.source)
        val addonName = when {
            matched != null -> "Wyzie · ${matched.displayName}"
            dto.source.isNullOrBlank() -> "Wyzie"
            else -> "Wyzie · ${dto.source}"
        }
        return Subtitle(
            id = "wyzie:${dto.id}",
            url = dto.url,
            lang = dto.language,
            addonName = addonName,
            addonLogo = logoFor(matched),
        )
    }

    fun mapAll(dtos: List<WyzieSubtitleDto>): List<Subtitle> = dtos.map(::map)
}
