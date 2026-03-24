<template>
  <PortalShell :signed-in="signedIn" :active-view="activeView" @set-view="setView" @sign-out="signOut">
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
      <PortalToastStack :toasts="state.toasts" @dismiss="dismissToast" />

      <div class="account-portal-view w-full">
        <section
          v-if="state.error"
          class="bg-error/10 border border-error/20 text-error p-4 rounded-xl flex items-center gap-3 mb-6"
        >
          <svg class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
          {{ state.error }}
        </section>

        <div class="portal-view-transition animate-fade-in w-full">
          <AddonManager
            v-if="activeView === 'addons'"
            :addons="state.addons"
            :secret-statuses="secretStatusMap"
            :busy="state.saving"
            :migration="state.migration"
            @persist="persistSnapshot"
            @add-addon="addAddon"
            @remove-addon="removeAddon"
            @move-addon="moveAddon"
            @reorder-addons="reorderAddons"
            @toggle-addon="toggleAddon"
            @update-addon-parser-preset="updateAddonParserPreset"
            @migration-pull="pullAddonMigration"
            @migration-commit="commitAddonMigration"
          />

          <CatalogInventory
            v-else-if="activeView === 'catalogs'"
            :catalogs="catalogInventory"
            :addons="state.addons"
            :disabled-keys="state.settings.catalogs.home.disabledHomeCatalogKeys"
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
            :trakt-search-results="state.traktDiscovery.searchResults"
            :mdblist-personal-lists="state.mdblistDiscovery.personalLists"
            :mdblist-top-lists="state.mdblistDiscovery.topLists"
            :mdblist-search-results="state.mdblistDiscovery.searchResults"
            :mdblist-validating="state.mdblistDiscovery.validating"
            :mdblist-error="state.mdblistDiscovery.error"
            :imdb-validating="state.imdbValidation.validating"
            :imdb-valid="state.imdbValidation.valid"
            :imdb-error="state.imdbValidation.error"
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
            @validate-imdb="handleValidateIMDb"
            @toggle-mdblist-personal-list="(key, currentlyHidden) => setMDBListPersonalListEnabled(key, currentlyHidden)"
            @toggle-mdblist-top-list="(key, shouldSelect) => setMDBListTopListSelected(key, shouldSelect)"
            @search-trakt-lists="searchTraktLists"
            @search-mdblist-lists="searchMDBListLists"
          />
          <FormatterWorkspace
            v-else-if="activeView === 'formatter'"
            :settings="state.settings"
            :busy="state.saving"
            @update="updateSetting"
            @persist="persistSnapshot"
          />
        </div>
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
import PortalShell from '~/components/portal/PortalShell.vue'
import SettingsWorkspace from '~/components/portal/SettingsWorkspace.vue'
import PortalToastStack from '~/components/portal/PortalToastStack.vue'
import FormatterWorkspace from '~/components/portal/FormatterWorkspace.vue'
import { usePortalStore } from '~/composables/usePortalStore'
import { accountGroups } from '~/utils/portal-metadata'

const route = useRoute()
const router = useRouter()
const {
  state,
  bootstrap,
  dismissToast,
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
  reorderAddons,
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
  validateIMDb,
  searchMDBListLists,
  setMDBListPersonalListEnabled,
  setMDBListTopListSelected,
  startTraktDeviceFlow,
  completeTraktDeviceFlow,
  startRealDebridDeviceFlow,
  completeRealDebridDeviceFlow,
  refreshTraktPopularLists,
  searchTraktLists,
  toggleTraktPopularList,
  disconnectTrakt,
  disconnectRealDebrid,
  pullAddonMigration,
  commitAddonMigration
} = usePortalStore()

const integrationGroups = computed(() => accountGroups.integrations || [])

const nav = [
  { id: 'addons', label: 'Addons' },
  { id: 'catalogs', label: 'Catalogs' },
  { id: 'integrations', label: 'Integrations' },
  { id: 'formatter', label: 'Formatter' }
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

async function handleValidateIMDb() {
  await validateIMDb().catch(() => undefined)
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

.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.shadow-glow {
  box-shadow: 0 0 20px rgba(186, 158, 255, 0.1);
}
</style>
