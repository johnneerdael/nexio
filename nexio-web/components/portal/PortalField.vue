<template>
  <div class="field-shell">
    <div>
      <label :for="field.path">{{ field.label }}</label>
      <p>{{ field.description }}</p>
    </div>

    <div v-if="field.kind === 'toggle'" class="toggle-row">
      <span>{{ value ? 'Enabled' : 'Disabled' }}</span>
      <button type="button" :class="['switch', { active: Boolean(value) }]" @click="$emit('update', !value)" />
    </div>

    <select
      v-else-if="field.kind === 'select'"
      :id="field.path"
      :value="value as string | number"
      @change="$emit('update', ($event.target as HTMLSelectElement).value)"
    >
      <option v-for="option in field.options" :key="String(option.value)" :value="option.value">
        {{ option.label }}
      </option>
    </select>

    <div v-else-if="field.kind === 'slider'" style="display:grid; gap:0.7rem;">
      <input
        :id="field.path"
        type="range"
        :min="field.min"
        :max="field.max"
        :step="field.step"
        :value="Number(value)"
        @input="$emit('update', Number(($event.target as HTMLInputElement).value))"
      >
      <strong>{{ value }}</strong>
    </div>

    <textarea
      v-else-if="field.kind === 'list'"
      :id="field.path"
      :placeholder="field.placeholder"
      :value="Array.isArray(value) ? value.join(', ') : value"
      @input="$emit('update', ($event.target as HTMLTextAreaElement).value)"
    />

    <input
      v-else
      :id="field.path"
      :type="field.kind === 'secret' ? 'password' : 'text'"
      :placeholder="field.placeholder"
      :value="value == null ? '' : value"
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
