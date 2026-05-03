package com.nexio.tv.core.integration

object IntegrationHeaderPolicies {
    const val JSON_BODY_NO_AUTH_V1 = "json-body-no-auth-v1"
    const val PUBLIC_JSON_V1 = "public-json-v1"
    const val GRAPHQL_JSON_V1 = "graphql-json-v1"
    const val TMDB_JSON_V1 = "tmdb-json-v1"
    const val TVDB_JSON_BEARER_V1 = "tvdb-json-bearer-v1"
    const val TRAKT_JSON_V2 = "trakt-json-v2"
    const val SIMKL_JSON_V1 = "simkl-json-v1"
    const val IMAGE_FETCH_DEFAULT_V1 = "image-fetch-default-v1"
    const val RPDB_JSON_API_KEY_V1 = "rpdb-json-api-key-v1"
    const val RPDB_IMAGE_PATH_KEY_V1 = "rpdb-image-path-key-v1"
    const val TOP_POSTERS_IMAGE_PATH_KEY_V1 = "topposters-image-path-key-v1"
    const val TOP_POSTERS_THUMBNAIL_V1 = "topposters-thumbnail-v1"
    const val INTRODB_JSON_OPTIONAL_BEARER_V1 = "introdb-json-optional-bearer-v1"
    const val MDBLIST_API_KEY_V1 = "mdblist-api-key-v1"
    const val OMDB_QUERY_API_KEY_V1 = "omdb-query-api-key-v1"
    const val CUSTOM_IMDB_JSON_V1 = "custom-imdb-json-v1"
    const val ADDON_JSON_V1 = "addon-json-v1"
    const val COLLECTOR_JSON_V1 = "collector-json-v1"
    const val GITHUB_JSON_V1 = "github-json-v1"
    const val YOUTUBE_HTML_V1 = "youtube-html-v1"
    const val SUBTITLE_PROVIDER_V1 = "subtitle-provider-v1"
    const val REAL_DEBRID_JSON_TOKEN_V1 = "real_debrid-json-token-v1"
    const val PREMIUMIZE_JSON_TOKEN_V1 = "premiumize-json-token-v1"
    const val TORBOX_JSON_TOKEN_V1 = "torbox-json-token-v1"
    const val EASY_DEBRID_JSON_TOKEN_V1 = "easy_debrid-json-token-v1"

    fun tokenPolicyFor(provider: IntegrationProvider): String =
        when (provider) {
            IntegrationProvider.REAL_DEBRID -> REAL_DEBRID_JSON_TOKEN_V1
            IntegrationProvider.PREMIUMIZE -> PREMIUMIZE_JSON_TOKEN_V1
            IntegrationProvider.TORBOX -> TORBOX_JSON_TOKEN_V1
            IntegrationProvider.EASY_DEBRID -> EASY_DEBRID_JSON_TOKEN_V1
            else -> error("No token header policy for $provider")
        }

    fun defaultFor(provider: IntegrationProvider, apiShapeId: String): String =
        when (provider) {
            IntegrationProvider.TMDB -> TMDB_JSON_V1
            IntegrationProvider.TVDB -> if (apiShapeId == TvdbApiShapes.LOGIN) JSON_BODY_NO_AUTH_V1 else TVDB_JSON_BEARER_V1
            IntegrationProvider.TRAKT -> TRAKT_JSON_V2
            IntegrationProvider.SIMKL -> SIMKL_JSON_V1
            IntegrationProvider.KITSU,
            IntegrationProvider.ANISKIP,
            IntegrationProvider.ARM -> PUBLIC_JSON_V1
            IntegrationProvider.ANIMESKIP -> GRAPHQL_JSON_V1
            IntegrationProvider.RPDB,
            IntegrationProvider.TOP_POSTERS -> IMAGE_FETCH_DEFAULT_V1
            IntegrationProvider.THEINTRODB -> INTRODB_JSON_OPTIONAL_BEARER_V1
            IntegrationProvider.MDBLIST -> MDBLIST_API_KEY_V1
            IntegrationProvider.OMDB -> OMDB_QUERY_API_KEY_V1
            IntegrationProvider.CUSTOM_IMDB -> CUSTOM_IMDB_JSON_V1
            IntegrationProvider.REAL_DEBRID,
            IntegrationProvider.PREMIUMIZE,
            IntegrationProvider.TORBOX,
            IntegrationProvider.EASY_DEBRID -> tokenPolicyFor(provider)
            IntegrationProvider.ADDON -> ADDON_JSON_V1
            IntegrationProvider.SHADOW_COLLECTOR -> COLLECTOR_JSON_V1
            IntegrationProvider.GITHUB -> GITHUB_JSON_V1
            IntegrationProvider.YOUTUBE_TRAILER -> YOUTUBE_HTML_V1
            IntegrationProvider.OPEN_SUBTITLES,
            IntegrationProvider.SUBTITLE_SOURCE_DOWNLOAD,
            IntegrationProvider.SUBTITLE_TRANSLATION,
            IntegrationProvider.WYZIE_SUBTITLES -> SUBTITLE_PROVIDER_V1
        }
}
