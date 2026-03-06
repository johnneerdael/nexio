<template>
  <section class="glass" style="padding:1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.2rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-end; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Addon Stack</p>
        <h2 class="section-title" style="font-size:1.9rem; margin:0.7rem 0 0.4rem;">Control the synced addon stack.</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 46rem; line-height:1.6;">
          Add, remove, reorder, and enable account-managed addons. Secret-backed installs stay masked and never render credential parameters here.
        </p>
      </div>
      <button class="primary-btn" :disabled="busy" @click="emit('persist')">{{ busy ? 'Nexio Syncing...' : 'Nexio Sync' }}</button>
    </div>

    <div class="field-shell">
      <label for="addon-url">Install addon</label>
      <input id="addon-url" v-model="addonUrl" placeholder="https://example.com/manifest.json">
      <button class="secondary-btn" @click="submitAddon">Add addon</button>
    </div>

    <article class="field-shell">
      <div style="display:flex; justify-content:space-between; align-items:center; gap:1rem; flex-wrap:wrap;">
        <div>
          <label>Installed addons</label>
          <p>Each change is pushed to the account snapshot and pulled again on TV startup.</p>
        </div>
        <span class="badge"><strong>{{ addons.length }}</strong></span>
      </div>

      <div style="display:grid; gap:0.8rem;">
        <div v-for="addon in addons" :key="addon.id" class="addon-row">
          <div style="display:grid; gap:0.25rem; min-width:0;">
            <strong style="font-size:1rem;">{{ addon.name }}</strong>
            <p style="word-break: break-word;">{{ addonDisplayUrl(addon) }}</p>
            <p v-if="addon.secretRef && secretStatuses[addon.secretRef]">
              {{ secretStatuses[addon.secretRef].maskedPreview || 'Secret configured' }}
            </p>
            <p v-if="addon.description">{{ addon.description }}</p>
          </div>
          <div style="display:flex; gap:0.45rem; flex-wrap:wrap; justify-content:flex-end;">
            <button class="ghost-btn" @click="emit('move-addon', addon.id, -1)">Up</button>
            <button class="ghost-btn" @click="emit('move-addon', addon.id, 1)">Down</button>
            <button :class="addon.enabled ? 'toggle-chip active' : 'toggle-chip'" @click="emit('toggle-addon', addon.id)">
              {{ addon.enabled ? 'Enabled' : 'Disabled' }}
            </button>
            <button class="danger-btn" @click="emit('remove-addon', addon.id)">Remove</button>
          </div>
        </div>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { AddonRecord } from '~/types/portal'

const props = defineProps<{
  addons: AddonRecord[]
  secretStatuses: Record<string, { maskedPreview?: string | null }>
  busy?: boolean
}>()

const emit = defineEmits<{
  persist: []
  'add-addon': [url: string]
  'remove-addon': [id: string]
  'move-addon': [id: string, direction: -1 | 1]
  'toggle-addon': [id: string]
}>()

const addonUrl = ref('')

function addonDisplayUrl(addon: AddonRecord) {
  const params = new URLSearchParams(addon.publicQueryParams ?? {})
  const base = addon.manifestUrl || `${addon.url.replace(/\/$/, '')}/manifest.json`
  const query = params.toString()
  return query ? `${base}?${query}` : base
}

function submitAddon() {
  emit('add-addon', addonUrl.value)
  addonUrl.value = ''
}
</script>
