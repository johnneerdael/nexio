<template>
  <section class="glass" style="padding: 1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.25rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Account Overview</p>
        <h2 class="section-title" style="font-size: 2rem; margin: 0.8rem 0 0.4rem;">{{ title }}</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 44rem; line-height: 1.6;">
          One account portal for every Nexio screen.
        </p>
      </div>
      <div style="display:flex; gap:0.7rem; flex-wrap:wrap;">
        <span class="badge"><strong>{{ syncRevision }}</strong> revision</span>
        <span class="badge"><strong>{{ addonsCount }}</strong> addons</span>
        <span class="badge"><strong>{{ linkedDevices }}</strong> linked TVs</span>
      </div>
    </div>

    <div class="grid-3">
      <article class="field-shell">
        <span style="color: var(--text-dim);">Last sync</span>
        <strong>{{ lastSyncedAtLabel }}</strong>
        <p>Nexio Sync writes account changes immediately and makes them the next startup snapshot for every linked TV.</p>
      </article>
      <article class="field-shell">
        <span style="color: var(--text-dim);">Portal scope</span>
        <strong>Settings, catalogs, addons</strong>
        <p>Manage the shared UI, integration state, addon stack, and TV approvals from one account surface.</p>
      </article>
      <article class="field-shell">
        <span style="color: var(--text-dim);">Device control</span>
        <strong>{{ devices.length }} connected records</strong>
        <p>Review linked TVs and remove stale devices remotely without visiting the television.</p>
      </article>
    </div>

    <article class="field-shell">
      <div style="display:flex; justify-content:space-between; align-items:center; gap:1rem; flex-wrap:wrap;">
        <div>
          <label>Linked TVs</label>
          <p>Live account links for TVs currently connected to this account.</p>
        </div>
        <span class="badge">{{ devices.length }} records</span>
      </div>

      <div v-if="devices.length === 0" class="field-shell" style="padding:0.95rem; background: rgba(255,255,255,0.02);">
        <strong>No linked TVs yet</strong>
        <p>QR approvals and account sign-ins add TVs here automatically.</p>
      </div>

      <div v-else style="display:grid; gap:0.75rem;">
        <div v-for="device in devices" :key="device.id" class="device-row">
          <div style="min-width:0; display:grid; gap:0.2rem;">
            <strong style="font-size:1rem;">{{ device.name }}</strong>
            <span style="color: var(--text-dim); font-size:0.92rem;">{{ device.model }} · {{ device.platform }}</span>
          </div>
          <div style="display:flex; gap:0.65rem; align-items:center; justify-content:flex-end; flex-wrap:wrap;">
            <span class="badge"><strong>{{ device.status }}</strong></span>
            <button class="danger-btn" @click="emit('unlink-device', device.deviceUserId ?? device.id)">Unlink</button>
          </div>
        </div>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { LinkedDevice } from '~/types/portal'

const props = defineProps<{
  title: string
  syncRevision: number
  addonsCount: number
  linkedDevices: number
  lastSyncedAt: string | null
  devices: LinkedDevice[]
}>()

const emit = defineEmits<{
  'unlink-device': [deviceUserId: string]
}>()

const lastSyncedAtLabel = computed(() => {
  if (!props.lastSyncedAt) {
    return 'Not synced yet'
  }

  return new Date(props.lastSyncedAt).toLocaleString()
})
</script>
