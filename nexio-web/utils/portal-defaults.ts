import type {
  AddonRecord,
  CatalogId,
  LinkedDevice,
  PluginRepository,
  PortalSettings,
  SyncExclusion
} from '~/types/portal'

export const defaultTraktCatalogOrder: CatalogId[] = [
  'trakt_up_next',
  'trakt_trending_movies',
  'trakt_trending_shows',
  'trakt_popular_movies',
  'trakt_popular_shows',
  'trakt_recommended_movies',
  'trakt_recommended_shows',
  'trakt_calendar_next_7_days'
]

export const defaultSyncExclusions: SyncExclusion[] = [
  { key: 'playback.audioTrailer.mapDV7ToHevc', reason: 'Dolby Vision handling is TV-specific.' },
  { key: 'playback.audioTrailer.experimentalDv7ToDv81Enabled', reason: 'DV7 conversion depends on device codec behavior.' },
  { key: 'playback.audioTrailer.experimentalDv7ToDv81PreserveMappingEnabled', reason: 'DV mapping behavior depends on local playback capability.' },
  { key: 'playback.audioTrailer.experimentalDv5ToDv81Enabled', reason: 'DV5 compatibility is device-specific.' },
  { key: 'playback.audioTrailer.experimentalDtsIecPassthroughEnabled', reason: 'Fire OS audio quirks must stay local to the device.' },
  { key: 'playback.bufferNetwork.vodCacheSizeMode', reason: 'Cache size should remain per-device.' },
  { key: 'playback.bufferNetwork.vodCacheSizeMb', reason: 'Cache size should remain per-device.' },
  { key: 'playback.bufferNetwork.useParallelConnections', reason: 'Parallel fetch tuning should remain per-device.' },
  { key: 'playback.bufferNetwork.parallelConnectionCount', reason: 'Parallel fetch tuning should remain per-device.' },
  { key: 'playback.bufferNetwork.parallelChunkSizeMb', reason: 'Parallel fetch tuning should remain per-device.' }
]

export const defaultSettings = (): PortalSettings => ({
  appearance: {
    theme: 'WHITE',
    font: 'INTER',
    localeTag: 'system'
  },
  layout: {
    selectedLayout: 'MODERN',
    modernLandscapePostersEnabled: false,
    heroCatalogKeys: [],
    homeCatalogOrderKeys: [],
    disabledHomeCatalogKeys: [],
    sidebarCollapsedByDefault: false,
    modernSidebarEnabled: false,
    modernSidebarBlurEnabled: false,
    heroSectionEnabled: true,
    searchDiscoverEnabled: true,
    posterLabelsEnabled: true,
    catalogAddonNameEnabled: true,
    catalogTypeSuffixEnabled: true,
    hideUnreleasedContent: false,
    blurUnwatchedEpisodes: false,
    preferExternalMetaAddonDetail: false,
    focusedPosterBackdropExpandEnabled: false,
    focusedPosterBackdropExpandDelaySeconds: 3,
    posterCardWidthDp: 126,
    posterCardCornerRadiusDp: 12
  },
  plugins: {
    pluginsEnabled: true,
    repositories: []
  },
  integrations: {
    tmdb: {
      enabled: false,
      apiKey: '',
      useArtwork: true,
      useBasicInfo: true,
      useDetails: true,
      useCredits: true,
      useProductions: true,
      useNetworks: true,
      useEpisodes: true,
      useMoreLikeThis: true,
      useCollections: true
    },
    mdblist: {
      enabled: false,
      apiKey: '',
      showTrakt: true,
      showImdb: true,
      showTmdb: true,
      showLetterboxd: true,
      showTomatoes: true,
      showAudience: true,
      showMetacritic: true,
      hiddenPersonalListKeys: [],
      selectedTopListKeys: [],
      catalogOrder: []
    },
    animeSkip: {
      enabled: false,
      clientId: ''
    },
    posterRatings: {
      rpdbEnabled: false,
      rpdbApiKey: '',
      topPostersEnabled: false,
      topPostersApiKey: ''
    },
    traktAuth: {
      connected: false,
      username: '',
      userSlug: '',
      connectedAt: null,
      pending: false,
      accessToken: '',
      refreshToken: '',
      tokenType: '',
      createdAt: null,
      expiresIn: null
    }
  },
  playback: {
    general: {
      loadingOverlayEnabled: true,
      pauseOverlayEnabled: true,
      osdClockEnabled: true,
      skipIntroEnabled: true,
      frameRateMatchingMode: 'OFF',
      resolutionMatchingEnabled: false
    },
    streamSelection: {
      playerPreference: 'INTERNAL',
      streamReuseLastLinkEnabled: false,
      streamReuseLastLinkCacheHours: 24,
      streamAutoPlayMode: 'MANUAL',
      streamAutoPlaySource: 'ALL_SOURCES',
      streamAutoPlaySelectedAddons: [],
      streamAutoPlayRegex: '',
      streamAutoPlayNextEpisodeEnabled: false,
      streamAutoPlayPreferBingeGroupForNextEpisode: true,
      nextEpisodeThresholdMode: 'PERCENTAGE',
      nextEpisodeThresholdPercent: 99,
      nextEpisodeThresholdMinutesBeforeEnd: 2
    },
    audioTrailer: {
      preferredAudioLanguage: 'device',
      secondaryPreferredAudioLanguage: null,
      skipSilence: false,
      decoderPriority: 1,
      tunnelingEnabled: false
    },
    subtitles: {
      preferredLanguage: 'en',
      secondaryPreferredLanguage: null,
      subtitleOrganizationMode: 'NONE',
      addonSubtitleStartupMode: 'ALL_SUBTITLES',
      size: 100,
      verticalOffset: 5,
      bold: false,
      textColor: -1,
      backgroundColor: 0,
      outlineEnabled: true,
      outlineColor: -16777216,
      useLibass: false
    },
    bufferNetwork: {
      minBufferMs: 20000,
      maxBufferMs: 50000,
      bufferForPlaybackMs: 3000,
      bufferForPlaybackAfterRebufferMs: 5000,
      targetBufferSizeMb: 100,
      backBufferDurationMs: 0,
      enableBufferLogs: false
    }
  },
  trakt: {
    continueWatchingDaysCap: 60,
    showUnairedNextUp: true,
    catalogEnabledSet: [
      'trakt_up_next',
      'trakt_recommended_movies',
      'trakt_recommended_shows',
      'trakt_calendar_next_7_days'
    ],
    catalogOrder: defaultTraktCatalogOrder,
    selectedPopularListKeys: []
  },
  debug: {
    accountTabEnabled: false,
    syncCodeFeaturesEnabled: false,
    bufferLogsEnabled: false
  }
})

export const defaultAccountAddons = (): AddonRecord[] => [
  {
    id: 'addon-cinemeta',
    url: 'https://v3-cinemeta.strem.io',
    manifestUrl: 'https://v3-cinemeta.strem.io/manifest.json',
    name: 'Cinemeta',
    enabled: true,
    description: 'Default metadata and catalog provider.',
    sortOrder: 0
  },
  {
    id: 'addon-opensubtitles',
    url: 'https://opensubtitles-v3.strem.io',
    manifestUrl: 'https://opensubtitles-v3.strem.io/manifest.json',
    name: 'OpenSubtitles',
    enabled: true,
    description: 'Default subtitle provider.',
    sortOrder: 1
  }
]

export const demoRepositories = (): PluginRepository[] => []

export const demoDevices = (): LinkedDevice[] => [
  {
    id: 'device-living-room',
    name: 'Living Room TV',
    model: 'Fire TV Cube',
    platform: 'Fire OS',
    status: 'online',
    lastSeenAt: new Date().toISOString()
  },
  {
    id: 'device-bedroom',
    name: 'Bedroom OLED',
    model: 'Chromecast 4K',
    platform: 'Android TV',
    status: 'idle',
    lastSeenAt: new Date(Date.now() - 1000 * 60 * 14).toISOString()
  }
]
