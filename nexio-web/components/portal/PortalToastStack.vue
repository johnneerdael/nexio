<template>
  <Teleport to="body">
    <div class="fixed top-24 right-4 z-[80] flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-3 pointer-events-none">
      <TransitionGroup name="portal-toast">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          class="pointer-events-auto overflow-hidden rounded-2xl border backdrop-blur-xl shadow-[0_18px_50px_rgba(0,0,0,0.35)]"
          :class="toast.tone === 'success'
            ? 'border-emerald-400/30 bg-zinc-950/92 shadow-[0_18px_50px_rgba(16,185,129,0.18)]'
            : 'border-rose-400/30 bg-zinc-950/92 shadow-[0_18px_50px_rgba(244,63,94,0.2)]'"
        >
          <div class="flex items-start gap-3 px-4 py-3.5">
            <div
              class="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full border"
              :class="toast.tone === 'success'
                ? 'border-emerald-400/25 bg-emerald-500/12 text-emerald-300'
                : 'border-rose-400/25 bg-rose-500/12 text-rose-300'"
            >
              <svg v-if="toast.tone === 'success'" class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.25" d="M5 13l4 4L19 7" />
              </svg>
              <svg v-else class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.25" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>

            <div class="min-w-0 flex-1">
              <p class="text-[11px] font-black uppercase tracking-[0.22em] text-zinc-400">
                {{ toast.tone === 'success' ? 'Provider Status' : 'Validation Error' }}
              </p>
              <p class="mt-1 text-sm font-semibold text-zinc-100">
                {{ toast.title }}
              </p>
              <p class="mt-1 text-xs leading-5 text-zinc-400">
                {{ toast.message }}
              </p>
            </div>

            <button
              class="rounded-full border border-white/10 bg-white/5 p-1.5 text-zinc-400 transition-colors hover:text-zinc-100"
              type="button"
              @click="$emit('dismiss', toast.id)"
            >
              <svg class="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.25" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{
  toasts: Array<{
    id: string
    tone: 'success' | 'error'
    title: string
    message: string
  }>
}>()

defineEmits<{
  dismiss: [toastId: string]
}>()
</script>

<style scoped>
.portal-toast-enter-active,
.portal-toast-leave-active {
  transition: all 0.22s ease;
}

.portal-toast-enter-from,
.portal-toast-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.98);
}
</style>
