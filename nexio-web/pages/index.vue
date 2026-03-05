<template>
  <PortalShell :signed-in="signedIn" @sign-out="signOut">
    <div ref="landingPage" class="landing-page" :class="{ 'is-ready': pageReady }">
    <section class="landing-hero glass reveal reveal-hero">
      <div class="landing-copy">
        <span class="badge">Nexio Account Portal</span>
        <p class="landing-kicker">Secure sync for a serious playback platform</p>
        <h1 class="section-title landing-title">One account portal for every Nexio screen.</h1>
        <p class="landing-summary">
          Nexio now ties together a premium playback engine and a full account cloud: complete web to addon settings sync, Vault-backed secret handling for paid services, advanced catalog management, and a single control surface for every linked screen in the house.
        </p>
        <div class="landing-actions">
          <NuxtLink class="primary-btn" to="/account">Open account portal</NuxtLink>
        </div>

        <div class="landing-metrics">
          <article class="metric-card">
            <span>Account model</span>
            <strong>Public sync + Vault secrets</strong>
            <p>Addon credentials, Trakt tokens, MDBList keys, and poster-service keys stay out of the public sync payload.</p>
          </article>
          <article class="metric-card">
            <span>Playback core</span>
            <strong>Custom Media3 and Fire OS tuning</strong>
            <p>Built around a tuned playback stack with Dolby Vision conversion work and Amazon device compatibility in mind.</p>
          </article>
        </div>
      </div>

      <div class="landing-stage reveal reveal-stage">
        <div class="stage-aura stage-aura-a" />
        <div class="stage-aura stage-aura-b" />
        <div class="stage-grid" />

        <article class="device-frame hero-device">
          <div class="device-screen playback-screen">
            <div class="screen-brand">
              <img src="/landing-logo.webp" alt="Nexio emblem">
              <span>Nexio Playback Core</span>
            </div>
            <div class="playback-copy">
              <span class="mini-pill">Realtime video pipeline</span>
              <h2>DV7 to DV8.1 conversion, Fire OS hardening, premium stream throughput.</h2>
              <p>Advanced disk cache strategy, reusable startup bootstrap windows, and native parallel range downloading for large progressive streams.</p>
            </div>
            <div class="playback-stats">
              <div>
                <span>Playback stack</span>
                <strong>Custom Media3 fork</strong>
              </div>
              <div>
                <span>Performance path</span>
                <strong>Cache + parallel fetch</strong>
              </div>
            </div>
          </div>
        </article>

        <article class="floating-card portal-card glass">
          <header>
            <span class="mini-pill">Account portal</span>
            <strong>Full settings parity</strong>
          </header>
          <div class="mock-portal">
            <aside>
              <span class="active">Appearance</span>
              <span>Layout</span>
              <span>Integrations</span>
              <span>Playback</span>
            </aside>
            <div>
              <div class="mock-row">
                <label>Theme</label>
                <span>White</span>
              </div>
              <div class="mock-row">
                <label>Catalog order</label>
                <span>Shared across screens</span>
              </div>
              <div class="mock-row">
                <label>Addon stack</label>
                <span>Instant account push</span>
              </div>
            </div>
          </div>
        </article>

        <article class="floating-card catalog-card surface">
          <header>
            <span class="mini-pill">Catalog system</span>
            <strong>Trakt, MDBList, addons</strong>
          </header>
          <div class="mock-catalogs">
            <div class="catalog-chip accent">Up Next</div>
            <div class="catalog-chip">Trending Movies</div>
            <div class="catalog-chip">MDBList Top 250</div>
            <div class="catalog-chip">Popular Lists</div>
            <div class="catalog-chip">Cinemeta Featured</div>
            <div class="catalog-chip accent-amber">Custom rails</div>
          </div>
        </article>
      </div>
    </section>

    <section class="marquee-strip">
      <span
        v-for="(item, index) in featureRibbon"
        :key="item"
        class="marquee-pill scroll-reveal"
        :style="{ '--reveal-delay': `${420 + (index * 45)}ms` }"
      >
        {{ item }}
      </span>
    </section>

    <section class="story-grid">
      <article class="story-panel story-panel-large glass scroll-reveal scroll-reveal-wide" style="--reveal-delay: 80ms;">
        <div class="story-copy">
          <span class="badge">Cloud Account</span>
          <h2 class="section-title">Complete settings sync between the web and every linked addon instance.</h2>
          <p>
            The portal now mirrors the addon settings surface instead of acting like a basic login utility. Appearance, layout, playback behavior, addon stack, repositories, catalogs, poster providers, and integration state all move through a single account snapshot.
          </p>
        </div>
        <div class="story-visual sync-visual">
          <div class="sync-node">
            <strong>Web portal</strong>
            <span>Manage settings, secrets, addons, catalogs</span>
          </div>
          <div class="sync-line" />
          <div class="sync-node active">
            <strong>Supabase account core</strong>
            <span>Public sync state plus Vault-backed secrets</span>
          </div>
          <div class="sync-line" />
          <div class="sync-cluster">
            <div class="sync-tv">Living room TV</div>
            <div class="sync-tv">Bedroom TV</div>
            <div class="sync-tv">Office TV</div>
          </div>
        </div>
      </article>

      <article class="story-panel surface scroll-reveal scroll-reveal-left" style="--reveal-delay: 120ms;">
        <div class="story-copy">
          <span class="badge">Native Integrations</span>
          <h3 class="section-title">Trakt and MDBList go beyond basic auth.</h3>
          <p>
            Trakt covers scrobble, check-in, continue watching, built-in rails, and custom popular-list catalogs. MDBList adds ratings overlays plus personal-list and top-list catalog management with ordering and visibility controls.
          </p>
        </div>
        <div class="integration-stack">
          <div class="integration-tile">
            <strong>Trakt</strong>
            <span>Scrobble, check-in, Up Next, custom lists</span>
          </div>
          <div class="integration-tile">
            <strong>MDBList</strong>
            <span>IMDb, TMDB, Trakt, Letterboxd, Rotten Tomatoes, audience, Metacritic</span>
          </div>
        </div>
      </article>

      <article class="story-panel glass scroll-reveal scroll-reveal-right" style="--reveal-delay: 160ms;">
        <div class="story-copy">
          <span class="badge">Poster Stack</span>
          <h3 class="section-title">Custom posters without breaking the account model.</h3>
          <p>
            RPDB and TOP Posters remain first-class integrations, with their keys handled through the secret channel instead of leaking into the synced settings document.
          </p>
        </div>
        <div class="poster-wall">
          <div class="poster-slab poster-slab-a">RPDB</div>
          <div class="poster-slab poster-slab-b">TOP Posters</div>
          <div class="poster-slab poster-slab-c">Fallback-safe artwork</div>
        </div>
      </article>

      <article class="story-panel surface scroll-reveal scroll-reveal-left" style="--reveal-delay: 200ms;">
        <div class="story-copy">
          <span class="badge">Localization</span>
          <h3 class="section-title">One account language profile across every screen.</h3>
          <p>
            Language preferences stay aligned across English, German, French, Spanish, Dutch, and Mandarin, so the account experience feels coherent whether users configure from the portal or directly on the TV.
          </p>
        </div>
        <div class="language-cloud">
          <span>English</span>
          <span>Deutsch</span>
          <span>Français</span>
          <span>Español</span>
          <span>Nederlands</span>
          <span>中文</span>
        </div>
      </article>
    </section>

    <section class="closing-banner glass scroll-reveal scroll-reveal-wide" style="--reveal-delay: 120ms;">
      <div>
        <span class="badge">Ready</span>
        <h2 class="section-title">Control onboarding, secrets, catalogs, and synced settings from one place.</h2>
        <p>
          The account portal is now part of the product story, not a side utility. It sells Nexio as a high-end playback platform with serious account sync and integration depth.
        </p>
      </div>
      <div class="landing-actions">
        <NuxtLink class="primary-btn" to="/account">Launch portal</NuxtLink>
      </div>
    </section>
    </div>
  </PortalShell>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import PortalShell from '~/components/portal/PortalShell.vue'
import { usePortalStore } from '~/composables/usePortalStore'

const featureRibbon = [
  'Vault-backed account secrets',
  'Complete web to addon settings sync',
  'Custom Media3 playback core',
  'Realtime Dolby Vision conversion work',
  'Amazon Fire OS optimization',
  'Advanced disk cache and parallel downloading',
  'Native Trakt scrobble and catalogs',
  'Native MDBList ratings and curated rails',
  'RPDB and TOP Posters integrations',
  'English, German, French, Spanish, Dutch, Mandarin'
]

const pageReady = ref(false)
const landingPage = ref<HTMLElement | null>(null)
const { bootstrap, signedIn, signOut } = usePortalStore()
let revealObserver: IntersectionObserver | null = null

const setupScrollReveals = async () => {
  await nextTick()
  revealObserver?.disconnect()

  const root = landingPage.value
  if (!root || typeof window === 'undefined' || !('IntersectionObserver' in window)) {
    root?.querySelectorAll<HTMLElement>('.scroll-reveal').forEach((element) => {
      element.classList.add('is-visible')
    })
    return
  }

  revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) {
          return
        }

        entry.target.classList.add('is-visible')
        revealObserver?.unobserve(entry.target)
      })
    },
    {
      root: null,
      rootMargin: '0px 0px -12% 0px',
      threshold: 0.16
    }
  )

  root.querySelectorAll<HTMLElement>('.scroll-reveal').forEach((element) => {
    revealObserver?.observe(element)
  })
}

onMounted(() => {
  bootstrap()
  requestAnimationFrame(() => {
    pageReady.value = true
    setupScrollReveals()
  })
})

onBeforeUnmount(() => {
  revealObserver?.disconnect()
})
</script>

<style scoped>
.landing-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 1.5rem;
  padding: 2rem;
  border-radius: var(--radius-xl);
  min-height: 42rem;
}

.landing-page {
  display: grid;
  gap: 1rem;
}

.reveal {
  opacity: 0;
  transform: translateY(28px) scale(0.985);
  filter: blur(10px);
  transition:
    opacity 780ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 900ms cubic-bezier(0.22, 1, 0.36, 1),
    filter 900ms cubic-bezier(0.22, 1, 0.36, 1);
  transition-delay: var(--reveal-delay, 0ms);
}

.landing-page.is-ready .reveal {
  opacity: 1;
  transform: translateY(0) scale(1);
  filter: blur(0);
}

.landing-copy {
  display: grid;
  align-content: start;
  gap: 1rem;
  padding: 0.5rem 0;
}

.landing-kicker {
  margin: 0;
  text-transform: uppercase;
  letter-spacing: 0.22em;
  font-size: 0.74rem;
  color: var(--accent);
}

.landing-title {
  margin: 0;
  max-width: 11ch;
  font-size: clamp(3.2rem, 6vw, 5.8rem);
  line-height: 0.92;
}

.landing-summary {
  margin: 0;
  max-width: 48rem;
  color: var(--text-soft);
  font-size: 1.08rem;
  line-height: 1.8;
}

.landing-actions {
  display: flex;
  gap: 0.85rem;
  flex-wrap: wrap;
}

.landing-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
  margin-top: 1rem;
}

.metric-card {
  padding: 1.15rem;
  border-radius: 22px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.06), rgba(255, 255, 255, 0.02));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.metric-card span {
  display: block;
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  color: var(--text-dim);
}

.metric-card strong {
  display: block;
  margin-top: 0.55rem;
  font-size: 1.05rem;
}

.metric-card p {
  margin: 0.55rem 0 0;
  color: var(--text-soft);
  line-height: 1.55;
}

.landing-stage {
  position: relative;
  min-height: 38rem;
  border-radius: 30px;
  overflow: hidden;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.01)),
    linear-gradient(135deg, rgba(7, 16, 28, 0.98), rgba(8, 21, 34, 0.9));
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.stage-grid,
.stage-aura {
  position: absolute;
  pointer-events: none;
}

.stage-grid {
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.9), transparent 92%);
  opacity: 0.25;
}

.stage-aura {
  border-radius: 999px;
  filter: blur(50px);
}

.stage-aura-a {
  top: 6%;
  right: 8%;
  width: 14rem;
  height: 14rem;
  background: rgba(123, 255, 211, 0.18);
}

.stage-aura-b {
  left: 10%;
  bottom: 10%;
  width: 16rem;
  height: 16rem;
  background: rgba(255, 191, 105, 0.16);
}

.device-frame,
.floating-card {
  position: absolute;
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.38);
}

.hero-device {
  top: 3.5rem;
  left: 3.2rem;
  width: 31rem;
  max-width: calc(100% - 8rem);
  padding: 0.8rem;
  border-radius: 30px;
  background: rgba(2, 8, 14, 0.72);
  backdrop-filter: blur(14px);
  transform: rotate(-4deg);
}

.device-screen {
  border-radius: 24px;
  min-height: 23rem;
  overflow: hidden;
  position: relative;
}

.playback-screen {
  display: grid;
  align-content: space-between;
  padding: 1.3rem;
  background:
    radial-gradient(circle at top left, rgba(255, 209, 102, 0.22), transparent 26%),
    radial-gradient(circle at top right, rgba(123, 255, 211, 0.22), transparent 24%),
    linear-gradient(160deg, rgba(9, 20, 35, 0.92), rgba(5, 10, 17, 0.98)),
    url('/landing-logo.webp');
  background-size: auto, auto, cover, 42%;
  background-repeat: no-repeat, no-repeat, no-repeat, no-repeat;
  background-position: top left, top right, center, 112% 116%;
}

.screen-brand {
  display: inline-flex;
  align-items: center;
  gap: 0.7rem;
  padding: 0.45rem 0.75rem;
  width: fit-content;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(14px);
}

.screen-brand img {
  width: 1.4rem;
  height: 1.4rem;
  object-fit: contain;
}

.screen-brand span {
  font-size: 0.78rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.8);
}

.playback-copy {
  max-width: 24rem;
  display: grid;
  gap: 0.7rem;
}

.playback-copy h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 2rem;
  line-height: 1.02;
  letter-spacing: -0.04em;
}

.playback-copy p {
  margin: 0;
  color: var(--text-soft);
  line-height: 1.65;
}

.playback-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.8rem;
}

.playback-stats div {
  padding: 0.85rem 0.95rem;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.07);
  backdrop-filter: blur(12px);
}

.playback-stats span,
.floating-card header span {
  display: block;
  font-size: 0.72rem;
  color: var(--text-dim);
  text-transform: uppercase;
  letter-spacing: 0.18em;
}

.playback-stats strong {
  display: block;
  margin-top: 0.45rem;
}

.floating-card {
  padding: 1rem;
  border-radius: 24px;
}

.portal-card {
  right: 2.2rem;
  top: 4rem;
  width: 17rem;
  transform: rotate(4deg);
}

.catalog-card {
  right: 3.5rem;
  bottom: 2.4rem;
  width: 18.5rem;
  transform: rotate(-6deg);
}

.landing-page.is-ready .hero-device {
  animation: heroFloat 9s ease-in-out 1.05s infinite;
}

.landing-page.is-ready .portal-card {
  animation: cardFloatA 8.5s ease-in-out 1.2s infinite;
}

.landing-page.is-ready .catalog-card {
  animation: cardFloatB 9.5s ease-in-out 1.45s infinite;
}

.floating-card header {
  display: grid;
  gap: 0.45rem;
  margin-bottom: 0.95rem;
}

.floating-card header strong {
  font-size: 1.05rem;
}

.mini-pill {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  padding: 0.38rem 0.65rem;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 0.72rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.86);
}

.mock-portal {
  display: grid;
  grid-template-columns: 6rem 1fr;
  gap: 0.8rem;
}

.mock-portal aside,
.mock-portal > div {
  display: grid;
  gap: 0.55rem;
}

.mock-portal aside span,
.mock-row {
  padding: 0.7rem;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.05);
}

.mock-portal aside .active {
  background: linear-gradient(135deg, rgba(123, 255, 211, 0.24), rgba(155, 231, 255, 0.16));
  color: white;
}

.mock-row label {
  display: block;
  font-size: 0.72rem;
  color: var(--text-dim);
  text-transform: uppercase;
  letter-spacing: 0.14em;
}

.mock-row span {
  display: block;
  margin-top: 0.4rem;
  color: var(--text-soft);
}

.mock-catalogs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem;
}

.catalog-chip {
  padding: 0.65rem 0.8rem;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-soft);
  font-size: 0.82rem;
}

.catalog-chip.accent {
  background: rgba(123, 255, 211, 0.16);
  color: white;
}

.catalog-chip.accent-amber {
  background: rgba(255, 191, 105, 0.16);
  color: white;
}

.marquee-strip {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin: 1rem 0 1.2rem;
}

.marquee-pill {
  opacity: 0;
  transform: translateY(16px);
  filter: blur(6px);
  transition:
    opacity 620ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 760ms cubic-bezier(0.22, 1, 0.36, 1),
    filter 760ms cubic-bezier(0.22, 1, 0.36, 1);
  transition-delay: var(--reveal-delay, 0ms);
  padding: 0.82rem 1rem;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-soft);
  backdrop-filter: blur(12px);
}

.scroll-reveal.is-visible.marquee-pill {
  opacity: 1;
  transform: translateY(0);
  filter: blur(0);
}

.scroll-reveal {
  opacity: 0;
  transform: translate3d(0, 34px, 0) scale(0.985);
  filter: blur(12px);
  transition:
    opacity 860ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 1050ms cubic-bezier(0.22, 1, 0.36, 1),
    filter 1050ms cubic-bezier(0.22, 1, 0.36, 1);
  transition-delay: var(--reveal-delay, 0ms);
  will-change: opacity, transform, filter;
}

.scroll-reveal-left {
  transform: translate3d(-26px, 34px, 0) scale(0.985);
}

.scroll-reveal-right {
  transform: translate3d(26px, 34px, 0) scale(0.985);
}

.scroll-reveal-wide {
  transform: translate3d(0, 42px, 0) scale(0.978);
}

.scroll-reveal.is-visible {
  opacity: 1;
  transform: translate3d(0, 0, 0) scale(1);
  filter: blur(0);
}

.story-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

.story-panel {
  display: grid;
  gap: 1.2rem;
  padding: 1.5rem;
  border-radius: var(--radius-xl);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.story-panel > *,
.closing-banner > * {
  opacity: 0;
  transform: translateY(18px);
  filter: blur(8px);
  transition:
    opacity 720ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 860ms cubic-bezier(0.22, 1, 0.36, 1),
    filter 860ms cubic-bezier(0.22, 1, 0.36, 1);
}

.story-panel > *:nth-child(1),
.closing-banner > *:nth-child(1) {
  transition-delay: 110ms;
}

.story-panel > *:nth-child(2),
.closing-banner > *:nth-child(2) {
  transition-delay: 220ms;
}

.scroll-reveal.is-visible.story-panel > *,
.scroll-reveal.is-visible.closing-banner > * {
  opacity: 1;
  transform: translateY(0);
  filter: blur(0);
}

.story-panel-large {
  grid-column: span 2;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  align-items: center;
}

.story-copy h2,
.story-copy h3 {
  margin: 0.6rem 0 0.5rem;
  font-size: 2rem;
  line-height: 1.02;
}

.story-copy h3 {
  font-size: 1.5rem;
}

.story-copy p {
  margin: 0;
  color: var(--text-soft);
  line-height: 1.72;
}

.story-visual {
  min-height: 18rem;
}

.sync-visual {
  display: grid;
  align-items: center;
  gap: 0.9rem;
}

.sync-node,
.sync-tv,
.integration-tile,
.poster-slab {
  padding: 1rem;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.sync-node,
.sync-line,
.sync-tv,
.integration-tile,
.poster-slab,
.language-cloud span {
  opacity: 0;
  transform: translateY(14px);
  transition:
    opacity 640ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 760ms cubic-bezier(0.22, 1, 0.36, 1);
}

.scroll-reveal.is-visible .sync-node,
.scroll-reveal.is-visible .sync-line,
.scroll-reveal.is-visible .sync-tv,
.scroll-reveal.is-visible .integration-tile,
.scroll-reveal.is-visible .poster-slab,
.scroll-reveal.is-visible .language-cloud span {
  opacity: 1;
  transform: translateY(0);
}

.scroll-reveal.is-visible .sync-node:nth-child(1),
.scroll-reveal.is-visible .integration-tile:nth-child(1),
.scroll-reveal.is-visible .poster-slab:nth-child(1),
.scroll-reveal.is-visible .language-cloud span:nth-child(1) {
  transition-delay: 160ms;
}

.scroll-reveal.is-visible .sync-node:nth-child(3),
.scroll-reveal.is-visible .integration-tile:nth-child(2),
.scroll-reveal.is-visible .poster-slab:nth-child(2),
.scroll-reveal.is-visible .language-cloud span:nth-child(2) {
  transition-delay: 240ms;
}

.scroll-reveal.is-visible .sync-cluster .sync-tv:nth-child(1),
.scroll-reveal.is-visible .poster-slab:nth-child(3),
.scroll-reveal.is-visible .language-cloud span:nth-child(3) {
  transition-delay: 320ms;
}

.scroll-reveal.is-visible .sync-cluster .sync-tv:nth-child(2),
.scroll-reveal.is-visible .language-cloud span:nth-child(4) {
  transition-delay: 400ms;
}

.scroll-reveal.is-visible .sync-cluster .sync-tv:nth-child(3),
.scroll-reveal.is-visible .language-cloud span:nth-child(5) {
  transition-delay: 480ms;
}

.scroll-reveal.is-visible .language-cloud span:nth-child(6) {
  transition-delay: 560ms;
}

.sync-node strong,
.integration-tile strong {
  display: block;
  margin-bottom: 0.4rem;
}

.sync-node span,
.sync-tv,
.integration-tile span,
.poster-slab {
  color: var(--text-soft);
}

.sync-node.active {
  background: linear-gradient(135deg, rgba(123, 255, 211, 0.14), rgba(255, 191, 105, 0.12));
}

.sync-line {
  width: 2px;
  height: 2.1rem;
  margin-left: 1.4rem;
  background: linear-gradient(180deg, rgba(123, 255, 211, 0.5), rgba(255, 191, 105, 0.2));
}

.sync-cluster {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.7rem;
}

.integration-stack,
.poster-wall {
  display: grid;
  gap: 0.8rem;
}

.poster-wall {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.poster-slab {
  min-height: 7rem;
  display: grid;
  place-items: center;
  text-align: center;
  font-weight: 700;
}

.poster-slab-a {
  background: linear-gradient(160deg, rgba(123, 255, 211, 0.18), rgba(255, 255, 255, 0.04));
}

.poster-slab-b {
  background: linear-gradient(160deg, rgba(255, 191, 105, 0.18), rgba(255, 255, 255, 0.04));
}

.poster-slab-c {
  background: linear-gradient(160deg, rgba(152, 201, 255, 0.16), rgba(255, 255, 255, 0.04));
}

.language-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 0.7rem;
}

.language-cloud span {
  padding: 0.75rem 0.95rem;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-soft);
}

.closing-banner {
  margin-top: 1rem;
  padding: 1.7rem;
  border-radius: var(--radius-xl);
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: end;
}

.closing-banner h2 {
  margin: 0.55rem 0 0.35rem;
  font-size: 2rem;
  max-width: 16ch;
}

.closing-banner p {
  margin: 0;
  color: var(--text-soft);
  max-width: 46rem;
  line-height: 1.7;
}

@keyframes heroFloat {
  0%,
  100% {
    transform: rotate(-4deg) translate3d(0, 0, 0);
  }
  50% {
    transform: rotate(-3deg) translate3d(0, -10px, 0);
  }
}

@keyframes cardFloatA {
  0%,
  100% {
    transform: rotate(4deg) translate3d(0, 0, 0);
  }
  50% {
    transform: rotate(3deg) translate3d(-4px, -8px, 0);
  }
}

@keyframes cardFloatB {
  0%,
  100% {
    transform: rotate(-6deg) translate3d(0, 0, 0);
  }
  50% {
    transform: rotate(-5deg) translate3d(4px, -10px, 0);
  }
}

@media (max-width: 1180px) {
  .landing-hero,
  .story-panel-large {
    grid-template-columns: 1fr;
  }

  .landing-stage {
    min-height: 44rem;
  }
}

@media (max-width: 980px) {
  .story-grid,
  .landing-metrics,
  .playback-stats,
  .sync-cluster,
  .poster-wall {
    grid-template-columns: 1fr;
  }

  .story-panel-large {
    grid-column: span 1;
  }

  .hero-device {
    position: relative;
    inset: auto;
    width: auto;
    max-width: none;
    margin: 1rem;
    transform: none;
  }

  .portal-card,
  .catalog-card {
    position: relative;
    inset: auto;
    width: auto;
    margin: 0 1rem 1rem;
    transform: none;
  }

  .landing-stage {
    display: grid;
    align-content: start;
    padding-bottom: 1rem;
  }

  .closing-banner {
    flex-direction: column;
    align-items: start;
  }
}

@media (max-width: 680px) {
  .landing-hero,
  .story-panel,
  .closing-banner {
    padding: 1.2rem;
  }

  .landing-title {
    max-width: none;
    font-size: clamp(2.6rem, 12vw, 4rem);
  }

  .mock-portal {
    grid-template-columns: 1fr;
  }
}
</style>
