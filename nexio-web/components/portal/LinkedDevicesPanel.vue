<template>
  <section class="glass" style="padding:1.2rem; border-radius: var(--radius-xl); display:grid; gap:0.9rem;">
    <div style="display:flex; justify-content:space-between; align-items:center; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Linked TVs</p>
        <p style="margin:0.45rem 0 0; color: var(--text-dim);">Debug-facing device links. Unlink stale TVs remotely from here.</p>
      </div>
      <span class="badge"><strong>{{ devices.length }}</strong> records</span>
    </div>

    <div v-if="devices.length === 0" class="field-shell" style="padding:0.9rem;">
      <strong>No linked TVs yet</strong>
      <p>QR approvals and account sign-ins add TVs here automatically.</p>
    </div>

    <div v-else style="display:grid; gap:0.65rem;">
        <div v-for="device in devices" :key="device.id" class="device-row device-row--compact">
          <div style="min-width:0; display:grid; gap:0.1rem;">
            <strong style="font-size:0.98rem;">{{ device.name }}</strong>
            <span style="color: var(--text-dim); font-size:0.88rem;">{{ device.model }} · {{ device.platform }}</span>
          </div>
          <div style="display:flex; gap:0.55rem; align-items:center; justify-content:flex-end; flex-wrap:wrap;">
            <span class="badge"><strong>{{ device.status }}</strong></span>
            <button
              class="danger-btn"
              :disabled="!unlinkableId(device)"
              @click="emit('unlink-device', unlinkableId(device)!)"
            >
              Unlink
            </button>
          </div>
        </div>
      </div>
  </section>
</template>

<script setup lang="ts">
import type { LinkedDevice } from '~/types/portal'

defineProps<{
  devices: LinkedDevice[]
}>()

const emit = defineEmits<{
  'unlink-device': [deviceUserId: string]
}>()

function unlinkableId(device: LinkedDevice) {
  const candidate = (device.deviceUserId ?? device.id ?? '').trim()
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(candidate)
    ? candidate
    : null
}
</script>
