<template>
  <PublicShell :signed-in="signedIn" @sign-out="signOut">
    <section class="glass" style="padding:1.6rem; border-radius: var(--radius-xl); display:grid; gap:1.2rem;">
      <div>
        <p class="badge">TV Login Approval</p>
        <h1 class="section-title" style="font-size:2.35rem; margin:0.8rem 0 0.4rem;">Approve this TV sign-in request.</h1>
        <p style="margin:0; color: var(--text-soft); max-width: 54rem; line-height:1.65;">
          Review the request after signing in, then confirm it so the TV can finish onboarding against your account.
        </p>
      </div>

      <section v-if="!hasApprovalContext" class="field-shell">
        <label>Approval link unavailable</label>
        <p style="margin:0; color: var(--text-soft); line-height:1.65;">
          This approval link is incomplete or expired. Start the sign-in flow again from the TV and rescan the QR code.
        </p>
        <div style="display:flex; gap:0.8rem; flex-wrap:wrap;">
          <NuxtLink class="secondary-btn" to="/account">Open account portal</NuxtLink>
        </div>
      </section>

      <AuthPanel
        v-else-if="!signedIn"
        :busy="state.loading"
        :error="state.error"
        @sign-in="handleSignIn"
        @sign-up="handleSignUp"
        @google="handleGoogle"
      />

      <section v-else class="field-shell">
        <label>Approve TV login</label>
        <p>Once approved, the TV can exchange the session and immediately pull the latest account settings and addon state from Nexio Live.</p>
        <div style="display:flex; gap:0.8rem; flex-wrap:wrap;">
          <button class="primary-btn" :disabled="approving" @click="approve">{{ approving ? 'Approving...' : 'Approve This TV' }}</button>
          <NuxtLink class="secondary-btn" to="/account">Open account dashboard</NuxtLink>
        </div>
        <div v-if="message" class="glass" :style="isError ? 'padding: 1rem 1.25rem; border-radius: var(--radius-lg); border: 1px solid rgba(255, 123, 130, 0.35); color: #ffb1b5; background: rgba(43, 8, 8, 0.4); backdrop-filter: blur(12px); display: flex; align-items: center; gap: 0.75rem;' : 'padding: 1rem 1.25rem; border-radius: var(--radius-lg); border: 1px solid rgba(123, 255, 211, 0.35); color: #7bffd3; background: rgba(8, 43, 22, 0.4); backdrop-filter: blur(12px); display: flex; align-items: center; gap: 0.75rem;'">
          <svg v-if="isError" class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
          <svg v-else class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
          {{ message }}
        </div>
      </section>
    </section>
  </PublicShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from '#imports'
import AuthPanel from '~/components/portal/AuthPanel.vue'
import PublicShell from '~/components/portal/PublicShell.vue'
import { usePortalStore } from '~/composables/usePortalStore'

const route = useRoute()
const code = computed(() => (typeof route.query.code === 'string' ? route.query.code.trim().toUpperCase() : ''))
const nonce = computed(() => (typeof route.query.nonce === 'string' ? route.query.nonce.trim() : ''))
const hasApprovalContext = computed(() => Boolean(code.value && nonce.value))
const message = ref('')
const isError = ref(false)
const approving = ref(false)

const { state, bootstrap, signIn, signUp, startGoogleSignIn, signOut, signedIn, approveTvLogin } = usePortalStore()

async function handleSignIn(email: string, password: string) {
  await signIn(email, password)
}

async function handleSignUp(email: string, password: string) {
  await signUp(email, password)
}

function handleGoogle() {
  startGoogleSignIn(route.fullPath)
}

async function approve() {
  if (!hasApprovalContext.value) {
    message.value = 'This approval link is incomplete or expired.'
    return
  }

  approving.value = true
  message.value = ''
  isError.value = false
  try {
    const result = await approveTvLogin(code.value, nonce.value)
    message.value = result.message
    isError.value = false
  } catch (error) {
    const raw = error instanceof Error ? error.message.trim() : 'Approval failed.'
    const lower = raw.toLowerCase()
    isError.value = true
    if (lower.includes('jwt expired') || lower.includes('missing sub claim') || lower.includes('invalid claim')) message.value = 'Your session has expired. Please sign in again.'
    else if (lower.includes('invalid login credentials')) message.value = 'Invalid email or password.'
    else if (lower.includes('user not found')) message.value = 'User not found.'
    else message.value = raw
  } finally {
    approving.value = false
  }
}

onMounted(() => {
  bootstrap()
})
</script>
