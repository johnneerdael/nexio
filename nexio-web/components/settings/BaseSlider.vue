<template>
  <div class="flex items-center gap-4 w-full">
    <div class="flex-grow">
      <input
        type="range"
        :min="min"
        :max="max"
        :step="step"
        v-model.number="internalValue"
        :disabled="disabled"
        class="w-full h-1.5 bg-zinc-800 rounded-lg appearance-none cursor-pointer focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed"
      />
    </div>
    <div class="w-20 shrink-0">
      <input
        type="number"
        :min="min"
        :max="max"
        :step="step"
        v-model.number="internalValue"
        :disabled="disabled"
        class="block w-full rounded-lg border border-zinc-700 bg-zinc-900/50 px-3 py-1.5 text-right text-sm text-zinc-200 placeholder-zinc-500 focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: number
  min?: number
  max?: number
  step?: number
  disabled?: boolean
}>(), {
  min: 0,
  max: 100,
  step: 1,
  disabled: false
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
}>()

const internalValue = computed({
  get: () => props.modelValue,
  set: (val) => {
    // clamp it for the number input
    let safeVal = val
    if (safeVal < props.min) safeVal = props.min
    if (safeVal > props.max) safeVal = props.max
    emit('update:modelValue', safeVal)
  }
})
</script>

<style scoped>
/* Custom Webkit Slider Thumb */
input[type='range']::-webkit-slider-thumb {
  appearance: none;
  width: 16px;
  height: 16px;
  background: #6366f1; /* indigo-500 */
  border-radius: 50%;
  cursor: pointer;
  box-shadow: 0 0 0 2px #09090b; /* zinc-950 stroke */
  transition: transform 0.1s;
}

input[type='range']::-webkit-slider-thumb:hover {
  transform: scale(1.15);
}

input[type='range']::-moz-range-thumb {
  width: 16px;
  height: 16px;
  background: #6366f1;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  box-shadow: 0 0 0 2px #09090b;
  transition: transform 0.1s;
}

input[type='range']::-moz-range-thumb:hover {
  transform: scale(1.15);
}
</style>
