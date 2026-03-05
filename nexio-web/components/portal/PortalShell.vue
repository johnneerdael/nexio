<template>
  <div class="portal-page">
    <div class="portal-shell" style="padding: 18px 0 40px; display:grid; gap:1.2rem;">
      <header class="glass" style="padding: 1rem 1.2rem; border-radius: var(--radius-xl); display:flex; justify-content:space-between; align-items:center; gap:1rem; flex-wrap:wrap;">
        <div style="display:flex; align-items:center; gap:1rem; flex-wrap:wrap;">
          <div style="width: 48px; height: 48px; border-radius: 14px; background: linear-gradient(135deg, var(--accent), #9be7ff); display:grid; place-items:center; color:#021019; font-weight:800;">N</div>
          <div>
            <strong style="display:block; font-family: var(--font-display); font-size: 1.1rem;">Nexio Portal</strong>
            <span style="color: var(--text-dim); font-size: 0.92rem;">Account-level settings sync and QR approval</span>
          </div>
        </div>
        <nav style="display:flex; gap:0.55rem; flex-wrap:wrap; align-items:center;">
          <NuxtLink class="secondary-btn" to="/">Home</NuxtLink>
          <NuxtLink class="secondary-btn" to="/account">Account</NuxtLink>
          <NuxtLink class="secondary-btn" to="/approve">Approve TV</NuxtLink>
          <button v-if="signedIn" class="ghost-btn" @click="$emit('sign-out')">Sign out</button>
        </nav>
      </header>

      <slot />
    </div>
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
