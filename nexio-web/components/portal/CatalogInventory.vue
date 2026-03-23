<template>
  <div class="catalog-inventory-container w-full pb-16 md:pb-0">
    <PortalManagementHero
      eyebrow="Discovery Layout"
      title="Catalog & Rail Management"
      description="Reorder and manage all home rails. Only enabled catalogs can be displayed across the experience."
      class="mb-12"
    >
      <template #right>
        <div class="grid grid-cols-2 gap-3">
          <div class="rounded-2xl border border-outline-variant/10 bg-surface-container/70 p-4">
            <div class="text-[10px] font-bold uppercase tracking-[0.18em] text-zinc-500">Total Catalogs</div>
            <div class="mt-2 text-3xl font-headline font-black text-white">{{ catalogs.length }}</div>
          </div>
          <div class="rounded-2xl border border-outline-variant/10 bg-surface-container/70 p-4">
            <div class="text-[10px] font-bold uppercase tracking-[0.18em] text-zinc-500">Visible Catalogs</div>
            <div class="mt-2 text-3xl font-headline font-black text-secondary">{{ enabledCatalogCount }}</div>
          </div>
        </div>
      </template>
    </PortalManagementHero>

    <!-- Control Bar -->
    <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-8">
      <div class="flex flex-col sm:flex-row gap-4 w-full sm:w-auto">
        <div class="flex items-center gap-2 px-4 py-2 bg-surface-container-lowest rounded-full border border-outline-variant/20 w-full sm:w-auto">
          <span class="material-symbols-outlined text-on-surface-variant text-sm">search</span>
          <input class="bg-transparent border-none focus:ring-0 text-sm p-0 placeholder:text-zinc-600 w-full sm:w-48 text-on-surface" placeholder="Filter rails..." type="text"/>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-[10px] font-bold uppercase text-zinc-500 tracking-wider">Status:</span>
          <select v-model="statusFilter" class="bg-surface-container-low border-none focus:ring-0 text-xs text-on-surface-variant rounded-full px-4 py-2 cursor-pointer appearance-none pr-8 relative">
            <option value="ALL">All Rails</option>
            <option value="ENABLED">Enabled</option>
            <option value="DISABLED">Disabled</option>
          </select>
        </div>
      </div>
      <div class="flex flex-wrap md:flex-nowrap w-full md:w-auto items-center gap-3">
        <button class="px-5 py-2.5 rounded-xl border border-outline-variant/20 text-on-surface-variant text-sm font-semibold hover:bg-surface-container-highest transition-colors flex-1 md:flex-none whitespace-nowrap">
          Reset to Defaults
        </button>
        <button class="px-6 py-2.5 rounded-xl bg-gradient-to-br from-primary to-primary-dim text-on-primary font-bold shadow-[0_0_20px_rgba(186,158,255,0.2)] hover:shadow-[0_0_30px_rgba(186,158,255,0.4)] active:scale-95 transition-all flex-1 md:flex-none whitespace-nowrap" :disabled="busy" @click="emit('persist')">
          {{ busy ? 'Syncing...' : 'Save Configuration' }}
        </button>
      </div>
    </div>

    <!-- Rail List Container -->
    <draggable v-model="localCatalogs" item-key="key" handle=".cursor-grab" @end="onDragEnd" class="space-y-2 w-full">
      <template #item="{ element: catalog }">
        <div v-show="isCatalogVisible(catalog)" class="group relative px-6 py-4 rounded-xl border transition-all duration-300 flex items-center justify-between gap-4 w-full" :class="!isCatalogEnabled(catalog) ? 'bg-[#131313]/30 backdrop-blur-md border-white/5 opacity-60' : 'bg-[#131313]/60 backdrop-blur-md border-white/5 hover:border-primary/30'">

          <div class="flex items-center gap-8 flex-grow">
            <div class="cursor-grab active:cursor-grabbing transition-colors flex items-center" :class="!isCatalogEnabled(catalog) ? 'text-zinc-800' : 'text-zinc-600 group-hover:text-primary'" title="Drag to reorder">
              <span class="material-symbols-outlined">drag_indicator</span>
            </div>

          <div class="flex flex-col min-w-0 max-w-[200px] sm:max-w-xs md:max-w-md lg:max-w-lg">
             <h3 class="font-headline font-bold text-base truncate" :class="!isCatalogEnabled(catalog) ? 'text-zinc-500 line-through' : 'text-on-surface'">{{ displayCatalogName(catalog) }}</h3>
             <p class="text-xs font-medium truncate" :class="!isCatalogEnabled(catalog) ? 'text-zinc-600' : 'text-zinc-500'">{{ formatSource(catalog) }}</p>
             <p class="text-[10px] font-body uppercase tracking-wider mt-0.5 truncate" :class="!isCatalogEnabled(catalog) ? 'text-zinc-700' : 'text-zinc-600'">{{ formatDisableKey(catalog.disableKey || catalog.key) }}</p>
          </div>
        </div>

        <div class="flex items-center gap-4">
          <div class="min-w-[140px] flex justify-end cursor-pointer" @click="emit('toggle-catalog', catalog.disableKey || catalog.key, catalog.key)">
            <span v-if="!isCatalogEnabled(catalog)" class="px-4 py-1.5 rounded-lg bg-zinc-900 text-zinc-600 text-[10px] font-bold border border-zinc-800 tracking-tight uppercase whitespace-nowrap hover:bg-zinc-800/80 transition-colors">
              Disabled
            </span>
            <span v-else-if="catalog.source === 'trakt'" class="px-4 py-1.5 rounded-lg bg-zinc-800/50 text-zinc-300 text-[10px] font-bold border border-white/10 tracking-tight uppercase whitespace-nowrap hover:bg-zinc-700/50 transition-colors">
              Managed in Trakt
            </span>
            <span v-else-if="catalog.source === 'mdblist'" class="px-3 py-1.5 rounded-lg bg-violet-950/20 text-violet-400 text-[10px] font-bold border border-violet-500/20 tracking-tight uppercase whitespace-nowrap hover:bg-violet-900/30 transition-colors">
              Managed implicitly
            </span>
            <span v-else class="px-4 py-1.5 rounded-lg bg-emerald-950/20 text-emerald-400 text-[10px] font-bold border border-emerald-500/20 tracking-tight uppercase whitespace-nowrap hover:bg-emerald-900/30 transition-colors">
              Enabled
            </span>
          </div>
        </div>

        </div>
      </template>
    </draggable>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import draggable from 'vuedraggable'
import type { AddonCatalogRecord, CatalogId, AddonRecord } from '~/types/portal'
import { traktCatalogLabels } from '~/utils/portal-metadata'

const props = defineProps<{
  catalogs: AddonCatalogRecord[]
  disabledKeys: string[]
  addons?: AddonRecord[]
  busy?: boolean
}>()

const emit = defineEmits<{
  persist: []
  'move-catalog': [key: string, direction: -1 | 1]
  'reorder-catalogs': [keys: string[]]
  'toggle-catalog': [key: string, legacyKey: string]
}>()

const localCatalogs = ref([...props.catalogs])
watch(() => props.catalogs, (newVal) => {
  localCatalogs.value = [...newVal]
}, { deep: true })

const statusFilter = ref<'ALL' | 'ENABLED' | 'DISABLED'>('ALL')
const enabledCatalogCount = computed(() => props.catalogs.filter(isCatalogEnabled).length)

function getDebugParent(catalog: AddonCatalogRecord) {
  if (!props.addons) return undefined
  return props.addons.find(a => {
    const canonical = a.url.trim().replace(/\/manifest\.json$/i, '').replace(/\/$/, '')
    const exactMatch = catalog.addonUrl && (canonical === catalog.addonUrl || a.url === catalog.addonUrl || a.manifestUrl === catalog.addonUrl)
    const prefixMatch = catalog.disableKey && (catalog.disableKey.startsWith(canonical + '_') || catalog.disableKey.startsWith(a.url + '_') || catalog.disableKey.startsWith(a.manifestUrl + '_'))
    return exactMatch || prefixMatch
  })
}

function isCatalogEnabled(catalog: AddonCatalogRecord) {
  if (props.disabledKeys.includes(catalog.disableKey) || props.disabledKeys.includes(catalog.key)) return false;
  if ((!catalog.source || catalog.source === 'addon') && props.addons) {
    const parent = getDebugParent(catalog)
    if (!parent || !parent.enabled) return false;
  }
  return true;
}

function isCatalogVisible(catalog: AddonCatalogRecord) {
  const enabled = isCatalogEnabled(catalog);
  if (statusFilter.value === 'ENABLED' && !enabled) return false;
  if (statusFilter.value === 'DISABLED' && enabled) return false;
  return true;
}

function onDragEnd() {
  emit('reorder-catalogs', localCatalogs.value.map(c => c.key))
}

function formatSource(c: AddonCatalogRecord) {
  if (c.source === 'trakt') return 'Trakt \u2022 built-in'
  if (c.source === 'mdblist') return 'MDBList \u2022 dynamic list'
  if (c.addonName) return `${c.addonName} \u2022 active`
  return 'Local Storage \u2022 active'
}

function formatDisableKey(key: string) {
  if (!key) return ''
  return key.replace(/_/g, ' ').replace(/:/g, ' ')
}

function displayCatalogName(c: AddonCatalogRecord) {
  if (c.source === 'trakt') {
    return traktCatalogLabels[c.key as CatalogId] || c.catalogName || 'Unknown Catalog'
  }
  return c.catalogName || 'Unknown Catalog'
}
</script>
