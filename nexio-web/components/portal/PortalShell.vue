<template>
  <div class="bg-background text-on-surface min-h-screen selection:bg-primary/30">
    <!-- TopNavBar -->
    <header class="fixed top-0 w-full z-50 bg-zinc-950/60 backdrop-blur-xl bg-gradient-to-b from-zinc-800/20 to-transparent shadow-[0_8px_32px_0_rgba(186,158,255,0.05)] flex justify-between items-center px-4 md:px-8 py-4">
      <div class="flex items-center gap-4">
        <button class="md:hidden text-white focus:outline-none" @click="isMobileMenuOpen = !isMobileMenuOpen">
          <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"></path></svg>
        </button>
        <div class="text-2xl font-black tracking-tighter text-transparent bg-clip-text bg-gradient-to-br from-violet-400 to-violet-600 font-headline uppercase">NEXIO</div>
      </div>
      <nav class="hidden md:flex items-center gap-8 font-headline tracking-tight text-white">
        <NuxtLink class="text-zinc-400 hover:text-zinc-100 transition-colors" to="/">Home</NuxtLink>
        <NuxtLink class="text-zinc-400 hover:text-zinc-100 transition-colors" to="/account">Account</NuxtLink>
        <button v-if="signedIn" class="text-zinc-400 hover:text-zinc-100 transition-colors" @click="$emit('sign-out')">Sign-out</button>
      </nav>
      <!-- Removed unwired header icons -->
    </header>

    <div class="flex pt-20 h-screen overflow-hidden">
      <!-- SideNavBar -->
      <aside class="fixed md:static left-0 top-0 h-full w-64 pt-20 md:pt-0 bg-zinc-950 flex flex-col border-r border-zinc-800/30 shadow-[4px_0_24px_rgba(0,0,0,0.5)] z-40 transition-transform duration-300" :class="isMobileMenuOpen ? 'translate-x-0' : '-translate-x-full md:translate-x-0'">
        <div class="px-6 py-6 border-b border-zinc-900/50">
          <p class="font-headline text-sm font-medium tracking-wide text-zinc-500 uppercase">Management</p>
          <p class="text-[10px] text-zinc-600 tracking-widest mt-1 uppercase font-bold">Core Systems</p>
        </div>

        <nav class="flex-1 mt-4" v-if="signedIn">
          <button
            @click="$emit('set-view', 'addons'); isMobileMenuOpen = false"
            class="w-full flex items-center gap-3 px-6 py-3 transition-colors font-headline text-sm font-medium tracking-wide group"
            :class="activeView === 'addons' ? 'bg-gradient-to-r from-violet-500/10 to-transparent text-violet-300 border-l-4 border-violet-500' : 'text-zinc-500 hover:text-zinc-300'"
          >
            <svg class="w-5 h-5 flex-shrink-0 transition-transform" :class="activeView !== 'addons' ? 'group-hover:translate-x-1' : ''" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 14v6m-3-3h6M6 10h2a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v2a2 2 0 002 2zm10 0h2a2 2 0 002-2V6a2 2 0 00-2-2h-2a2 2 0 00-2-2v2a2 2 0 002 2zM6 20h2a2 2 0 002-2v-2a2 2 0 00-2-2H6a2 2 0 00-2 2v2a2 2 0 002 2z"></path></svg>
            <span>Addons</span>
          </button>

          <button
            @click="$emit('set-view', 'catalogs'); isMobileMenuOpen = false"
            class="w-full flex items-center gap-3 px-6 py-3 transition-colors font-headline text-sm font-medium tracking-wide group"
            :class="activeView === 'catalogs' ? 'bg-gradient-to-r from-violet-500/10 to-transparent text-violet-300 border-l-4 border-violet-500' : 'text-zinc-500 hover:text-zinc-300'"
          >
            <svg class="w-5 h-5 flex-shrink-0 transition-transform" :class="activeView !== 'catalogs' ? 'group-hover:translate-x-1' : ''" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"></path></svg>
            <span>Catalogs</span>
          </button>

          <button
            @click="$emit('set-view', 'integrations'); isMobileMenuOpen = false"
            class="w-full flex items-center gap-3 px-6 py-3 transition-colors font-headline text-sm font-medium tracking-wide group"
            :class="activeView === 'integrations' ? 'bg-gradient-to-r from-violet-500/10 to-transparent text-violet-300 border-l-4 border-violet-500' : 'text-zinc-500 hover:text-zinc-300'"
          >
             <svg class="w-5 h-5 flex-shrink-0 transition-transform" :class="activeView !== 'integrations' ? 'group-hover:translate-x-1' : ''" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
            <span>Integrations</span>
          </button>

          <button
            @click="$emit('set-view', 'formatter'); isMobileMenuOpen = false"
            class="w-full flex items-center gap-3 px-6 py-3 transition-colors font-headline text-sm font-medium tracking-wide group"
            :class="activeView === 'formatter' ? 'bg-gradient-to-r from-violet-500/10 to-transparent text-violet-300 border-l-4 border-violet-500' : 'text-zinc-500 hover:text-zinc-300'"
          >
             <svg class="w-5 h-5 flex-shrink-0 transition-transform" :class="activeView !== 'formatter' ? 'group-hover:translate-x-1' : ''" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"></path></svg>
            <span>Formatter</span>
          </button>
        </nav>

        <!-- Removed unwired sidebar links -->
      </aside>

      <!-- Overlay for mobile -->
      <div v-if="isMobileMenuOpen" class="fixed inset-0 bg-black/50 z-30 md:hidden" @click="isMobileMenuOpen = false"></div>

      <!-- Main Content Area -->
      <main class="flex-1 overflow-y-auto bg-surface relative h-full">
        <!-- Luminescent Scrim Gradient -->
        <div class="absolute top-0 right-0 w-[500px] h-[500px] bg-secondary/5 rounded-full blur-[120px] pointer-events-none hidden md:block"></div>

        <div class="max-w-[1400px] mx-auto px-4 md:px-8 py-4 md:py-6 relative z-10 w-full h-full">
          <slot />
        </div>
      </main>
    </div>

    <!-- Mobile Bottom Nav -->
    <div class="md:hidden fixed bottom-0 left-0 w-full bg-zinc-950/80 backdrop-blur-xl border-t border-outline-variant/10 flex justify-around items-center py-3 z-50">
      <NuxtLink to="/" class="flex flex-col items-center gap-1 text-zinc-500">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"></path></svg>
        <span class="text-[10px] font-bold uppercase">Home</span>
      </NuxtLink>
      <button @click="$emit('set-view', 'addons')" class="flex flex-col items-center gap-1" :class="activeView === 'addons' || !activeView ? 'text-primary' : 'text-zinc-500'">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 14v6m-3-3h6M6 10h2a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v2a2 2 0 002 2zm10 0h2a2 2 0 002-2V6a2 2 0 00-2-2h-2a2 2 0 00-2-2v2a2 2 0 002 2zM6 20h2a2 2 0 002-2v-2a2 2 0 00-2-2H6a2 2 0 00-2 2v2a2 2 0 002 2z"></path></svg>
        <span class="text-[10px] font-bold uppercase">Addons</span>
      </button>
      <button @click="$emit('set-view', 'catalogs')" class="flex flex-col items-center gap-1" :class="activeView === 'catalogs' ? 'text-primary' : 'text-zinc-500'">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"></path></svg>
        <span class="text-[10px] font-bold uppercase">Catalogs</span>
      </button>
      <button @click="$emit('set-view', 'integrations')" class="flex flex-col items-center gap-1" :class="activeView === 'integrations' ? 'text-primary' : 'text-zinc-500'">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
        <span class="text-[10px] font-bold uppercase">Integrations</span>
      </button>
      <button @click="$emit('set-view', 'formatter')" class="flex flex-col items-center gap-1" :class="activeView === 'formatter' ? 'text-primary' : 'text-zinc-500'">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"></path></svg>
        <span class="text-[10px] font-bold uppercase">Formatter</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  signedIn: boolean
  activeView?: string
}>()

defineEmits<{
  'sign-out': []
  'set-view': [view: string]
}>()

const isMobileMenuOpen = ref(false)
</script>
