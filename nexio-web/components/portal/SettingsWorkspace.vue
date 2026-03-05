<template>
  <section class="glass" style="padding:1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.3rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-end; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Settings Sync</p>
        <h2 class="section-title" style="font-size:1.9rem; margin:0.7rem 0 0.4rem;">{{ title }}</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 52rem; line-height: 1.6;">{{ subtitle }}</p>
      </div>
      <button class="primary-btn" @click="emit('persist')">Save To Supabase</button>
    </div>

    <article v-if="showTrakt" class="field-shell">
      <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; flex-wrap:wrap;">
        <div>
          <label>Trakt account</label>
          <p>Use Trakt device auth from the web portal and keep the account token state inside the synced profile.</p>
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
          <p v-else>Not connected.</p>
        </div>
        <div v-if="traktFlow" class="field-shell" style="padding:0.95rem;">
          <strong>Verification</strong>
          <p>{{ traktFlow.verificationUrl }}</p>
          <p>Code: {{ traktFlow.userCode }}</p>
        </div>
      </div>

      <div class="field-shell" style="padding:0.95rem;">
        <strong>Enabled Trakt catalogs</strong>
        <div style="display:flex; gap:0.55rem; flex-wrap:wrap;">
          <button
            v-for="catalog in settings.trakt.catalogOrder"
            :key="catalog"
            class="secondary-btn"
            style="padding:0.65rem 0.95rem;"
            @click="toggleCatalog(catalog)"
          >
            {{ traktCatalogLabels[catalog] }} · {{ settings.trakt.catalogEnabledSet.includes(catalog) ? 'On' : 'Off' }}
          </button>
        </div>
      </div>

      <div class="field-shell" style="padding:0.95rem;">
        <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; flex-wrap:wrap;">
          <div>
            <strong>Popular Trakt lists</strong>
            <p style="margin-top:0.3rem;">These are the same popular-list catalog options the Android app can turn into rails.</p>
          </div>
          <span class="badge">{{ traktPopularLists.length }} discovered</span>
        </div>
        <div style="display:flex; gap:0.55rem; flex-wrap:wrap;">
          <button
            v-for="list in traktPopularLists"
            :key="list.key"
            class="secondary-btn"
            style="padding:0.65rem 0.95rem;"
            @click="emit('toggle-trakt-list', list.key)"
          >
            {{ list.title }} · {{ settings.trakt.selectedPopularListKeys.includes(list.key) ? 'Selected' : 'Off' }}
          </button>
        </div>
      </div>
    </article>

    <article v-if="showIntegrations" class="field-shell">
      <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; flex-wrap:wrap;">
        <div>
          <label>MDBList catalog discovery</label>
          <p>Validate the API key and pull the same personal/top list options used by the app.</p>
        </div>
        <button class="secondary-btn" @click="emit('validate-mdblist')">{{ mdblistValidating ? 'Validating' : 'Validate MDBList' }}</button>
      </div>
      <p v-if="mdblistError" style="color: var(--danger);">{{ mdblistError }}</p>

      <div class="grid-2">
        <div class="field-shell" style="padding:0.95rem;">
          <strong>Personal lists</strong>
          <div style="display:flex; gap:0.55rem; flex-wrap:wrap;">
            <button
              v-for="list in mdblistPersonalLists"
              :key="list.key"
              class="secondary-btn"
              style="padding:0.65rem 0.95rem;"
              @click="emit('toggle-mdblist-personal-list', list.key, settings.integrations.mdblist.hiddenPersonalListKeys.includes(list.key))"
            >
              {{ list.title }} · {{ settings.integrations.mdblist.hiddenPersonalListKeys.includes(list.key) ? 'Hidden' : 'Visible' }}
            </button>
          </div>
        </div>

        <div class="field-shell" style="padding:0.95rem;">
          <strong>Top lists</strong>
          <div style="display:flex; gap:0.55rem; flex-wrap:wrap;">
            <button
              v-for="list in mdblistTopLists"
              :key="list.key"
              class="secondary-btn"
              style="padding:0.65rem 0.95rem;"
              @click="emit('toggle-mdblist-top-list', list.key, !settings.integrations.mdblist.selectedTopListKeys.includes(list.key))"
            >
              {{ list.title }} · {{ settings.integrations.mdblist.selectedTopListKeys.includes(list.key) ? 'Selected' : 'Off' }}
            </button>
          </div>
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
import PortalField from '~/components/portal/PortalField.vue'
import { fieldValue, traktCatalogLabels, type PortalGroup } from '~/utils/portal-metadata'
import type { CatalogId, MDBListListOption, PortalSettings, TraktDeviceFlow, TraktPopularListOption } from '~/types/portal'

const props = defineProps<{
  title: string
  subtitle: string
  groups: PortalGroup[]
  settings: PortalSettings
  traktFlow: TraktDeviceFlow | null
  traktPopularLists?: TraktPopularListOption[]
  mdblistPersonalLists?: MDBListListOption[]
  mdblistTopLists?: MDBListListOption[]
  mdblistValidating?: boolean
  mdblistError?: string | null
  showTrakt?: boolean
  showIntegrations?: boolean
}>()

const emit = defineEmits<{
  persist: []
  update: [path: string, value: unknown]
  'start-trakt': []
  'complete-trakt': []
  'refresh-trakt-lists': []
  'disconnect-trakt': []
  'toggle-trakt-list': [key: string]
  'validate-mdblist': []
  'toggle-mdblist-personal-list': [key: string, currentlyHidden: boolean]
  'toggle-mdblist-top-list': [key: string, shouldSelect: boolean]
}>()

function toggleCatalog(catalog: CatalogId) {
  const enabled = props.settings.trakt.catalogEnabledSet.includes(catalog)
  const next = enabled
    ? props.settings.trakt.catalogEnabledSet.filter((entry) => entry !== catalog)
    : [...props.settings.trakt.catalogEnabledSet, catalog]

  emit('update', 'trakt.catalogEnabledSet', next)
}
</script>
