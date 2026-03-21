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
      <div class="account-portal-container max-w-[1280px] mx-auto pb-16">
        <OverviewPanel
          :title="state.session?.user.email ?? 'Nexio account'"
          :addons-count="state.addons.length"
          :last-synced-at="state.lastSyncedAt"
          class="mb-6"
        />

        <section
          v-if="state.error"
          class="glass error-card"
        >
          <svg class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
          {{ state.error }}
        </section>

        <section class="portal-sticky-nav glass flex flex-wrap items-center justify-between gap-4 p-4 rounded-2xl mb-8 z-20 shadow-lg border border-white/5 backdrop-blur-xl">
          <div class="flex flex-wrap gap-3">
            <button 
              v-for="item in nav" 
              :key="item.id" 
              class="nav-btn transition-all duration-300 rounded-full px-6 py-2.5 font-medium text-sm border border-transparent"
              :class="activeView === item.id ? 'bg-gradient-to-r from-[#7bffd3] to-[#9be7ff] text-[#031018] shadow-[0_0_20px_rgba(123,255,211,0.2)]' : 'text-white/70 bg-white/5 hover:bg-white/10 hover:border-white/10 border border-white/5'" 
              @click="setView(item.id)"
            >
              {{ item.label }}
            </button>
          </div>
          <span class="badge shadow-glow"><strong>{{ state.demoMode ? 'Demo mode' : 'Nexio Live' }}</strong></span>
        </section>

        <div class="portal-view-transition animate-fade-in">
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
            v-else-if="activeView === 'integrations'"
            title="Integrations sync"
            subtitle="Debrid services, TMDB, MDBList, Anime Skip, poster providers, and Trakt account state belong to the account, not a single TV."
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
            @save-premiumize-key="savePremiumizeApiKey"
            @clear-premiumize-key="clearPremiumizeApiKey"
            @refresh-premiumize="refreshPremiumizeStatus"
            @start-trakt="startTraktDeviceFlow"
            @complete-trakt="completeTraktDeviceFlow"
            @start-realdebrid="startRealDebridDeviceFlow"
            @complete-realdebrid="completeRealDebridDeviceFlow"
            @disconnect-realdebrid="disconnectRealDebrid"
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
        </div>

        <LinkedDevicesPanel
          v-if="state.linkedDevices.length > 0"
          :devices="state.linkedDevices"
          class="mt-12 opacity-80 hover:opacity-100 transition-opacity"
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
  savePremiumizeApiKey,
  clearPremiumizeApiKey,
  refreshPremiumizeStatus,
  setSecretDraft,
  saveDraftSecret,
  deleteSecret,
  validateMDBList,
  setMDBListPersonalListEnabled,
  setMDBListTopListSelected,
  startTraktDeviceFlow,
  completeTraktDeviceFlow,
  startRealDebridDeviceFlow,
  completeRealDebridDeviceFlow,
  refreshTraktPopularLists,
  toggleTraktPopularList,
  disconnectTrakt,
  disconnectRealDebrid
} = usePortalStore()

const integrationGroups = computed(() => accountGroups.integrations || [])

const nav = [
  { id: 'addons', label: 'Addons' },
  { id: 'catalogs', label: 'Catalogs' },
  { id: 'integrations', label: 'Integrations' }
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

<style scoped>
.error-card {
  padding: 1rem 1.25rem;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(255, 123, 130, 0.35);
  color: #ffb1b5;
  background: rgba(43, 8, 8, 0.4);
  backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
}

.portal-sticky-nav {
  position: sticky;
  top: 1rem;
}

.nav-btn:hover {
  transform: translateY(-1px);
}

.nav-btn:active {
  transform: translateY(1px) scale(0.98);
}

.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.shadow-glow {
  box-shadow: 0 0 20px rgba(123, 255, 211, 0.1);
}
</style>
