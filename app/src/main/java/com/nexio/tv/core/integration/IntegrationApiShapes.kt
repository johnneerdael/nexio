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
    const val DEVICE_CAPABILITY_REPORT_UPLOAD = "shadow_collector.device_capability_report_upload"
}

object CustomImdbApiShapes {
    const val TITLE_BULK = "custom_imdb.title.bulk"
    const val EPISODE_SERIES = "custom_imdb.episode.series"
    const val TRANSPORT_EXECUTE = "custom_imdb.transport.execute"
}

object DebridApiShapes {
    const val EASY_DEBRID_ACCOUNT = "easy_debrid.account"
    const val EASY_DEBRID_GENERATE = "easy_debrid.generate"
    const val EASY_DEBRID_LOOKUP = "easy_debrid.lookup"
    const val EASY_DEBRID_LOOKUP_DETAILS = "easy_debrid.lookup_details"
    const val PREMIUMIZE_ACCOUNT = "premiumize.account"
    const val PREMIUMIZE_CACHE_CHECK = "premiumize.cache_check"
    const val PREMIUMIZE_DIRECT_DOWNLOAD = "premiumize.direct_download"
    const val PREMIUMIZE_ITEM_DETAILS = "premiumize.item_details"
    const val PREMIUMIZE_LIST_ALL = "premiumize.list_all"
    const val REAL_DEBRID_ADD_MAGNET = "real_debrid.add_magnet"
    const val REAL_DEBRID_DELETE_TORRENT = "real_debrid.delete_torrent"
    const val REAL_DEBRID_DEVICE_CODE = "real_debrid.device_code"
    const val REAL_DEBRID_DEVICE_CREDENTIALS = "real_debrid.device_credentials"
    const val REAL_DEBRID_DOWNLOADS = "real_debrid.downloads"
    const val REAL_DEBRID_INSTANT_AVAILABILITY = "real_debrid.instant_availability"
    const val REAL_DEBRID_ACCOUNT = "real_debrid.account"
    const val REAL_DEBRID_MEDIA_INFOS = "real_debrid.media_infos"
    const val REAL_DEBRID_REVOKE_TOKEN = "real_debrid.revoke_token"
    const val REAL_DEBRID_SELECT_FILES = "real_debrid.select_files"
    const val REAL_DEBRID_TOKEN = "real_debrid.token"
    const val REAL_DEBRID_TORRENT_INFO = "real_debrid.torrent_info"
    const val REAL_DEBRID_TORRENTS = "real_debrid.torrents"
    const val REAL_DEBRID_UNRESTRICT_LINK = "real_debrid.unrestrict_link"
    const val TORBOX_ACCOUNT = "torbox.account"
    const val TORBOX_CHECK_CACHED = "torbox.check_cached"
    const val TORBOX_CREATE_TORRENT = "torbox.create_torrent"
    const val TORBOX_DOWNLOAD_LINK = "torbox.download_link"
    const val TORBOX_TORRENT_LIST = "torbox.torrent_list"
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
    const val ANIME_REVIEWS = "kitsu.anime.reviews"
    const val SEARCH_TEXT = "kitsu.search.text"
}

object MDBListApiShapes {
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
    const val PIN_START = "simkl.pin.start"
    const val PIN_STATUS = "simkl.pin.status"
    const val PLAYBACK = "simkl.playback"
    const val SCROBBLE = "simkl.scrobble"
    const val USER_SETTINGS = "simkl.user_settings"
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
    const val OPEN_SUBTITLES_SEARCH = "opensubtitles.search"
    const val SOURCE_DOWNLOAD = "subtitle.source_download"
    const val TRANSLATION = "subtitle.translation"
    const val WYZIE_SEARCH = "wyzie.search"
}

object TmdbApiShapes {
    const val COMPANY_DETAIL = "tmdb.company.detail"
    const val COMPANY_FIND_BY_NAME = "tmdb.company.find_by_name"
    const val COLLECTION = "tmdb.collection"
    const val DISCOVER_MOVIE = "tmdb.discover.movie"
    const val DISCOVER_MOVIE_BY_COMPANY = "tmdb.discover.movie.by_company"
    const val DISCOVER_TV = "tmdb.discover.tv"
    const val DISCOVER_TV_BY_COMPANY_OR_NETWORK = "tmdb.discover.tv.by_company_or_network"
    const val FIND_EXTERNAL_ID = "tmdb.find.external_id"
    const val MOVIE_CORE = "tmdb.movie.core"
    const val MOVIE_RECOMMENDATIONS = "tmdb.movie.recommendations"
    const val MOVIE_REVIEWS = "tmdb.movie.reviews"
    const val MOVIE_VIDEOS = "tmdb.movie.videos"
    const val NETWORK_DETAIL = "tmdb.network.detail"
    const val PERSON_COMBINED_CREDITS = "tmdb.person.combined_credits"
    const val PERSON_DETAIL = "tmdb.person.detail"
    const val PERSON_FIND_BY_NAME = "tmdb.person.find_by_name"
    const val POPULAR_MOVIE = "tmdb.popular.movie"
    const val POPULAR_TV = "tmdb.popular.tv"
    const val SEARCH_COMPANIES = "tmdb.search.companies"
    const val SEARCH_MOVIE = "tmdb.search.movie"
    const val SEARCH_MULTI = "tmdb.search.multi"
    const val SEARCH_PEOPLE = "tmdb.search.people"
    const val SEARCH_TV = "tmdb.search.tv"
    const val SEASON_EPISODES = "tmdb.season.episodes"
    const val SEASON_VIDEOS = "tmdb.season.videos"
    const val TRENDING_MOVIE = "tmdb.trending.movie"
    const val TRENDING_TV = "tmdb.trending.tv"
    const val TV_CORE = "tmdb.tv.core"
    const val TV_RECOMMENDATIONS = "tmdb.tv.recommendations"
    const val TV_REVIEWS = "tmdb.tv.reviews"
    const val TV_VIDEOS = "tmdb.tv.videos"
    const val VALIDATE_KEY = "tmdb.key_validation"
}

object TraktApiShapes {
    // Auth
    const val DEVICE_CODE = "trakt.device_code"
    const val DEVICE_TOKEN = "trakt.device_token"
    const val TOKEN_REFRESH = "trakt.token_refresh"
    const val TOKEN_REVOKE = "trakt.token_revoke"
    // User
    const val USER_SETTINGS = "trakt.user.settings"
    const val USER_STATS = "trakt.user.stats"
    const val USER_LISTS = "trakt.user.lists"
    const val USER_LIST_CREATE = "trakt.user.list_create"
    const val USER_LIST_UPDATE = "trakt.user.list_update"
    const val USER_LIST_DELETE = "trakt.user.list_delete"
    const val USER_LIST_ITEMS = "trakt.user.list_items"
    const val USER_LIST_ITEMS_ADD = "trakt.user.list_items.add"
    const val USER_LIST_ITEMS_REMOVE = "trakt.user.list_items.remove"
    const val USER_LISTS_REORDER = "trakt.user.lists_reorder"
    // Discovery
    const val CALENDAR_SHOWS = "trakt.calendar.shows"
    const val TRENDING_MOVIES = "trakt.trending.movies"
    const val TRENDING_SHOWS = "trakt.trending.shows"
    const val POPULAR_MOVIES = "trakt.popular.movies"
    const val POPULAR_SHOWS = "trakt.popular.shows"
    const val POPULAR_LISTS = "trakt.popular.lists"
    const val RECOMMENDED_MOVIES = "trakt.recommended.movies"
    const val RECOMMENDED_SHOWS = "trakt.recommended.shows"
    const val RECOMMENDATION_HIDE = "trakt.recommendation.hide"
    const val HIDDEN_ITEMS = "trakt.hidden_items"
    // Collection & watchlist
    const val WATCHLIST_MOVIES = "trakt.watchlist.movies"
    const val WATCHLIST_SHOWS = "trakt.watchlist.shows"
    const val WATCHLIST_ADD = "trakt.watchlist.add"
    const val WATCHLIST_REMOVE = "trakt.watchlist.remove"
    // History & watched
    const val LAST_ACTIVITIES = "trakt.last_activities"
    const val HISTORY_ADD = "trakt.history.add"
    const val HISTORY_REMOVE = "trakt.history.remove"
    const val WATCHED = "trakt.watched"
    const val WATCHED_SHOWS = "trakt.watched.shows"
    const val EPISODE_HISTORY = "trakt.episode.history"
    // Playback & scrobble
    const val PLAYBACK = "trakt.playback"
    const val PLAYBACK_DELETE = "trakt.playback.delete"
    const val SCROBBLE = "trakt.scrobble"
    const val CHECKIN = "trakt.checkin"
    // Media detail
    const val MOVIE_COMMENTS = "trakt.movie.comments"
    const val SHOW_COMMENTS = "trakt.show.comments"
    const val SHOW_PROGRESS_WATCHED = "trakt.show.progress_watched"
    const val SHOW_SEASONS_WITH_EPISODES = "trakt.show.seasons_with_episodes"
    const val SEASON_EPISODES = "trakt.season.episodes"
    const val EPISODE_SUMMARY = "trakt.episode.summary"
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
    const val REFERENCE_ARTWORK_STATUSES = "tvdb.reference.artwork_statuses"
    const val REFERENCE_SERIES_STATUSES = "tvdb.reference.series_statuses"
    const val REFERENCE_CONTENT_RATINGS = "tvdb.reference.content_ratings"
    const val REFERENCE_SEASON_TYPES = "tvdb.reference.season_types"
    const val REFERENCE_SOURCE_TYPES = "tvdb.reference.source_types"
    const val REFERENCE_ENTITY_TYPES = "tvdb.reference.entity_types"
    const val REFERENCE_COMPANY_TYPES = "tvdb.reference.company_types"
    const val PERSON_EXTENDED = "tvdb.person.extended"
    const val TV_TRAILERS = "tvdb.tv.trailers"
}

object YouTubeTrailerApiShapes {
    const val TRANSPORT_EXECUTE = "youtube_trailer.transport.execute"
    const val TRANSPORT_PROBE = "youtube_trailer.transport.probe"
}
