<template>
  <section class="glass" style="padding: 1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.4rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Account Overview</p>
        <h2 class="section-title" style="font-size: 2rem; margin: 0.8rem 0 0.4rem;">{{ title }}</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 48rem; line-height: 1.6;">
          One account-level control plane for settings, addons, scraper repositories, Trakt, and QR approval.
        </p>
      </div>
      <div style="display:flex; gap:0.7rem; flex-wrap:wrap;">
        <span class="badge"><strong>{{ syncRevision }}</strong> revision</span>
        <span class="badge"><strong>{{ addonsCount }}</strong> addons</span>
        <span class="badge"><strong>{{ linkedDevices }}</strong> TVs</span>
      </div>
    </div>

    <div class="grid-3">
      <article class="field-shell">
        <span style="color: var(--text-dim);">Last sync</span>
        <strong>{{ lastSyncedAtLabel }}</strong>
        <p>Edits save immediately to Supabase and become the next startup source of truth for every linked TV.</p>
      </article>
      <article class="field-shell">
        <span style="color: var(--text-dim);">Scope</span>
        <strong>{{ syncScopeLabel }}</strong>
        <p>Library, watch progress, and watched-item sync stay out of this redesign. Only settings and addon controls are in scope.</p>
      </article>
      <article class="field-shell">
        <span style="color: var(--text-dim);">Exclusions</span>
        <strong>{{ exclusions.length }} device-only fields</strong>
        <p>VOD cache, parallel connections, Dolby Vision, and Fire OS knobs stay local to each device.</p>
      </article>
    </div>

    <div class="grid-2">
      <article class="field-shell">
        <div style="display:flex; justify-content:space-between; align-items:center; gap:1rem;">
          <div>
            <label>Linked TVs</label>
            <p>Live status from linked devices connected to the same account.</p>
          </div>
          <span class="badge">{{ devices.length }} active records</span>
        </div>
        <div style="display:grid; gap:0.8rem;">
          <div v-for="device in devices" :key="device.id" class="field-shell" style="padding:0.9rem; background: rgba(255,255,255,0.02);">
            <div style="display:flex; justify-content:space-between; gap:0.8rem; align-items:center;">
              <div>
                <strong>{{ device.name }}</strong>
                <p>{{ device.model }} · {{ device.platform }}</p>
              </div>
              <span class="badge"><strong>{{ device.status }}</strong></span>
            </div>
          </div>
        </div>
      </article>

      <article class="field-shell">
        <div>
          <label>Excluded sync parameters</label>
          <p>These remain device-scoped and are intentionally not written to Supabase.</p>
        </div>
        <div style="display:grid; gap:0.7rem;">
          <div v-for="item in exclusions" :key="item.key" style="padding:0.8rem 0.9rem; border-radius:12px; background: rgba(255,255,255,0.03); border:1px solid var(--stroke);">
            <strong style="display:block; margin-bottom:0.25rem;">{{ item.key }}</strong>
            <span style="color: var(--text-dim); font-size: 0.9rem;">{{ item.reason }}</span>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { LinkedDevice, SyncExclusion } from '~/types/portal'

const props = defineProps<{
  title: string
  syncRevision: number
  addonsCount: number
  linkedDevices: number
  syncScopeLabel: string
  lastSyncedAt: string | null
  devices: LinkedDevice[]
  exclusions: SyncExclusion[]
}>()

const lastSyncedAtLabel = computed(() => {
  if (!props.lastSyncedAt) {
    return 'Not synced yet'
  }

  return new Date(props.lastSyncedAt).toLocaleString()
})
</script>
