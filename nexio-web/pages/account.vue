<template>
  <PortalShell :signed-in="signedIn" @sign-out="signOut">
    <template v-if="!signedIn">
      <AuthPanel
        :busy="state.loading"
        :error="state.error"
        @sign-in="handleSignIn"
        @sign-up="handleSignUp"
        @google="handleGoogle"
      />
    </template>

    <template v-else>
      <OverviewPanel
        :title="state.session?.user.email ?? 'Nexio account'"
        :addons-count="state.addons.length"
        :last-synced-at="state.lastSyncedAt"
      />

      <section class="glass portal-sticky-nav" style="padding:1rem; border-radius: var(--radius-xl); display:flex; gap:0.65rem; flex-wrap:wrap; align-items:center; justify-content:space-between;">
        <div style="display:flex; gap:0.65rem; flex-wrap:wrap;">
          <button v-for="item in nav" :key="item.id" :class="activeView === item.id ? 'primary-btn' : 'secondary-btn'" @click="setView(item.id)">
            {{ item.label }}
          </button>
        </div>
        <span class="badge"><strong>{{ state.demoMode ? 'Demo mode' : 'Nexio Live' }}</strong></span>
      </section>

      <AddonManager
        v-if="activeView === 'addons'"
        :addons="state.addons"
        :secret-statuses="secretStatusMap"
        :busy="state.saving"
        @persist="persistSnapshot"
        @add-addon="addAddon"
        @remove-addon="removeAddon"
        @move-addon="moveAddon"
        @toggle-addon="toggleAddon"
        @update-addon-parser-preset="updateAddonParserPreset"
      />

      <CatalogInventory
        v-else-if="activeView === 'catalogs'"
        :catalogs="catalogInventory"
        :disabled-keys="state.settings.layout.disabledHomeCatalogKeys"
        :busy="state.saving"
        @persist="persistSnapshot"
        @move-catalog="moveCatalog"
        @reorder-catalogs="reorderCatalogs"
        @toggle-catalog="toggleCatalog"
      />

      <SettingsWorkspace
        v-else-if="activeView === 'appearance'"
        title="Appearance sync"
        subtitle="Brand, typography, and locale settings that should feel identical across every TV."
        :groups="accountGroups.appearance"
        :settings="state.settings"
        :secret-statuses="secretStatusMap"
        :secret-drafts="state.secretDrafts"
        :trakt-flow="state.traktFlow"
        :busy="state.saving"
        @persist="persistSnapshot"
        @update="updateSetting"
      />

      <SettingsWorkspace
        v-else-if="activeView === 'layout'"
        title="Layout sync"
        subtitle="Tune the home screen presentation, hero strategy, and catalog choreography."
        :groups="accountGroups.layout"
        :settings="state.settings"
        :secret-statuses="secretStatusMap"
        :secret-drafts="state.secretDrafts"
        :trakt-flow="state.traktFlow"
        :busy="state.saving"
        @persist="persistSnapshot"
        @update="updateSetting"
      />

      <SettingsWorkspace
        v-else-if="activeView === 'integrations'"
        title="Integrations sync"
        subtitle="TMDB, MDBList, Anime Skip, poster providers, and Trakt account state belong to the account, not a single TV."
        :groups="integrationGroups"
        :settings="state.settings"
        :secret-statuses="secretStatusMap"
        :secret-drafts="state.secretDrafts"
        :trakt-flow="state.traktFlow"
        :trakt-popular-lists="state.traktDiscovery.popularLists"
        :mdblist-personal-lists="state.mdblistDiscovery.personalLists"
        :mdblist-top-lists="state.mdblistDiscovery.topLists"
        :mdblist-validating="state.mdblistDiscovery.validating"
        :mdblist-error="state.mdblistDiscovery.error"
        :busy="state.saving"
        show-trakt
        show-integrations
        @persist="persistSnapshot"
        @update="updateSetting"
        @save-tmdb-key="saveTmdbApiKey"
        @clear-tmdb-key="clearTmdbApiKey"
        @start-trakt="startTraktDeviceFlow"
        @complete-trakt="completeTraktDeviceFlow"
        @refresh-trakt-lists="refreshTraktPopularLists"
        @disconnect-trakt="disconnectTrakt"
        @toggle-trakt-list="toggleTraktPopularList"
        @update-secret-draft="setSecretDraft"
        @save-secret="saveDraftSecret"
        @delete-secret="deleteSecret"
        @validate-mdblist="validateMDBList"
        @toggle-mdblist-personal-list="(key, currentlyHidden) => setMDBListPersonalListEnabled(key, currentlyHidden)"
        @toggle-mdblist-top-list="(key, shouldSelect) => setMDBListTopListSelected(key, shouldSelect)"
      />

      <SettingsWorkspace
        v-else-if="activeView === 'playback'"
        title="Playback sync"
        subtitle="Everything that should feel identical across devices, minus the device-specific playback exclusions."
        :groups="accountGroups.playback"
        :settings="state.settings"
        :secret-statuses="secretStatusMap"
        :secret-drafts="state.secretDrafts"
        :trakt-flow="state.traktFlow"
        :busy="state.saving"
        @persist="persistSnapshot"
        @update="updateSetting"
      />

      <div v-else style="display:grid; gap:1rem;">
        <SettingsWorkspace
          title="Debug sync"
          subtitle="Migration and developer controls while the Nexio account contract rolls out."
          :groups="accountGroups.debug"
          :settings="state.settings"
          :secret-statuses="secretStatusMap"
          :secret-drafts="state.secretDrafts"
          :trakt-flow="state.traktFlow"
          :busy="state.saving"
          @persist="persistSnapshot"
          @update="updateSetting"
        />

        <LinkedDevicesPanel
          :devices="state.linkedDevices"
          @unlink-device="unlinkDevice"
        />
      </div>
    </template>
  </PortalShell>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from '#imports'
import AddonManager from '~/components/portal/AddonManager.vue'
import AuthPanel from '~/components/portal/AuthPanel.vue'
import CatalogInventory from '~/components/portal/CatalogInventory.vue'
import LinkedDevicesPanel from '~/components/portal/LinkedDevicesPanel.vue'
import OverviewPanel from '~/components/portal/OverviewPanel.vue'
import PortalShell from '~/components/portal/PortalShell.vue'
import SettingsWorkspace from '~/components/portal/SettingsWorkspace.vue'
import { usePortalStore } from '~/composables/usePortalStore'
import { accountGroups } from '~/utils/portal-metadata'

const route = useRoute()
const router = useRouter()
const {
  state,
  bootstrap,
  signIn,
  signUp,
  startGoogleSignIn,
  signOut,
  signedIn,
  secretStatusMap,
  catalogInventory,
  updateSetting,
  addAddon,
  removeAddon,
  moveAddon,
  toggleAddon,
  updateAddonParserPreset,
  moveCatalog,
  reorderCatalogs,
  toggleCatalog,
  unlinkDevice,
  persistSnapshot,
  saveTmdbApiKey,
  clearTmdbApiKey,
  setSecretDraft,
  saveDraftSecret,
  deleteSecret,
  validateMDBList,
  setMDBListPersonalListEnabled,
  setMDBListTopListSelected,
  startTraktDeviceFlow,
  completeTraktDeviceFlow,
  refreshTraktPopularLists,
  toggleTraktPopularList,
  disconnectTrakt
} = usePortalStore()

const integrationGroups = computed(() => [] as typeof accountGroups.integrations)

const nav = [
  { id: 'addons', label: 'Addons' },
  { id: 'catalogs', label: 'Catalogs' },
  { id: 'appearance', label: 'Appearance' },
  { id: 'layout', label: 'Layout' },
  { id: 'integrations', label: 'Integrations' },
  { id: 'playback', label: 'Playback' },
  { id: 'debug', label: 'Debug' }
]

const activeView = computed(() => {
  const view = typeof route.query.view === 'string' ? route.query.view : 'addons'
  return nav.some((item) => item.id === view) ? view : 'addons'
})

function setView(view: string) {
  router.replace({ query: { ...route.query, view } })
}

async function handleSignIn(email: string, password: string) {
  await signIn(email, password)
}

async function handleSignUp(email: string, password: string) {
  await signUp(email, password)
}

function handleGoogle() {
  startGoogleSignIn(route.fullPath)
}

onMounted(() => {
  bootstrap()
})
</script>
