package com.nexio.tv.core.integration

object AddonApiShapes {
    const val MANIFEST = "addon.manifest"
    const val CATALOG = "addon.catalog"
    const val META = "addon.meta"
    const val STREAMS = "addon.streams"
    const val SUBTITLES = "addon.subtitles"
}

object CollectorApiShapes {
    const val SHADOW_AUTOPLAY_UPLOAD = "shadow_collector.autoplay_upload"
}

object CustomImdbApiShapes {
    const val TITLE_BULK = "custom_imdb.title.bulk"
    const val EPISODE_SERIES = "custom_imdb.episode.series"
    const val TRANSPORT_EXECUTE = "custom_imdb.transport.execute"
}

object DebridApiShapes {
    const val EASY_DEBRID_ACCOUNT = "easy_debrid.account"
    const val EASY_DEBRID_GENERATE = "easy_debrid.generate"
    const val PREMIUMIZE_ACCOUNT = "premiumize.account"
    const val PREMIUMIZE_DIRECT_DOWNLOAD = "premiumize.direct_download"
    const val REAL_DEBRID_DELETE_TORRENT = "real_debrid.delete_torrent"
    const val REAL_DEBRID_ACCOUNT = "real_debrid.account"
    const val REAL_DEBRID_TORRENT_INFO = "real_debrid.torrent_info"
    const val TORBOX_ACCOUNT = "torbox.account"
    const val TORBOX_CREATE_TORRENT = "torbox.create_torrent"
}

object GitHubApiShapes {
    const val LATEST_RELEASE = "github.latest_release"
    const val ASSET_DOWNLOAD = "github.asset_download"
}

object KitsuApiShapes {
    const val DISCOVERY_TRENDING = "kitsu.discovery.trending"
    const val DISCOVERY_ANIME = "kitsu.discovery.anime"
    const val ANIME_CORE = "kitsu.anime.core"
    const val ANIME_EPISODES = "kitsu.anime.episodes"
    const val CASTINGS = "kitsu.castings"
    const val ANIME_STAFF = "kitsu.anime_staff"
    const val ANIME_PRODUCTIONS = "kitsu.anime_productions"
    const val MEDIA_RELATIONSHIPS = "kitsu.media_relationships"
    const val SEARCH_TEXT = "kitsu.search.text"
    const val ADVANCED_DETAIL = "kitsu.advanced_detail"
}

object MDBListApiShapes {
    const val USER = "mdblist.user"
    const val RATING_BATCH = "mdblist.rating.batch"
    const val RAW_URL_LIST = "mdblist.raw_url.list"
    const val VALIDATE_KEY = "mdblist.key_validation"
}

object OmdbApiShapes {
    const val SEASON_RATINGS = "omdb.season.ratings"
    const val VALIDATE_KEY = "omdb.key_validation"
}

object PlaybackApiShapes {
    const val OPEN_SUBTITLES_HASH = "playback.opensubtitles_hash"
    const val PREFLIGHT_HEAD = "playback.preflight_head"
    const val PREFLIGHT_RANGE = "playback.preflight_range"
}

object PosterApiShapes {
    const val RPDB_KEY_VALIDATION = "rpdb.key_validation"
    const val TOP_POSTERS_KEY_VALIDATION = "topposters.key_validation"
    const val RPDB_POSTER_TEMPLATE = "rpdb.poster_template"
    const val TOP_POSTERS_POSTER_TEMPLATE = "topposters.poster_template"
}

object SimklApiShapes {
    const val LAST_ACTIVITIES = "simkl.last_activities"
    const val DISCOVERY = "simkl.discovery"
    const val LIBRARY_READ = "simkl.library_read"
    const val LIBRARY_WRITE = "simkl.library_write"
}

object SkipApiShapes {
    const val THEINTRODB_MEDIA = "theintrodb.media"
    const val ANISKIP_SKIP_TIMES = "aniskip.skip_times"
    const val ANIMESKIP_GRAPHQL = "animeskip.graphql"
    const val ANIMESKIP_SHOWS = "animeskip.shows"
    const val ANIMESKIP_VALIDATE = "animeskip.key_validation"
    const val ARM_IMDB_BRIDGE = "arm.imdb_bridge"
    const val ARM_IDS_BRIDGE = "arm.ids_bridge"
}

object SubtitleApiShapes {
    const val SOURCE_DOWNLOAD = "subtitle.source_download"
    const val TRANSLATION = "subtitle.translation"
}

object TmdbApiShapes {
    const val FIND_EXTERNAL_ID = "tmdb.find.external_id"
    const val MOVIE_CORE = "tmdb.movie.core"
    const val TV_CORE = "tmdb.tv.core"
    const val SEASON_EPISODES = "tmdb.season.episodes"
    const val MOVIE_VIDEOS = "tmdb.movie.videos"
    const val TV_VIDEOS = "tmdb.tv.videos"
    const val SEASON_VIDEOS = "tmdb.season.videos"
    const val MOVIE_RECOMMENDATIONS = "tmdb.movie.recommendations"
    const val TV_RECOMMENDATIONS = "tmdb.tv.recommendations"
    const val MOVIE_REVIEWS = "tmdb.movie.reviews"
    const val TV_REVIEWS = "tmdb.tv.reviews"
    const val COLLECTION = "tmdb.collection"
    const val PERSON_DETAIL = "tmdb.person.detail"
    const val PERSON_COMBINED_CREDITS = "tmdb.person.combined_credits"
    const val COMPANY_DETAIL = "tmdb.company.detail"
    const val NETWORK_DETAIL = "tmdb.network.detail"
    const val DISCOVER_MOVIE_BY_COMPANY = "tmdb.discover.movie.by_company"
    const val DISCOVER_TV_BY_COMPANY_OR_NETWORK = "tmdb.discover.tv.by_company_or_network"
    const val VALIDATE_KEY = "tmdb.key_validation"
}

object TraktApiShapes {
    const val MOVIE_COMMENTS = "trakt.movie.comments"
    const val SHOW_COMMENTS = "trakt.show.comments"
    const val CALENDAR_SHOWS = "trakt.calendar.shows"
    const val TRENDING_MOVIES = "trakt.trending.movies"
    const val TRENDING_SHOWS = "trakt.trending.shows"
    const val POPULAR_MOVIES = "trakt.popular.movies"
    const val POPULAR_SHOWS = "trakt.popular.shows"
    const val RECOMMENDED_MOVIES = "trakt.recommended.movies"
    const val RECOMMENDED_SHOWS = "trakt.recommended.shows"
    const val COLLECTION_MOVIES = "trakt.collection.movies"
    const val COLLECTION_SHOWS = "trakt.collection.shows"
    const val WATCHLIST_MOVIES = "trakt.watchlist.movies"
    const val WATCHLIST_SHOWS = "trakt.watchlist.shows"
}

object TvdbApiShapes {
    const val LOGIN = "tvdb.login"
    const val REMOTE_ID_LOOKUP = "tvdb.remoteid.lookup"
    const val SEARCH = "tvdb.search"
    const val SERIES_BASE = "tvdb.series.base"
    const val SERIES_EXTENDED = "tvdb.series.extended"
    const val SERIES_TRANSLATION = "tvdb.series.translation"
    const val SERIES_EPISODES_SEASON_TYPE = "tvdb.series.episodes.season_type"
    const val SERIES_EPISODES_LANGUAGE = "tvdb.series.episodes.language"
    const val EPISODE_TRANSLATION = "tvdb.episode.translation"
    const val UPDATES = "tvdb.updates"
    const val REFERENCE_ARTWORK_TYPES = "tvdb.reference.artwork_types"
    const val REFERENCE_GENRES = "tvdb.reference.genres"
    const val REFERENCE_LANGUAGES = "tvdb.reference.languages"
    const val REFERENCE_CONTENT_RATINGS = "tvdb.reference.content_ratings"
    const val REFERENCE_SEASON_TYPES = "tvdb.reference.season_types"
    const val PERSON_EXTENDED = "tvdb.person.extended"
}

object YouTubeTrailerApiShapes {
    const val DEVICE_CODE = "youtube_trailer.device_code"
    const val TOKEN = "youtube_trailer.token"
}
