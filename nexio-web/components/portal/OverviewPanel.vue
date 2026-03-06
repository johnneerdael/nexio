<template>
  <section class="glass overview-strip">
    <div class="overview-strip__lead">
      <span class="secondary-btn overview-pill">Account Overview - {{ title }}</span>
      <span class="badge"><strong>{{ addonsCount }}</strong> addons</span>
    </div>
    <div class="overview-strip__meta">
      <span class="badge"><strong>Last sync</strong> {{ lastSyncedAtLabel }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  title: string
  addonsCount: number
  lastSyncedAt: string | null
}>()

const lastSyncedAtLabel = computed(() => {
  if (!props.lastSyncedAt) {
    return 'Not synced yet'
  }

  return new Date(props.lastSyncedAt).toLocaleString()
})
</script>
