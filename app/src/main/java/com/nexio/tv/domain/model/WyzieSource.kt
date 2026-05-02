package com.nexio.tv.domain.model

/**
 * The nine Wyzie subtitle sources Nexio routes to.
 *
 * `apiName` is the wire value sent in the `source=` query parameter to https://sub.wyzie.io/search.
 * `displayName` is the human-readable label rendered in the subtitle picker as "Wyzie · <displayName>".
 *
 * yify is intentionally absent — it returns SRT inside a ZIP archive, which Media3 cannot unwrap.
 */
enum class WyzieSource(val apiName: String, val displayName: String) {
    OPENSUBTITLES("opensubtitles", "OpenSubtitles"),
    SUBDL("subdl", "SubDL"),
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
