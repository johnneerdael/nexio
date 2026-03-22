<template>
  <div class="space-y-6 pb-12">
      <PortalManagementHero
        eyebrow="Personalization"
        title="Stream Formatter"
        description="Configure the same AIO-style uniform formatting pipeline used by Nexio TV. Pick a built-in formatter, customize your own templates, and validate the output with a live preview before it reaches your devices."
      />

      <div class="space-y-6">
        <section class="rounded-3xl border border-outline-variant/10 bg-surface-container p-6 md:p-8">
          <div class="mb-5">
            <div class="mb-2 inline-flex rounded-lg border border-outline-variant/20 bg-surface-container-low px-4 py-1.5 text-xs font-bold uppercase tracking-[0.18em] text-zinc-300">
              Formatter Selection
            </div>
            <p class="text-sm text-on-surface-variant">Choose how your stream cards should be formatted everywhere uniform formatting is enabled.</p>
          </div>

          <div class="space-y-3">
            <label class="block text-sm font-medium text-zinc-200">Formatter</label>
            <select
              :value="settings.formatter.selectedTemplateId"
              class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40"
              @change="onTemplateChange"
            >
              <option v-for="template in validTemplates" :key="template.id" :value="template.id">
                {{ template.label }}
              </option>
            </select>
            <p class="text-sm text-zinc-400">{{ selectedTemplateSummary }}</p>
          </div>
        </section>

        <section v-if="isCustomSelected" class="rounded-3xl border border-primary/20 bg-surface-container p-6 md:p-8">
          <div class="mb-5 flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
            <div class="space-y-2">
              <div class="inline-flex rounded-lg border border-primary/20 bg-primary/10 px-4 py-1.5 text-xs font-bold uppercase tracking-[0.18em] text-primary">
                Custom Formatter
              </div>
              <p class="max-w-4xl text-sm leading-relaxed text-on-surface-variant">
                Define your own formatter using AIO-compatible template syntax. Type <code class="rounded bg-black/30 px-1 py-0.5 font-mono text-violet-200">{debug.jsonf}</code> to inspect the available variables. See the
                <a class="ml-1 text-primary hover:underline" href="https://docs.aiostreams.viren070.me/reference/custom-formatter" target="_blank" rel="noopener noreferrer">docs</a>
                and the
                <a class="ml-1 text-primary hover:underline" href="https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/formatters/predefined.ts" target="_blank" rel="noopener noreferrer">predefined formatter definitions</a>.
              </p>
            </div>

            <div class="flex flex-wrap gap-2">
              <button class="rounded-xl border border-outline-variant/20 bg-surface-container-low px-4 py-2 text-sm font-semibold text-white transition hover:border-outline-variant/40" @click="snippetsOpen = true">
                Snippets
              </button>
              <button class="rounded-xl border border-outline-variant/20 bg-surface-container-low px-4 py-2 text-sm font-semibold text-white transition hover:border-outline-variant/40" @click="triggerImport">
                Import
              </button>
              <button class="rounded-xl border border-outline-variant/20 bg-surface-container-low px-4 py-2 text-sm font-semibold text-white transition hover:border-outline-variant/40" @click="exportCustom">
                Export
              </button>
              <button class="rounded-xl border border-outline-variant/20 bg-surface-container-low px-4 py-2 text-sm font-semibold text-zinc-300 transition hover:border-outline-variant/40" @click="resetCustom">
                Reset
              </button>
              <button
                class="rounded-xl bg-primary px-4 py-2 text-sm font-bold text-on-primary shadow-lg shadow-primary/20 transition hover:scale-[1.02] disabled:cursor-not-allowed disabled:opacity-50"
                :disabled="!hasDraftChanges"
                @click="saveCustom"
              >
                Apply Changes
              </button>
            </div>
          </div>

          <div class="space-y-4">
            <div>
              <label class="mb-2 block text-sm font-medium text-zinc-200">Name Template</label>
              <textarea
                v-model="customDraft.nameTemplate"
                rows="4"
                class="min-h-[130px] w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 font-mono text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40"
                placeholder="Enter a template for the stream name"
              />
            </div>

            <div>
              <label class="mb-2 block text-sm font-medium text-zinc-200">Description Template</label>
              <textarea
                v-model="customDraft.descriptionTemplate"
                rows="8"
                class="min-h-[220px] w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 font-mono text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40"
                placeholder="Enter a template for the stream description"
              />
            </div>
          </div>

          <input ref="importInput" type="file" accept="application/json" class="hidden" @change="handleImport" />
        </section>

        <section class="rounded-3xl border border-outline-variant/10 bg-surface-container p-6 md:p-8">
          <div class="mb-5">
            <div class="mb-2 inline-flex rounded-lg border border-outline-variant/20 bg-surface-container-low px-4 py-1.5 text-xs font-bold uppercase tracking-[0.18em] text-zinc-300">
              Preview
            </div>
            <p class="text-sm text-on-surface-variant">Mirror the AIO formatter workflow: tune realistic stream variables, inspect the live result, and validate how the current template resolves before syncing.</p>
          </div>

          <div class="space-y-6">
            <div class="flex flex-wrap gap-2">
              <button
                v-for="preset in previewPresets"
                :key="preset.id"
                class="rounded-xl border px-4 py-2 text-sm font-semibold transition"
                :class="activePresetId === preset.id ? 'border-secondary/50 bg-secondary/10 text-secondary' : 'border-outline-variant/20 bg-surface-container-low text-zinc-300 hover:border-outline-variant/40'"
                @click="applyPreset(preset.id)"
              >
                {{ preset.label }}
              </button>
            </div>

            <div class="overflow-hidden rounded-3xl border border-white/10 bg-[#09090b]">
              <div class="relative min-h-[310px] w-full overflow-hidden">
                <img
                  v-if="activePreset?.thumbnail"
                  :src="activePreset.thumbnail"
                  class="absolute inset-0 h-full w-full object-cover opacity-35"
                  alt="Preview artwork"
                />
                <div class="absolute inset-0 bg-gradient-to-tr from-black via-black/55 to-black/25" />
                <div class="relative z-10 flex min-h-[310px] items-end p-6 md:p-8">
                  <div class="w-full max-w-3xl rounded-2xl border border-white/10 bg-black/55 p-5 shadow-2xl shadow-black/40 backdrop-blur-md">
                    <div v-if="formattedOutput" class="space-y-2">
                      <h3 class="text-xl font-bold leading-tight text-white md:text-2xl">{{ formattedOutput.title }}</h3>
                      <p class="whitespace-pre-line text-sm leading-relaxed text-zinc-200 md:text-base">{{ formattedOutput.description }}</p>
                    </div>
                    <div v-else class="space-y-2">
                      <h3 class="text-lg font-bold text-white">Preview unavailable</h3>
                      <p class="text-sm text-zinc-400">{{ previewError || 'The current formatter did not produce any visible output.' }}</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Filename</span>
                <input v-model="previewState.filename" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Folder Name</span>
                <input v-model="previewState.folderName" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
            </div>

            <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-6">
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Indexer</span>
                <input v-model="previewState.indexer" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Seeders</span>
                <input v-model.number="previewState.seeders" type="number" min="0" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Age</span>
                <input v-model="previewState.age" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" placeholder="10d" />
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Duration (s)</span>
                <input v-model.number="previewState.durationSeconds" type="number" min="0" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">File Size (bytes)</span>
                <input v-model.number="previewState.fileSize" type="number" min="0" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Folder Size (bytes)</span>
                <input v-model.number="previewState.folderSize" type="number" min="0" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
            </div>

            <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Service</span>
                <select v-model="previewState.providerId" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40">
                  <option value="none">None</option>
                  <option v-for="service in serviceOptions" :key="service.value" :value="service.value">{{ service.label }}</option>
                </select>
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Addon Name</span>
                <input v-model="previewState.addonName" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Stream Type</span>
                <select v-model="previewState.type" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40">
                  <option v-for="streamType in streamTypeOptions" :key="streamType.value" :value="streamType.value">{{ streamType.label }}</option>
                </select>
              </label>
              <label class="space-y-2">
                <span class="block text-sm font-medium text-zinc-200">Regex Matched</span>
                <input v-model="previewState.regexMatched" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
              </label>
            </div>

            <label class="space-y-2">
              <span class="block text-sm font-medium text-zinc-200">Message</span>
              <input v-model="previewState.message" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
            </label>

            <div class="flex justify-center">
              <button class="rounded-xl bg-white px-5 py-2.5 text-sm font-bold text-zinc-950 transition hover:scale-[1.02]" @click="advancedOpen = true">
                Advanced Variables
              </button>
            </div>

            <div class="flex flex-wrap justify-center gap-5 rounded-2xl border border-outline-variant/10 bg-surface-container-low px-4 py-4">
              <label class="flex items-center gap-3 text-sm text-zinc-200">
                <input v-model="previewState.cached" type="checkbox" class="h-4 w-4 rounded border-outline-variant/40 bg-surface-container-low text-primary focus:ring-primary/30" />
                Cached
              </label>
              <label class="flex items-center gap-3 text-sm text-zinc-200">
                <input v-model="previewState.library" type="checkbox" class="h-4 w-4 rounded border-outline-variant/40 bg-surface-container-low text-primary focus:ring-primary/30" />
                Library
              </label>
              <label class="flex items-center gap-3 text-sm text-zinc-200">
                <input v-model="previewState.privateTorrent" type="checkbox" class="h-4 w-4 rounded border-outline-variant/40 bg-surface-container-low text-primary focus:ring-primary/30" />
                Private
              </label>
              <label class="flex items-center gap-3 text-sm text-zinc-200">
                <input v-model="previewState.proxied" type="checkbox" class="h-4 w-4 rounded border-outline-variant/40 bg-surface-container-low text-primary focus:ring-primary/30" />
                Proxied
              </label>
            </div>
          </div>
        </section>
      </div>
      <Teleport to="body">
      <div v-if="snippetsOpen" class="fixed inset-0 z-[80] flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm" @click.self="snippetsOpen = false">
        <div class="max-h-[80vh] w-full max-w-4xl overflow-hidden rounded-3xl border border-outline-variant/20 bg-[#151517] shadow-2xl">
          <div class="flex items-center justify-between border-b border-outline-variant/10 px-6 py-4">
            <h3 class="text-lg font-bold text-white">Formatter Snippets</h3>
            <button class="rounded-full p-2 text-zinc-400 transition hover:bg-white/5 hover:text-white" @click="snippetsOpen = false">
              <span class="material-symbols-outlined text-[18px]">close</span>
            </button>
          </div>
          <div class="space-y-4 overflow-y-auto p-6">
            <div v-for="snippet in snippets" :key="snippet.name" class="rounded-2xl border border-outline-variant/10 bg-surface-container p-4">
              <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                <div class="space-y-2">
                  <h4 class="text-base font-semibold text-white">{{ snippet.name }}</h4>
                  <p class="text-sm text-zinc-400">{{ snippet.description }}</p>
                  <code class="block overflow-x-auto rounded-xl bg-black/35 px-3 py-2 font-mono text-xs text-violet-200">{{ snippet.value }}</code>
                </div>
                <button class="rounded-xl border border-outline-variant/20 bg-surface-container-low px-4 py-2 text-sm font-semibold text-white transition hover:border-outline-variant/40" @click="copySnippet(snippet.value)">
                  Copy
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="advancedOpen" class="fixed inset-0 z-[80] flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm" @click.self="advancedOpen = false">
        <div class="max-h-[85vh] w-full max-w-5xl overflow-hidden rounded-3xl border border-outline-variant/20 bg-[#151517] shadow-2xl">
          <div class="flex items-center justify-between border-b border-outline-variant/10 px-6 py-4">
            <h3 class="text-lg font-bold text-white">Advanced Formatter Variables</h3>
            <button class="rounded-full p-2 text-zinc-400 transition hover:bg-white/5 hover:text-white" @click="advancedOpen = false">
              <span class="material-symbols-outlined text-[18px]">close</span>
            </button>
          </div>
          <div class="space-y-6 overflow-y-auto p-6">
            <section class="space-y-4">
              <h4 class="text-sm font-bold uppercase tracking-[0.18em] text-zinc-400">Score Variables</h4>
              <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
                <label class="space-y-2">
                  <span class="block text-sm font-medium text-zinc-200">Regex Score</span>
                  <input v-model.number="previewState.regexScore" type="number" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
                </label>
                <label class="space-y-2">
                  <span class="block text-sm font-medium text-zinc-200">Highest Regex Score</span>
                  <input v-model.number="previewState.maxRegexScore" type="number" min="1" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
                </label>
                <label class="space-y-2">
                  <span class="block text-sm font-medium text-zinc-200">SE Score</span>
                  <input v-model.number="previewState.streamExpressionScore" type="number" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
                </label>
                <label class="space-y-2">
                  <span class="block text-sm font-medium text-zinc-200">Highest SE Score</span>
                  <input v-model.number="previewState.maxSeScore" type="number" min="1" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
                </label>
              </div>
            </section>

            <section class="space-y-4">
              <h4 class="text-sm font-bold uppercase tracking-[0.18em] text-zinc-400">Matched Variables</h4>
              <div class="grid grid-cols-1 gap-4">
                <label class="space-y-2">
                  <span class="block text-sm font-medium text-zinc-200">SE Matched</span>
                  <input v-model="previewState.seMatched" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
                </label>
                <label class="space-y-2">
                  <span class="block text-sm font-medium text-zinc-200">Ranked SE Matched (comma-separated)</span>
                  <input v-model="previewState.rankedStreamExpressionsMatched" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
                </label>
                <label class="space-y-2">
                  <span class="block text-sm font-medium text-zinc-200">Ranked Regex Matched (comma-separated)</span>
                  <input v-model="previewState.rankedRegexMatched" class="w-full rounded-2xl border border-outline-variant/20 bg-surface-container-low px-4 py-3 text-sm text-white outline-none transition focus:border-primary/50 focus:ring-1 focus:ring-primary/40" />
                </label>
              </div>
            </section>

            <section class="space-y-4">
              <h4 class="text-sm font-bold uppercase tracking-[0.18em] text-zinc-400">SeaDex Variables</h4>
              <div class="flex flex-wrap justify-center gap-5 rounded-2xl border border-outline-variant/10 bg-surface-container-low px-4 py-4">
                <label class="flex items-center gap-3 text-sm text-zinc-200">
                  <input v-model="previewState.seadex" type="checkbox" class="h-4 w-4 rounded border-outline-variant/40 bg-surface-container-low text-primary focus:ring-primary/30" />
                  SeaDex
                </label>
                <label class="flex items-center gap-3 text-sm text-zinc-200">
                  <input v-model="previewState.seadexBest" :disabled="!previewState.seadex" type="checkbox" class="h-4 w-4 rounded border-outline-variant/40 bg-surface-container-low text-primary focus:ring-primary/30 disabled:opacity-40" />
                  SeaDex Best
                </label>
              </div>
            </section>

            <div class="flex justify-end">
              <button class="rounded-xl bg-primary px-5 py-2.5 text-sm font-bold text-on-primary transition hover:scale-[1.02]" @click="advancedOpen = false">
                Done
              </button>
            </div>
          </div>
        </div>
      </div>
      </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, watchEffect } from 'vue'
import * as constants from '~/formatter/constants'
import { CustomFormatter } from '~/formatter/custom'
import { SNIPPETS } from '~/formatter/constants'
import type { PortalSettings } from '~/types/portal'
import { builtInTemplates } from '~/utils/formatter-templates'
import {
  DEFAULT_FORMATTER_PREVIEW_STATE,
  FORMATTER_PREVIEW_SERVICE_OPTIONS,
  FORMATTER_PREVIEW_PRESETS,
  buildFormatterPreviewStream,
  type FormatterPreviewState,
} from '~/utils/formatter-preview'

const props = defineProps<{
  settings: PortalSettings
  busy: boolean
}>()

const emit = defineEmits<{
  update: [path: string, value: unknown]
  persist: []
}>()

const previewPresets = FORMATTER_PREVIEW_PRESETS
const snippets = SNIPPETS
const importInput = ref<HTMLInputElement | null>(null)

const validTemplates = [
  ...builtInTemplates,
  {
    id: 'custom',
    label: 'Custom',
    source: 'custom' as const,
    nameTemplate: '',
    descriptionTemplate: '',
  },
]

const customDraft = ref({
  nameTemplate: '',
  descriptionTemplate: '',
})

const activePresetId = ref(previewPresets[0]?.id || 'movie_remux')
const previewState = ref<FormatterPreviewState>({ ...DEFAULT_FORMATTER_PREVIEW_STATE })
const formattedOutput = ref<{ title: string; description: string } | null>(null)
const previewError = ref<string | null>(null)
const snippetsOpen = ref(false)
const advancedOpen = ref(false)

function selectTemplate(id: string) {
  emit('update', 'formatter.selectedTemplateId', id)
}

function onTemplateChange(event: Event) {
  const target = event.target as HTMLSelectElement | null
  if (!target) return
  selectTemplate(target.value)
}

function loadCustomDraft() {
  const existing = props.settings.formatter.customTemplate
  customDraft.value = {
    nameTemplate:
      existing?.nameTemplate ||
      builtInTemplates.find((template) => template.id === 'universal')?.nameTemplate ||
      '',
    descriptionTemplate:
      existing?.descriptionTemplate ||
      builtInTemplates.find((template) => template.id === 'universal')?.descriptionTemplate ||
      '',
  }
}

watch(
  () => props.settings.formatter.customTemplate,
  () => loadCustomDraft(),
  { immediate: true, deep: true },
)

const isCustomSelected = computed(() => props.settings.formatter.selectedTemplateId === 'custom')

const selectedTemplate = computed(() =>
  validTemplates.find((template) => template.id === props.settings.formatter.selectedTemplateId) ||
  validTemplates.find((template) => template.id === 'universal') ||
  validTemplates[0],
)

const selectedTemplateSummary = computed(() => {
  if (isCustomSelected.value) {
    return 'Custom formatter with live preview, snippets, import/export, and advanced variable controls.'
  }
  return `Built-in ${selectedTemplate.value?.label || 'formatter'} template.`
})

const currentNameTemplate = computed(() =>
  isCustomSelected.value ? customDraft.value.nameTemplate : (selectedTemplate.value?.nameTemplate || ''),
)

const currentDescriptionTemplate = computed(() =>
  isCustomSelected.value ? customDraft.value.descriptionTemplate : (selectedTemplate.value?.descriptionTemplate || ''),
)

const hasDraftChanges = computed(() => {
  const existing = props.settings.formatter.customTemplate
  if (!existing) {
    return Boolean(customDraft.value.nameTemplate || customDraft.value.descriptionTemplate)
  }
  return (
    customDraft.value.nameTemplate !== existing.nameTemplate ||
    customDraft.value.descriptionTemplate !== existing.descriptionTemplate
  )
})

function saveCustom() {
  emit('update', 'formatter.customTemplate', {
    id: 'custom',
    label: 'Custom',
    nameTemplate: customDraft.value.nameTemplate,
    descriptionTemplate: customDraft.value.descriptionTemplate,
  })
}

function resetCustom() {
  loadCustomDraft()
}

function triggerImport() {
  importInput.value?.click()
}

async function handleImport(event: Event) {
  const target = event.target as HTMLInputElement | null
  const file = target?.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const parsed = JSON.parse(text)
    if (typeof parsed.name !== 'string' || typeof parsed.description !== 'string') {
      throw new Error('Invalid formatter format')
    }
    customDraft.value = {
      nameTemplate: parsed.name,
      descriptionTemplate: parsed.description,
    }
  } catch (error) {
    console.error('Failed to import formatter', error)
  } finally {
    if (target) target.value = ''
  }
}

function exportCustom() {
  const payload = {
    name: customDraft.value.nameTemplate,
    description: customDraft.value.descriptionTemplate,
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], {
    type: 'application/json',
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'custom-formatter.json'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

async function copySnippet(value: string) {
  await navigator.clipboard.writeText(value)
}

const activePreset = computed(() => previewPresets.find((preset) => preset.id === activePresetId.value))

function applyPreset(presetId: string) {
  const preset = previewPresets.find((entry) => entry.id === presetId)
  activePresetId.value = presetId
  previewState.value = {
    ...DEFAULT_FORMATTER_PREVIEW_STATE,
    ...preset?.state,
  }
}

watch(
  () => activePresetId.value,
  (presetId) => {
    if (!previewPresets.some((preset) => preset.id === presetId)) {
      applyPreset(previewPresets[0]?.id || 'movie_remux')
    }
  },
  { immediate: true },
)

const serviceOptions = FORMATTER_PREVIEW_SERVICE_OPTIONS

const streamTypeOptions = (constants.STREAM_TYPES as readonly string[]).map((type) => ({
  label: type.charAt(0).toUpperCase() + type.slice(1),
  value: type,
}))

watchEffect(async () => {
  const nameTemplate = currentNameTemplate.value
  const descriptionTemplate = currentDescriptionTemplate.value

  if (!nameTemplate.trim() && !descriptionTemplate.trim()) {
    formattedOutput.value = null
    previewError.value = null
    return
  }

  try {
    const formatter = new CustomFormatter(nameTemplate, descriptionTemplate, {
      userData: {
        formatter: {
          id: isCustomSelected.value ? 'custom' : (props.settings.formatter.selectedTemplateId || 'universal'),
        },
      },
      maxRegexScore: previewState.value.maxRegexScore,
      maxSeScore: previewState.value.maxSeScore,
    } as any)

    const result = await formatter.format(buildFormatterPreviewStream(previewState.value))
    formattedOutput.value = {
      title: result.name,
      description: result.description,
    }
    previewError.value = null
  } catch (error) {
    console.error('Formatter preview failed', error)
    formattedOutput.value = null
    previewError.value = error instanceof Error ? error.message : 'Preview failed'
  }
})
</script>
