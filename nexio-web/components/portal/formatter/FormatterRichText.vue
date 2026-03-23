<template>
  <span class="formatter-rich-text" :class="rootClass">
    <template v-for="(segment, index) in segments" :key="`${index}-${segment.type}`">
      <span v-if="segment.type === 'text'">{{ segment.text }}</span>
      <span
        v-else-if="!failedIconIndexes.has(index)"
        class="formatter-rich-text__icon"
        :class="iconClass(segment)"
        :aria-label="segment.fallbackLabel"
      >
        <img
          :src="segment.src"
          alt=""
          aria-hidden="true"
          class="formatter-rich-text__image"
          :class="imageClass(segment)"
          loading="lazy"
          decoding="async"
          @error="markFailed(index)"
        >
      </span>
      <span v-else>{{ fallbackText(index, segment) }}</span>
    </template>
  </span>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  parseFormatterRichText,
  type FormatterRichTextSegment,
  type FormatterRichTextVariant,
} from './rich-text'

const props = withDefaults(
  defineProps<{
    text: string
    variant?: FormatterRichTextVariant
  }>(),
  {
    variant: 'detail',
  },
)

const segments = computed(() => parseFormatterRichText(props.text, props.variant))
const failedIconIndexes = ref<Set<number>>(new Set())
const rootClass = computed(() =>
  props.variant === 'title'
    ? 'formatter-rich-text--title'
    : 'formatter-rich-text--detail',
)

watch(
  segments,
  () => {
    failedIconIndexes.value = new Set()
  },
  { immediate: true },
)

function markFailed(index: number) {
  if (failedIconIndexes.value.has(index)) return
  failedIconIndexes.value = new Set(failedIconIndexes.value).add(index)
}

function iconClass(segment: FormatterRichTextSegment) {
  if (segment.type !== 'icon') return ''
  return segment.scaleClass === 'title-prominent'
    ? 'formatter-rich-text__icon--title'
    : 'formatter-rich-text__icon--inline'
}

function imageClass(segment: FormatterRichTextSegment) {
  if (segment.type !== 'icon') return ''
  return segment.scaleClass === 'title-prominent'
    ? 'formatter-rich-text__image--title'
    : 'formatter-rich-text__image--inline'
}

function fallbackText(index: number, segment: FormatterRichTextSegment) {
  if (segment.type !== 'icon') return ''
  const next = segments.value[index + 1]
  if (next?.type === 'text' && startsWithLabel(next.text, segment.fallbackLabel)) {
    return ''
  }
  return segment.fallbackLabel
}

function startsWithLabel(text: string, label: string) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`^\\s*${escaped}(?:\\b|\\s|$)`, 'i').test(text)
}
</script>

<style scoped>
.formatter-rich-text {
  display: inline;
}

.formatter-rich-text--detail {
  --formatter-icon-inline-height: 1em;
  --formatter-icon-title-height: 1em;
}

.formatter-rich-text--title {
  --formatter-icon-inline-height: 1.02em;
  --formatter-icon-title-height: 1.12em;
}

.formatter-rich-text__icon {
  display: inline-flex;
  align-items: center;
  vertical-align: -0.15em;
}

.formatter-rich-text__icon--inline {
  height: var(--formatter-icon-inline-height);
}

.formatter-rich-text__icon--title {
  height: var(--formatter-icon-title-height);
}

.formatter-rich-text__image {
  display: block;
  width: auto;
  object-fit: contain;
}

.formatter-rich-text__image--inline {
  height: var(--formatter-icon-inline-height);
}

.formatter-rich-text__image--title {
  height: var(--formatter-icon-title-height);
}
</style>
