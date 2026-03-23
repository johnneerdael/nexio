import { ref, reactive, watch } from 'vue'

export const useSettings = () => {
  // Try to use Supabase if available. 
  // Depending on environment, useSupabaseClient() might auto-import or require an import from '#imports'
  // const supabase = useSupabaseClient()

  const isSaving = ref(false)
  const error = ref<string | null>(null)

  // Initial State matching settings-sync.schema.json structure
  const settings = reactive({
    appearance: {
      theme: 'WHITE',
      font: 'INTER',
      localeTag: null
    },
    layout: {
      selectedLayout: 'MODERN',
      modernLandscapePostersEnabled: false,
      heroCatalogKeys: [],
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
    integrations: {
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
      mdblist: {
        enabled: false,
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
      gemini: {
        enabled: false
      },
      posterRatings: {
        rpdbEnabled: false,
        topPostersEnabled: false
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
        streamReuseLastLinkEnabled: false,
        streamReuseLastLinkCacheHours: 24,
        uniformStreamFormattingEnabled: false,
        groupStreamsAcrossAddonsEnabled: false,
        deduplicateGroupedStreamsEnabled: false,
        filterEpisodeMismatchStreamsEnabled: false,
        filterMovieYearMismatchStreamsEnabled: false,
        streamAutoPlayMode: 'MANUAL',
        streamAutoPlaySource: 'ALL_SOURCES',
        streamAutoPlaySelectedAddons: [],
        streamAutoPlayRegex: '',
        streamAutoPlayNextEpisodeEnabled: false,
        streamAutoPlayPreferBingeGroupForNextEpisode: true,
        nextEpisodeThresholdMode: 'PERCENTAGE',
        nextEpisodeThresholdPercent: 99.0,
        nextEpisodeThresholdMinutesBeforeEnd: 2.0
      },
      audio: {
        preferredAudioLanguage: 'device',
        secondaryPreferredAudioLanguage: null,
        skipSilence: false,
        decoderPriority: 1,
        tunnelingEnabled: false,
        experimentalDv7ToDv81Enabled: true,
        experimentalDtsIecPassthroughEnabled: false,
        experimentalDv7ToDv81PreserveMappingEnabled: false,
        experimentalDv5ToDv81Enabled: false
      },
      subtitles: {
        preferredLanguage: 'en',
        secondaryPreferredLanguage: null,
        subtitleOrganizationMode: 'BY_LANGUAGE',
        addonSubtitleStartupMode: 'ALL_SUBTITLES',
        size: 120,
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
        vodCacheSizeMode: 'AUTO',
        vodCacheSizeMb: 500,
        useParallelConnections: false,
        parallelConnectionCount: 2,
        parallelChunkSizeMb: 16,
        enableBufferLogs: false
      }
    },
    trakt: {
      continueWatchingDaysCap: 60,
      showUnairedNextUp: true,
      catalogEnabledSet: ['trakt_up_next', 'trakt_recommended_movies', 'trakt_recommended_shows', 'trakt_calendar_next_7_days'],
      catalogOrder: ['trakt_up_next', 'trakt_trending_movies', 'trakt_trending_shows', 'trakt_popular_movies', 'trakt_popular_shows', 'trakt_recommended_movies', 'trakt_recommended_shows', 'trakt_calendar_next_7_days'],
      selectedPopularListKeys: []
    },
    debug: {
      accountTabEnabled: false,
      syncCodeFeaturesEnabled: false,
      bufferLogsEnabled: false
    }
  })

  // Watch for logic rules (e.g. mutually exclusive rpdb and topPosters)
  watch(() => settings.integrations.posterRatings.rpdbEnabled, (newVal) => {
    if (newVal) {
      settings.integrations.posterRatings.topPostersEnabled = false
    }
  })

  watch(() => settings.integrations.posterRatings.topPostersEnabled, (newVal) => {
    if (newVal) {
      settings.integrations.posterRatings.rpdbEnabled = false
    }
  })

  const loadSettings = async () => {
    // try {
    //   const { data: { user } } = await supabase.auth.getUser()
    //   if (!user) return
      
    //   const { data, error: sbError } = await supabase
    //     .from('user_profiles')
    //     .select('settings_payload')
    //     .eq('user_id', user.id)
    //     .single()
        
    //   if (sbError) throw sbError
    //   if (data && data.settings_payload) {
    //     Object.assign(settings, data.settings_payload)
    //   }
    // } catch (e: any) {
    //   error.value = e.message
    // }
  }

  const saveSettings = async () => {
    isSaving.value = true
    error.value = null
    // try {
    //   const { data: { user } } = await supabase.auth.getUser()
    //   if (!user) throw new Error('Not authenticated')
      
    //   const { error: sbError } = await supabase
    //     .from('user_profiles')
    //     .update({ settings_payload: JSON.parse(JSON.stringify(settings)) })
    //     .eq('user_id', user.id)
        
    //   if (sbError) throw sbError
    // } catch (e: any) {
    //   error.value = e.message
    // } finally {
    //   isSaving.value = false
    // }
  }

  return {
    settings,
    isSaving,
    error,
    loadSettings,
    saveSettings
  }
}
