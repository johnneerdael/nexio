<template>
  <div
    class="flex items-center justify-between px-5 py-4 transition-colors hover:bg-zinc-800/20"
    :class="{ 'opacity-50 pointer-events-none': disabled }"
  >
    <div class="flex-1 pr-6 flex flex-col justify-center">
      <h4 class="text-sm font-medium text-zinc-200" :class="{ 'text-zinc-500': disabled }">
        {{ label }}
      </h4>
      <p v-if="description" class="mt-0.5 text-xs text-zinc-400" :class="{ 'text-zinc-600': disabled }">
        {{ description }}
      </p>
    </div>
    <div class="flex shrink-0 items-center justify-end" :style="{ width: controlWidth }">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  label: string
  description?: string
  disabled?: boolean
  controlMaxWidth?: string // useful for inputs or selects to not be squished. default 'auto'
}>()

const controlWidth = computed(() => props.controlMaxWidth || 'auto')
</script>
