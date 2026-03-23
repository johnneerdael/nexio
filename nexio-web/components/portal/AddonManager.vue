<template>
  <div class="addon-manager-container w-full pb-16 md:pb-0">
    <PortalManagementHero
      eyebrow="Extension Layer"
      title="Addon Management"
      description="Extend your experience with third-party modules and community scrapers while keeping parser presets and ordering under your control."
      class="mb-10"
    >
      <template #right>
        <div class="rounded-2xl border border-outline-variant/10 bg-surface-container/70 p-4 md:p-5">
          <label class="mb-3 block text-[10px] font-bold uppercase tracking-[0.18em] text-primary">Install from URL</label>
          <div class="space-y-3">
            <div class="relative group">
              <span class="material-symbols-outlined absolute left-4 top-1/2 -translate-y-1/2 text-outline-variant group-focus-within:text-primary transition-colors" data-icon="link">link</span>
              <input class="w-full rounded-xl border border-outline-variant/15 bg-surface-container-lowest pl-12 pr-4 py-3 text-sm text-on-surface placeholder:text-zinc-700 focus:border-primary focus:ring-0" placeholder="https://addon-registry.nexio.io/manifest.json" type="text" v-model="addonUrl"/>
            </div>
            <div class="flex flex-col gap-3 sm:flex-row">
              <div class="relative flex-1">
                <select v-model="parserPreset" class="w-full rounded-xl border border-outline-variant/15 bg-surface-container-lowest px-4 py-3 text-sm text-on-surface appearance-none cursor-pointer focus:border-primary focus:ring-0">
                  <option value="GENERIC">Generic Parser</option>
                  <option value="STREMTHRU">StremThru</option>
                  <option value="TORRENTIO">Torrentio</option>
                  <option value="WEBSTREAMR">WebStreamr</option>
                </select>
              </div>
              <button @click="submitAddon" class="whitespace-nowrap rounded-xl bg-gradient-to-br from-primary to-primary-dim px-6 py-3 font-bold text-on-primary shadow-[0_0_20px_rgba(186,158,255,0.2)] transition-all hover:shadow-[0_0_30px_rgba(186,158,255,0.4)] active:scale-95">
                Install
              </button>
            </div>
          </div>
        </div>
      </template>
    </PortalManagementHero>

    <!-- Filters -->
    <div class="flex items-center justify-between mb-6 gap-4">
      <div class="relative flex-1 max-w-xs group">
        <span class="material-symbols-outlined absolute left-3 top-1/2 -translate-y-1/2 text-zinc-600 group-focus-within:text-secondary transition-colors text-xl" data-icon="search">search</span>
        <input class="w-full bg-surface-container-low border-none focus:ring-0 pl-10 pr-4 py-2 rounded-full text-xs text-on-surface-variant transition-all" placeholder="Search installed..." type="text"/>
      </div>
      <div class="flex items-center gap-2">
        <span class="text-[10px] font-bold uppercase text-zinc-500 tracking-wider">Status:</span>
        <select class="bg-surface-container-low border-none focus:ring-0 text-xs text-on-surface-variant rounded-full px-4 py-2 cursor-pointer appearance-none pr-8 relative">
          <option>All Addons</option>
          <option>Enabled</option>
          <option>Disabled</option>
        </select>
      </div>
    </div>

    <!-- Addon List -->
    <draggable v-model="localAddons" item-key="id" handle=".cursor-grab" @end="onDragEnd" class="flex flex-col gap-2">
      <template #item="{ element: addon }">
        <div class="group flex items-center gap-4 p-3 bg-surface-container rounded-xl hover:bg-surface-container-high transition-all border border-outline-variant/5">
          <div class="cursor-grab active:cursor-grabbing px-1 text-outline-variant hover:text-on-surface transition-colors flex items-center" title="Drag to reorder">
            <span class="material-symbols-outlined" data-icon="drag_indicator">drag_indicator</span>
          </div>
        <div class="w-12 h-12 rounded-lg bg-surface-container-lowest flex items-center justify-center text-primary border border-outline-variant/10 overflow-hidden shrink-0">
          <img v-if="addon.logo || fetchedLogos[addon.id]" :src="addon.logo || fetchedLogos[addon.id]" class="w-full h-full object-cover">
          <span v-else class="material-symbols-outlined text-3xl" data-icon="extension">extension</span>
        </div>
        <div class="flex-1 min-w-0">
          <h3 class="font-headline font-bold text-on-surface truncate">{{ addon.name }}</h3>
          <p class="text-xs text-on-surface-variant truncate">{{ addonPathLabel(addon) }}</p>
        </div>
        <div class="flex items-center gap-6 px-4 shrink-0">
          <select :value="addon.parserPreset" @change="emit('update-addon-parser-preset', addon.id, (($event.target as HTMLSelectElement).value as AddonRecord['parserPreset']))" class="bg-surface-container-lowest border border-outline-variant/15 text-[10px] uppercase font-bold text-on-surface-variant rounded-lg px-2 py-1.5 cursor-pointer appearance-none transition-colors hover:border-outline-variant focus:ring-0 hidden md:block">
            <option value="GENERIC">Generic</option>
            <option value="STREMTHRU">StremThru</option>
            <option value="TORRENTIO">Torrentio</option>
            <option value="WEBSTREAMR">WebStreamr</option>
          </select>

          <div class="relative inline-flex items-center cursor-pointer" @click="emit('toggle-addon', addon.id)">
            <input :checked="addon.enabled" class="sr-only peer" type="checkbox" readOnly/>
            <div class="w-10 h-5 bg-zinc-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-zinc-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
          </div>
          <button @click="emit('remove-addon', addon.id)" class="text-outline-variant hover:text-error-dim transition-colors material-symbols-outlined" data-icon="delete">delete</button>
        </div>
      </div>
      </template>
    </draggable>

    <!-- Sync Button -->
    <div class="mt-8 flex justify-end">
      <button class="px-6 py-3 bg-gradient-to-br from-primary to-primary-dim text-on-primary font-bold rounded-xl active:scale-95 transition-all shadow-[0_0_20px_rgba(186,158,255,0.2)] hover:shadow-[0_0_30px_rgba(186,158,255,0.4)]" :disabled="busy" @click="emit('persist')">
        {{ busy ? 'Nexio Syncing...' : 'Save & Sync' }}
      </button>
    </div>

    <div
      class="mt-10 rounded-2xl border p-5 md:p-6"
      :class="props.addons.length === 0
        ? 'border-primary/50 bg-primary/5 shadow-[0_0_30px_rgba(186,158,255,0.15)]'
        : 'border-outline-variant/20 bg-surface-container-low'"
    >
      <div class="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <p class="text-[10px] font-bold uppercase tracking-[0.18em] text-primary">Migration Assistant</p>
          <h3 class="text-lg font-bold text-on-surface mt-1">Import addons from Stremio or Nuvio</h3>
          <p class="text-xs text-on-surface-variant mt-1">
            Credentials are used server-side for one-time pull. Only addon URLs and metadata are imported.
          </p>
        </div>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-4 gap-3 mt-4">
        <select v-model="migrationSource" class="rounded-xl border border-outline-variant/20 bg-surface-container-lowest px-4 py-3 text-sm text-on-surface">
          <option value="stremio">Stremio</option>
          <option value="nuvio">Nuvio</option>
        </select>
        <input v-model="migrationEmail" type="email" placeholder="Source email" class="rounded-xl border border-outline-variant/20 bg-surface-container-lowest px-4 py-3 text-sm text-on-surface placeholder:text-zinc-700" />
        <input v-model="migrationPassword" type="password" placeholder="Source password" class="rounded-xl border border-outline-variant/20 bg-surface-container-lowest px-4 py-3 text-sm text-on-surface placeholder:text-zinc-700" />
        <button
          class="rounded-xl bg-gradient-to-br from-secondary to-cyan-500 px-4 py-3 text-sm font-bold text-black disabled:opacity-60"
          :disabled="migration.pulling || !migrationEmail || !migrationPassword"
          @click="submitMigrationPull"
        >
          {{ migration.pulling ? 'Pulling…' : 'Pull Addons' }}
        </button>
      </div>

      <p v-if="migration.error" class="mt-3 text-xs text-red-300">{{ migration.error }}</p>

      <div v-if="migration.importedAddons.length > 0" class="mt-4 rounded-xl border border-outline-variant/20 bg-surface-container p-4">
        <div class="flex items-center justify-between gap-3 flex-wrap">
          <p class="text-xs text-on-surface-variant">
            {{ migration.importedAddons.length }} addons discovered. Select what to import.
          </p>
          <div class="flex items-center gap-2">
            <button class="text-xs text-secondary" @click="selectAllImported">Select all</button>
            <button class="text-xs text-secondary" @click="clearImportedSelection">Clear</button>
          </div>
        </div>

        <div class="mt-3 max-h-64 overflow-auto space-y-2 pr-1">
          <label
            v-for="addon in migration.importedAddons"
            :key="addon.id"
            class="flex items-center gap-3 rounded-lg border border-outline-variant/15 bg-surface-container-lowest px-3 py-2"
          >
            <input
              type="checkbox"
              :checked="selectedImportedUrls.has(addon.url)"
              @change="toggleImportedSelection(addon.url)"
            />
            <div class="min-w-0">
              <p class="text-sm text-on-surface truncate">{{ addon.name }}</p>
              <p class="text-xs text-on-surface-variant truncate">{{ addon.manifestUrl }}</p>
            </div>
          </label>
        </div>

        <div class="mt-4 flex justify-end">
          <button
            class="rounded-xl bg-gradient-to-br from-primary to-primary-dim px-5 py-2.5 text-sm font-bold text-on-primary disabled:opacity-60"
            :disabled="migration.committing || selectedImportedUrls.size === 0"
            @click="submitMigrationCommit"
          >
            {{ migration.committing ? 'Importing…' : `Import ${selectedImportedUrls.size} Addon(s)` }}
          </button>
        </div>
      </div>
    </div>

    <!-- System Message -->
    <div class="mt-12 p-6 glass-panel rounded-xl border-l-4 border-primary/40 flex items-start gap-4 hidden md:flex">
      <span class="material-symbols-outlined text-primary" data-icon="info">info</span>
      <div>
        <h4 class="text-sm font-bold text-white mb-1">Addon Sandboxing</h4>
        <p class="text-xs text-on-surface-variant leading-relaxed">Nexio runs all third-party addons in an isolated sandbox. We never share your debrid API keys directly with addon maintainers. Use the toggle to temporarily disable addons without removing their configuration.</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import draggable from 'vuedraggable'
import type { AddonRecord } from '~/types/portal'
import { normalizeAddonManifestUrl } from '~/utils/account-secrets'

type ImportedAddonCandidate = {
  id: string
  url: string
  manifestUrl: string
  name: string
  description?: string
  logo?: string
  parserPreset: AddonRecord['parserPreset']
  enabled: boolean
  publicQueryParams: Record<string, string>
}

const props = defineProps<{
  addons: AddonRecord[]
  secretStatuses: Record<string, { maskedPreview?: string | null }>
  migration: {
    pulling: boolean
    committing: boolean
    error: string | null
    importedAddons: ImportedAddonCandidate[]
    lastPulledAt: string | null
  }
  busy?: boolean
}>()

const emit = defineEmits<{
  persist: []
  'add-addon': [url: string, parserPreset: AddonRecord['parserPreset']]
  'remove-addon': [id: string]
  'move-addon': [id: string, direction: -1 | 1]
  'reorder-addons': [ids: string[]]
  'toggle-addon': [id: string]
  'update-addon-parser-preset': [id: string, parserPreset: AddonRecord['parserPreset']]
  'migration-pull': [source: 'stremio' | 'nuvio', email: string, password: string]
  'migration-commit': [selectedUrls: string[]]
}>()

const localAddons = ref([...props.addons])
watch(() => props.addons, (newVal) => {
  localAddons.value = [...newVal]
}, { deep: true })

function onDragEnd() {
  emit('reorder-addons', localAddons.value.map(a => a.id))
}

const addonUrl = ref('')
const parserPreset = ref<AddonRecord['parserPreset']>('GENERIC')
const fetchedLogos = ref<Record<string, string>>({})

function addonDisplayUrl(addon: AddonRecord) {
  const params = new URLSearchParams(addon.publicQueryParams ?? {})
  const base = normalizeAddonManifestUrl(addon.url, addon.manifestUrl)
  const query = params.toString()
  return query ? `${base}?${query}` : base
}

async function fetchAddonManifest(addon: AddonRecord) {
  if (addon.logo || fetchedLogos.value[addon.id]) return;
  const url = addonDisplayUrl(addon);
  try {
    const res = await fetch(url)
    const data = await res.json()
    if (data && data.logo) {
      fetchedLogos.value[addon.id] = data.logo
    }
  } catch (e) {
    // console.warn('Failed to fetch logo for', addon.id)
  }
}

watch(() => props.addons, (newAddons) => {
  newAddons.forEach(addon => {
    fetchAddonManifest(addon)
  })
}, { immediate: true })

function addonPathLabel(addon: AddonRecord) {
  try {
    const url = new URL(normalizeAddonManifestUrl(addon.url, addon.manifestUrl))
    return url.host + (url.pathname !== '/manifest.json' ? ' · Custom Path' : '')
  } catch {
    return 'Invalid URL'
  }
}

const migration = props.migration
const migrationSource = ref<'stremio' | 'nuvio'>('stremio')
const migrationEmail = ref('')
const migrationPassword = ref('')
const selectedImportedUrls = ref<Set<string>>(new Set())

watch(
  () => props.migration.importedAddons,
  (addons) => {
    selectedImportedUrls.value = new Set(addons.map((addon) => addon.url))
  },
  { immediate: true }
)

function toggleImportedSelection(url: string) {
  const next = new Set(selectedImportedUrls.value)
  if (next.has(url)) next.delete(url)
  else next.add(url)
  selectedImportedUrls.value = next
}

function selectAllImported() {
  selectedImportedUrls.value = new Set(props.migration.importedAddons.map((addon) => addon.url))
}

function clearImportedSelection() {
  selectedImportedUrls.value = new Set()
}

function submitMigrationPull() {
  if (!migrationEmail.value || !migrationPassword.value) return
  emit('migration-pull', migrationSource.value, migrationEmail.value, migrationPassword.value)
}

function submitMigrationCommit() {
  emit('migration-commit', [...selectedImportedUrls.value])
}

function submitAddon() {
  if (!addonUrl.value) return
  emit('add-addon', addonUrl.value, parserPreset.value)
  addonUrl.value = ''
  parserPreset.value = 'GENERIC'
}
</script>
