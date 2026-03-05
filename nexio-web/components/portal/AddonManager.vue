<template>
  <section class="glass" style="padding:1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.2rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-end; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Addons And Repositories</p>
        <h2 class="section-title" style="font-size:1.9rem; margin:0.7rem 0 0.4rem;">Control the synced catalog stack.</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 46rem; line-height:1.6;">
          Add or remove addons, change their order, and manage scraper repositories that should follow the account profile.
        </p>
      </div>
      <button class="primary-btn" @click="emit('persist')">Sync Now</button>
    </div>

    <div class="grid-2">
      <div class="field-shell">
        <label for="addon-url">Install addon</label>
        <input id="addon-url" v-model="addonUrl" placeholder="https://example.com/manifest.json">
        <button class="secondary-btn" @click="submitAddon">Add addon</button>
      </div>
      <div class="field-shell">
        <label for="repo-url">Add repository</label>
        <input id="repo-url" v-model="repositoryUrl" placeholder="https://addons.nexio.app/community-repo.json">
        <input v-model="repositoryLabel" placeholder="Community Scrapers">
        <button class="secondary-btn" @click="submitRepository">Add repository</button>
      </div>
    </div>

    <div class="grid-2">
      <article class="field-shell">
        <div style="display:flex; justify-content:space-between; align-items:center; gap:1rem;">
          <div>
            <label>Installed addons</label>
            <p>These URLs are pushed instantly and pulled on TV startup.</p>
          </div>
          <span class="badge"><strong>{{ addons.length }}</strong></span>
        </div>
        <div style="display:grid; gap:0.8rem;">
          <div v-for="addon in addons" :key="addon.id" class="field-shell" style="padding:0.95rem; background: rgba(255,255,255,0.03);">
            <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; flex-wrap:wrap;">
              <div>
                <strong>{{ addon.name }}</strong>
                <p>{{ addon.url }}</p>
                <p v-if="addon.description">{{ addon.description }}</p>
              </div>
              <div style="display:flex; gap:0.45rem; flex-wrap:wrap;">
                <button class="ghost-btn" @click="emit('move-addon', addon.id, -1)">Up</button>
                <button class="ghost-btn" @click="emit('move-addon', addon.id, 1)">Down</button>
                <button class="secondary-btn" @click="emit('toggle-addon', addon.id)">{{ addon.enabled ? 'Disable' : 'Enable' }}</button>
                <button class="danger-btn" @click="emit('remove-addon', addon.id)">Remove</button>
              </div>
            </div>
          </div>
        </div>
      </article>

      <article class="field-shell">
        <div style="display:flex; justify-content:space-between; align-items:center; gap:1rem;">
          <div>
            <label>Scraper repositories</label>
            <p>Account-managed repository URLs and enabled scraper ids.</p>
          </div>
          <span class="badge"><strong>{{ repositories.length }}</strong></span>
        </div>
        <div style="display:grid; gap:0.8rem;">
          <div v-for="repository in repositories" :key="repository.id" class="field-shell" style="padding:0.95rem; background: rgba(255,255,255,0.03);">
            <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; flex-wrap:wrap;">
              <div>
                <strong>{{ repository.label }}</strong>
                <p>{{ repository.url }}</p>
              </div>
              <button class="danger-btn" @click="emit('remove-repository', repository.id)">Remove</button>
            </div>
            <input
              :value="repository.enabledScraperIds.join(', ')"
              placeholder="torrentio-lite, comet"
              @input="emit('set-repository-scrapers', repository.id, ($event.target as HTMLInputElement).value)"
            >
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { AddonRecord, PluginRepository } from '~/types/portal'

defineProps<{
  addons: AddonRecord[]
  repositories: PluginRepository[]
}>()

const emit = defineEmits<{
  persist: []
  'add-addon': [url: string]
  'remove-addon': [id: string]
  'move-addon': [id: string, direction: -1 | 1]
  'toggle-addon': [id: string]
  'add-repository': [url: string, label: string]
  'remove-repository': [id: string]
  'set-repository-scrapers': [id: string, value: string]
}>()

const addonUrl = ref('')
const repositoryUrl = ref('')
const repositoryLabel = ref('')

function submitAddon() {
  emit('add-addon', addonUrl.value)
  addonUrl.value = ''
}

function submitRepository() {
  emit('add-repository', repositoryUrl.value, repositoryLabel.value)
  repositoryUrl.value = ''
  repositoryLabel.value = ''
}
</script>
