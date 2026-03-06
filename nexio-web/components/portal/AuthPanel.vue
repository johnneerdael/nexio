<template>
  <section class="glass" style="padding: 1.5rem; border-radius: var(--radius-xl); display: grid; gap: 1rem;">
    <div>
      <p class="badge">Portal Access</p>
      <h2 class="section-title" style="font-size: 2rem; margin: 0.8rem 0 0.4rem;">Sign in to control every screen.</h2>
      <p style="margin: 0; color: var(--text-soft); line-height: 1.6;">
        The redesigned portal signs into Nexio, syncs settings instantly, and approves TV QR sessions without falling back to the legacy page.
      </p>
    </div>

    <div class="grid-2">
      <div class="field-shell">
        <label for="email">Email</label>
        <input id="email" v-model="email" type="email" placeholder="john@example.com" @input="localError = null">
      </div>
      <div class="field-shell">
        <label for="password">Password</label>
        <input id="password" v-model="password" type="password" placeholder="At least 6 characters" @input="localError = null">
      </div>
    </div>

    <div style="display:flex; gap:0.8rem; flex-wrap:wrap;">
      <button class="primary-btn" :disabled="busy" @click="submit('sign-in')">Sign In</button>
      <button class="secondary-btn" :disabled="busy" @click="submit('sign-up')">Create Account</button>
      <button class="secondary-btn" :disabled="busy" @click="emit('google')">Continue with Google</button>
    </div>

    <p v-if="displayError" style="margin: 0; color: #ffd7da;">{{ displayError }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  busy: boolean
  error: string | null
}>()

const emit = defineEmits<{
  'sign-in': [email: string, password: string]
  'sign-up': [email: string, password: string]
  'google': []
}>()

const email = ref('')
const password = ref('')
const localError = ref<string | null>(null)

const displayError = computed(() => localError.value || props.error)

function submit(action: 'sign-in' | 'sign-up') {
  const nextEmail = email.value.trim()
  const nextPassword = password.value

  if (!nextEmail || !nextPassword) {
    localError.value = 'Enter both email and password.'
    return
  }

  localError.value = null
  emit(action, nextEmail, nextPassword)
}
</script>
