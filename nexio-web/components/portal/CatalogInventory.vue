<template>
  <section class="glass" style="padding:1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.2rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-end; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">My Catalogs</p>
        <h2 class="section-title" style="font-size:1.9rem; margin:0.7rem 0 0.4rem;">Reorder addon-fed home rails.</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 50rem; line-height:1.6;">
          This editor only manages catalogs discovered from installed addons such as Cinemeta and future addon manifests. Trakt and MDBList catalogs stay in the Integrations view.
        </p>
      </div>
      <div style="display:flex; gap:0.65rem; align-items:center; flex-wrap:wrap;">
        <span class="badge"><strong>{{ catalogs.length }}</strong> addon catalogs</span>
        <button class="primary-btn" :disabled="busy" @click="emit('persist')">{{ busy ? 'Nexio Syncing...' : 'Nexio Sync' }}</button>
      </div>
    </div>

    <article v-if="catalogs.length === 0" class="field-shell">
      <label>No addon catalogs detected</label>
      <p>Cinemeta and other addon rails appear here after manifest inspection succeeds.</p>
    </article>

    <div v-else style="display:grid; gap:0.85rem;">
      <article v-for="catalog in catalogs" :key="catalog.key" class="catalog-row">
        <div style="display:grid; gap:0.2rem; min-width:0;">
          <strong style="font-size:1rem;">{{ catalog.catalogName }}</strong>
          <p>{{ catalog.addonName }} · {{ catalog.type }}</p>
          <p style="word-break: break-word;">{{ catalog.key }}</p>
        </div>
        <div style="display:flex; gap:0.45rem; flex-wrap:wrap; justify-content:flex-end;">
          <button class="ghost-btn" @click="emit('move-catalog', catalog.key, -1)">Up</button>
          <button class="ghost-btn" @click="emit('move-catalog', catalog.key, 1)">Down</button>
          <button :class="isDisabled(catalog.key) ? 'toggle-chip' : 'toggle-chip active'" @click="emit('toggle-catalog', catalog.key)">
            {{ isDisabled(catalog.key) ? 'Disabled' : 'Enabled' }}
          </button>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { AddonCatalogRecord } from '~/types/portal'

const props = defineProps<{
  catalogs: AddonCatalogRecord[]
  disabledKeys: string[]
  busy?: boolean
}>()

const emit = defineEmits<{
  persist: []
  'move-catalog': [key: string, direction: -1 | 1]
  'toggle-catalog': [key: string]
}>()

function isDisabled(key: string) {
  return props.disabledKeys.includes(key)
}
</script>
