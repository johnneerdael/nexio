<template>
  <section class="glass" style="padding:1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.3rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-end; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Settings Sync</p>
        <h2 class="section-title" style="font-size:1.9rem; margin:0.7rem 0 0.4rem;">{{ title }}</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 52rem; line-height: 1.6;">{{ subtitle }}</p>
      </div>
      <button class="primary-btn" :disabled="busy" @click="emit('persist')">{{ busy ? 'Nexio Syncing...' : 'Nexio Sync' }}</button>
    </div>

    <article v-if="showTrakt" class="field-shell">
      <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; flex-wrap:wrap;">
        <div>
          <label>Trakt account</label>
          <p>Authenticate once from the portal. Tokens stay in the secret channel while profile state syncs publicly.</p>
        </div>
        <div style="display:flex; gap:0.6rem; flex-wrap:wrap;">
          <button v-if="!settings.integrations.traktAuth.connected && !traktFlow" class="secondary-btn" @click="emit('start-trakt')">Start QR flow</button>
          <button v-if="traktFlow" class="primary-btn" @click="emit('complete-trakt')">Check approval</button>
          <button v-if="settings.integrations.traktAuth.connected" class="secondary-btn" @click="emit('refresh-trakt-lists')">Refresh lists</button>
          <button v-if="settings.integrations.traktAuth.connected" class="danger-btn" @click="emit('disconnect-trakt')">Disconnect</button>
        </div>
      </div>

      <div class="grid-2">
        <div class="field-shell" style="padding:0.95rem;">
          <strong>Status</strong>
          <p v-if="settings.integrations.traktAuth.connected">Connected as {{ settings.integrations.traktAuth.username || 'Trakt user' }}</p>
          <p v-else-if="traktFlow">Awaiting approval with code {{ traktFlow.userCode }}</p>
          <p v-else>Connect Trakt to unlock Trakt-powered catalogs and live scrobble state.</p>
          <p v-if="secretStatuses['integration:trakt']?.maskedPreview">{{ secretStatuses['integration:trakt']?.maskedPreview }}</p>
        </div>
        <div v-if="traktFlow" class="field-shell" style="padding:0.95rem;">
          <strong>Verification</strong>
          <p>{{ traktFlow.verificationUrl }}</p>
          <p>Code {{ traktFlow.userCode }}</p>
        </div>
      </div>

      <template v-if="settings.integrations.traktAuth.connected">
        <div class="field-shell" style="padding:0.95rem;">
          <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; flex-wrap:wrap;">
            <div>
              <strong>Built-in Trakt catalogs</strong>
              <p style="margin-top:0.3rem;">Enabled rails stay highlighted here. Their position is managed from the Catalogs view.</p>
            </div>
          </div>
          <div class="selection-grid compact-grid">
            <button
              v-for="catalog in settings.trakt.catalogOrder"
              :key="catalog"
              :class="settings.trakt.catalogEnabledSet.includes(catalog) ? 'toggle-chip active block' : 'toggle-chip block'"
              @click="toggleCatalog(catalog)"
            >
              {{ traktCatalogLabels[catalog] }}
            </button>
          </div>
        </div>

        <details class="collapsible-shell">
          <summary class="collapsible-summary">
            <span>
              <strong>Popular Trakt lists</strong>
              <small>{{ traktPopularLists.length }} discovered</small>
            </span>
            <span class="badge">Collapsed by default</span>
          </summary>
          <div class="selection-grid">
            <button
              v-for="list in traktPopularLists"
              :key="list.key"
              :class="settings.trakt.selectedPopularListKeys.includes(list.key) ? 'toggle-chip active block' : 'toggle-chip block'"
              @click="emit('toggle-trakt-list', list.key)"
            >
              {{ list.title }}
            </button>
          </div>
        </details>
      </template>
    </article>

    <article v-if="showIntegrations" class="field-shell">
      <div class="grid-2 integrations-grid">
        <div class="field-shell" style="padding:0.95rem;">
          <div class="integration-header">
            <div>
              <label>TMDB</label>
              <p>Enable TMDB metadata and manage the shared API key from one compact panel.</p>
            </div>
            <button :class="settings.integrations.tmdb.enabled ? 'toggle-chip active' : 'toggle-chip'" @click="emit('update', 'integrations.tmdb.enabled', !settings.integrations.tmdb.enabled)">
              TMDB
            </button>
          </div>
          <input v-model="tmdbDraft" placeholder="Paste TMDB API key">
          <div style="display:flex; gap:0.55rem; flex-wrap:wrap; margin-top:0.1rem;">
            <button class="secondary-btn" :disabled="!tmdbDraft.trim()" @click="handleSaveTmdb">Save key</button>
            <button v-if="secretStatuses['integration:tmdb']" class="danger-btn" @click="emit('clear-tmdb-key')">Clear key</button>
          </div>
          <p v-if="maskedTmdbKey">{{ maskedTmdbKey }}</p>
          <details class="collapsible-shell">
            <summary class="collapsible-summary">
              <span>
                <strong>Advanced Settings</strong>
                <small>Artwork, credits, networks, episodes, and discovery depth.</small>
              </span>
            </summary>
            <div class="selection-grid compact-grid">
              <button v-for="field in tmdbFields" :key="field.path" :class="fieldValue(settings, field.path) ? 'toggle-chip active block' : 'toggle-chip block'" @click="emit('update', field.path, !fieldValue(settings, field.path))">
                {{ field.label }}
              </button>
            </div>
          </details>
        </div>

        <div class="field-shell" style="padding:0.95rem;">
          <div class="integration-header">
            <div>
              <label>MDBList</label>
              <p>Store the shared API key separately, then discover personal and top-list rails without keeping the page open and expanded.</p>
            </div>
            <button :class="settings.integrations.mdblist.enabled ? 'toggle-chip active' : 'toggle-chip'" @click="emit('update', 'integrations.mdblist.enabled', !settings.integrations.mdblist.enabled)">
              MDBList
            </button>
          </div>
          <input
            :value="secretDrafts['integration:mdblist'] || ''"
            placeholder="Paste MDBList API key"
            @input="emit('update-secret-draft', 'integration:mdblist', ($event.target as HTMLInputElement).value)"
          >
          <div style="display:flex; gap:0.55rem; flex-wrap:wrap; margin-top:0.1rem;">
            <button class="secondary-btn" @click="emit('save-secret', 'mdblist_api_key', 'integration:mdblist')">Save key</button>
            <button
              v-if="secretStatuses['integration:mdblist']"
              class="danger-btn"
              @click="emit('delete-secret', 'mdblist_api_key', 'integration:mdblist')"
            >
              Clear key
            </button>
            <button class="secondary-btn" :disabled="mdblistValidating" @click="emit('validate-mdblist')">
              {{ mdblistValidating ? 'Refreshing...' : 'Refresh Lists' }}
            </button>
          </div>
          <p v-if="secretStatuses['integration:mdblist']?.maskedPreview">{{ secretStatuses['integration:mdblist']?.maskedPreview }}</p>
          <p v-if="mdblistError" style="color: var(--danger);">{{ mdblistError }}</p>
          <details class="collapsible-shell">
            <summary class="collapsible-summary">
              <span>
                <strong>Advanced Settings</strong>
                <small>Ratings overlays and rail discovery stay tucked away until needed.</small>
              </span>
            </summary>
            <div class="selection-grid compact-grid">
              <button v-for="field in mdblistFields" :key="field.path" :class="fieldValue(settings, field.path) ? 'toggle-chip active block' : 'toggle-chip block'" @click="emit('update', field.path, !fieldValue(settings, field.path))">
                {{ field.label }}
              </button>
            </div>
            <details class="collapsible-shell nested-collapse">
              <summary class="collapsible-summary">
                <span>
                  <strong>Personal Lists</strong>
                  <small>{{ mdblistPersonalLists.length }} discovered</small>
                </span>
              </summary>
              <div class="selection-grid">
                <button
                  v-for="list in mdblistPersonalLists"
                  :key="list.key"
                  :class="settings.integrations.mdblist.hiddenPersonalListKeys.includes(list.key) ? 'toggle-chip block' : 'toggle-chip active block'"
                  @click="emit('toggle-mdblist-personal-list', list.key, settings.integrations.mdblist.hiddenPersonalListKeys.includes(list.key))"
                >
                  {{ list.title }}
                </button>
              </div>
            </details>
            <details class="collapsible-shell nested-collapse">
              <summary class="collapsible-summary">
                <span>
                  <strong>Top Lists</strong>
                  <small>{{ mdblistTopLists.length }} discovered</small>
                </span>
              </summary>
              <div class="selection-grid">
                <button
                  v-for="list in mdblistTopLists"
                  :key="list.key"
                  :class="settings.integrations.mdblist.selectedTopListKeys.includes(list.key) ? 'toggle-chip active block' : 'toggle-chip block'"
                  @click="emit('toggle-mdblist-top-list', list.key, !settings.integrations.mdblist.selectedTopListKeys.includes(list.key))"
                >
                  {{ list.title }}
                </button>
              </div>
            </details>
          </details>
        </div>
      </div>

      <div class="field-shell" style="padding:0.95rem;">
        <div class="integration-header">
          <div>
            <label>Anime Skip</label>
            <p>Intro-skip account settings that still belong at the account layer.</p>
          </div>
          <button :class="settings.integrations.animeSkip.enabled ? 'toggle-chip active' : 'toggle-chip'" @click="emit('update', 'integrations.animeSkip.enabled', !settings.integrations.animeSkip.enabled)">
            Anime Skip
          </button>
        </div>
        <input
          :value="animeSkipClientId"
          placeholder="Paste Anime Skip client ID"
          @input="emit('update', 'integrations.animeSkip.clientId', ($event.target as HTMLInputElement).value)"
        >
      </div>

      <div class="grid-2 integrations-grid">
        <div class="field-shell" :style="providerCardStyle('rpdb')">
          <div style="display:grid; gap:0.35rem;">
            <strong>RPDB</strong>
            <p>Use rating posters from RPDB. Enabling this makes TOP Posters unavailable.</p>
          </div>
          <button
            :class="providerButtonClass('rpdb')"
            :disabled="posterProviderLocked('rpdb')"
            @click="togglePosterProvider('rpdb')"
          >
            RPDB
          </button>
          <template v-if="settings.integrations.posterRatings.rpdbEnabled">
            <input
              :value="secretDrafts['integration:rpdb'] || ''"
              placeholder="Paste RPDB API key"
              @input="emit('update-secret-draft', 'integration:rpdb', ($event.target as HTMLInputElement).value)"
            >
            <div style="display:flex; gap:0.55rem; flex-wrap:wrap;">
              <button class="secondary-btn" @click="emit('save-secret', 'rpdb_api_key', 'integration:rpdb')">Save key</button>
              <button v-if="secretStatuses['integration:rpdb']" class="danger-btn" @click="emit('delete-secret', 'rpdb_api_key', 'integration:rpdb')">Clear key</button>
            </div>
            <p v-if="secretStatuses['integration:rpdb']?.maskedPreview">{{ secretStatuses['integration:rpdb']?.maskedPreview }}</p>
          </template>
        </div>

        <div class="field-shell" :style="providerCardStyle('topposters')">
          <div style="display:grid; gap:0.35rem;">
            <strong>TOP Posters</strong>
            <p>Use TOP Posters artwork. Enabling this makes RPDB unavailable.</p>
          </div>
          <button
            :class="providerButtonClass('topposters')"
            :disabled="posterProviderLocked('topposters')"
            @click="togglePosterProvider('topposters')"
          >
            TOP Posters
          </button>
          <template v-if="settings.integrations.posterRatings.topPostersEnabled">
            <input
              :value="secretDrafts['integration:topposters'] || ''"
              placeholder="Paste TOP Posters API key"
              @input="emit('update-secret-draft', 'integration:topposters', ($event.target as HTMLInputElement).value)"
            >
            <div style="display:flex; gap:0.55rem; flex-wrap:wrap;">
              <button class="secondary-btn" @click="emit('save-secret', 'top_posters_api_key', 'integration:topposters')">Save key</button>
              <button v-if="secretStatuses['integration:topposters']" class="danger-btn" @click="emit('delete-secret', 'top_posters_api_key', 'integration:topposters')">Clear key</button>
            </div>
            <p v-if="secretStatuses['integration:topposters']?.maskedPreview">{{ secretStatuses['integration:topposters']?.maskedPreview }}</p>
          </template>
        </div>
      </div>
    </article>

    <article v-for="group in groups" :key="group.id" class="surface" style="padding:1.2rem; border-radius: var(--radius-lg); display:grid; gap:1rem;">
      <div>
        <h3 class="section-title" style="margin:0 0 0.3rem; font-size:1.35rem;">{{ group.title }}</h3>
        <p style="margin:0; color: var(--text-dim); line-height:1.5;">{{ group.subtitle }}</p>
      </div>
      <div class="grid-2">
        <PortalField
          v-for="field in group.fields"
          :key="field.path"
          :field="field"
          :value="fieldValue(settings, field.path)"
          @update="emit('update', field.path, $event)"
        />
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import PortalField from '~/components/portal/PortalField.vue'
import { accountGroups, fieldValue, traktCatalogLabels, type PortalGroup } from '~/utils/portal-metadata'
import type { CatalogId, MDBListListOption, PortalSettings, SecretMetadata, SecretType, TraktDeviceFlow, TraktPopularListOption } from '~/types/portal'

const props = withDefaults(defineProps<{
  title: string
  subtitle: string
  groups: PortalGroup[]
  settings: PortalSettings
  traktFlow: TraktDeviceFlow | null
  traktPopularLists?: TraktPopularListOption[]
  mdblistPersonalLists?: MDBListListOption[]
  mdblistTopLists?: MDBListListOption[]
  secretStatuses?: Record<string, SecretMetadata>
  secretDrafts?: Record<string, string>
  mdblistValidating?: boolean
  mdblistError?: string | null
  showTrakt?: boolean
  showIntegrations?: boolean
  busy?: boolean
}>(), {
  traktPopularLists: () => [],
  mdblistPersonalLists: () => [],
  mdblistTopLists: () => [],
  secretStatuses: () => ({}),
  secretDrafts: () => ({})
})

const emit = defineEmits<{
  persist: []
  update: [path: string, value: unknown]
  'start-trakt': []
  'complete-trakt': []
  'refresh-trakt-lists': []
  'disconnect-trakt': []
  'toggle-trakt-list': [key: string]
  'save-tmdb-key': [apiKey: string]
  'clear-tmdb-key': []
  'update-secret-draft': [secretRef: string, value: string]
  'save-secret': [secretType: SecretType, secretRef: string]
  'delete-secret': [secretType: SecretType, secretRef: string]
  'validate-mdblist': []
  'toggle-mdblist-personal-list': [key: string, currentlyHidden: boolean]
  'toggle-mdblist-top-list': [key: string, shouldSelect: boolean]
}>()

const tmdbFields = accountGroups.integrations
  .find((group) => group.id === 'tmdb')
  ?.fields.filter((field) => field.path !== 'integrations.tmdb.enabled') ?? []

const mdblistFields = accountGroups.integrations
  .find((group) => group.id === 'mdblist')
  ?.fields.filter((field) => !['integrations.mdblist.enabled', 'integrations.mdblist.hiddenPersonalListKeys', 'integrations.mdblist.selectedTopListKeys', 'integrations.mdblist.catalogOrder'].includes(field.path)) ?? []

const tmdbDraft = ref('')

watch(() => props.secretStatuses['integration:tmdb']?.updatedAt, () => {
  tmdbDraft.value = ''
})

const maskedTmdbKey = computed(() => {
  return props.secretStatuses['integration:tmdb']?.maskedPreview ?? ''
})

const animeSkipClientId = computed(() => {
  const value = fieldValue(props.settings, 'integrations.animeSkip.clientId')
  return typeof value === 'string' ? value : ''
})

function toggleCatalog(catalog: CatalogId) {
  const enabled = props.settings.trakt.catalogEnabledSet.includes(catalog)
  const next = enabled
    ? props.settings.trakt.catalogEnabledSet.filter((entry) => entry !== catalog)
    : [...props.settings.trakt.catalogEnabledSet, catalog]

  emit('update', 'trakt.catalogEnabledSet', next)
}

function posterProviderLocked(provider: 'rpdb' | 'topposters') {
  if (provider === 'rpdb') {
    return props.settings.integrations.posterRatings.topPostersEnabled
  }
  return props.settings.integrations.posterRatings.rpdbEnabled
}

function providerCardStyle(provider: 'rpdb' | 'topposters') {
  if (posterProviderLocked(provider)) {
    return 'padding:0.95rem; opacity:0.55;'
  }
  return 'padding:0.95rem;'
}

function providerButtonClass(provider: 'rpdb' | 'topposters') {
  const active = provider === 'rpdb'
    ? props.settings.integrations.posterRatings.rpdbEnabled
    : props.settings.integrations.posterRatings.topPostersEnabled
  return active ? 'toggle-chip active block' : 'toggle-chip block'
}

function togglePosterProvider(provider: 'rpdb' | 'topposters') {
  if (provider === 'rpdb') {
    emit('update', 'integrations.posterRatings.rpdbEnabled', !props.settings.integrations.posterRatings.rpdbEnabled)
    return
  }
  emit('update', 'integrations.posterRatings.topPostersEnabled', !props.settings.integrations.posterRatings.topPostersEnabled)
}

function handleSaveTmdb() {
  const apiKey = tmdbDraft.value.trim()
  if (!apiKey) {
    return
  }
  emit('save-tmdb-key', apiKey)
}
</script>
