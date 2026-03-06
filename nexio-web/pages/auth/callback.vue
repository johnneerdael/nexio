<template>
  <PortalShell :signed-in="signedIn" @sign-out="signOut">
    <section class="glass" style="padding:1.6rem; border-radius: var(--radius-xl); display:grid; gap:1rem;">
      <div>
        <p class="badge">Portal Access</p>
        <h1 class="section-title" style="font-size:2.15rem; margin:0.8rem 0 0.4rem;">Completing sign in.</h1>
        <p style="margin:0; color: var(--text-soft); line-height:1.65;">
          Finalizing your Nexio session and loading the account portal.
        </p>
      </div>

      <p v-if="status" style="margin:0; color: var(--text-soft);">{{ status }}</p>
      <p v-if="error" style="margin:0; color: #ffd7da;">{{ error }}</p>

      <div style="display:flex; gap:0.8rem; flex-wrap:wrap;">
        <NuxtLink class="secondary-btn" to="/account">Back to account</NuxtLink>
      </div>
    </section>
  </PortalShell>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from '#imports'
import PortalShell from '~/components/portal/PortalShell.vue'
import { usePortalStore } from '~/composables/usePortalStore'
import type { PortalSession } from '~/types/portal'

const route = useRoute()
const router = useRouter()
const error = ref('')
const status = ref('Waiting for Nexio redirect payload...')
const { signedIn, signOut, completeOAuthSession } = usePortalStore()

function readHashPayload() {
  if (!process.client) {
    return new URLSearchParams()
  }

  const hash = window.location.hash.startsWith('#')
    ? window.location.hash.slice(1)
    : window.location.hash

  return new URLSearchParams(hash)
}

function safeNextPath(value: unknown) {
  if (typeof value !== 'string') {
    return '/account'
  }

  return value.startsWith('/') ? value : '/account'
}

onMounted(async () => {
  const hash = readHashPayload()
  const hashError = hash.get('error_description') || hash.get('error') || ''
  const queryError = typeof route.query.error_description === 'string'
    ? route.query.error_description
    : typeof route.query.error === 'string'
      ? route.query.error
      : ''

  if (hashError || queryError) {
    status.value = ''
    error.value = hashError || queryError
    return
  }

  const accessToken = hash.get('access_token') || ''
  const refreshToken = hash.get('refresh_token') || ''
  const expiresIn = Number(hash.get('expires_in') || '3600')

  if (!accessToken || !refreshToken) {
    status.value = ''
    error.value = 'Google sign-in did not return a usable session.'
    return
  }

  status.value = 'Loading your account...'

  try {
    const session = await $fetch<PortalSession>('/api/auth/oauth-complete', {
      method: 'POST',
      body: {
        accessToken,
        refreshToken,
        expiresIn
      }
    })

    await completeOAuthSession(session)
    status.value = 'Redirecting...'
    await router.replace(safeNextPath(route.query.next))
  } catch (cause) {
    status.value = ''
    error.value = cause instanceof Error ? cause.message : 'Unable to complete Google sign in.'
  }
})
</script>
