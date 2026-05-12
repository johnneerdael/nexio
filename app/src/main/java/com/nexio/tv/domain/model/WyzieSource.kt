package com.nexio.tv.domain.model

/**
 * Wyzie subtitle sources Nexio knows about.
 *
 * `apiName` is the wire value sent in the `source=` query parameter to https://sub.wyzie.io/search.
 * `displayName` is the human-readable label rendered in the subtitle picker as "Wyzie · <displayName>".
 *
 * Routing currently asks Wyzie for [OPENSUBTITLES], [SUBDL], and [TVSUBTITLES] regardless of
 * content type (see [com.nexio.tv.data.integration.subtitles.wyzie.WyzieSourceRouter]). The
 * other values exist so [WyzieResultMapper] can still parse historical or pro-tier responses.
 *
 * yify is intentionally absent — it returns SRT inside a ZIP archive, which Media3 cannot unwrap.
 */
enum class WyzieSource(val apiName: String, val displayName: String) {
    OPENSUBTITLES("opensubtitles", "OpenSubtitles"),
    SUBDL("subdl", "SubDL"),
    TVSUBTITLES("tvsubtitles", "TVSubtitles"),
    SUBF2M("subf2m", "Subf2m"),
    PODNAPISI("podnapisi", "Podnapisi"),
    GESTDOWN("gestdown", "Gestdown"),
    ANIMETOSHO("animetosho", "AnimeTosho"),
    JIMAKU("jimaku", "Jimaku"),
    KITSUNEKKO("kitsunekko", "Kitsunekko"),
    AJATTTOOLS("ajatttools", "AjattTools"),
    ;

    companion object {
        private val byApiName: Map<String, WyzieSource> = values().associateBy { it.apiName }

        fun fromApiNameOrNull(apiName: String?): WyzieSource? =
            apiName?.lowercase()?.let { byApiName[it] }
    }
}
