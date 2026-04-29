# IntegrationRuntime Connectivity & Policy Audit

Generated: `2026-04-29T13:36:46.348428Z`
Git SHA: `9f0555a5a`
Git worktree: `DIRTY` (4 changed files, 3 untracked)

## Section A - Executive Verdict

Verdict: `PASS`

Control-plane gate: `PASS`
MetadataRouter-readiness gate: `PASS_WITH_WARNINGS`

| Metric | Count |
| --- | ---: |
| total in-scope providers | 24 |
| total in-scope endpoint shapes | 127 |
| total runtime-covered calls | 93 |
| total direct-bypass calls | 0 |
| total missing policy entries | 0 |
| total missing policy fields | 0 |
| total endpoint-shape mismatches | 0 |
| total missing endpoint-shape ids | 0 |
| total missing header policies | 0 |
| total missing operation keys | 0 |
| active required endpoint shapes missing runtime spec | 0 |
| planned-not-active endpoint shapes | 46 |
| total undocumented exemptions | 0 |

## Section B - Provider Coverage Summary

| Provider | Lifecycle status | Adapter exists | Policy exists | Raw callers confined | Runtime covered calls | Cache policy modes used | Verdict |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| ADDON | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 7 | ObserveOnlyOrMutation | PASS |
| TRAKT | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 11 | CacheFirst | PASS |
| SIMKL | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 2 | ObserveOnlyOrMutation | PASS |
| TMDB | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 19 | CacheFirst, Disabled, ObserveOnlyOrMutation | PASS |
| TVDB | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 10 | CacheFirst, Disabled, ObserveOnlyOrMutation | PASS |
| KITSU | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 8 | CacheFirst, ObserveOnly, ObserveOnlyOrMutation | PASS |
| MDBLIST | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 5 | CacheFirst, Disabled, ObserveOnlyOrMutation | PASS |
| OMDB | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 2 | CacheFirst, Disabled | PASS |
| CUSTOM_IMDB | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | ObserveOnlyOrMutation | PASS |
| THEINTRODB | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | CacheFirst | PASS |
| ANISKIP | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | CacheFirst | PASS |
| ANIMESKIP | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 3 | CacheFirst, Disabled | PASS |
| ARM | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 7 | CacheFirst | PASS |
| RPDB | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 2 | CacheFirst, Disabled | PASS |
| TOP_POSTERS | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 2 | CacheFirst, Disabled | PASS |
| REAL_DEBRID | DORMANT_PROVIDER | yes | yes | yes | 0 | none | PASS |
| PREMIUMIZE | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | ObserveOnlyOrMutation | PASS |
| TORBOX | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | ObserveOnlyOrMutation | PASS |
| EASY_DEBRID | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | ObserveOnlyOrMutation | PASS |
| SHADOW_COLLECTOR | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | ObserveOnlyOrMutation | PASS |
| GITHUB | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 2 | ObserveOnlyOrMutation | PASS |
| YOUTUBE_TRAILER | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 3 | ObserveOnlyOrMutation | PASS |
| SUBTITLE_SOURCE_DOWNLOAD | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 2 | ObserveOnlyOrMutation | PASS |
| SUBTITLE_TRANSLATION | ACTIVE_RUNTIME_COVERED | yes | yes | yes | 1 | ObserveOnlyOrMutation | PASS |

## Section C - Call-Site Coverage Matrix

| Call ID | Provider | Header policy | Adapter method | Operation key | Runtime spec key template | Work class | Cache policy | Raw client | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `addon.catalog` | ADDON | `addon-json-v1` | `AddonCatalogIntegrationProvider.getCatalog` | `addon.catalog.getCatalog` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `addonApi.getCatalog` | PASS |
| `addon.manifest` | ADDON | `addon-json-v1` | `AddonManifestIntegrationProvider.getManifest` | `addon.manifest.getManifest` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `addonApi.getManifest` | PASS |
| `addon.meta` | ADDON | `addon-json-v1` | `AddonMetaIntegrationProvider.getMeta` | `addon.meta.getMeta` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `addonApi.getMeta` | PASS |
| `addon.streams` | ADDON | `addon-json-v1` | `AddonStreamIntegrationProvider.getStreams` | `addon.stream.getStreams` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `addonApi.getStreams` | PASS |
| `addon.subtitles` | ADDON | `addon-json-v1` | `AddonSubtitleIntegrationProvider.getSubtitles` | `addon.subtitle.getSubtitles` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `addonApi.getSubtitles` | PASS |
| `playback.preflight_head` | ADDON | `addon-json-v1` | `PlaybackPreflightIntegrationProvider.isPlayable` | `playback.preflight.isPlayable` | `` | PLAYBACK_RESOLUTION | ObserveOnlyOrMutation | `transport.redirectLocationForHead` | PASS |
| `playback.preflight_range` | ADDON | `addon-json-v1` | `PlaybackPreflightIntegrationProvider.rangeProbeWorks` | `playback.preflight.rangeProbeWorks` | `` | PLAYBACK_RESOLUTION | ObserveOnlyOrMutation | `transport.rangeProbe` | PASS |
| `animeskip.shows` | ANIMESKIP | `graphql-json-v1` | `AnimeSkipIntegrationProvider.resolveShowIds` | `animeskip.graphql.resolveShowIds` | `animeskip:shows:$anilistId` | PLAYBACK_RESOLUTION | CacheFirst | `animeSkipApi.query` | PASS |
| `animeskip.graphql` | ANIMESKIP | `graphql-json-v1` | `AnimeSkipIntegrationProvider.queryEpisodes` | `animeskip.graphql.queryEpisodes` | `animeskip:episodes:$showId` | PLAYBACK_RESOLUTION | CacheFirst | `animeSkipApi.query` | PASS |
| `animeskip.key_validation` | ANIMESKIP | `graphql-json-v1` | `AnimeSkipIntegrationProvider.validateClientId` | `animeskip.graphql.validateClientId` | `animeskip:validate:credentialHash:$credentialHash` | USER_VISIBLE | Disabled | `animeSkipApi.query` | PASS |
| `aniskip.skip_times` | ANISKIP | `public-json-v1` | `AniSkipIntegrationProvider.getSkipIntervals` | `aniskip.skipTimes.getSkipIntervals` | `aniskip:$malId:$episode` | PLAYBACK_RESOLUTION | CacheFirst | `aniSkipApi.getSkipTimes` | PASS |
| `arm.imdb_bridge` | ARM | `public-json-v1` | `ArmIntegrationProvider.resolveImdbToAnilist` | `arm.imdb.resolveImdbToAnilist` | `arm:imdb:$imdbId:anilist` | PLAYBACK_RESOLUTION | CacheFirst | `armApi.resolveImdbToAnilist` | PASS |
| `arm.imdb_bridge` | ARM | `public-json-v1` | `ArmIntegrationProvider.resolveImdbToMal` | `arm.imdb.resolveImdbToMal` | `arm:imdb:$imdbId:mal` | PLAYBACK_RESOLUTION | CacheFirst | `armApi.resolveImdbToMal` | PASS |
| `arm.ids_bridge` | ARM | `public-json-v1` | `ArmIntegrationProvider.resolveMalToAnilist` | `arm.ids.resolveMalToAnilist` | `arm:mal:$malId:anilist` | PLAYBACK_RESOLUTION | CacheFirst | `armApi.resolveMalToAnilist` | PASS |
| `arm.ids_bridge` | ARM | `public-json-v1` | `ArmIntegrationProvider.resolveMalToImdb` | `arm.ids.resolveMalToImdb` | `arm:mal:$malId:imdb` | PLAYBACK_RESOLUTION | CacheFirst | `armApi.resolveMalToImdb` | PASS |
| `arm.ids_bridge` | ARM | `public-json-v1` | `ArmIntegrationProvider.resolveKitsuToMal` | `arm.ids.resolveKitsuToMal` | `arm:kitsu:$kitsuId:mal` | PLAYBACK_RESOLUTION | CacheFirst | `armApi.resolveKitsuToMal` | PASS |
| `arm.ids_bridge` | ARM | `public-json-v1` | `ArmIntegrationProvider.resolveKitsuToAnilist` | `arm.ids.resolveKitsuToAnilist` | `arm:kitsu:$kitsuId:anilist` | PLAYBACK_RESOLUTION | CacheFirst | `armApi.resolveKitsuToAnilist` | PASS |
| `arm.ids_bridge` | ARM | `public-json-v1` | `ArmIntegrationProvider.resolveKitsuToImdb` | `arm.ids.resolveKitsuToImdb` | `arm:kitsu:$kitsuId:imdb` | PLAYBACK_RESOLUTION | CacheFirst | `armApi.resolveKitsuToImdb` | PASS |
| `custom_imdb.transport.execute` | CUSTOM_IMDB | `custom-imdb-json-v1` | `CustomImdbRatingsIntegrationProvider.execute` | `custom_imdb.ratings_execute` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.execute` | PASS |
| `easy_debrid.account` | EASY_DEBRID | `easy_debrid-json-token-v1` | `EasyDebridIntegrationProvider.fetchAccountInfo` | `easy_debrid.account.fetch` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `easyDebridApi.getUserDetails` | PASS |
| `github.asset_download` | GITHUB | `github-json-v1` | `GitHubAssetDownloadIntegrationProvider.openDownload` | `github.asset.openDownload` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.openDownloadStream` | PASS |
| `github.latest_release` | GITHUB | `github-json-v1` | `GitHubReleaseIntegrationProvider.fetchLatestRelease` | `github.release.fetchLatestRelease` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `gitHubReleaseApi.getLatestRelease` | PASS |
| `kitsu.discovery.trending` | KITSU | `public-json-v1` | `KitsuDiscoveryIntegrationProvider.fetchCatalog` | `kitsu.fetch_catalog` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `kitsuApi.getTrendingAnime` | PASS |
| `kitsu.anime.core` | KITSU | `public-json-v1` | `KitsuIntegrationProvider.fetchEnrichment` | `kitsu.fetch_enrichment` | `kitsu:${mediaKind.name.lowercase()}:$rawId:enrichment` | USER_VISIBLE | CacheFirst | `name.lowercase` | PASS |
| `kitsu.anime.episodes` | KITSU | `public-json-v1` | `KitsuIntegrationProvider.fetchEpisodeEnrichment` | `kitsu.fetch_episode_enrichment` | `kitsu:${mediaKind.name.lowercase()}:$rawId:episodes` | USER_VISIBLE | CacheFirst | `name.lowercase` | PASS |
| `kitsu.castings` | KITSU | `public-json-v1` | `KitsuIntegrationProvider.fetchCastings` | `kitsu.castings` | `kitsu:${mediaKind.name.lowercase()}:$rawId:castings` | USER_VISIBLE | CacheFirst | `name.lowercase` | PASS |
| `kitsu.anime_staff` | KITSU | `public-json-v1` | `KitsuIntegrationProvider.fetchAnimeStaff` | `kitsu.anime_staff` | `kitsu:${mediaKind.name.lowercase()}:$rawId:anime_staff` | USER_VISIBLE | CacheFirst | `name.lowercase` | PASS |
| `kitsu.anime_productions` | KITSU | `public-json-v1` | `KitsuIntegrationProvider.fetchAnimeProductions` | `kitsu.anime_productions` | `kitsu:${mediaKind.name.lowercase()}:$rawId:anime_productions` | USER_VISIBLE | CacheFirst | `name.lowercase` | PASS |
| `kitsu.media_relationships` | KITSU | `public-json-v1` | `KitsuIntegrationProvider.fetchMediaRelationships` | `kitsu.media_relationships` | `kitsu:${mediaKind.name.lowercase()}:$rawId:media_relationships` | USER_VISIBLE | CacheFirst | `name.lowercase` | PASS |
| `kitsu.search.text` | KITSU | `public-json-v1` | `KitsuIntegrationProvider.searchAnimeByText` | `kitsu.search.text` | `` | USER_VISIBLE | ObserveOnly | `kitsuAuthService.validAccessToken` | PASS |
| `mdblist.raw_url.list` | MDBLIST | `mdblist-api-key-v1` | `MDBListIntegrationProvider.accountCallSpec` | `operation` | `` | BACKGROUND_HYDRATION | ObserveOnlyOrMutation | `` | PASS |
| `mdblist.raw_url.list` | MDBLIST | `mdblist-api-key-v1` | `MDBListIntegrationProvider.accountCallSpec` | `operationKey` | `` | BACKGROUND_HYDRATION | ObserveOnlyOrMutation | `` | PASS |
| `mdblist.rating.batch` | MDBLIST | `mdblist-api-key-v1` | `MDBListIntegrationProvider.fetchRatings` | `mdblist.fetch_ratings` | `mdblist:$mediaType:$imdbId:$providerHash:credentialHash:$credentialHash` | USER_VISIBLE | CacheFirst | `ownershipFactory.media` | PASS |
| `mdblist.rating.batch` | MDBLIST | `mdblist-api-key-v1` | `MDBListIntegrationProvider.fetchEpisodeRatingsForSeason` | `mdblist.fetch_episode_ratings_for_season` | `mdblist:episodes:$cacheNamespace:season:$season:credentialHash:$credentialHash` | USER_VISIBLE | CacheFirst | `ownershipFactory.media` | PASS |
| `mdblist.key_validation` | MDBLIST | `mdblist-api-key-v1` | `MDBListIntegrationProvider.validateApiKey` | `mdblist.validate_api_key` | `mdblist:validate:credentialHash:$credentialHash` | USER_VISIBLE | Disabled | `mdbListApi.getUser` | PASS |
| `omdb.season.ratings` | OMDB | `omdb-query-api-key-v1` | `OmdbIntegrationProvider.getSeasonRatings` | `omdb.get_season_ratings` | `omdb:$seriesImdbId:season:$season:credentialHash:$credentialHash` | USER_VISIBLE | CacheFirst | `ownershipFactory.media` | PASS |
| `omdb.key_validation` | OMDB | `omdb-query-api-key-v1` | `OmdbIntegrationProvider.validateApiKey` | `omdb.validate_api_key` | `omdb:validate:credentialHash:$credentialHash` | USER_VISIBLE | Disabled | `omdbApi.getSeason` | PASS |
| `premiumize.account` | PREMIUMIZE | `premiumize-json-token-v1` | `PremiumizeIntegrationProvider.fetchAccountInfo` | `premiumize.account.fetch` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `premiumizeApi.getAccountInfo` | PASS |
| `rpdb.poster_template` | RPDB | `rpdb-image-path-key-v1` | `RpdbIntegrationProvider.fetchPoster` | `rpdb.poster.fetchPoster` | `runtime-request.cacheKey` | USER_VISIBLE | CacheFirst | `posterTransport.execute` | PASS |
| `rpdb.key_validation` | RPDB | `rpdb-json-api-key-v1` | `RpdbIntegrationProvider.validateApiKey` | `rpdb.key.validate` | `rpdb:validate:credentialHash:$credentialHash` | USER_VISIBLE | Disabled | `rpdbApi.verifyApiKey` | PASS |
| `shadow_collector.autoplay_upload` | SHADOW_COLLECTOR | `collector-json-v1` | `ShadowAutoplayUploadIntegrationProvider.uploadEvent` | `shadowCollector.autoplay.uploadEvent` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.uploadEvent` | PASS |
| `simkl.last_activities` | SIMKL | `simkl-json-v1` | `SimklIntegrationProvider.getLastActivities` | `accountOperationKey` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `simklAuthService.executeAuthOwnerRequest` | PASS |
| `simkl.discovery` | SIMKL | `simkl-json-v1` | `SimklIntegrationProvider.fetchDiscoveryBody` | `simkl.discovery.fetch_body` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.fetchDiscoveryBody` | PASS |
| `playback.opensubtitles_hash` | SUBTITLE_SOURCE_DOWNLOAD | `subtitle-provider-v1` | `OpenSubtitlesHashIntegrationProvider.spec` | `playback.opensubtitlesHash.compute` | `` | PLAYBACK_RESOLUTION | ObserveOnlyOrMutation | `transport.contentLength` | PASS |
| `subtitle.source_download` | SUBTITLE_SOURCE_DOWNLOAD | `subtitle-provider-v1` | `SubtitleSourceDownloadIntegrationProvider.execute` | `subtitle.source.download` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.executeUrl` | PASS |
| `subtitle.translation` | SUBTITLE_TRANSLATION | `subtitle-provider-v1` | `SubtitleTranslationIntegrationProvider.execute` | `subtitle.translation.execute` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.execute` | PASS |
| `theintrodb.media` | THEINTRODB | `introdb-json-optional-bearer-v1` | `IntroDbIntegrationProvider.getIntervals` | `theintrodb.media.getIntervals` | `theintrodb:$contentId:$season:$episode` | PLAYBACK_RESOLUTION | CacheFirst | `introDbApi.getMedia` | PASS |
| `tmdb.tv.core` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchEnrichment` | `tmdb.fetch_enrichment` | `tmdb:$tmdbType:$tmdbId:$normalizedLanguage:enrichment:$providerToken` | USER_VISIBLE | CacheFirst | `ownershipFactory.media` | PASS |
| `tmdb.movie.core` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchMovieCore` | `tmdb.movie.core:$movieId:$normalizedLanguage:policy:$localizationPolicyVersion` | `tmdb:movie:$movieId:$normalizedLanguage:core:$providerToken:policy:$localizationPolicyVersion` | USER_VISIBLE | CacheFirst | `ownershipFactory.media` | PASS |
| `tmdb.tv.core` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchTvCore` | `tmdb.tv.core:$tvId:$normalizedLanguage:policy:$localizationPolicyVersion` | `tmdb:tv:$tvId:$normalizedLanguage:core:$providerToken:policy:$localizationPolicyVersion` | USER_VISIBLE | CacheFirst | `ownershipFactory.media` | PASS |
| `tmdb.season.episodes` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchTvSeasonEpisodes` | `tmdb.season.episodes:$tvId:$seasonNumber:$normalizedLanguage:policy:$localizationPolicyVersion` | `tmdb:tv:$tvId:season:$seasonNumber:episodes:$normalizedLanguage:policy:$localizationPolicyVersion` | USER_VISIBLE | CacheFirst | `tmdbApi.getTvSeasonDetails` | PASS |
| `tmdb.movie.videos` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchMovieVideos` | `tmdb.movie.videos` | `tmdb:movie:$movieId:videos:$normalizedLanguage` | USER_VISIBLE | CacheFirst | `tmdbApi.getMovieVideos` | PASS |
| `tmdb.tv.videos` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchTvVideos` | `tmdb.tv.videos` | `tmdb:tv:$tvId:videos:$normalizedLanguage` | USER_VISIBLE | CacheFirst | `tmdbApi.getTvVideos` | PASS |
| `tmdb.tv.core` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchTvDetails` | `tmdb.tv.core` | `tmdb:tv:$tvId:$normalizedLanguage:core` | USER_VISIBLE | CacheFirst | `tmdbApi.getTvDetails` | PASS |
| `tmdb.season.videos` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchSeasonVideos` | `tmdb.season.videos` | `tmdb:tv:$tvId:season:$seasonNumber:videos:$normalizedLanguage` | USER_VISIBLE | CacheFirst | `tmdbApi.getTvSeasonVideos` | PASS |
| `tmdb.movie.recommendations` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchMovieRecommendations` | `tmdb.movie.recommendations` | `tmdb:movie:$movieId:recommendations:$normalizedLanguage:page:$page` | USER_VISIBLE | CacheFirst | `tmdbApi.getMovieRecommendations` | PASS |
| `tmdb.tv.recommendations` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchTvRecommendations` | `tmdb.tv.recommendations` | `tmdb:tv:$tvId:recommendations:$normalizedLanguage:page:$page` | USER_VISIBLE | CacheFirst | `tmdbApi.getTvRecommendations` | PASS |
| `tmdb.movie.reviews` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchMovieReviews` | `tmdb.movie.reviews` | `tmdb:movie:$movieId:reviews:$normalizedLanguage:page:$page` | USER_VISIBLE | CacheFirst | `tmdbApi.getMovieReviews` | PASS |
| `tmdb.tv.reviews` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.fetchTvReviews` | `tmdb.tv.reviews` | `tmdb:tv:$tvId:reviews:$normalizedLanguage:page:$page` | USER_VISIBLE | CacheFirst | `tmdbApi.getTvReviews` | PASS |
| `tmdb.find.external_id` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.findByExternalId` | `tmdb.find_by_external_id` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `tmdbApi.findByExternalId` | PASS |
| `tmdb.season.episodes` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.loadTvSeasonEpisodes` | `tmdb.season.episodes` | `tmdb:tv:$tvId:season:$seasonNumber:episodes:$normalizedLanguage` | USER_VISIBLE | CacheFirst | `tmdbApi.getTvSeasonDetails` | PASS |
| `tmdb.person.detail` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.loadPersonDetails` | `tmdb.person.detail` | `tmdb:person:$personId:detail` | USER_VISIBLE | CacheFirst | `tmdbApi.getPersonDetails` | PASS |
| `tmdb.person.combined_credits` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.loadPersonCombinedCredits` | `tmdb.person.combined_credits` | `tmdb:person:$personId:combined_credits` | USER_VISIBLE | CacheFirst | `tmdbApi.getPersonCombinedCredits` | PASS |
| `tmdb.search.people` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.searchPeople` | `tmdb.search.people` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `tmdbApi.searchPeople` | PASS |
| `tmdb.search.companies` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.searchCompanies` | `tmdb.search.companies` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `tmdbApi.searchCompanies` | PASS |
| `tmdb.key_validation` | TMDB | `tmdb-json-v1` | `TmdbIntegrationProvider.validateApiKey` | `tmdb.validate_api_key` | `tmdb:validate:credentialHash:$credentialHash` | USER_VISIBLE | Disabled | `tmdbApi.getConfiguration` | PASS |
| `topposters.poster_template` | TOP_POSTERS | `topposters-image-path-key-v1` | `TopPostersIntegrationProvider.fetchPoster` | `topposters.poster.fetchPoster` | `runtime-request.cacheKey` | USER_VISIBLE | CacheFirst | `posterTransport.execute` | PASS |
| `topposters.key_validation` | TOP_POSTERS | `topposters-image-path-key-v1` | `TopPostersIntegrationProvider.validateApiKey` | `topposters.key.validate` | `topposters:validate:credentialHash:$credentialHash` | USER_VISIBLE | Disabled | `topPostersApi.verifyApiKey` | PASS |
| `torbox.account` | TORBOX | `torbox-json-token-v1` | `TorBoxIntegrationProvider.fetchAccountInfo` | `torbox.account.fetch` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `torBoxApi.getCurrentUser` | PASS |
| `trakt.calendar.shows` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchCalendarShows` | `accountOperationKey` | `globalContentCacheKey("trakt:calendar:shows:start:$startDate:days:$days")` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.trending.movies` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchTrendingMovies` | `accountOperationKey` | `globalContentCacheKey("trakt:trending:movies:limit:$limit")` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.trending.shows` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchTrendingShows` | `accountOperationKey` | `globalContentCacheKey("trakt:trending:shows:limit:$limit")` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.popular.movies` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchPopularMovies` | `accountOperationKey` | `globalContentCacheKey("trakt:popular:movies:limit:$limit")` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.popular.shows` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchPopularShows` | `accountOperationKey` | `globalContentCacheKey("trakt:popular:shows:limit:$limit")` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.recommended.shows` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchRecommendations` | `accountOperationKey` | `globalContentCacheKey("trakt:recommendations:$type:limit:$limit")` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.popular.lists` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchPopularLists` | `accountOperationKey` | `accountCacheKey(session` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.user.lists` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchUserLists` | `accountOperationKey` | `accountCacheKey(session` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.user.list_items` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchUserListItems` | `accountOperationKey` | `accountCacheKey(session` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.movie.comments` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchMovieCommentsPage` | `accountOperationKey` | `accountCacheKey(session` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `trakt.show.comments` | TRAKT | `trakt-json-v2` | `TraktIntegrationProvider.fetchShowCommentsPage` | `accountOperationKey` | `accountCacheKey(session` | USER_VISIBLE | CacheFirst | `traktAuthService.executeAuthorizedRequestWithinRuntimeCall` | PASS |
| `tvdb.login` | TVDB | `json-body-no-auth-v1` | `TvdbIntegrationProvider.login` | `tvdb.login` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `tvdbApi.login` | PASS |
| `tvdb.updates` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.runMaintenanceUpdate` | `tvdb.run_maintenance_update` | `tvdb:updates:$triggerKey` | MAINTENANCE | Disabled | `` | PASS |
| `tvdb.series.extended` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.fetchSeriesEnrichmentRuntime` | `tvdb.fetch_series_enrichment` | `tvdb:series:$resolvedId:$normalizedLanguage:$providerToken:enrichment` | USER_VISIBLE | CacheFirst | `ownershipFactory.media` | PASS |
| `tvdb.series.extended` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.fetchSeriesExtendedCached` | `tvdb.series.extended` | `tvdb:series:$tvdbId:extended:policy:$localizationPolicyVersion` | USER_VISIBLE | CacheFirst | `` | PASS |
| `tvdb.series.episodes.season_type` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.fetchSeriesEpisodes` | `tvdb.series.episodes.season_type` | `tvdb:series:$tvdbId:episodes:$seasonType:season:${season ?: ` | USER_VISIBLE | CacheFirst | `tvdbApi.getSeriesEpisodes` | PASS |
| `tvdb.series.translation` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.fetchSeriesTranslationWithTrace` | `tvdb.series.translation:$tvdbId:$language:policy:$localizationPolicyVersion` | `tvdbSeriesTranslationCacheKey(tvdbId` | USER_VISIBLE | CacheFirst | `tvdbApi.getSeriesTranslation` | PASS |
| `tvdb.series.episodes.language` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.fetchSeriesEpisodesTranslatedWithTrace` | `tvdb.series.episodes.language:$tvdbId:$seasonType:$language:season:${season ?: ` | `tvdb:series:$tvdbId:episodes:$seasonType:$language:season:${season ?: ` | USER_VISIBLE | CacheFirst | `tvdbApi.getSeriesEpisodesTranslated` | PASS |
| `tvdb.episode.translation` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.fetchEpisodeTranslationWithTrace` | `tvdb.episode.translation:$episodeId:$language:policy:$localizationPolicyVersion` | `tvdb:episode:$episodeId:translation:$language:policy:$localizationPolicyVersion` | USER_VISIBLE | CacheFirst | `tvdbApi.getEpisodeTranslation` | PASS |
| `tvdb.remoteid.lookup` | TVDB | `tvdb-json-bearer-v1` | `TvdbIntegrationProvider.searchByRemoteId` | `tvdb.remoteid.lookup` | `tvdb:remoteid:$remoteId` | USER_VISIBLE | CacheFirst | `tvdbApi.searchByRemoteId` | PASS |
| `tvdb.login` | TVDB | `json-body-no-auth-v1` | `TvdbLoginIntegrationProvider.requestToken` | `tvdb.login` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `tvdbApi.login` | PASS |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | `youtube-html-v1` | `TrailerBackendProvider.resolveYouTubePlaybackSource` | `youtube_trailer.backend.resolve` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `trailerApi.getTrailer` | PASS |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | `youtube-html-v1` | `YouTubeTrailerIntegrationProvider.fetch` | `youtube.trailer.fetch` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.execute` | PASS |
| `youtube_trailer.transport.probe` | YOUTUBE_TRAILER | `youtube-html-v1` | `YouTubeTrailerIntegrationProvider.probe` | `youtube.trailer.probe` | `` | USER_VISIBLE | ObserveOnlyOrMutation | `transport.probe` | PASS |

## Section D - Runtime Policy Matrix

| Call ID | Provider | Scope | Work class | Cache policy | Codec | Lane concurrency | Backoff scope |
| --- | --- | --- | --- | --- | --- | ---: | --- |
| `addon.catalog` | ADDON | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `addon.manifest` | ADDON | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `addon.meta` | ADDON | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `addon.streams` | ADDON | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `addon.subtitles` | ADDON | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `playback.preflight_head` | ADDON | Global | PLAYBACK_RESOLUTION | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `playback.preflight_range` | ADDON | Global | PLAYBACK_RESOLUTION | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `animeskip.shows` | ANIMESKIP | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<List<String>>` | 1 | provider/scope |
| `animeskip.graphql` | ANIMESKIP | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<List<AnimeSkipEpisode>>` | 1 | provider/scope |
| `animeskip.key_validation` | ANIMESKIP | Global | USER_VISIBLE | Disabled | `StringIntegrationCodec` | 1 | provider/scope |
| `aniskip.skip_times` | ANISKIP | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<List<SkipInterval>>` | 1 | provider/scope |
| `arm.imdb_bridge` | ARM | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<List<String>>` | 1 | provider/scope |
| `arm.imdb_bridge` | ARM | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<String?>` | 1 | provider/scope |
| `arm.ids_bridge` | ARM | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<String?>` | 1 | provider/scope |
| `arm.ids_bridge` | ARM | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<String?>` | 1 | provider/scope |
| `arm.ids_bridge` | ARM | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<String?>` | 1 | provider/scope |
| `arm.ids_bridge` | ARM | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<String?>` | 1 | provider/scope |
| `arm.ids_bridge` | ARM | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<String?>` | 1 | provider/scope |
| `custom_imdb.transport.execute` | CUSTOM_IMDB | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `easy_debrid.account` | EASY_DEBRID | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `github.asset_download` | GITHUB | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `github.latest_release` | GITHUB | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `kitsu.discovery.trending` | KITSU | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `kitsu.anime.core` | KITSU | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvMetadataEnrichment>` | 1 | provider/scope |
| `kitsu.anime.episodes` | KITSU | Global | USER_VISIBLE | CacheFirst | `gsonCodec<Map<Pair<Int, Int>, TvEpisodeMetadata>>` | 1 | provider/scope |
| `kitsu.castings` | KITSU | Global | USER_VISIBLE | CacheFirst | `gsonCodec<KitsuCollectionResponse<KitsuCastingResource>>` | 1 | provider/scope |
| `kitsu.anime_staff` | KITSU | Global | USER_VISIBLE | CacheFirst | `gsonCodec<KitsuCollectionResponse<KitsuAnimeStaffResource>>` | 1 | provider/scope |
| `kitsu.anime_productions` | KITSU | Global | USER_VISIBLE | CacheFirst | `gsonCodec<KitsuCollectionResponse<KitsuAnimeProductionResource>>` | 1 | provider/scope |
| `kitsu.media_relationships` | KITSU | Global | USER_VISIBLE | CacheFirst | `gsonCodec<KitsuCollectionResponse<KitsuMediaRelationshipResource>>` | 1 | provider/scope |
| `kitsu.search.text` | KITSU | Global | USER_VISIBLE | ObserveOnly | `gsonCodec<KitsuCollectionResponse<KitsuAnimeResource>>` | 1 | provider/scope |
| `mdblist.raw_url.list` | MDBLIST | GlobalContent | BACKGROUND_HYDRATION | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `mdblist.raw_url.list` | MDBLIST | Account( | BACKGROUND_HYDRATION | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `mdblist.rating.batch` | MDBLIST | Global | USER_VISIBLE | CacheFirst | `gsonCodec<MDBListRatingsResult>` | 1 | provider/scope |
| `mdblist.rating.batch` | MDBLIST | Global | USER_VISIBLE | CacheFirst | `gsonCodec<EpisodeRatingsCacheDto>` | 1 | provider/scope |
| `mdblist.key_validation` | MDBLIST | Global | USER_VISIBLE | Disabled | `StringIntegrationCodec` | 1 | provider/scope |
| `omdb.season.ratings` | OMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<EpisodeRatingsCacheDto>` | 1 | provider/scope |
| `omdb.key_validation` | OMDB | Global | USER_VISIBLE | Disabled | `StringIntegrationCodec` | 1 | provider/scope |
| `premiumize.account` | PREMIUMIZE | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `rpdb.poster_template` | RPDB | Global | USER_VISIBLE | CacheFirst | `ByteArrayIntegrationCodec` | 1 | provider/scope |
| `rpdb.key_validation` | RPDB | Global | USER_VISIBLE | Disabled | `StringIntegrationCodec` | 1 | provider/scope |
| `shadow_collector.autoplay_upload` | SHADOW_COLLECTOR | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `simkl.last_activities` | SIMKL | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `simkl.discovery` | SIMKL | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `playback.opensubtitles_hash` | SUBTITLE_SOURCE_DOWNLOAD | GlobalContent | PLAYBACK_RESOLUTION | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `subtitle.source_download` | SUBTITLE_SOURCE_DOWNLOAD | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `subtitle.translation` | SUBTITLE_TRANSLATION | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `theintrodb.media` | THEINTRODB | Global | PLAYBACK_RESOLUTION | CacheFirst | `gsonCodec<List<SkipInterval>>` | 1 | provider/scope |
| `tmdb.tv.core` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbEnrichment>` | 1 | provider/scope |
| `tmdb.movie.core` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbEnrichment>` | 1 | provider/scope |
| `tmdb.tv.core` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbEnrichment>` | 1 | provider/scope |
| `tmdb.season.episodes` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbSeasonResponse>` | 1 | provider/scope |
| `tmdb.movie.videos` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbVideosResponse>` | 1 | provider/scope |
| `tmdb.tv.videos` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbVideosResponse>` | 1 | provider/scope |
| `tmdb.tv.core` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbDetailsResponse>` | 1 | provider/scope |
| `tmdb.season.videos` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbVideosResponse>` | 1 | provider/scope |
| `tmdb.movie.recommendations` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbRecommendationsResponse>` | 1 | provider/scope |
| `tmdb.tv.recommendations` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbRecommendationsResponse>` | 1 | provider/scope |
| `tmdb.movie.reviews` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbReviewsResponse>` | 1 | provider/scope |
| `tmdb.tv.reviews` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbReviewsResponse>` | 1 | provider/scope |
| `tmdb.find.external_id` | TMDB | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `tmdb.season.episodes` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TmdbEpisode>>` | 1 | provider/scope |
| `tmdb.person.detail` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbPersonResponse>` | 1 | provider/scope |
| `tmdb.person.combined_credits` | TMDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TmdbPersonCreditsResponse>` | 1 | provider/scope |
| `tmdb.search.people` | TMDB | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `tmdb.search.companies` | TMDB | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `tmdb.key_validation` | TMDB | Global | USER_VISIBLE | Disabled | `StringIntegrationCodec` | 1 | provider/scope |
| `topposters.poster_template` | TOP_POSTERS | Global | USER_VISIBLE | CacheFirst | `ByteArrayIntegrationCodec` | 1 | provider/scope |
| `topposters.key_validation` | TOP_POSTERS | Global | USER_VISIBLE | Disabled | `StringIntegrationCodec` | 1 | provider/scope |
| `torbox.account` | TORBOX | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `trakt.calendar.shows` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktCalendarEpisodeItemDto>>` | 1 | provider/scope |
| `trakt.trending.movies` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktTrendingMovieItemDto>>` | 1 | provider/scope |
| `trakt.trending.shows` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktTrendingShowItemDto>>` | 1 | provider/scope |
| `trakt.popular.movies` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktMovieDto>>` | 1 | provider/scope |
| `trakt.popular.shows` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktShowDto>>` | 1 | provider/scope |
| `trakt.recommended.shows` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktRecommendationItemDto>>` | 1 | provider/scope |
| `trakt.popular.lists` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktPopularListItemDto>>` | 1 | provider/scope |
| `trakt.user.lists` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktListSummaryDto>>` | 1 | provider/scope |
| `trakt.user.list_items` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<List<TraktListItemDto>>` | 1 | provider/scope |
| `trakt.movie.comments` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TraktCommentsPage>` | 1 | provider/scope |
| `trakt.show.comments` | TRAKT | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TraktCommentsPage>` | 1 | provider/scope |
| `tvdb.login` | TVDB | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `tvdb.updates` | TVDB | Global | MAINTENANCE | Disabled | `IntegrationCodec<T>` | 1 | provider/scope |
| `tvdb.series.extended` | TVDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvMetadataEnrichment>` | 1 | provider/scope |
| `tvdb.series.extended` | TVDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvdbSeriesExtendedRecord>` | 1 | provider/scope |
| `tvdb.series.episodes.season_type` | TVDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvdbSeriesEpisodesData>` | 1 | provider/scope |
| `tvdb.series.translation` | TVDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvdbTranslationRecord>` | 1 | provider/scope |
| `tvdb.series.episodes.language` | TVDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvdbSeriesEpisodesData>` | 1 | provider/scope |
| `tvdb.episode.translation` | TVDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvdbTranslationRecord>` | 1 | provider/scope |
| `tvdb.remoteid.lookup` | TVDB | Global | USER_VISIBLE | CacheFirst | `gsonCodec<TvdbRemoteIdSearchResponse>` | 1 | provider/scope |
| `tvdb.login` | TVDB | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | Global | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |
| `youtube_trailer.transport.probe` | YOUTUBE_TRAILER | ProviderConfig( | USER_VISIBLE | ObserveOnlyOrMutation | `` | 1 | provider/scope |

## Section E - Endpoint-Shape Matrix

| Shape ID | Expected shape | Request shape | Auth context | Redaction rules | Actual adapter method | Actual source | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `tmdb.find.external_id` | `GET /find/{external_id}` | single-item | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.findByExternalId` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.movie.core` | `GET /movie/{movie_id}` | one-call multi-field enrichment | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchMovieCore` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.tv.core` | `GET /tv/{tv_id}` | one-call multi-field enrichment | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchEnrichment` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.season.episodes` | `GET /tv/{series_id}/season/{season_number}` | season batch | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchTvSeasonEpisodes` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.movie.videos` | `GET /movie/{movie_id}/videos` | list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchMovieVideos` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.tv.videos` | `GET /tv/{tv_id}/videos` | list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchTvVideos` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.season.videos` | `GET /tv/{series_id}/season/{season_number}/videos` | list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchSeasonVideos` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.movie.recommendations` | `GET /movie/{movie_id}/recommendations` | paginated list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchMovieRecommendations` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.tv.recommendations` | `GET /tv/{tv_id}/recommendations` | paginated list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchTvRecommendations` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.movie.reviews` | `GET /movie/{movie_id}/reviews` | paginated list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchMovieReviews` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.tv.reviews` | `GET /tv/{tv_id}/reviews` | paginated list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.fetchTvReviews` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.collection` | `GET /collection/{collection_id}` | collection | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.person.detail` | `GET /person/{person_id}` | single-item enrichment | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.loadPersonDetails` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.person.combined_credits` | `GET /person/{person_id}/combined_credits` | list batch | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.loadPersonCombinedCredits` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.company.detail` | `GET /company/{company_id}` | single-item | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.network.detail` | `GET /network/{network_id}` | single-item | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.discover.movie.by_company` | `GET /discover/movie` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.discover.tv.by_company_or_network` | `GET /discover/tv` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.login` | `POST /login` | auth | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.login` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.remoteid.lookup` | `GET /search/remoteid/{remoteId}` | single-item | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.searchByRemoteId` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.search` | `GET /search` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.series.base` | `GET /series/{id}` | single-item | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.series.extended` | `GET /series/{id}/extended` | one-call multi-field enrichment | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.fetchSeriesEnrichmentRuntime` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.series.translation` | `GET /series/{id}/translations/{language}` | single-item | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.fetchSeriesTranslationWithTrace` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.series.episodes.season_type` | `GET /series/{id}/episodes/{seasonType}` | season batch | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.fetchSeriesEpisodes` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.series.episodes.language` | `GET /series/{id}/episodes/{seasonType}/{language}` | season batch | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.fetchSeriesEpisodesTranslatedWithTrace` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.episode.translation` | `GET /episodes/{id}/translations/{language}` | single-item | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.fetchEpisodeTranslationWithTrace` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.updates` | `GET /updates` | maintenance page | api-key-or-token | hash/redact credentials and URLs | `TvdbIntegrationProvider.runMaintenanceUpdate` | `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.reference.artwork_types` | `GET /artwork/types` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.genres` | `GET /genres` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.languages` | `GET /languages` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.content_ratings` | `GET /content/ratings` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.season_types` | `GET /seasons/types` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.person.extended` | `GET /people/{id}/extended` | one-call multi-field enrichment | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `kitsu.discovery.trending` | `GET /trending/anime` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `KitsuDiscoveryIntegrationProvider.fetchCatalog` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuDiscoveryIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.discovery.anime` | `GET /anime` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `kitsu.anime.core` | `GET /anime/{id}` | one-call multi-field enrichment | none-or-provider-specific | hash/redact credentials and URLs | `KitsuIntegrationProvider.fetchEnrichment` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.anime.episodes` | `GET /anime/{id}/episodes` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `KitsuIntegrationProvider.fetchEpisodeEnrichment` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.castings` | `GET /castings` | list batch | none-or-provider-specific | hash/redact credentials and URLs | `KitsuIntegrationProvider.fetchCastings` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.anime_staff` | `GET /anime/{id}/anime-staff` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `KitsuIntegrationProvider.fetchAnimeStaff` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.anime_productions` | `GET /anime/{id}/anime-productions` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `KitsuIntegrationProvider.fetchAnimeProductions` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.media_relationships` | `GET /anime/{id}/media-relationships` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `KitsuIntegrationProvider.fetchMediaRelationships` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.search.text` | `GET /anime` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `KitsuIntegrationProvider.searchAnimeByText` | `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `mdblist.user` | `GET /user` | single-item | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `mdblist.rating.batch` | `POST /rating/{mediaType}/{ratingType}` | batch ratings | api-key-or-token | hash/redact credentials and URLs | `MDBListIntegrationProvider.fetchRatings` | `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `mdblist.raw_url.list` | `GET {remoteUrl}` | paginated list | api-key-or-token | hash/redact credentials and URLs | `MDBListIntegrationProvider.accountCallSpec` | `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `omdb.season.ratings` | `GET /` | season batch | api-key-or-token | hash/redact credentials and URLs | `OmdbIntegrationProvider.getSeasonRatings` | `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `custom_imdb.title.bulk` | `POST /v1/ratings/bulk` | batch ratings | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `custom_imdb.episode.series` | `GET /v1/ratings/{tconst}` | season/episode ratings | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `trakt.movie.comments` | `GET /movies/{id}/comments/{sort}` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchMovieCommentsPage` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.show.comments` | `GET /shows/{id}/comments/{sort}` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchShowCommentsPage` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `theintrodb.media` | `GET /media` | single-item | none-or-provider-specific | hash/redact credentials and URLs | `IntroDbIntegrationProvider.getIntervals` | `app/src/main/java/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `aniskip.skip_times` | `GET /skip-times/{malId}/{episode}` | single-item | none-or-provider-specific | hash/redact credentials and URLs | `AniSkipIntegrationProvider.getSkipIntervals` | `app/src/main/java/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `animeskip.graphql` | `POST /graphql` | list batch | none-or-provider-specific | hash/redact credentials and URLs | `AnimeSkipIntegrationProvider.queryEpisodes` | `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `arm.imdb_bridge` | `GET /imdb` | bridge lookup | none-or-provider-specific | hash/redact credentials and URLs | `ArmIntegrationProvider.resolveImdbToAnilist` | `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `arm.ids_bridge` | `GET /ids` | bridge lookup | none-or-provider-specific | hash/redact credentials and URLs | `ArmIntegrationProvider.resolveMalToAnilist` | `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `rpdb.key_validation` | `GET /{apiKey}/isValid` | single-item | api-key-or-token | hash/redact credentials and URLs | `RpdbIntegrationProvider.validateApiKey` | `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `topposters.key_validation` | `GET /auth/verify/{apiKey}` | single-item | api-key-or-token | hash/redact credentials and URLs | `TopPostersIntegrationProvider.validateApiKey` | `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `rpdb.poster_template` | `GET {posterUrl}` | remote poster fetch | api-key-or-token | hash/redact credentials and URLs | `RpdbIntegrationProvider.fetchPoster` | `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `topposters.poster_template` | `GET {posterUrl}` | remote poster fetch | api-key-or-token | hash/redact credentials and URLs | `TopPostersIntegrationProvider.fetchPoster` | `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `topposters.thumbnail` | `GET /{api_key}/{id_type}/thumbnail/{media_id}/S{season}E{episode}.jpg` | thumbnail template | api-key-or-token | hash/redact credentials and URLs | `` | `` | EXEMPT |
| `addon.manifest` | `GET {manifestUrl}` | single-item | none-or-provider-specific | hash/redact credentials and URLs | `AddonManifestIntegrationProvider.getManifest` | `app/src/main/java/com/nexio/tv/data/integration/addon/AddonManifestIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `addon.catalog` | `GET {catalogUrl}` | paginated list | none-or-provider-specific | hash/redact credentials and URLs | `AddonCatalogIntegrationProvider.getCatalog` | `app/src/main/java/com/nexio/tv/data/integration/addon/AddonCatalogIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `addon.meta` | `GET {metaUrl}` | single-item enrichment | none-or-provider-specific | hash/redact credentials and URLs | `AddonMetaIntegrationProvider.getMeta` | `app/src/main/java/com/nexio/tv/data/integration/addon/AddonMetaIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `addon.streams` | `GET {streamUrl}` | stream list | none-or-provider-specific | hash/redact credentials and URLs | `AddonStreamIntegrationProvider.getStreams` | `app/src/main/java/com/nexio/tv/data/integration/addon/AddonStreamIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `addon.subtitles` | `GET {subtitleUrl}` | subtitle list | none-or-provider-specific | hash/redact credentials and URLs | `AddonSubtitleIntegrationProvider.getSubtitles` | `app/src/main/java/com/nexio/tv/data/integration/addon/AddonSubtitleIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `github.latest_release` | `GET /repos/{owner}/{repo}/releases/latest` | single-item | none-or-provider-specific | hash/redact credentials and URLs | `GitHubReleaseIntegrationProvider.fetchLatestRelease` | `app/src/main/java/com/nexio/tv/data/integration/github/GitHubReleaseIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `github.asset_download` | `GET {assetUrl}` | stream download | none-or-provider-specific | hash/redact credentials and URLs | `GitHubAssetDownloadIntegrationProvider.openDownload` | `app/src/main/java/com/nexio/tv/data/integration/github/GitHubAssetDownloadIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `subtitle.translation` | `ANY {requestUrl}` | single request | none-or-provider-specific | hash/redact credentials and URLs | `SubtitleTranslationIntegrationProvider.execute` | `app/src/main/java/com/nexio/tv/data/integration/subtitles/SubtitleTranslationIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `subtitle.source_download` | `GET {url}` | remote subtitle fetch | none-or-provider-specific | hash/redact credentials and URLs | `SubtitleSourceDownloadIntegrationProvider.execute` | `app/src/main/java/com/nexio/tv/data/integration/subtitles/SubtitleSourceDownloadIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `playback.opensubtitles_hash` | `GET {mediaUrl}` | range hash | none-or-provider-specific | hash/redact credentials and URLs | `OpenSubtitlesHashIntegrationProvider.spec` | `app/src/main/java/com/nexio/tv/data/integration/playback/OpenSubtitlesHashIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `playback.preflight_head` | `HEAD {streamUrl}` | single probe | none-or-provider-specific | hash/redact credentials and URLs | `PlaybackPreflightIntegrationProvider.isPlayable` | `app/src/main/java/com/nexio/tv/data/integration/playback/PlaybackPreflightIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `playback.preflight_range` | `GET {streamUrl}` | range probe | none-or-provider-specific | hash/redact credentials and URLs | `PlaybackPreflightIntegrationProvider.rangeProbeWorks` | `app/src/main/java/com/nexio/tv/data/integration/playback/PlaybackPreflightIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `shadow_collector.autoplay_upload` | `POST {baseUrl}/autoplay` | event upload | none-or-provider-specific | hash/redact credentials and URLs | `ShadowAutoplayUploadIntegrationProvider.uploadEvent` | `app/src/main/java/com/nexio/tv/data/integration/collector/ShadowAutoplayUploadIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `youtube_trailer.transport.execute` | `ANY {callUrl}` | single request | api-key-or-token | hash/redact credentials and URLs | `TrailerBackendProvider.resolveYouTubePlaybackSource` | `app/src/main/java/com/nexio/tv/data/integration/trailer/TrailerBackendProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `youtube_trailer.transport.probe` | `GET {url}` | single probe | api-key-or-token | hash/redact credentials and URLs | `YouTubeTrailerIntegrationProvider.probe` | `app/src/main/java/com/nexio/tv/data/integration/youtube/YouTubeTrailerIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `animeskip.shows` | `POST /graphql` | list batch | none-or-provider-specific | hash/redact credentials and URLs | `AnimeSkipIntegrationProvider.resolveShowIds` | `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `animeskip.key_validation` | `POST /graphql` | auth | none-or-provider-specific | hash/redact credentials and URLs | `AnimeSkipIntegrationProvider.validateClientId` | `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.search.movie` | `GET /search/movie` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.search.tv` | `GET /search/tv` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.search.people` | `GET /search/person` | paginated list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.searchPeople` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.search.companies` | `GET /search/company` | paginated list | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.searchCompanies` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tmdb.trending.movie` | `GET /trending/movie/day` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.trending.tv` | `GET /trending/tv/day` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.popular.movie` | `GET /movie/popular` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.popular.tv` | `GET /tv/popular` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.discover.movie` | `GET /discover/movie` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.discover.tv` | `GET /discover/tv` | paginated list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tmdb.key_validation` | `GET /configuration` | auth | api-key-or-token | hash/redact credentials and URLs | `TmdbIntegrationProvider.validateApiKey` | `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `kitsu.advanced_detail` | `GET {advancedDetailBatch}` | one-call multi-field enrichment | none-or-provider-specific | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `mdblist.key_validation` | `GET /user` | auth | api-key-or-token | hash/redact credentials and URLs | `MDBListIntegrationProvider.validateApiKey` | `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `omdb.key_validation` | `GET /` | auth | api-key-or-token | hash/redact credentials and URLs | `OmdbIntegrationProvider.validateApiKey` | `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `tvdb.reference.artwork_statuses` | `GET /artwork/statuses` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.series_statuses` | `GET /series/statuses` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.source_types` | `GET /sources/types` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.entity_types` | `GET /entities/types` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `tvdb.reference.company_types` | `GET /companies/types` | reference list | api-key-or-token | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `easy_debrid.lookup` | `POST /link/lookup` | playback lookup | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `easy_debrid.lookup_details` | `POST /link/lookup/details` | playback lookup | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `real_debrid.device_code` | `GET /oauth/v2/device/code` | auth bootstrap | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `real_debrid.device_credentials` | `GET /oauth/v2/device/credentials` | auth polling | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `real_debrid.token` | `POST /oauth/v2/token` | auth token exchange | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `real_debrid.account` | `GET /rest/1.0/user` | account | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `real_debrid.revoke_token` | `GET /rest/1.0/disable_access_token` | auth mutation | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `premiumize.list_all` | `GET /folder/list` | account list | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `simkl.last_activities` | `GET /sync/activities` | account state | account | hash/redact credentials and URLs | `SimklIntegrationProvider.getLastActivities` | `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `simkl.discovery` | `GET {discoveryUrl}` | paginated list | account | hash/redact credentials and URLs | `SimklIntegrationProvider.fetchDiscoveryBody` | `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `simkl.pin.start` | `GET /oauth/pin` | auth bootstrap | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `simkl.pin.status` | `GET /oauth/pin/{user_code}` | auth polling | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `simkl.user_settings` | `POST /users/settings` | account state | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `simkl.playback` | `GET /sync/playback/{type}` | account list | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `simkl.scrobble` | `POST /sync/scrobble` | mutation | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `torbox.torrent_list` | `GET /torrents/mylist` | account list | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `easy_debrid.account` | `GET /user` | account | account | hash/redact credentials and URLs | `EasyDebridIntegrationProvider.fetchAccountInfo` | `app/src/main/java/com/nexio/tv/data/integration/debrid/EasyDebridIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `premiumize.account` | `GET /account/info` | account | account | hash/redact credentials and URLs | `PremiumizeIntegrationProvider.fetchAccountInfo` | `app/src/main/java/com/nexio/tv/data/integration/debrid/PremiumizeIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `torbox.account` | `GET /user/me` | account | account | hash/redact credentials and URLs | `TorBoxIntegrationProvider.fetchAccountInfo` | `app/src/main/java/com/nexio/tv/data/integration/debrid/TorBoxIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.calendar.shows` | `GET /calendars/my/shows/{start_date}/{days}` | dated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchCalendarShows` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.trending.movies` | `GET /movies/trending` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchTrendingMovies` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.trending.shows` | `GET /shows/trending` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchTrendingShows` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.popular.movies` | `GET /movies/popular` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchPopularMovies` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.popular.shows` | `GET /shows/popular` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchPopularShows` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.recommended.shows` | `GET /recommendations/shows` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchRecommendations` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.popular.lists` | `GET /lists/popular` | paginated list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchPopularLists` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.user.lists` | `GET /users/{id}/lists` | list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchUserLists` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.user.list_items` | `GET /users/{id}/lists/{list_id}/items/{type}` | list | account | hash/redact credentials and URLs | `TraktIntegrationProvider.fetchUserListItems` | `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |
| `trakt.authorized_response` | `ANY {authorizedMutationOrRead}` | wrapper | account | hash/redact credentials and URLs | `` | `` | PLANNED_NOT_ACTIVE |
| `custom_imdb.transport.execute` | `ANY {resolvedCustomImdbRoute}` | dynamic bridge | api-key-or-token | hash/redact credentials and URLs | `CustomImdbRatingsIntegrationProvider.execute` | `app/src/main/java/com/nexio/tv/data/integration/imdb/CustomImdbRatingsIntegrationProvider.kt` | ACTIVE_RUNTIME_COVERED |

## Section E1 - MetadataRouter Readiness

Gate: `PASS_WITH_WARNINGS`

| Shape ID | Provider | Required for MetadataRouter | Status | Actual adapter method | Required action |
| --- | --- | ---: | --- | --- | --- |
| `tmdb.find.external_id` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.findByExternalId` | none |
| `tmdb.movie.core` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchMovieCore` | none |
| `tmdb.tv.core` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchEnrichment` | none |
| `tmdb.season.episodes` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchTvSeasonEpisodes` | none |
| `tmdb.movie.videos` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchMovieVideos` | none |
| `tmdb.tv.videos` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchTvVideos` | none |
| `tmdb.season.videos` | TMDB | no | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchSeasonVideos` | none |
| `tmdb.movie.recommendations` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchMovieRecommendations` | none |
| `tmdb.tv.recommendations` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchTvRecommendations` | none |
| `tmdb.movie.reviews` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchMovieReviews` | none |
| `tmdb.tv.reviews` | TMDB | yes | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.fetchTvReviews` | none |
| `tmdb.collection` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.person.detail` | TMDB | no | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.loadPersonDetails` | none |
| `tmdb.person.combined_credits` | TMDB | no | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.loadPersonCombinedCredits` | none |
| `tmdb.company.detail` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.network.detail` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.discover.movie.by_company` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.discover.tv.by_company_or_network` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.login` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.login` | none |
| `tvdb.remoteid.lookup` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.searchByRemoteId` | none |
| `tvdb.search` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.series.base` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.series.extended` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.fetchSeriesEnrichmentRuntime` | none |
| `tvdb.series.translation` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.fetchSeriesTranslationWithTrace` | none |
| `tvdb.series.episodes.season_type` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.fetchSeriesEpisodes` | none |
| `tvdb.series.episodes.language` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.fetchSeriesEpisodesTranslatedWithTrace` | none |
| `tvdb.episode.translation` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.fetchEpisodeTranslationWithTrace` | none |
| `tvdb.updates` | TVDB | yes | ACTIVE_RUNTIME_COVERED | `TvdbIntegrationProvider.runMaintenanceUpdate` | none |
| `tvdb.reference.artwork_types` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.genres` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.languages` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.content_ratings` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.season_types` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.person.extended` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `kitsu.discovery.trending` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuDiscoveryIntegrationProvider.fetchCatalog` | none |
| `kitsu.discovery.anime` | KITSU | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `kitsu.anime.core` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuIntegrationProvider.fetchEnrichment` | none |
| `kitsu.anime.episodes` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuIntegrationProvider.fetchEpisodeEnrichment` | none |
| `kitsu.castings` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuIntegrationProvider.fetchCastings` | none |
| `kitsu.anime_staff` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuIntegrationProvider.fetchAnimeStaff` | none |
| `kitsu.anime_productions` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuIntegrationProvider.fetchAnimeProductions` | none |
| `kitsu.media_relationships` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuIntegrationProvider.fetchMediaRelationships` | none |
| `kitsu.search.text` | KITSU | yes | ACTIVE_RUNTIME_COVERED | `KitsuIntegrationProvider.searchAnimeByText` | none |
| `mdblist.user` | MDBLIST | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `mdblist.rating.batch` | MDBLIST | no | ACTIVE_RUNTIME_COVERED | `MDBListIntegrationProvider.fetchRatings` | none |
| `mdblist.raw_url.list` | MDBLIST | no | ACTIVE_RUNTIME_COVERED | `MDBListIntegrationProvider.accountCallSpec` | none |
| `omdb.season.ratings` | OMDB | no | ACTIVE_RUNTIME_COVERED | `OmdbIntegrationProvider.getSeasonRatings` | none |
| `custom_imdb.title.bulk` | CUSTOM_IMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `custom_imdb.episode.series` | CUSTOM_IMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `trakt.movie.comments` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchMovieCommentsPage` | none |
| `trakt.show.comments` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchShowCommentsPage` | none |
| `theintrodb.media` | THEINTRODB | no | ACTIVE_RUNTIME_COVERED | `IntroDbIntegrationProvider.getIntervals` | none |
| `aniskip.skip_times` | ANISKIP | no | ACTIVE_RUNTIME_COVERED | `AniSkipIntegrationProvider.getSkipIntervals` | none |
| `animeskip.graphql` | ANIMESKIP | no | ACTIVE_RUNTIME_COVERED | `AnimeSkipIntegrationProvider.queryEpisodes` | none |
| `arm.imdb_bridge` | ARM | no | ACTIVE_RUNTIME_COVERED | `ArmIntegrationProvider.resolveImdbToAnilist` | none |
| `arm.ids_bridge` | ARM | no | ACTIVE_RUNTIME_COVERED | `ArmIntegrationProvider.resolveMalToAnilist` | none |
| `rpdb.key_validation` | RPDB | no | ACTIVE_RUNTIME_COVERED | `RpdbIntegrationProvider.validateApiKey` | none |
| `topposters.key_validation` | TOP_POSTERS | no | ACTIVE_RUNTIME_COVERED | `TopPostersIntegrationProvider.validateApiKey` | none |
| `rpdb.poster_template` | RPDB | no | ACTIVE_RUNTIME_COVERED | `RpdbIntegrationProvider.fetchPoster` | none |
| `topposters.poster_template` | TOP_POSTERS | no | ACTIVE_RUNTIME_COVERED | `TopPostersIntegrationProvider.fetchPoster` | none |
| `topposters.thumbnail` | TOP_POSTERS | no | EXEMPT | `` | keep exemption owner/review status current |
| `addon.manifest` | ADDON | no | ACTIVE_RUNTIME_COVERED | `AddonManifestIntegrationProvider.getManifest` | none |
| `addon.catalog` | ADDON | no | ACTIVE_RUNTIME_COVERED | `AddonCatalogIntegrationProvider.getCatalog` | none |
| `addon.meta` | ADDON | no | ACTIVE_RUNTIME_COVERED | `AddonMetaIntegrationProvider.getMeta` | none |
| `addon.streams` | ADDON | no | ACTIVE_RUNTIME_COVERED | `AddonStreamIntegrationProvider.getStreams` | none |
| `addon.subtitles` | ADDON | no | ACTIVE_RUNTIME_COVERED | `AddonSubtitleIntegrationProvider.getSubtitles` | none |
| `github.latest_release` | GITHUB | no | ACTIVE_RUNTIME_COVERED | `GitHubReleaseIntegrationProvider.fetchLatestRelease` | none |
| `github.asset_download` | GITHUB | no | ACTIVE_RUNTIME_COVERED | `GitHubAssetDownloadIntegrationProvider.openDownload` | none |
| `subtitle.translation` | SUBTITLE_TRANSLATION | no | ACTIVE_RUNTIME_COVERED | `SubtitleTranslationIntegrationProvider.execute` | none |
| `subtitle.source_download` | SUBTITLE_SOURCE_DOWNLOAD | no | ACTIVE_RUNTIME_COVERED | `SubtitleSourceDownloadIntegrationProvider.execute` | none |
| `playback.opensubtitles_hash` | SUBTITLE_SOURCE_DOWNLOAD | no | ACTIVE_RUNTIME_COVERED | `OpenSubtitlesHashIntegrationProvider.spec` | none |
| `playback.preflight_head` | ADDON | no | ACTIVE_RUNTIME_COVERED | `PlaybackPreflightIntegrationProvider.isPlayable` | none |
| `playback.preflight_range` | ADDON | no | ACTIVE_RUNTIME_COVERED | `PlaybackPreflightIntegrationProvider.rangeProbeWorks` | none |
| `shadow_collector.autoplay_upload` | SHADOW_COLLECTOR | no | ACTIVE_RUNTIME_COVERED | `ShadowAutoplayUploadIntegrationProvider.uploadEvent` | none |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | no | ACTIVE_RUNTIME_COVERED | `TrailerBackendProvider.resolveYouTubePlaybackSource` | none |
| `youtube_trailer.transport.probe` | YOUTUBE_TRAILER | no | ACTIVE_RUNTIME_COVERED | `YouTubeTrailerIntegrationProvider.probe` | none |
| `animeskip.shows` | ANIMESKIP | no | ACTIVE_RUNTIME_COVERED | `AnimeSkipIntegrationProvider.resolveShowIds` | none |
| `animeskip.key_validation` | ANIMESKIP | no | ACTIVE_RUNTIME_COVERED | `AnimeSkipIntegrationProvider.validateClientId` | none |
| `tmdb.search.movie` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.search.tv` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.search.people` | TMDB | no | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.searchPeople` | none |
| `tmdb.search.companies` | TMDB | no | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.searchCompanies` | none |
| `tmdb.trending.movie` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.trending.tv` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.popular.movie` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.popular.tv` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.discover.movie` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.discover.tv` | TMDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tmdb.key_validation` | TMDB | no | ACTIVE_RUNTIME_COVERED | `TmdbIntegrationProvider.validateApiKey` | none |
| `kitsu.advanced_detail` | KITSU | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `mdblist.key_validation` | MDBLIST | no | ACTIVE_RUNTIME_COVERED | `MDBListIntegrationProvider.validateApiKey` | none |
| `omdb.key_validation` | OMDB | no | ACTIVE_RUNTIME_COVERED | `OmdbIntegrationProvider.validateApiKey` | none |
| `tvdb.reference.artwork_statuses` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.series_statuses` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.source_types` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.entity_types` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `tvdb.reference.company_types` | TVDB | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `easy_debrid.lookup` | EASY_DEBRID | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `easy_debrid.lookup_details` | EASY_DEBRID | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `real_debrid.device_code` | REAL_DEBRID | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `real_debrid.device_credentials` | REAL_DEBRID | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `real_debrid.token` | REAL_DEBRID | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `real_debrid.account` | REAL_DEBRID | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `real_debrid.revoke_token` | REAL_DEBRID | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `premiumize.list_all` | PREMIUMIZE | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `simkl.last_activities` | SIMKL | no | ACTIVE_RUNTIME_COVERED | `SimklIntegrationProvider.getLastActivities` | none |
| `simkl.discovery` | SIMKL | no | ACTIVE_RUNTIME_COVERED | `SimklIntegrationProvider.fetchDiscoveryBody` | none |
| `simkl.pin.start` | SIMKL | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `simkl.pin.status` | SIMKL | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `simkl.user_settings` | SIMKL | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `simkl.playback` | SIMKL | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `simkl.scrobble` | SIMKL | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `torbox.torrent_list` | TORBOX | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `easy_debrid.account` | EASY_DEBRID | no | ACTIVE_RUNTIME_COVERED | `EasyDebridIntegrationProvider.fetchAccountInfo` | none |
| `premiumize.account` | PREMIUMIZE | no | ACTIVE_RUNTIME_COVERED | `PremiumizeIntegrationProvider.fetchAccountInfo` | none |
| `torbox.account` | TORBOX | no | ACTIVE_RUNTIME_COVERED | `TorBoxIntegrationProvider.fetchAccountInfo` | none |
| `trakt.calendar.shows` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchCalendarShows` | none |
| `trakt.trending.movies` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchTrendingMovies` | none |
| `trakt.trending.shows` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchTrendingShows` | none |
| `trakt.popular.movies` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchPopularMovies` | none |
| `trakt.popular.shows` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchPopularShows` | none |
| `trakt.recommended.shows` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchRecommendations` | none |
| `trakt.popular.lists` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchPopularLists` | none |
| `trakt.user.lists` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchUserLists` | none |
| `trakt.user.list_items` | TRAKT | no | ACTIVE_RUNTIME_COVERED | `TraktIntegrationProvider.fetchUserListItems` | none |
| `trakt.authorized_response` | TRAKT | no | PLANNED_NOT_ACTIVE | `` | no MetadataRouter blocker; classify as planned inventory until implemented |
| `custom_imdb.transport.execute` | CUSTOM_IMDB | no | ACTIVE_RUNTIME_COVERED | `CustomImdbRatingsIntegrationProvider.execute` | none |

## Section E2 - Header Policy Matrix

| API shape | Provider | Header policy | Required headers | Optional headers | Forbidden headers | Credential location | User-Agent policy | Response headers captured | Cache vary | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `tmdb.find.external_id` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.movie.core` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.tv.core` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.season.episodes` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.movie.videos` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.tv.videos` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.season.videos` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.movie.recommendations` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.tv.recommendations` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.movie.reviews` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.tv.reviews` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.collection` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.person.detail` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.person.combined_credits` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.company.detail` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.network.detail` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.discover.movie.by_company` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.discover.tv.by_company_or_network` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.login` | TVDB | `json-body-no-auth-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|api_key|Authorization` | body | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.remoteid.lookup` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.search` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.series.base` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.series.extended` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.series.translation` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `query.language` | PASS |
| `tvdb.series.episodes.season_type` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.series.episodes.language` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `query.language` | PASS |
| `tvdb.episode.translation` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `query.language` | PASS |
| `tvdb.updates` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.artwork_types` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.genres` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.languages` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `query.language` | PASS |
| `tvdb.reference.content_ratings` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.season_types` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.person.extended` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.discovery.trending` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.discovery.anime` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.anime.core` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.anime.episodes` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.castings` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.anime_staff` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.anime_productions` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.media_relationships` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.search.text` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `mdblist.user` | MDBLIST | `mdblist-api-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | query-or-path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `mdblist.rating.batch` | MDBLIST | `mdblist-api-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | query-or-path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `mdblist.raw_url.list` | MDBLIST | `mdblist-api-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | query-or-path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `omdb.season.ratings` | OMDB | `omdb-query-api-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | query | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `custom_imdb.title.bulk` | CUSTOM_IMDB | `custom-imdb-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `custom_imdb.episode.series` | CUSTOM_IMDB | `custom-imdb-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.movie.comments` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.show.comments` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `theintrodb.media` | THEINTRODB | `introdb-json-optional-bearer-v1` | `` | `Authorization` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `X-RateLimit-Limit|X-RateLimit-Remaining|X-RateLimit-Reset|X-UsageLimit-Limit|X-UsageLimit-Remaining|X-UsageLimit-Reset|Retry-After` | `` | PASS |
| `aniskip.skip_times` | ANISKIP | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `animeskip.graphql` | ANIMESKIP | `graphql-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `arm.imdb_bridge` | ARM | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `arm.ids_bridge` | ARM | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `rpdb.key_validation` | RPDB | `rpdb-json-api-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | query-or-path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `topposters.key_validation` | TOP_POSTERS | `topposters-image-path-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key|Authorization` | path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `rpdb.poster_template` | RPDB | `rpdb-image-path-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key|Authorization` | path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `topposters.poster_template` | TOP_POSTERS | `topposters-image-path-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key|Authorization` | path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `topposters.thumbnail` | TOP_POSTERS | `topposters-thumbnail-v1` | `` | `query.user_agent` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key|Authorization` | path | default-or-approved-browser-profile | `Retry-After` | `query.badge_position|query.badge_size|query.blur|query.user_agent_profile_id` | PASS |
| `addon.manifest` | ADDON | `addon-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `addon.catalog` | ADDON | `addon-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `addon.meta` | ADDON | `addon-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `addon.streams` | ADDON | `addon-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `addon.subtitles` | ADDON | `addon-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `github.latest_release` | GITHUB | `github-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `github.asset_download` | GITHUB | `github-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `subtitle.translation` | SUBTITLE_TRANSLATION | `subtitle-provider-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `subtitle.source_download` | SUBTITLE_SOURCE_DOWNLOAD | `subtitle-provider-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `playback.opensubtitles_hash` | SUBTITLE_SOURCE_DOWNLOAD | `subtitle-provider-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `playback.preflight_head` | ADDON | `addon-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `playback.preflight_range` | ADDON | `addon-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `shadow_collector.autoplay_upload` | SHADOW_COLLECTOR | `collector-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | `youtube-html-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `youtube_trailer.transport.probe` | YOUTUBE_TRAILER | `youtube-html-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `animeskip.shows` | ANIMESKIP | `graphql-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `animeskip.key_validation` | ANIMESKIP | `graphql-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.search.movie` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.search.tv` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.search.people` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.search.companies` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.trending.movie` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.trending.tv` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.popular.movie` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.popular.tv` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.discover.movie` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.discover.tv` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tmdb.key_validation` | TMDB | `tmdb-json-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `kitsu.advanced_detail` | KITSU | `public-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `mdblist.key_validation` | MDBLIST | `mdblist-api-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | query-or-path | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `omdb.key_validation` | OMDB | `omdb-query-api-key-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | query | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.artwork_statuses` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.series_statuses` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.source_types` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.entity_types` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `tvdb.reference.company_types` | TVDB | `tvdb-json-bearer-v1` | `Authorization` | `` | `X-Trakt-API-Key|simkl-api-key|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `easy_debrid.lookup` | EASY_DEBRID | `easy_debrid-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `easy_debrid.lookup_details` | EASY_DEBRID | `easy_debrid-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `real_debrid.device_code` | REAL_DEBRID | `real_debrid-oauth-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `real_debrid.device_credentials` | REAL_DEBRID | `real_debrid-oauth-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `real_debrid.token` | REAL_DEBRID | `real_debrid-oauth-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `real_debrid.account` | REAL_DEBRID | `real_debrid-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `real_debrid.revoke_token` | REAL_DEBRID | `real_debrid-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `premiumize.list_all` | PREMIUMIZE | `premiumize-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `simkl.last_activities` | SIMKL | `simkl-json-v1` | `simkl-api-key` | `` | `X-Trakt-API-Key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `simkl.discovery` | SIMKL | `simkl-json-v1` | `simkl-api-key` | `` | `X-Trakt-API-Key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `simkl.pin.start` | SIMKL | `simkl-json-v1` | `simkl-api-key` | `` | `X-Trakt-API-Key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `simkl.pin.status` | SIMKL | `simkl-json-v1` | `simkl-api-key` | `` | `X-Trakt-API-Key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `simkl.user_settings` | SIMKL | `simkl-json-v1` | `simkl-api-key` | `` | `X-Trakt-API-Key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `simkl.playback` | SIMKL | `simkl-json-v1` | `simkl-api-key` | `` | `X-Trakt-API-Key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `simkl.scrobble` | SIMKL | `simkl-json-v1` | `simkl-api-key` | `` | `X-Trakt-API-Key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `torbox.torrent_list` | TORBOX | `torbox-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `easy_debrid.account` | EASY_DEBRID | `easy_debrid-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `premiumize.account` | PREMIUMIZE | `premiumize-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `torbox.account` | TORBOX | `torbox-json-token-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.calendar.shows` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.trending.movies` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.trending.shows` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.popular.movies` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.popular.shows` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.recommended.shows` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.popular.lists` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.user.lists` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.user.list_items` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `trakt.authorized_response` | TRAKT | `trakt-json-v2` | `X-Trakt-API-Key|X-Trakt-API-Version` | `Authorization` | `simkl-api-key|X-TVDB-ApiKey|api_key` | header | nexio-default-user-agent | `Retry-After` | `` | PASS |
| `custom_imdb.transport.execute` | CUSTOM_IMDB | `custom-imdb-json-v1` | `` | `` | `X-Trakt-API-Key|simkl-api-key|X-TVDB-ApiKey|api_key` | none-or-provider-specific | nexio-default-user-agent | `Retry-After` | `` | PASS |

## Section E3 - Cache-Policy Conformance

| API shape | Provider | Expected cache contract | Expected policy | Actual policy | TTL | Stale after expiry | Scope | Codec | Verdict |
| --- | --- | --- | --- | --- | ---: | ---: | --- | --- | --- |
| `tmdb.find.external_id` | TMDB | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `tmdb.movie.core` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbEnrichment>` | PASS |
| `tmdb.tv.core` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbEnrichment>` | PASS |
| `tmdb.season.episodes` | TMDB | `season-batch-v1` | CacheFirst | CacheFirst | 24h | 7d | Global | `gsonCodec<TmdbSeasonResponse>` | PASS |
| `tmdb.movie.videos` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbVideosResponse>` | PASS |
| `tmdb.tv.videos` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbVideosResponse>` | PASS |
| `tmdb.season.videos` | TMDB | `season-batch-v1` | CacheFirst | CacheFirst | 24h | 7d | Global | `gsonCodec<TmdbVideosResponse>` | PASS |
| `tmdb.movie.recommendations` | TMDB | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Global | `gsonCodec<TmdbRecommendationsResponse>` | PASS |
| `tmdb.tv.recommendations` | TMDB | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Global | `gsonCodec<TmdbRecommendationsResponse>` | PASS |
| `tmdb.movie.reviews` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbReviewsResponse>` | PASS |
| `tmdb.tv.reviews` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbReviewsResponse>` | PASS |
| `tmdb.collection` | TMDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tmdb.person.detail` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbPersonResponse>` | PASS |
| `tmdb.person.combined_credits` | TMDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TmdbPersonCreditsResponse>` | PASS |
| `tmdb.company.detail` | TMDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tmdb.network.detail` | TMDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tmdb.discover.movie.by_company` | TMDB | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Global | `` | PASS |
| `tmdb.discover.tv.by_company_or_network` | TMDB | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Global | `` | PASS |
| `tvdb.login` | TVDB | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `tvdb.remoteid.lookup` | TVDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TvdbRemoteIdSearchResponse>` | PASS |
| `tvdb.search` | TVDB | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Global | `` | PASS |
| `tvdb.series.base` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.series.extended` | TVDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TvMetadataEnrichment>` | PASS |
| `tvdb.series.translation` | TVDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TvdbTranslationRecord>` | PASS |
| `tvdb.series.episodes.season_type` | TVDB | `season-batch-v1` | CacheFirst | CacheFirst | 24h | 7d | Global | `gsonCodec<TvdbSeriesEpisodesData>` | PASS |
| `tvdb.series.episodes.language` | TVDB | `season-batch-v1` | CacheFirst | CacheFirst | 24h | 7d | Global | `gsonCodec<TvdbSeriesEpisodesData>` | PASS |
| `tvdb.episode.translation` | TVDB | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TvdbTranslationRecord>` | PASS |
| `tvdb.updates` | TVDB | `account-state-observe-v1` | ObserveOnly | Disabled | none | none | Global | `IntegrationCodec<T>` | PASS |
| `tvdb.reference.artwork_types` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.reference.genres` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.reference.languages` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.reference.content_ratings` | TVDB | `ratings-dynamic-v1` | CacheFirst |  | 12h | 3d | Global | `` | PASS |
| `tvdb.reference.season_types` | TVDB | `season-batch-v1` | CacheFirst |  | 24h | 7d | Global | `` | PASS |
| `tvdb.person.extended` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `kitsu.discovery.trending` | KITSU | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `kitsu.discovery.anime` | KITSU | `account-state-observe-v1` | ObserveOnly |  | none | none | Global | `` | PASS |
| `kitsu.anime.core` | KITSU | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<TvMetadataEnrichment>` | PASS |
| `kitsu.anime.episodes` | KITSU | `season-batch-v1` | CacheFirst | CacheFirst | 24h | 7d | Global | `gsonCodec<Map<Pair<Int, Int>, TvEpisodeMetadata>>` | PASS |
| `kitsu.castings` | KITSU | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<KitsuCollectionResponse<KitsuCastingResource>>` | PASS |
| `kitsu.anime_staff` | KITSU | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<KitsuCollectionResponse<KitsuAnimeStaffResource>>` | PASS |
| `kitsu.anime_productions` | KITSU | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<KitsuCollectionResponse<KitsuAnimeProductionResource>>` | PASS |
| `kitsu.media_relationships` | KITSU | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<KitsuCollectionResponse<KitsuMediaRelationshipResource>>` | PASS |
| `kitsu.search.text` | KITSU | `account-state-observe-v1` | ObserveOnly | ObserveOnly | none | none | Global | `gsonCodec<KitsuCollectionResponse<KitsuAnimeResource>>` | PASS |
| `mdblist.user` | MDBLIST | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `mdblist.rating.batch` | MDBLIST | `ratings-dynamic-v1` | CacheFirst | CacheFirst | 12h | 3d | Global | `gsonCodec<MDBListRatingsResult>` | PASS |
| `mdblist.raw_url.list` | MDBLIST | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `omdb.season.ratings` | OMDB | `ratings-dynamic-v1` | CacheFirst | CacheFirst | 12h | 3d | Global | `gsonCodec<EpisodeRatingsCacheDto>` | PASS |
| `custom_imdb.title.bulk` | CUSTOM_IMDB | `account-state-observe-v1` | ObserveOnly |  | none | none | Global | `` | PASS |
| `custom_imdb.episode.series` | CUSTOM_IMDB | `account-state-observe-v1` | ObserveOnly |  | none | none | Global | `` | PASS |
| `trakt.movie.comments` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<TraktCommentsPage>` | PASS |
| `trakt.show.comments` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<TraktCommentsPage>` | PASS |
| `theintrodb.media` | THEINTRODB | `skip-segments-episode-v1` | CacheFirst | CacheFirst | 30d | 90d | Global | `gsonCodec<List<SkipInterval>>` | PASS |
| `aniskip.skip_times` | ANISKIP | `skip-segments-episode-v1` | CacheFirst | CacheFirst | 30d | 90d | Global | `gsonCodec<List<SkipInterval>>` | PASS |
| `animeskip.graphql` | ANIMESKIP | `skip-segments-episode-v1` | CacheFirst | CacheFirst | 30d | 90d | Global | `gsonCodec<List<AnimeSkipEpisode>>` | PASS |
| `arm.imdb_bridge` | ARM | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<List<String>>` | PASS |
| `arm.ids_bridge` | ARM | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Global | `gsonCodec<String?>` | PASS |
| `rpdb.key_validation` | RPDB | `disabled-no-cache-v1` | Disabled | Disabled | none | none | Global | `StringIntegrationCodec` | PASS |
| `topposters.key_validation` | TOP_POSTERS | `disabled-no-cache-v1` | Disabled | Disabled | none | none | Global | `StringIntegrationCodec` | PASS |
| `rpdb.poster_template` | RPDB | `poster-generated-v1` | CacheFirst | CacheFirst | 24h | 7d | Global | `ByteArrayIntegrationCodec` | PASS |
| `topposters.poster_template` | TOP_POSTERS | `poster-generated-v1` | CacheFirst | CacheFirst | 24h | 7d | Global | `ByteArrayIntegrationCodec` | PASS |
| `topposters.thumbnail` | TOP_POSTERS | `poster-generated-v1` | CacheFirst |  | 24h | 7d | Global | `` | PASS |
| `addon.manifest` | ADDON | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `addon.catalog` | ADDON | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `addon.meta` | ADDON | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `addon.streams` | ADDON | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `addon.subtitles` | ADDON | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `github.latest_release` | GITHUB | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `github.asset_download` | GITHUB | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `subtitle.translation` | SUBTITLE_TRANSLATION | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `subtitle.source_download` | SUBTITLE_SOURCE_DOWNLOAD | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `playback.opensubtitles_hash` | SUBTITLE_SOURCE_DOWNLOAD | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `playback.preflight_head` | ADDON | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `playback.preflight_range` | ADDON | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `shadow_collector.autoplay_upload` | SHADOW_COLLECTOR | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `youtube_trailer.transport.probe` | YOUTUBE_TRAILER | `disabled-no-cache-v1` | Disabled | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `animeskip.shows` | ANIMESKIP | `skip-segments-episode-v1` | CacheFirst | CacheFirst | 30d | 90d | Global | `gsonCodec<List<String>>` | PASS |
| `animeskip.key_validation` | ANIMESKIP | `disabled-no-cache-v1` | Disabled | Disabled | none | none | Global | `StringIntegrationCodec` | PASS |
| `tmdb.search.movie` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.search.tv` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.trending.movie` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.trending.tv` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.popular.movie` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.popular.tv` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.discover.movie` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.discover.tv` | TMDB | `disabled-no-cache-v1` | Disabled |  | none | none | Global | `` | PASS |
| `tmdb.key_validation` | TMDB | `disabled-no-cache-v1` | Disabled | Disabled | none | none | Global | `StringIntegrationCodec` | PASS |
| `kitsu.advanced_detail` | KITSU | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `mdblist.key_validation` | MDBLIST | `disabled-no-cache-v1` | Disabled | Disabled | none | none | Global | `StringIntegrationCodec` | PASS |
| `omdb.key_validation` | OMDB | `disabled-no-cache-v1` | Disabled | Disabled | none | none | Global | `StringIntegrationCodec` | PASS |
| `tvdb.reference.artwork_statuses` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.reference.series_statuses` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.reference.source_types` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.reference.entity_types` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `tvdb.reference.company_types` | TVDB | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `easy_debrid.lookup` | EASY_DEBRID | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `easy_debrid.lookup_details` | EASY_DEBRID | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Global | `` | PASS |
| `premiumize.list_all` | PREMIUMIZE | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Global | `` | PASS |
| `simkl.last_activities` | SIMKL | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Account | `` | PASS |
| `simkl.discovery` | SIMKL | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Account | `` | PASS |
| `simkl.pin.start` | SIMKL | `account-state-observe-v1` | ObserveOnly |  | none | none | Global | `` | PASS |
| `simkl.pin.status` | SIMKL | `account-state-observe-v1` | ObserveOnly |  | none | none | Global | `` | PASS |
| `simkl.user_settings` | SIMKL | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `simkl.playback` | SIMKL | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `simkl.scrobble` | SIMKL | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `torbox.torrent_list` | TORBOX | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Global | `` | PASS |
| `easy_debrid.account` | EASY_DEBRID | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `premiumize.account` | PREMIUMIZE | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `torbox.account` | TORBOX | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `trakt.calendar.shows` | TRAKT | `primary-metadata-core-v1` | CacheFirst | CacheFirst | 7d | 30d | Account | `gsonCodec<List<TraktCalendarEpisodeItemDto>>` | PASS |
| `trakt.trending.movies` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktTrendingMovieItemDto>>` | PASS |
| `trakt.trending.shows` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktTrendingShowItemDto>>` | PASS |
| `trakt.popular.movies` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktMovieDto>>` | PASS |
| `trakt.popular.shows` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktShowDto>>` | PASS |
| `trakt.recommended.shows` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktRecommendationItemDto>>` | PASS |
| `trakt.popular.lists` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktPopularListItemDto>>` | PASS |
| `trakt.user.lists` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktListSummaryDto>>` | PASS |
| `trakt.user.list_items` | TRAKT | `dynamic-list-v1` | CacheFirst | CacheFirst | 12h | 7d | Account | `gsonCodec<List<TraktListItemDto>>` | PASS |
| `trakt.authorized_response` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `custom_imdb.transport.execute` | CUSTOM_IMDB | `account-state-observe-v1` | ObserveOnly | ObserveOnlyOrMutation | none | none | Global | `` | PASS |
| `premiumize.cache_check` | PREMIUMIZE | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Account | `` | PASS |
| `premiumize.item_details` | PREMIUMIZE | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Account | `` | PASS |
| `real_debrid.add_magnet` | REAL_DEBRID | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `real_debrid.device_code` | REAL_DEBRID | `account-state-observe-v1` | ObserveOnly |  | none | none | Global | `` | PASS |
| `real_debrid.device_credentials` | REAL_DEBRID | `account-state-observe-v1` | ObserveOnly |  | none | none | Global | `` | PASS |
| `real_debrid.token` | REAL_DEBRID | `mutation-v1` | Mutation |  | none | none | Global | `` | PASS |
| `real_debrid.account` | REAL_DEBRID | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `real_debrid.revoke_token` | REAL_DEBRID | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `real_debrid.downloads` | REAL_DEBRID | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Account | `` | PASS |
| `real_debrid.instant_availability` | REAL_DEBRID | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Account | `` | PASS |
| `real_debrid.media_infos` | REAL_DEBRID | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `real_debrid.select_files` | REAL_DEBRID | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `real_debrid.torrents` | REAL_DEBRID | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Account | `` | PASS |
| `real_debrid.unrestrict_link` | REAL_DEBRID | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `premiumize.direct_download` | PREMIUMIZE | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `torbox.check_cached` | TORBOX | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Account | `` | PASS |
| `torbox.download_link` | TORBOX | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.checkin` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.device_code` | TRAKT | `disabled-no-cache-v1` | Disabled |  | none | none | Account | `` | PASS |
| `trakt.device_token` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.episode.history` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.episode.summary` | TRAKT | `primary-metadata-core-v1` | CacheFirst |  | 7d | 30d | Account | `` | PASS |
| `trakt.hidden_items` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.history.add` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.history.remove` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.last_activities` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.playback` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.playback.delete` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.recommendation.hide` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.scrobble` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.season.episodes` | TRAKT | `season-batch-v1` | CacheFirst |  | 24h | 7d | Account | `` | PASS |
| `trakt.show.progress_watched` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.token_refresh` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.token_revoke` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.user.list_create` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.user.list_delete` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.user.list_items.add` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.user.list_items.remove` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.user.list_update` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.user.lists_reorder` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.user.settings` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.user.stats` | TRAKT | `dynamic-list-v1` | CacheFirst |  | 12h | 7d | Account | `` | PASS |
| `trakt.watched` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.watched.shows` | TRAKT | `account-state-observe-v1` | ObserveOnly |  | none | none | Account | `` | PASS |
| `trakt.watchlist.add` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |
| `trakt.watchlist.remove` | TRAKT | `mutation-v1` | Mutation |  | none | none | Account | `` | PASS |

## Section E4 - Cache-Key Vary Conformance

| API shape | Include fields | Forbidden material | Actual template | Verdict |
| --- | --- | --- | --- | --- |
| `tmdb.find.external_id` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.movie.core` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:movie:$movieId:$normalizedLanguage:core:$providerToken:policy:$localizationPolicyVersion` | PASS |
| `tmdb.tv.core` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:$tmdbType:$tmdbId:$normalizedLanguage:enrichment:$providerToken` | PASS |
| `tmdb.season.episodes` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:tv:$tvId:season:$seasonNumber:episodes:$normalizedLanguage:policy:$localizationPolicyVersion` | PASS |
| `tmdb.movie.videos` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:movie:$movieId:videos:$normalizedLanguage` | PASS |
| `tmdb.tv.videos` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:tv:$tvId:videos:$normalizedLanguage` | PASS |
| `tmdb.season.videos` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:tv:$tvId:season:$seasonNumber:videos:$normalizedLanguage` | PASS |
| `tmdb.movie.recommendations` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:movie:$movieId:recommendations:$normalizedLanguage:page:$page` | PASS |
| `tmdb.tv.recommendations` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:tv:$tvId:recommendations:$normalizedLanguage:page:$page` | PASS |
| `tmdb.movie.reviews` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:movie:$movieId:reviews:$normalizedLanguage:page:$page` | PASS |
| `tmdb.tv.reviews` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:tv:$tvId:reviews:$normalizedLanguage:page:$page` | PASS |
| `tmdb.collection` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.person.detail` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:person:$personId:detail` | PASS |
| `tmdb.person.combined_credits` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:person:$personId:combined_credits` | PASS |
| `tmdb.company.detail` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.network.detail` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.discover.movie.by_company` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.discover.tv.by_company_or_network` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.login` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.remoteid.lookup` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tvdb:remoteid:$remoteId` | PASS |
| `tvdb.search` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.series.base` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.series.extended` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tvdb:series:$resolvedId:$normalizedLanguage:$providerToken:enrichment` | PASS |
| `tvdb.series.translation` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tvdbSeriesTranslationCacheKey(tvdbId` | PASS |
| `tvdb.series.episodes.season_type` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tvdb:series:$tvdbId:episodes:$seasonType:season:${season ?: ` | PASS |
| `tvdb.series.episodes.language` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tvdb:series:$tvdbId:episodes:$seasonType:$language:season:${season ?: ` | PASS |
| `tvdb.episode.translation` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tvdb:episode:$episodeId:translation:$language:policy:$localizationPolicyVersion` | PASS |
| `tvdb.updates` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tvdb:updates:$triggerKey` | PASS |
| `tvdb.reference.artwork_types` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.genres` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.languages` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.content_ratings` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.season_types` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.person.extended` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `kitsu.discovery.trending` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `kitsu.discovery.anime` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `kitsu.anime.core` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `kitsu:${mediaKind.name.lowercase()}:$rawId:enrichment` | PASS |
| `kitsu.anime.episodes` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `kitsu:${mediaKind.name.lowercase()}:$rawId:episodes` | PASS |
| `kitsu.castings` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `kitsu:${mediaKind.name.lowercase()}:$rawId:castings` | PASS |
| `kitsu.anime_staff` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `kitsu:${mediaKind.name.lowercase()}:$rawId:anime_staff` | PASS |
| `kitsu.anime_productions` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `kitsu:${mediaKind.name.lowercase()}:$rawId:anime_productions` | PASS |
| `kitsu.media_relationships` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `kitsu:${mediaKind.name.lowercase()}:$rawId:media_relationships` | PASS |
| `kitsu.search.text` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `mdblist.user` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `mdblist.rating.batch` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `mdblist:$mediaType:$imdbId:$providerHash:credentialHash:$credentialHash` | PASS |
| `mdblist.raw_url.list` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `omdb.season.ratings` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `omdb:$seriesImdbId:season:$season:credentialHash:$credentialHash` | PASS |
| `custom_imdb.title.bulk` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `custom_imdb.episode.series` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.movie.comments` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `accountCacheKey(session` | PASS |
| `trakt.show.comments` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `accountCacheKey(session` | PASS |
| `theintrodb.media` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `theintrodb:$contentId:$season:$episode` | PASS |
| `aniskip.skip_times` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `aniskip:$malId:$episode` | PASS |
| `animeskip.graphql` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `animeskip:episodes:$showId` | PASS |
| `arm.imdb_bridge` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `arm:imdb:$imdbId:anilist` | PASS |
| `arm.ids_bridge` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `arm:mal:$malId:anilist` | PASS |
| `rpdb.key_validation` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `rpdb:validate:credentialHash:$credentialHash` | PASS |
| `topposters.key_validation` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `topposters:validate:credentialHash:$credentialHash` | PASS |
| `rpdb.poster_template` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `runtime-request.cacheKey` | PASS |
| `topposters.poster_template` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `runtime-request.cacheKey` | PASS |
| `topposters.thumbnail` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash, user_agent_profile_id, badge_position, badge_size, blur` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `addon.manifest` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `addon.catalog` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `addon.meta` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `addon.streams` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `addon.subtitles` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `github.latest_release` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `github.asset_download` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `subtitle.translation` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `subtitle.source_download` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `playback.opensubtitles_hash` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `playback.preflight_head` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `playback.preflight_range` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `shadow_collector.autoplay_upload` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `youtube_trailer.transport.execute` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `youtube_trailer.transport.probe` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `animeskip.shows` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `animeskip:shows:$anilistId` | PASS |
| `animeskip.key_validation` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `animeskip:validate:credentialHash:$credentialHash` | PASS |
| `tmdb.search.movie` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.search.tv` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.trending.movie` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.trending.tv` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.popular.movie` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.popular.tv` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.discover.movie` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.discover.tv` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tmdb.key_validation` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `tmdb:validate:credentialHash:$credentialHash` | PASS |
| `kitsu.advanced_detail` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `mdblist.key_validation` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `mdblist:validate:credentialHash:$credentialHash` | PASS |
| `omdb.key_validation` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `omdb:validate:credentialHash:$credentialHash` | PASS |
| `tvdb.reference.artwork_statuses` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.series_statuses` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.source_types` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.entity_types` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `tvdb.reference.company_types` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `easy_debrid.lookup` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `easy_debrid.lookup_details` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `premiumize.list_all` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `simkl.last_activities` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `simkl.discovery` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `simkl.pin.start` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `simkl.pin.status` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `simkl.user_settings` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `simkl.playback` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `simkl.scrobble` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `torbox.torrent_list` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `easy_debrid.account` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `premiumize.account` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `torbox.account` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.calendar.shows` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `globalContentCacheKey("trakt:calendar:shows:start:$startDate:days:$days")` | PASS |
| `trakt.trending.movies` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `globalContentCacheKey("trakt:trending:movies:limit:$limit")` | PASS |
| `trakt.trending.shows` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `globalContentCacheKey("trakt:trending:shows:limit:$limit")` | PASS |
| `trakt.popular.movies` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `globalContentCacheKey("trakt:popular:movies:limit:$limit")` | PASS |
| `trakt.popular.shows` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `globalContentCacheKey("trakt:popular:shows:limit:$limit")` | PASS |
| `trakt.recommended.shows` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `globalContentCacheKey("trakt:recommendations:$type:limit:$limit")` | PASS |
| `trakt.popular.lists` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `accountCacheKey(session` | PASS |
| `trakt.user.lists` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `accountCacheKey(session` | PASS |
| `trakt.user.list_items` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `accountCacheKey(session` | PASS |
| `trakt.authorized_response` | `provider, apiShapeId, operation_inputs, schema_version, accountHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `custom_imdb.transport.execute` | `provider, apiShapeId, operation_inputs, schema_version, credentialHash` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `premiumize.cache_check` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `premiumize.item_details` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.add_magnet` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.device_code` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.device_credentials` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.token` | `provider, apiShapeId, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.account` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.revoke_token` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.downloads` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.instant_availability` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.media_infos` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.select_files` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.torrents` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `real_debrid.unrestrict_link` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `premiumize.direct_download` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `torbox.check_cached` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `torbox.download_link` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.checkin` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.device_code` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.device_token` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.episode.history` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.episode.summary` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.hidden_items` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.history.add` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.history.remove` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.last_activities` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.playback` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.playback.delete` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.recommendation.hide` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.scrobble` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.season.episodes` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.show.progress_watched` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.token_refresh` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.token_revoke` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.list_create` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.list_delete` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.list_items.add` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.list_items.remove` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.list_update` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.lists_reorder` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.settings` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.user.stats` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.watched` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.watched.shows` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.watchlist.add` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |
| `trakt.watchlist.remove` | `provider, apiShapeId, accountHash, operation_inputs, schema_version` | `raw Authorization token, raw API key, apiKey.hashCode` | `` | PASS |

## Section E5 - Provider Contract Provenance

| Provider | Lifecycle | Source file | Source type | Reviewed at | Reviewer | Used shapes | Active-required shapes | Verdict |
| --- | --- | --- | --- | --- | --- | ---: | ---: | --- |
| ADDON | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 7 | 0 | PASS |
| TMDB | ACTIVE_RUNTIME_COVERED | `apiblueprints/tmdb-contract.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 27 | 8 | PASS |
| TVDB | ACTIVE_RUNTIME_COVERED | `apiblueprints/tvdb-contract.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 21 | 6 | PASS |
| KITSU | ACTIVE_RUNTIME_COVERED | `apiblueprints/kitsu-contract.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 10 | 6 | PASS |
| TRAKT | ACTIVE_RUNTIME_COVERED | `apiblueprints/trakt-simkl.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 41 | 1 | PASS |
| SIMKL | ACTIVE_RUNTIME_COVERED | `apiblueprints/trakt-simkl.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 7 | 0 | PASS |
| OMDB | ACTIVE_RUNTIME_COVERED | `apiblueprints/tidb-mdblist.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 2 | 0 | PASS |
| CUSTOM_IMDB | ACTIVE_RUNTIME_COVERED | `apiblueprints/tidb-mdblist.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 3 | 0 | PASS |
| THEINTRODB | ACTIVE_RUNTIME_COVERED | `apiblueprints/tidb-mdblist.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 1 | 0 | PASS |
| ANISKIP | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 1 | 0 | PASS |
| ANIMESKIP | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 3 | 0 | PASS |
| ARM | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 2 | 0 | PASS |
| RPDB | ACTIVE_RUNTIME_COVERED | `apiblueprints/rpdb-topposters.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 2 | 0 | PASS |
| TOP_POSTERS | ACTIVE_RUNTIME_COVERED | `apiblueprints/rpdb-topposters.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 3 | 1 | PASS |
| REAL_DEBRID | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 12 | 0 | PASS |
| PREMIUMIZE | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 5 | 0 | PASS |
| TORBOX | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 4 | 0 | PASS |
| EASY_DEBRID | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 3 | 0 | PASS |
| SHADOW_COLLECTOR | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 1 | 0 | PASS |
| GITHUB | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 2 | 0 | PASS |
| YOUTUBE_TRAILER | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 2 | 0 | PASS |
| SUBTITLE_SOURCE_DOWNLOAD | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 2 | 0 | PASS |
| SUBTITLE_TRANSLATION | ACTIVE_RUNTIME_COVERED | `apiblueprints/full-audit.md` | audit scope classification | 2026-04-25 | IntegrationRuntime audit | 1 | 0 | PASS |
| MDBLIST | ACTIVE_RUNTIME_COVERED | `apiblueprints/tidb-mdblist.md` | human-reviewed blueprint brief | 2026-04-25 | IntegrationRuntime audit | 4 | 0 | PASS |

## Section E6 - Best-Practice Provider-Efficiency Conformance

| API shape | Provider | Rule | Verdict | Evidence |
| --- | --- | --- | --- | --- |
| `tmdb.find.external_id` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.movie.core` | TMDB | tmdb-detail-append-bundle | PASS | TMDB detail contract includes required append_to_response enrichment bundle |
| `tmdb.tv.core` | TMDB | tmdb-detail-append-bundle | PASS | TMDB detail contract includes required append_to_response enrichment bundle |
| `tmdb.season.episodes` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.movie.videos` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.tv.videos` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.season.videos` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.movie.recommendations` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.tv.recommendations` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.movie.reviews` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.tv.reviews` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.collection` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.person.detail` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.person.combined_credits` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.company.detail` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.network.detail` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.discover.movie.by_company` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.discover.tv.by_company_or_network` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.login` | TVDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tvdb.remoteid.lookup` | TVDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tvdb.search` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.series.base` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.series.extended` | TVDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tvdb.series.translation` | TVDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tvdb.series.episodes.season_type` | TVDB | tvdb-season-batch | PASS | TVDB season endpoint evidence is batch-oriented |
| `tvdb.series.episodes.language` | TVDB | tvdb-season-batch | PASS | TVDB season endpoint evidence is batch-oriented |
| `tvdb.episode.translation` | TVDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tvdb.updates` | TVDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tvdb.reference.artwork_types` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.reference.genres` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.reference.languages` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.reference.content_ratings` | TVDB | ratings-batch-where-available | WARN | ratings route is not proven batch-capable |
| `tvdb.reference.season_types` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.person.extended` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `kitsu.discovery.trending` | KITSU | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `kitsu.discovery.anime` | KITSU | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `kitsu.anime.core` | KITSU | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `kitsu.anime.episodes` | KITSU | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `kitsu.castings` | KITSU | kitsu-top-level-castings-include-graph | PASS | Kitsu castings contract uses top-level castings with person and character include graph |
| `kitsu.anime_staff` | KITSU | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `kitsu.anime_productions` | KITSU | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `kitsu.media_relationships` | KITSU | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `kitsu.search.text` | KITSU | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `mdblist.user` | MDBLIST | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `mdblist.rating.batch` | MDBLIST | ratings-batch-where-available | PASS | ratings contract is batch-capable or uses batch adapter evidence |
| `mdblist.raw_url.list` | MDBLIST | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `omdb.season.ratings` | OMDB | ratings-batch-where-available | PASS | ratings contract is batch-capable or uses batch adapter evidence |
| `custom_imdb.title.bulk` | CUSTOM_IMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `custom_imdb.episode.series` | CUSTOM_IMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.movie.comments` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.show.comments` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `theintrodb.media` | THEINTRODB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `aniskip.skip_times` | ANISKIP | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `animeskip.graphql` | ANIMESKIP | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `arm.imdb_bridge` | ARM | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `arm.ids_bridge` | ARM | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `rpdb.key_validation` | RPDB | poster-cache-key-all-output-varying-inputs | PASS | poster cache key contract captures output-varying inputs and forbids raw credentials |
| `topposters.key_validation` | TOP_POSTERS | poster-cache-key-all-output-varying-inputs | PASS | poster cache key contract captures output-varying inputs and forbids raw credentials |
| `rpdb.poster_template` | RPDB | poster-cache-key-all-output-varying-inputs | PASS | poster cache key contract captures output-varying inputs and forbids raw credentials |
| `topposters.poster_template` | TOP_POSTERS | poster-cache-key-all-output-varying-inputs | PASS | poster cache key contract captures output-varying inputs and forbids raw credentials |
| `topposters.thumbnail` | TOP_POSTERS | poster-cache-key-all-output-varying-inputs | PASS | poster cache key contract captures output-varying inputs and forbids raw credentials |
| `addon.manifest` | ADDON | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `addon.catalog` | ADDON | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `addon.meta` | ADDON | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `addon.streams` | ADDON | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `addon.subtitles` | ADDON | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `github.latest_release` | GITHUB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `github.asset_download` | GITHUB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `subtitle.translation` | SUBTITLE_TRANSLATION | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `subtitle.source_download` | SUBTITLE_SOURCE_DOWNLOAD | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `playback.opensubtitles_hash` | SUBTITLE_SOURCE_DOWNLOAD | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `playback.preflight_head` | ADDON | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `playback.preflight_range` | ADDON | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `shadow_collector.autoplay_upload` | SHADOW_COLLECTOR | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `youtube_trailer.transport.execute` | YOUTUBE_TRAILER | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `youtube_trailer.transport.probe` | YOUTUBE_TRAILER | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `animeskip.shows` | ANIMESKIP | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `animeskip.key_validation` | ANIMESKIP | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tmdb.search.movie` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.search.tv` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.trending.movie` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.trending.tv` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.popular.movie` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.popular.tv` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.discover.movie` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.discover.tv` | TMDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tmdb.key_validation` | TMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `kitsu.advanced_detail` | KITSU | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `mdblist.key_validation` | MDBLIST | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `omdb.key_validation` | OMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `tvdb.reference.artwork_statuses` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.reference.series_statuses` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.reference.source_types` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.reference.entity_types` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `tvdb.reference.company_types` | TVDB | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `easy_debrid.lookup` | EASY_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `easy_debrid.lookup_details` | EASY_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `premiumize.list_all` | PREMIUMIZE | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `simkl.last_activities` | SIMKL | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `simkl.discovery` | SIMKL | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `simkl.pin.start` | SIMKL | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `simkl.pin.status` | SIMKL | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `simkl.user_settings` | SIMKL | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `simkl.playback` | SIMKL | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `simkl.scrobble` | SIMKL | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `torbox.torrent_list` | TORBOX | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `easy_debrid.account` | EASY_DEBRID | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `premiumize.account` | PREMIUMIZE | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `torbox.account` | TORBOX | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.calendar.shows` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.trending.movies` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.trending.shows` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.popular.movies` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.popular.shows` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.recommended.shows` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.popular.lists` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.user.lists` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.user.list_items` | TRAKT | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `trakt.authorized_response` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `custom_imdb.transport.execute` | CUSTOM_IMDB | runtime-contract-baseline | PASS | runtime spec mapped to contract |
| `premiumize.cache_check` | PREMIUMIZE | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `premiumize.item_details` | PREMIUMIZE | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.add_magnet` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.device_code` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.device_credentials` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.token` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.account` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.revoke_token` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.downloads` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.instant_availability` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.media_infos` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.select_files` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.torrents` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `real_debrid.unrestrict_link` | REAL_DEBRID | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `premiumize.direct_download` | PREMIUMIZE | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `torbox.check_cached` | TORBOX | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `torbox.download_link` | TORBOX | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.checkin` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.device_code` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.device_token` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.episode.history` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.episode.summary` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.hidden_items` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.history.add` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.history.remove` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.last_activities` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.playback` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.playback.delete` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.recommendation.hide` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.scrobble` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.season.episodes` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.show.progress_watched` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.token_refresh` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.token_revoke` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.list_create` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.list_delete` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.list_items.add` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.list_items.remove` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.list_update` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.lists_reorder` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.settings` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.user.stats` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.watched` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.watched.shows` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.watchlist.add` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |
| `trakt.watchlist.remove` | TRAKT | runtime-contract-baseline | WARN | shape has no active runtime spec |

## Section F - Boundary And Bypass Report

No boundary violations detected by the static scanner.

## Section G - Runtime Event Audit

Sample events are written to `runtime-event-sample.jsonl`. Rows include `eventName`, source component, provider context, and redaction metadata. `DefaultIntegrationRuntime` emits the same phase model in tests, including fresh-cache hits with `loaderInvoked=false` and network starts with `networkStarted=true`.

## Exemptions

| Path | Host/provider | Reason | Owning file | Review status |
| --- | --- | --- | --- | --- |
| `@Named("playback") media transport bytes` | playback media hosts | out of scope for initial Android migration | `playback transport adapters` | documented |
| `@Named("addonStreams") stream transport` | addon stream hosts | out of scope for initial Android migration | `addon stream transport adapters` | documented |
| `raw media segment fetching` | media hosts | player transport bytes remain outside non-playback audit | `player transport` | documented |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/components/MonochromePosterPlaceholder.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/components/ProfileAvatarCircle.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/cast/CastDetailScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/detail/CastSection.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/detail/CompanyLogosSection.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/organization/OrganizationDetailScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/player/EpisodesSidePanel.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/player/LoadingOverlay.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/player/NextEpisodeCardOverlay.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlay.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/player/StreamComponents.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsDesignSystem.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreen.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt` | documented-temporary |
| `generic image model via Coil` | generic external artwork/avatar/logo hosts | temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests | `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt` | documented-temporary |
