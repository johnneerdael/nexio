<template>
  <PortalShell :signed-in="signedIn" @sign-out="signOut">
    <section class="glass" style="padding:1.6rem; border-radius: var(--radius-xl); display:grid; gap:1.2rem;">
      <div>
        <p class="badge">TV Login Approval</p>
        <h1 class="section-title" style="font-size:2.35rem; margin:0.8rem 0 0.4rem;">Approve the TV request from the new portal.</h1>
        <p style="margin:0; color: var(--text-soft); max-width: 54rem; line-height:1.65;">
          This replaces the bare `tv-login-web` approval flow with the same account session and visual system used by the rest of the portal.
        </p>
      </div>

      <div class="grid-2">
        <article class="field-shell">
          <label>Login code</label>
          <strong>{{ code || 'Missing' }}</strong>
          <p>Provided by the TV QR session.</p>
        </article>
        <article class="field-shell">
          <label>Device nonce</label>
          <strong style="word-break: break-all;">{{ nonce || 'Missing' }}</strong>
          <p>Used to bind the approval to the exact device request.</p>
        </article>
      </div>

      <AuthPanel v-if="!signedIn" :busy="state.loading" :error="state.error" @sign-in="handleSignIn" @sign-up="handleSignUp" />

      <section v-else class="field-shell">
        <label>Approve TV login</label>
        <p>Once approved, the TV can exchange the session and immediately pull the latest account settings and addon state from Supabase.</p>
        <div style="display:flex; gap:0.8rem; flex-wrap:wrap;">
          <button class="primary-btn" :disabled="!code || !nonce || approving" @click="approve">{{ approving ? 'Approving...' : 'Approve This TV' }}</button>
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
const message = ref('')
const approving = ref(false)

const { state, bootstrap, signIn, signUp, signOut, signedIn, approveTvLogin } = usePortalStore()

async function handleSignIn(email: string, password: string) {
  await signIn(email, password)
}

async function handleSignUp(email: string, password: string) {
  await signUp(email, password)
}

async function approve() {
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
