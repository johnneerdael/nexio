<template>
  <div class="bg-surface-container-lowest p-6 rounded-xl border border-outline-variant/15 flex flex-col justify-between gap-4 transition-all duration-300 hover:border-primary/20">
    <div>
      <label :for="field.path" class="block text-sm font-bold text-on-surface mb-1">{{ field.label }}</label>
      <p class="text-xs text-on-surface-variant leading-relaxed">{{ field.description }}</p>
    </div>

    <div v-if="field.kind === 'toggle'" class="flex items-center justify-between mt-2 pt-4 border-t border-outline-variant/10">
      <span class="text-xs font-bold text-on-surface-variant uppercase tracking-wider">{{ value ? 'Enabled' : 'Disabled' }}</span>
      <button
        type="button"
        :class="['w-11 h-6 rounded-full transition-colors relative focus:outline-none focus:ring-2 focus:ring-primary/50', value ? 'bg-primary' : 'bg-surface-bright border border-outline-variant/20']"
        @click="$emit('update', !value)"
      >
        <span
          :class="['absolute top-1 bg-white w-4 h-4 rounded-full transition-all', value ? 'left-6' : 'left-1 bg-on-surface-variant']"
        ></span>
      </button>
    </div>

    <div v-else-if="field.kind === 'select'" class="relative mt-2">
      <select
        :id="field.path"
        :value="value as string | number"
        class="w-full bg-surface-container border-none border-b-2 border-outline-variant/50 focus:border-primary focus:ring-0 px-4 py-3 rounded-lg text-sm text-on-surface appearance-none cursor-pointer hover:bg-surface-container-high transition-colors"
        @change="$emit('update', ($event.target as HTMLSelectElement).value)"
      >
        <option v-for="option in field.options" :key="String(option.value)" :value="option.value">
          {{ option.label }}
        </option>
      </select>
      <svg class="w-4 h-4 text-on-surface-variant absolute right-4 top-4 pointer-events-none" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"></path></svg>
    </div>

    <div v-else-if="field.kind === 'slider'" class="flex flex-col gap-3 mt-2">
      <input
        :id="field.path"
        type="range"
        :min="field.min"
        :max="field.max"
        :step="field.step"
        :value="Number(value)"
        class="w-full h-2 bg-surface-container-highest rounded-lg appearance-none cursor-pointer accent-primary"
        @input="$emit('update', Number(($event.target as HTMLInputElement).value))"
      >
      <div class="flex justify-between items-center px-1">
        <span class="text-[10px] text-on-surface-variant">{{ field.min }}</span>
        <strong class="text-sm font-bold text-primary bg-primary/10 px-3 py-1 rounded-lg border border-primary/20">{{ value }}</strong>
        <span class="text-[10px] text-on-surface-variant">{{ field.max }}</span>
      </div>
    </div>

    <textarea
      v-else-if="field.kind === 'list'"
      :id="field.path"
      :placeholder="field.placeholder"
      :value="Array.isArray(value) ? value.join(', ') : value"
      class="w-full bg-surface-container border-none border-b-2 border-outline-variant/50 focus:border-primary focus:ring-0 px-4 py-3 rounded-lg text-sm text-on-surface placeholder:text-on-surface-variant/40 mt-2 min-h-[100px] resize-y transition-colors hover:bg-surface-container-high"
      @input="$emit('update', ($event.target as HTMLTextAreaElement).value)"
    />

    <input
      v-else
      :id="field.path"
      :type="field.kind === 'secret' ? 'password' : 'text'"
      :placeholder="field.placeholder"
      :value="value == null ? '' : value"
      class="w-full bg-surface-container border-none border-b-2 border-outline-variant/50 focus:border-primary focus:ring-0 px-4 py-3 rounded-lg text-sm text-on-surface placeholder:text-on-surface-variant/40 mt-2 transition-colors hover:bg-surface-container-high"
      @input="$emit('update', ($event.target as HTMLInputElement).value)"
    >
  </div>
</template>

<script setup lang="ts">
import type { PortalField } from '~/utils/portal-metadata'

defineProps<{
  field: PortalField
  value: unknown
}>()

defineEmits<{
  update: [value: unknown]
}>()
</script>
