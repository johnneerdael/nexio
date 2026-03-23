<template>
  <div class="rounded-2xl border border-zinc-800/60 bg-zinc-900/40 p-1 shadow-sm backdrop-blur-xl">
    <div class="px-5 py-4 border-b border-zinc-800/60 flex items-center justify-between">
      <div>
        <h3 class="text-base font-medium text-white">{{ title }}</h3>
        <p v-if="description" class="mt-1 text-sm text-zinc-400">{{ description }}</p>
      </div>
      <slot name="header-action" />
    </div>
    
    <div class="divide-y divide-zinc-800/40">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>
