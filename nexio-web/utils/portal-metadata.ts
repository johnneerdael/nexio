import type { CatalogId, PortalSettings } from '~/types/portal'

export type FieldOption = {
  label: string
  value: string | number
}

export type PortalField = {
  path: string
  label: string
  description: string
  kind: 'toggle' | 'select' | 'slider' | 'text' | 'secret' | 'list'
  min?: number
  max?: number
  step?: number
  options?: FieldOption[]
  placeholder?: string
}

export type PortalGroup = {
  id: string
  title: string
  subtitle: string
  fields: PortalField[]
}

export const traktCatalogLabels: Record<CatalogId, string> = {
  trakt_up_next: 'Up Next',
  trakt_trending_movies: 'Trending Movies',
  trakt_trending_shows: 'Trending Shows',
  trakt_popular_movies: 'Popular Movies',
  trakt_popular_shows: 'Popular Shows',
  trakt_recommended_movies: 'Recommended Movies',
  trakt_recommended_shows: 'Recommended Shows',
  trakt_calendar_next_7_days: 'Calendar'
}

export const accountGroups: Record<string, PortalGroup[]> = {
  appearance: [
    {
      id: 'appearance-core',
      title: 'Visual Identity',
      subtitle: 'Match the portal to the polished, premium look of the player.',
      fields: [
        {
          path: 'appearance.theme',
          label: 'Theme',
          description: 'Sets the overall accent family used by the app and the web portal.',
          kind: 'select',
          options: [
            { label: 'White', value: 'WHITE' },
            { label: 'Crimson', value: 'CRIMSON' },
            { label: 'Ocean', value: 'OCEAN' },
            { label: 'Violet', value: 'VIOLET' },
            { label: 'Emerald', value: 'EMERALD' },
            { label: 'Amber', value: 'AMBER' },
            { label: 'Rose', value: 'ROSE' }
          ]
        },
        {
          path: 'appearance.font',
          label: 'Font',
          description: 'Choose the typography system for the synced interface.',
          kind: 'select',
          options: [
            { label: 'Inter', value: 'INTER' },
            { label: 'DM Sans', value: 'DM_SANS' },
            { label: 'Open Sans', value: 'OPEN_SANS' }
          ]
        },
        {
          path: 'appearance.localeTag',
          label: 'Language',
          description: 'Keeps the app language aligned across every connected TV.',
          kind: 'select',
          options: [
            { label: 'System default', value: 'system' },
            { label: 'English', value: 'en' },
            { label: 'Español', value: 'es' },
            { label: 'Français', value: 'fr' },
            { label: 'Deutsch', value: 'de' },
            { label: 'Nederlands', value: 'nl' },
            { label: '中文（简体）', value: 'zh-CN' }
          ]
        }
      ]
    }
  ],
  layout: [
    {
      id: 'layout-mode',
      title: 'Layout System',
      subtitle: 'Control the home screen composition, emphasis, and motion.',
      fields: [
        {
          path: 'layout.selectedLayout',
          label: 'Primary layout',
          description: 'Switches the core layout shell used by the app.',
          kind: 'select',
          options: [
            { label: 'Modern', value: 'MODERN' },
            { label: 'Grid', value: 'GRID' },
            { label: 'Classic', value: 'CLASSIC' }
          ]
        },
        { path: 'layout.modernLandscapePostersEnabled', label: 'Landscape hero cards', description: 'Use wider posters for modern cinematic rails.', kind: 'toggle' },
        { path: 'layout.heroCatalogKeys', label: 'Hero catalogs', description: 'CSV list of catalog keys to feature at the top of home.', kind: 'list', placeholder: 'trakt_up_next, cinemeta_featured' },
        { path: 'layout.homeCatalogOrderKeys', label: 'Catalog order', description: 'Define the ordered home feed rail stack.', kind: 'list', placeholder: 'trakt_up_next, mdblist_top_250, addon_new_releases' },
        { path: 'layout.disabledHomeCatalogKeys', label: 'Disabled catalogs', description: 'Catalog keys hidden from the account-wide home layout.', kind: 'list', placeholder: 'addon_experimental, old_discovery_feed' },
        { path: 'layout.sidebarCollapsedByDefault', label: 'Collapsed sidebar', description: 'Open the classic navigation in compact mode by default.', kind: 'toggle' },
        { path: 'layout.modernSidebarEnabled', label: 'Modern sidebar', description: 'Use the newer translucent navigation treatment.', kind: 'toggle' },
        { path: 'layout.modernSidebarBlurEnabled', label: 'Sidebar blur', description: 'Applies depth and glass blur to the modern navigation.', kind: 'toggle' },
        { path: 'layout.heroSectionEnabled', label: 'Hero section', description: 'Show a full-width hero section on the home view.', kind: 'toggle' },
        { path: 'layout.searchDiscoverEnabled', label: 'Search discover block', description: 'Adds suggestions and discovery prompts inside search.', kind: 'toggle' },
        { path: 'layout.posterLabelsEnabled', label: 'Poster labels', description: 'Show metadata labels on posters.', kind: 'toggle' },
        { path: 'layout.catalogAddonNameEnabled', label: 'Catalog addon label', description: 'Display addon names on catalog headers.', kind: 'toggle' },
        { path: 'layout.catalogTypeSuffixEnabled', label: 'Catalog type suffix', description: 'Append type suffixes to catalog titles.', kind: 'toggle' },
        { path: 'layout.hideUnreleasedContent', label: 'Hide unreleased content', description: 'Removes unreleased titles from home discovery.', kind: 'toggle' },
        { path: 'layout.blurUnwatchedEpisodes', label: 'Blur unwatched episodes', description: 'Softens artwork for unseen episodes in rail views.', kind: 'toggle' },
        { path: 'layout.preferExternalMetaAddonDetail', label: 'Prefer external detail metadata', description: 'Use metadata from external addons when available.', kind: 'toggle' },
        { path: 'layout.focusedPosterBackdropExpandEnabled', label: 'Backdrop expansion', description: 'Expands the focused backdrop with subtle animation.', kind: 'toggle' },
        { path: 'layout.focusedPosterBackdropExpandDelaySeconds', label: 'Backdrop delay', description: 'Delay before the focused backdrop expands.', kind: 'slider', min: 0, max: 10, step: 1 },
        { path: 'layout.posterCardWidthDp', label: 'Poster width', description: 'Tighten or relax poster density.', kind: 'select', options: [104, 112, 120, 126, 134, 140].map((value) => ({ label: `${value} dp`, value })) },
        { path: 'layout.posterCardCornerRadiusDp', label: 'Poster radius', description: 'Controls card curvature for the entire UI.', kind: 'select', options: [0, 4, 8, 12, 16].map((value) => ({ label: `${value} dp`, value })) }
      ]
    }
  ],
  integrations: [
    {
      id: 'tmdb',
      title: 'TMDB',
      subtitle: 'Metadata enrichment switches for artwork, credits, and detailed discovery.',
      fields: [
        { path: 'integrations.tmdb.enabled', label: 'Enable TMDB', description: 'Turns TMDB metadata enrichment on account-wide.', kind: 'toggle' },
        { path: 'integrations.tmdb.useArtwork', label: 'Use artwork', description: 'Prefer TMDB artwork when available.', kind: 'toggle' },
        { path: 'integrations.tmdb.useBasicInfo', label: 'Basic info', description: 'Use TMDB summary metadata.', kind: 'toggle' },
        { path: 'integrations.tmdb.useDetails', label: 'Detailed info', description: 'Pull extended detail metadata.', kind: 'toggle' },
        { path: 'integrations.tmdb.useCredits', label: 'Credits', description: 'Include cast and crew.', kind: 'toggle' },
        { path: 'integrations.tmdb.useProductions', label: 'Productions', description: 'Add production company details.', kind: 'toggle' },
        { path: 'integrations.tmdb.useNetworks', label: 'Networks', description: 'Show network and studio data.', kind: 'toggle' },
        { path: 'integrations.tmdb.useEpisodes', label: 'Episode data', description: 'Use TMDB episode metadata.', kind: 'toggle' },
        { path: 'integrations.tmdb.useMoreLikeThis', label: 'More like this', description: 'Enable related-title recommendations.', kind: 'toggle' },
        { path: 'integrations.tmdb.useCollections', label: 'Collections', description: 'Group franchise collections automatically.', kind: 'toggle' }
      ]
    },
    {
      id: 'mdblist',
      title: 'MDBList',
      subtitle: 'Ratings, catalog curation, and personal list controls. The API key is stored separately as a secret.',
      fields: [
        { path: 'integrations.mdblist.enabled', label: 'Enable MDBList', description: 'Turns MDBList ratings and rails on.', kind: 'toggle' },
        { path: 'integrations.mdblist.showTrakt', label: 'Show Trakt ratings', description: 'Display Trakt scoring.', kind: 'toggle' },
        { path: 'integrations.mdblist.showImdb', label: 'Show IMDb ratings', description: 'Display IMDb scoring.', kind: 'toggle' },
        { path: 'integrations.mdblist.showTmdb', label: 'Show TMDB ratings', description: 'Display TMDB scoring.', kind: 'toggle' },
        { path: 'integrations.mdblist.showLetterboxd', label: 'Show Letterboxd', description: 'Display Letterboxd scoring.', kind: 'toggle' },
        { path: 'integrations.mdblist.showTomatoes', label: 'Show Rotten Tomatoes', description: 'Display critic tomato scores.', kind: 'toggle' },
        { path: 'integrations.mdblist.showAudience', label: 'Show audience score', description: 'Display audience tomato scores.', kind: 'toggle' },
        { path: 'integrations.mdblist.showMetacritic', label: 'Show Metacritic', description: 'Display Metacritic scores.', kind: 'toggle' }
      ]
    },
    {
      id: 'anime-skip',
      title: 'Anime Skip',
      subtitle: 'Skip intro service credentials and toggle.',
      fields: [
        { path: 'integrations.animeSkip.enabled', label: 'Enable Anime Skip', description: 'Allows intro skip lookups for supported anime.', kind: 'toggle' },
        { path: 'integrations.animeSkip.clientId', label: 'Client ID', description: 'Synced Anime Skip client ID.', kind: 'text', placeholder: 'Paste Anime Skip client ID' }
      ]
    },
    {
      id: 'poster-ratings',
      title: 'Poster Ratings',
      subtitle: 'Choose the single active poster provider. RPDB and TOP Posters keys are stored separately as secrets.',
      fields: [
        { path: 'integrations.posterRatings.rpdbEnabled', label: 'Enable RPDB', description: 'Mutually exclusive with TOP Posters.', kind: 'toggle' },
        { path: 'integrations.posterRatings.topPostersEnabled', label: 'Enable TOP Posters', description: 'Mutually exclusive with RPDB.', kind: 'toggle' },
      ]
    }
  ],
  playback: [
    {
      id: 'playback-general',
      title: 'Playback',
      subtitle: 'Core player behavior that should stay aligned across every screen.',
      fields: [
        { path: 'playback.general.loadingOverlayEnabled', label: 'Loading overlay', description: 'Show the premium loading treatment during startup.', kind: 'toggle' },
        { path: 'playback.general.pauseOverlayEnabled', label: 'Pause overlay', description: 'Show overlay chrome while paused.', kind: 'toggle' },
        { path: 'playback.general.osdClockEnabled', label: 'OSD clock', description: 'Display the clock in playback overlays.', kind: 'toggle' },
        { path: 'playback.general.skipIntroEnabled', label: 'Skip intro', description: 'Enable intro skip actions.', kind: 'toggle' },
        { path: 'playback.general.frameRateMatchingMode', label: 'Frame-rate matching', description: 'Switch refresh rate to match content at playback boundaries.', kind: 'select', options: [
          { label: 'Off', value: 'OFF' },
          { label: 'On start', value: 'START' },
          { label: 'Start and stop', value: 'START_STOP' }
        ] },
        { path: 'playback.general.resolutionMatchingEnabled', label: 'Resolution matching', description: 'Allow the app to switch output resolution when needed.', kind: 'toggle' },
        { path: 'playback.streamSelection.playerPreference', label: 'Player preference', description: 'Choose the default player flow.', kind: 'select', options: [
          { label: 'Internal', value: 'INTERNAL' },
          { label: 'External', value: 'EXTERNAL' },
          { label: 'Ask every time', value: 'ASK_EVERY_TIME' }
        ] },
        { path: 'playback.streamSelection.streamReuseLastLinkEnabled', label: 'Reuse last stream', description: 'Prefer the last successful stream link.', kind: 'toggle' },
        { path: 'playback.streamSelection.streamReuseLastLinkCacheHours', label: 'Reuse cache window', description: 'How long previous stream links stay reusable.', kind: 'select', options: [1, 6, 12, 24, 48, 72, 168].map((value) => ({ label: `${value}h`, value })) },
        { path: 'playback.streamSelection.streamAutoPlayMode', label: 'Auto-play mode', description: 'Pick the account-wide stream selection behavior.', kind: 'select', options: [
          { label: 'Manual', value: 'MANUAL' },
          { label: 'First stream', value: 'FIRST_STREAM' },
          { label: 'Regex match', value: 'REGEX_MATCH' }
        ] },
        { path: 'playback.streamSelection.streamAutoPlaySource', label: 'Auto-play source', description: 'Limit auto-play selection scope.', kind: 'select', options: [
          { label: 'All sources', value: 'ALL_SOURCES' },
          { label: 'Installed addons only', value: 'INSTALLED_ADDONS_ONLY' }
        ] },
        { path: 'playback.streamSelection.streamAutoPlaySelectedAddons', label: 'Auto-play addons', description: 'CSV list of addon names preferred by auto-play.', kind: 'list', placeholder: 'Cinemeta, Orion, Comet' },
        { path: 'playback.streamSelection.streamAutoPlayRegex', label: 'Auto-play regex', description: 'Regex for prioritized stream picks.', kind: 'text', placeholder: '(remux|2160p|dolby vision)' },
        { path: 'playback.streamSelection.streamAutoPlayNextEpisodeEnabled', label: 'Auto-play next episode', description: 'Continue into the next episode automatically.', kind: 'toggle' },
        { path: 'playback.streamSelection.streamAutoPlayPreferBingeGroupForNextEpisode', label: 'Prefer binge groups', description: 'Prefer binge-group continuity when auto-playing.', kind: 'toggle' },
        { path: 'playback.streamSelection.nextEpisodeThresholdMode', label: 'Next episode threshold mode', description: 'Define how end-of-episode auto-play is measured.', kind: 'select', options: [
          { label: 'Percentage', value: 'PERCENTAGE' },
          { label: 'Minutes before end', value: 'MINUTES_BEFORE_END' }
        ] },
        { path: 'playback.streamSelection.nextEpisodeThresholdPercent', label: 'Next episode threshold percent', description: 'Percent watched before auto-playing the next episode.', kind: 'slider', min: 97, max: 99.5, step: 0.5 },
        { path: 'playback.streamSelection.nextEpisodeThresholdMinutesBeforeEnd', label: 'Next episode threshold minutes', description: 'Minutes before end before auto-playing the next episode.', kind: 'slider', min: 1, max: 3.5, step: 0.5 },
        { path: 'playback.audio.preferredAudioLanguage', label: 'Preferred audio language', description: 'Primary synced audio preference.', kind: 'text', placeholder: 'device' },
        { path: 'playback.audio.secondaryPreferredAudioLanguage', label: 'Secondary audio language', description: 'Fallback audio language code.', kind: 'text', placeholder: 'en' },
        { path: 'playback.audio.skipSilence', label: 'Skip silence', description: 'Use silence skipping when available.', kind: 'toggle' },
        { path: 'playback.audio.decoderPriority', label: 'Decoder priority', description: 'Renderer preference for playback codecs.', kind: 'select', options: [
          { label: '0', value: 0 },
          { label: '1', value: 1 },
          { label: '2', value: 2 }
        ] },
        { path: 'playback.audio.tunnelingEnabled', label: 'Enable tunneling', description: 'Keep tunneling consistent on supported TVs.', kind: 'toggle' }
      ]
    },
    {
      id: 'subtitles',
      title: 'Subtitles',
      subtitle: 'Keep caption behavior consistent across rooms.',
      fields: [
        { path: 'playback.subtitles.preferredLanguage', label: 'Preferred subtitle language', description: 'Primary subtitle language code.', kind: 'text', placeholder: 'en' },
        { path: 'playback.subtitles.secondaryPreferredLanguage', label: 'Secondary subtitle language', description: 'Secondary subtitle language code.', kind: 'text', placeholder: 'es' },
        { path: 'playback.subtitles.subtitleOrganizationMode', label: 'Subtitle grouping', description: 'Choose how subtitle tracks are grouped.', kind: 'select', options: [
          { label: 'None', value: 'NONE' },
          { label: 'By language', value: 'BY_LANGUAGE' },
          { label: 'By addon', value: 'BY_ADDON' }
        ] },
        { path: 'playback.subtitles.addonSubtitleStartupMode', label: 'Addon subtitle startup', description: 'Control how subtitle rails load at playback startup.', kind: 'select', options: [
          { label: 'Fast startup', value: 'FAST_STARTUP' },
          { label: 'Preferred only', value: 'PREFERRED_ONLY' },
          { label: 'All subtitles', value: 'ALL_SUBTITLES' }
        ] },
        { path: 'playback.subtitles.size', label: 'Subtitle size', description: 'Subtitle size percent.', kind: 'slider', min: 50, max: 200, step: 10 },
        { path: 'playback.subtitles.verticalOffset', label: 'Vertical offset', description: 'Move subtitles up or down.', kind: 'slider', min: -20, max: 50, step: 1 },
        { path: 'playback.subtitles.bold', label: 'Bold subtitles', description: 'Use bold subtitle text.', kind: 'toggle' },
        { path: 'playback.subtitles.textColor', label: 'Text color', description: 'ARGB integer for subtitle text color.', kind: 'text', placeholder: '-1' },
        { path: 'playback.subtitles.backgroundColor', label: 'Background color', description: 'ARGB integer for subtitle background.', kind: 'text', placeholder: '0' },
        { path: 'playback.subtitles.outlineEnabled', label: 'Outline', description: 'Use subtitle outlines.', kind: 'toggle' },
        { path: 'playback.subtitles.outlineColor', label: 'Outline color', description: 'ARGB integer for subtitle outline color.', kind: 'text', placeholder: '-16777216' },
        { path: 'playback.subtitles.useLibass', label: 'Use libass', description: 'Enable libass rendering when supported.', kind: 'toggle' }
      ]
    },
    {
      id: 'buffer',
      title: 'Buffering',
      subtitle: 'Network-neutral buffering values that are safe to sync across devices.',
      fields: [
        { path: 'playback.bufferNetwork.minBufferMs', label: 'Min buffer', description: 'Minimum buffer in milliseconds.', kind: 'slider', min: 5000, max: 120000, step: 5000 },
        { path: 'playback.bufferNetwork.maxBufferMs', label: 'Max buffer', description: 'Maximum buffer in milliseconds.', kind: 'slider', min: 5000, max: 120000, step: 5000 },
        { path: 'playback.bufferNetwork.bufferForPlaybackMs', label: 'Buffer for playback', description: 'Playback start buffer.', kind: 'slider', min: 1000, max: 60000, step: 1000 },
        { path: 'playback.bufferNetwork.bufferForPlaybackAfterRebufferMs', label: 'After rebuffer', description: 'Playback resume buffer after rebuffer.', kind: 'slider', min: 1000, max: 120000, step: 1000 },
        { path: 'playback.bufferNetwork.targetBufferSizeMb', label: 'Target buffer size', description: 'Target memory buffer size in MB.', kind: 'slider', min: 0, max: 1000, step: 10 },
        { path: 'playback.bufferNetwork.backBufferDurationMs', label: 'Back buffer duration', description: 'Back buffer retained behind the playhead.', kind: 'slider', min: 0, max: 120000, step: 5000 },
        { path: 'playback.bufferNetwork.enableBufferLogs', label: 'Buffer logs', description: 'Enable buffer diagnostics.', kind: 'toggle' }
      ]
    }
  ],
  debug: [
    {
      id: 'debug',
      title: 'Debug',
      subtitle: 'Developer-facing toggles. These should stay off for normal users.',
      fields: [
        { path: 'debug.accountTabEnabled', label: 'Account tab', description: 'Expose the account tab in debug builds.', kind: 'toggle' },
        { path: 'debug.syncCodeFeaturesEnabled', label: 'Legacy sync code tools', description: 'Shows old sync code tools during migration.', kind: 'toggle' },
        { path: 'debug.bufferLogsEnabled', label: 'Buffer logs', description: 'Mirror the player buffer log toggle.', kind: 'toggle' }
      ]
    }
  ]
}

export function fieldValue(settings: PortalSettings, path: string): unknown {
  return path.split('.').reduce<unknown>((current, key) => {
    if (current && typeof current === 'object' && key in current) {
      return (current as Record<string, unknown>)[key]
    }

    return undefined
  }, settings)
}
