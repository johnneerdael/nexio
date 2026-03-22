<template>
  <section
    class="relative overflow-hidden rounded-3xl border border-outline-variant/10 bg-surface-container-lowest/40 p-6 shadow-2xl backdrop-blur-xl md:p-10"
  >
    <div class="absolute -right-32 -top-32 h-96 w-96 rounded-full bg-primary/15 blur-[120px]" />
    <div class="absolute -bottom-32 -left-24 h-80 w-80 rounded-full bg-secondary/10 blur-[100px]" />

    <div class="relative z-10 flex flex-col gap-8 xl:flex-row xl:items-start xl:justify-between">
      <div class="max-w-4xl space-y-3">
        <span
          v-if="eyebrow"
          class="block text-xs font-bold uppercase tracking-[0.22em]"
          :class="eyebrowClass || 'text-secondary'"
        >
          {{ eyebrow }}
        </span>

        <div>
          <h1
            class="bg-gradient-to-br from-violet-100 via-white to-violet-400 bg-clip-text font-headline text-3xl font-black tracking-tight text-transparent md:text-5xl"
          >
            {{ title }}
          </h1>
          <p
            v-if="description"
            class="mt-3 max-w-3xl text-sm leading-relaxed text-on-surface-variant md:text-base"
          >
            {{ description }}
          </p>
        </div>
      </div>

      <div v-if="$slots.right" class="xl:min-w-[320px] xl:max-w-[420px] xl:self-stretch">
        <slot name="right" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  eyebrow?: string
  eyebrowClass?: string
  title: string
  description?: string
}>()
</script>
