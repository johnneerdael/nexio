<template>
  <PortalShell :signed-in="signedIn" @sign-out="signOut">
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
        <p>Once approved, the TV can exchange the session and immediately pull the latest account settings and addon state from Supabase.</p>
        <div style="display:flex; gap:0.8rem; flex-wrap:wrap;">
          <button class="primary-btn" :disabled="approving" @click="approve">{{ approving ? 'Approving...' : 'Approve This TV' }}</button>
          <NuxtLink class="secondary-btn" to="/account">Open account dashboard</NuxtLink>
        </div>
        <p v-if="message" style="margin:0; color: var(--accent);">{{ message }}</p>
      </section>
    </section>
  </PortalShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from '#imports'
import AuthPanel from '~/components/portal/AuthPanel.vue'
import PortalShell from '~/components/portal/PortalShell.vue'
import { usePortalStore } from '~/composables/usePortalStore'

const route = useRoute()
const code = computed(() => (typeof route.query.code === 'string' ? route.query.code.trim().toUpperCase() : ''))
const nonce = computed(() => (typeof route.query.nonce === 'string' ? route.query.nonce.trim() : ''))
const hasApprovalContext = computed(() => Boolean(code.value && nonce.value))
const message = ref('')
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
  try {
    const result = await approveTvLogin(code.value, nonce.value)
    message.value = result.message
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'Approval failed.'
  } finally {
    approving.value = false
  }
}

onMounted(() => {
  bootstrap()
})
</script>
