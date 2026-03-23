export type PortalTheme = 'CRIMSON' | 'OCEAN' | 'VIOLET' | 'EMERALD' | 'AMBER' | 'ROSE' | 'WHITE'
export type PortalFont = 'INTER' | 'DM_SANS' | 'OPEN_SANS'
export type PortalLocale = 'system' | 'en' | 'es' | 'fr' | 'de' | 'nl' | 'zh-CN'
export type LayoutMode = 'MODERN' | 'GRID' | 'CLASSIC'
export type FrameRateMode = 'OFF' | 'START' | 'START_STOP'
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
  | 'omdb_api_key'
  | 'imdb_api_key'
  | 'mdblist_api_key'
  | 'premiumize_api_key'
  | 'gemini_api_key'
  | 'rpdb_api_key'
  | 'top_posters_api_key'
  | 'realdebrid_access_token'
  | 'realdebrid_refresh_token'
  | 'trakt_access_token'
  | 'trakt_refresh_token'

export type SecretStatus = 'configured' | 'missing' | 'error'

export type AddonRecord = {
  id: string
  url: string
  name: string
  enabled: boolean
  manifestUrl: string
  parserPreset: 'GENERIC' | 'STREMTHRU' | 'TORRENTIO' | 'WEBSTREAMR'
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

export const ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 3

export type PortalIntegrations = {
  debrid: {
    premiumize: {
      configured: boolean
      customerId: number | null
    }
    realDebrid: {
      connected: boolean
      username: string
      pending: boolean
      deviceCode: string
      userCode: string
      verificationUrl: string
      expiresAt: number | null
    }
  }
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
  omdb: {
    enabled: boolean
  }
  imdb: {
    enabled: boolean
    baseUrl: string
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

export type HomeCatalogSyncSettings = {
  heroCatalogKeys: string[]
  homeCatalogOrderKeys: string[]
  disabledHomeCatalogKeys: string[]
}

export type TraktCatalogSyncSettings = {
  catalogEnabledSet: CatalogId[]
  catalogOrder: CatalogId[]
  selectedPopularListKeys: string[]
}

export type MDBListCatalogSyncSettings = {
  hiddenPersonalListKeys: string[]
  selectedTopListKeys: string[]
  catalogOrder: string[]
}

export type PortalCatalogs = {
  home: HomeCatalogSyncSettings
  trakt: TraktCatalogSyncSettings
  mdblist: MDBListCatalogSyncSettings
}

export type UniformStreamFormattingSettings = {
  enabled: boolean
  selectedTemplateId: string
  customTemplate?: {
    id: 'custom'
    label: string
    nameTemplate: string
    descriptionTemplate: string
  } | null
}

export type PortalSettings = {
  schemaVersion: number
  integrations: PortalIntegrations
  catalogs: PortalCatalogs
  formatter: UniformStreamFormattingSettings
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
