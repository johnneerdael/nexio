export type PortalTheme = 'CRIMSON' | 'OCEAN' | 'VIOLET' | 'EMERALD' | 'AMBER' | 'ROSE' | 'WHITE'
export type PortalFont = 'INTER' | 'DM_SANS' | 'OPEN_SANS'
export type PortalLocale = 'system' | 'en' | 'es' | 'fr' | 'de' | 'nl' | 'zh-CN'
export type LayoutMode = 'MODERN' | 'GRID' | 'CLASSIC'
export type FrameRateMode = 'OFF' | 'START' | 'START_STOP'
export type PlayerPreference = 'INTERNAL' | 'EXTERNAL' | 'ASK_EVERY_TIME'
export type StreamAutoPlayMode = 'MANUAL' | 'FIRST_STREAM' | 'REGEX_MATCH'
export type StreamAutoPlaySource = 'ALL_SOURCES' | 'INSTALLED_ADDONS_ONLY'
export type ThresholdMode = 'PERCENTAGE' | 'MINUTES_BEFORE_END'
export type SubtitleOrganizationMode = 'NONE' | 'BY_LANGUAGE' | 'BY_ADDON'
export type AddonSubtitleStartupMode = 'FAST_STARTUP' | 'PREFERRED_ONLY' | 'ALL_SUBTITLES'

export type CatalogId =
  | 'trakt_up_next'
  | 'trakt_trending_movies'
  | 'trakt_trending_shows'
  | 'trakt_popular_movies'
  | 'trakt_popular_shows'
  | 'trakt_recommended_movies'
  | 'trakt_recommended_shows'
  | 'trakt_calendar_next_7_days'

export type SecretType =
  | 'addon_credential'
  | 'tmdb_api_key'
  | 'mdblist_api_key'
  | 'gemini_api_key'
  | 'rpdb_api_key'
  | 'top_posters_api_key'
  | 'trakt_access_token'
  | 'trakt_refresh_token'

export type SecretStatus = 'configured' | 'missing' | 'error'

export type AddonRecord = {
  id: string
  url: string
  name: string
  enabled: boolean
  manifestUrl: string
  description?: string
  logo?: string
  transportUrl?: string
  installKind?: 'manifest' | 'configured'
  publicQueryParams?: Record<string, string>
  secretRef?: string | null
  sortOrder: number
}

export type AddonCatalogRecord = {
  key: string
  disableKey: string
  addonId: string
  addonName: string
  addonUrl: string
  catalogId: string
  catalogName: string
  type: string
  source: 'addon' | 'trakt' | 'mdblist'
  isSearchOnly: boolean
}

export type AddonManifestInspection = {
  addonUrl: string
  manifestUrl: string
  addonId: string
  addonName: string
  description?: string
  logo?: string
  version?: string
  types: string[]
  resources: string[]
  catalogs: AddonCatalogRecord[]
  error?: string
}

export type LinkedDevice = {
  id: string
  deviceUserId?: string
  name: string
  model: string
  lastSeenAt: string
  status: 'online' | 'idle' | 'offline'
  platform: string
}

export type TraktDeviceFlow = {
  deviceCode: string
  userCode: string
  verificationUrl: string
  verificationUrlComplete?: string
  expiresIn: number
  interval: number
  startedAt: string
}

export type TraktPopularListOption = {
  key: string
  userId: string
  listId: string
  catalogIdBase: string
  title: string
  itemCount: number
}

export type SecretMetadata = {
  id: string
  secretType: SecretType
  secretRef: string
  maskedPreview: string | null
  status: SecretStatus
  version: number
  updatedAt: string | null
}

export type MDBListListOption = {
  key: string
  owner: string
  listId: string
  title: string
  itemCount: number
  isPersonal: boolean
}

export type PortalSession = {
  accessToken: string
  refreshToken: string
  expiresAt?: number
  user: {
    id: string
    email: string
  }
}

export type PortalSettings = {
  appearance: {
    theme: PortalTheme
    font: PortalFont
    localeTag: PortalLocale
  }
  layout: {
    selectedLayout: LayoutMode
    modernLandscapePostersEnabled: boolean
    heroCatalogKeys: string[]
    homeCatalogOrderKeys: string[]
    disabledHomeCatalogKeys: string[]
    sidebarCollapsedByDefault: boolean
    modernSidebarEnabled: boolean
    modernSidebarBlurEnabled: boolean
    heroSectionEnabled: boolean
    searchDiscoverEnabled: boolean
    posterLabelsEnabled: boolean
    catalogAddonNameEnabled: boolean
    catalogTypeSuffixEnabled: boolean
    hideUnreleasedContent: boolean
    blurUnwatchedEpisodes: boolean
    preferExternalMetaAddonDetail: boolean
    focusedPosterBackdropExpandEnabled: boolean
    focusedPosterBackdropExpandDelaySeconds: number
    posterCardWidthDp: number
    posterCardCornerRadiusDp: number
  }
  integrations: {
    tmdb: {
      enabled: boolean
      useArtwork: boolean
      useBasicInfo: boolean
      useDetails: boolean
      useCredits: boolean
      useProductions: boolean
      useNetworks: boolean
      useEpisodes: boolean
      useMoreLikeThis: boolean
      useCollections: boolean
    }
    mdblist: {
      enabled: boolean
      showTrakt: boolean
      showImdb: boolean
      showTmdb: boolean
      showLetterboxd: boolean
      showTomatoes: boolean
      showAudience: boolean
      showMetacritic: boolean
      hiddenPersonalListKeys: string[]
      selectedTopListKeys: string[]
      catalogOrder: string[]
    }
    animeSkip: {
      enabled: boolean
      clientId: string
    }
    gemini: {
      enabled: boolean
    }
    posterRatings: {
      rpdbEnabled: boolean
      topPostersEnabled: boolean
    }
    traktAuth: {
      connected: boolean
      username: string
      userSlug: string
      connectedAt: string | null
      pending: boolean
    }
  }
  playback: {
    general: {
      loadingOverlayEnabled: boolean
      pauseOverlayEnabled: boolean
      osdClockEnabled: boolean
      skipIntroEnabled: boolean
      frameRateMatchingMode: FrameRateMode
      resolutionMatchingEnabled: boolean
    }
    streamSelection: {
      playerPreference: PlayerPreference
      streamReuseLastLinkEnabled: boolean
      streamReuseLastLinkCacheHours: number
      uniformStreamFormattingEnabled: boolean
      groupStreamsAcrossAddonsEnabled: boolean
      deduplicateGroupedStreamsEnabled: boolean
      filterWebDolbyVisionStreamsEnabled: boolean
      filterEpisodeMismatchStreamsEnabled: boolean
      filterMovieYearMismatchStreamsEnabled: boolean
      streamAutoPlayMode: StreamAutoPlayMode
      streamAutoPlaySource: StreamAutoPlaySource
      streamAutoPlaySelectedAddons: string[]
      streamAutoPlayRegex: string
      streamAutoPlayNextEpisodeEnabled: boolean
      streamAutoPlayPreferBingeGroupForNextEpisode: boolean
      nextEpisodeThresholdMode: ThresholdMode
      nextEpisodeThresholdPercent: number
      nextEpisodeThresholdMinutesBeforeEnd: number
    }
    audio: {
      preferredAudioLanguage: string
      secondaryPreferredAudioLanguage: string | null
      skipSilence: boolean
      decoderPriority: number
      tunnelingEnabled: boolean
    }
    subtitles: {
      preferredLanguage: string
      secondaryPreferredLanguage: string | null
      subtitleOrganizationMode: SubtitleOrganizationMode
      addonSubtitleStartupMode: AddonSubtitleStartupMode
      size: number
      verticalOffset: number
      bold: boolean
      textColor: number
      backgroundColor: number
      outlineEnabled: boolean
      outlineColor: number
      useLibass: boolean
    }
    bufferNetwork: {
      minBufferMs: number
      maxBufferMs: number
      bufferForPlaybackMs: number
      bufferForPlaybackAfterRebufferMs: number
      targetBufferSizeMb: number
      backBufferDurationMs: number
      enableBufferLogs: boolean
    }
  }
  trakt: {
    continueWatchingDaysCap: number
    showUnairedNextUp: boolean
    catalogEnabledSet: CatalogId[]
    catalogOrder: CatalogId[]
    selectedPopularListKeys: string[]
  }
  debug: {
    accountTabEnabled: boolean
    syncCodeFeaturesEnabled: boolean
    bufferLogsEnabled: boolean
  }
}

export type SyncExclusion = {
  key: string
  reason: string
}

export type PortalSnapshot = {
  settings: PortalSettings
  addons: AddonRecord[]
  secretStatuses: SecretMetadata[]
  linkedDevices: LinkedDevice[]
  syncRevision: number
  lastSyncedAt: string | null
}

export type BootstrapPayload = {
  session: PortalSession | null
  snapshot: PortalSnapshot
}
