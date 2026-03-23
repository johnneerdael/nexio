<template>
  <div class="bg-background text-on-surface min-h-screen selection:bg-primary/30">
    <!-- TopNavBar -->
    <header class="fixed top-0 w-full z-50 bg-zinc-950/60 backdrop-blur-xl bg-gradient-to-b from-zinc-800/20 to-transparent shadow-[0_8px_32px_0_rgba(186,158,255,0.05)] flex justify-between items-center px-4 md:px-8 py-4">
      <div class="flex items-center gap-4">
        <div class="text-2xl font-black tracking-tighter text-transparent bg-clip-text bg-gradient-to-br from-violet-400 to-violet-600 font-headline uppercase">NEXIO</div>
      </div>
      <nav class="hidden md:flex items-center gap-8 font-headline tracking-tight text-white">
        <NuxtLink class="text-zinc-400 hover:text-zinc-100 transition-colors" to="/">Home</NuxtLink>
        <NuxtLink class="text-zinc-400 hover:text-zinc-100 transition-colors" to="/account">Account</NuxtLink>
        <button v-if="signedIn" class="text-zinc-400 hover:text-zinc-100 transition-colors" @click="$emit('sign-out')">Sign-out</button>
      </nav>
      <div class="flex items-center gap-4 text-white">
        <button class="hover:bg-white/5 transition-all duration-300 p-2 rounded-full hidden md:block">
          <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"></path></svg>
        </button>
        <button class="hover:bg-white/5 transition-all duration-300 p-2 rounded-full hidden md:block">
           <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path></svg>
        </button>
      </div>
    </header>

    <main class="pt-20 relative">
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  signedIn: boolean
}>()

defineEmits<{
  'sign-out': []
}>()
</script>
