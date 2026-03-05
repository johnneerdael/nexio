<template>
  <PortalShell :signed-in="signedIn" @sign-out="signOut">
    <section class="glass" style="padding: 1.8rem; border-radius: var(--radius-xl); display:grid; gap:1.4rem; overflow:hidden;">
      <div class="grid-2" style="align-items:center;">
        <div style="display:grid; gap:1rem;">
          <span class="badge">Nexio Account Portal</span>
          <h1 class="section-title" style="font-size: clamp(2.8rem, 6vw, 5rem); line-height: 0.94; margin:0; max-width: 10ch;">
            One portal for every Nexio screen.
          </h1>
          <p style="margin:0; color: var(--text-soft); max-width: 46rem; font-size: 1.05rem; line-height:1.7;">
            Replace the old TV login page with a high-end account portal that mirrors the addon settings surface, manages synced addons, approves QR logins, and pushes account-wide preferences into Supabase instantly.
          </p>
          <div style="display:flex; gap:0.8rem; flex-wrap:wrap;">
            <NuxtLink class="primary-btn" to="/account">Open account portal</NuxtLink>
            <NuxtLink class="secondary-btn" to="/approve">Approve a TV QR login</NuxtLink>
          </div>
        </div>

        <div class="surface" style="padding:1.2rem; border-radius: var(--radius-xl); display:grid; gap:1rem; background:
          radial-gradient(circle at top right, rgba(123,255,211,0.18), transparent 30%),
          radial-gradient(circle at bottom left, rgba(255,191,105,0.14), transparent 28%),
          var(--panel-strong);">
          <div class="grid-2">
            <article class="field-shell">
              <span style="color: var(--text-dim);">Sync direction</span>
              <strong>Startup pulls from Supabase</strong>
              <p>Every TV starts from the account snapshot instead of fragmented local state.</p>
            </article>
            <article class="field-shell">
              <span style="color: var(--text-dim);">Write behavior</span>
              <strong>Instant account push</strong>
              <p>Settings and addon edits publish immediately to the account profile.</p>
            </article>
          </div>
          <article class="field-shell">
            <span style="color: var(--text-dim);">Excluded from sync</span>
            <strong>VOD cache, parallel connections, Dolby Vision, Fire OS quirks</strong>
            <p>Device-specific playback tuning remains local, everything else becomes account-owned.</p>
          </article>
          <article class="field-shell">
            <span style="color: var(--text-dim);">Not in scope</span>
            <strong>Library, watch progress, watched items</strong>
            <p>Those legacy sync paths are explicitly out of the redesigned portal and Supabase contract.</p>
          </article>
        </div>
      </div>
    </section>

    <section class="grid-3">
      <article class="glass" style="padding:1.4rem; border-radius: var(--radius-xl); display:grid; gap:0.9rem;">
        <span class="badge">Settings</span>
        <h2 class="section-title" style="font-size:1.55rem; margin:0;">Mirror the addon UI</h2>
        <p style="margin:0; color: var(--text-soft); line-height:1.65;">Appearance, layout, integrations, subtitles, playback behavior, Trakt catalogs, and debug controls all live in one account workspace.</p>
      </article>
      <article class="glass" style="padding:1.4rem; border-radius: var(--radius-xl); display:grid; gap:0.9rem;">
        <span class="badge">Addons</span>
        <h2 class="section-title" style="font-size:1.55rem; margin:0;">Own the addon stack</h2>
        <p style="margin:0; color: var(--text-soft); line-height:1.65;">Add, remove, reorder, and disable addons or repositories from the web, then let every linked TV adopt the same stack automatically.</p>
      </article>
      <article class="glass" style="padding:1.4rem; border-radius: var(--radius-xl); display:grid; gap:0.9rem;">
        <span class="badge">Approval</span>
        <h2 class="section-title" style="font-size:1.55rem; margin:0;">Approve TV QR logins</h2>
        <p style="margin:0; color: var(--text-soft); line-height:1.65;">The portal can replace the old `tv-login-web` approval page so the whole identity flow stays on-brand.</p>
      </article>
    </section>
  </PortalShell>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import PortalShell from '~/components/portal/PortalShell.vue'
import { usePortalStore } from '~/composables/usePortalStore'

const { bootstrap, signedIn, signOut } = usePortalStore()

onMounted(() => {
  bootstrap()
})
</script>
