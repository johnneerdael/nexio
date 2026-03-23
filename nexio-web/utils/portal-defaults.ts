import type {
  AddonRecord,
  CatalogId,
  PortalSettings
} from '~/types/portal'
import { ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION } from '~/types/portal'

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

export const defaultSettings = (): PortalSettings => ({
  schemaVersion: ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
  integrations: {
    debrid: {
      premiumize: {
        configured: false,
        customerId: null
      },
      realDebrid: {
        connected: false,
        username: '',
        pending: false,
        deviceCode: '',
        userCode: '',
        verificationUrl: '',
        expiresAt: null
      }
    },
    tmdb: {
      enabled: false,
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
    omdb: {
      enabled: false
    },
    mdblist: {
      enabled: false,
      showTrakt: true,
      showImdb: true,
      showTmdb: true,
      showLetterboxd: true,
      showTomatoes: true,
      showAudience: true,
      showMetacritic: true
    },
    animeSkip: {
      enabled: false,
      clientId: ''
    },
    gemini: {
      enabled: false
    },
    posterRatings: {
      rpdbEnabled: false,
      topPostersEnabled: false
    },
    traktAuth: {
      connected: false,
      username: '',
      userSlug: '',
      connectedAt: null,
      pending: false
    }
  },
  catalogs: {
    home: {
      heroCatalogKeys: [],
      homeCatalogOrderKeys: [],
      disabledHomeCatalogKeys: []
    },
    trakt: {
      catalogEnabledSet: [
        'trakt_up_next',
        'trakt_recommended_movies',
        'trakt_recommended_shows',
        'trakt_calendar_next_7_days'
      ],
      catalogOrder: defaultTraktCatalogOrder,
      selectedPopularListKeys: []
    },
    mdblist: {
      hiddenPersonalListKeys: [],
      selectedTopListKeys: [],
      catalogOrder: []
    }
  },
  formatter: {
    enabled: true,
    selectedTemplateId: 'universal',
    customTemplate: null
  }
})

export const defaultAccountAddons = (): AddonRecord[] => [
  {
    id: 'addon-cinemeta',
    url: 'https://v3-cinemeta.strem.io',
    manifestUrl: 'https://v3-cinemeta.strem.io/manifest.json',
    parserPreset: 'GENERIC',
    name: 'Cinemeta',
    enabled: true,
    description: 'Default metadata and catalog provider.',
    installKind: 'manifest',
    publicQueryParams: {},
    secretRef: null,
    sortOrder: 0
  },
  {
    id: 'addon-opensubtitles',
    url: 'https://opensubtitles-v3.strem.io',
    manifestUrl: 'https://opensubtitles-v3.strem.io/manifest.json',
    parserPreset: 'GENERIC',
    name: 'OpenSubtitles',
    enabled: true,
    description: 'Default subtitle provider.',
    installKind: 'manifest',
    publicQueryParams: {},
    secretRef: null,
    sortOrder: 1
  }
]
