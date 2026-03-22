<template>
  <PublicShell :signed-in="signedIn" @sign-out="signOut">
    <div ref="landingPage" class="landing-page" :class="{ 'is-ready': pageReady }">
      
      <!-- Side-by-Side Enterprise Hero -->
      <section class="landing-hero reveal reveal-hero pt-4 md:pt-8 pb-16 relative max-w-7xl mx-auto px-4 lg:px-8 w-full">
        <!-- Luminescent Scrim -->
        <div class="absolute top-[-10%] right-[-5%] w-[800px] h-[800px] bg-secondary/[0.05] blur-[120px] rounded-full pointer-events-none z-0"></div>

        <div class="grid grid-cols-1 lg:grid-cols-12 gap-12 lg:gap-8 items-center relative z-10">
          
          <!-- Left: Copy & Actions -->
          <div class="lg:col-span-5 flex flex-col items-center text-center lg:items-start lg:text-left">
            <span class="badge premium-badge shadow-glow mb-8 inline-flex">
              <img src="/app_logo_mark.webp" alt="Nexio" class="brand-icon" />
              <span class="text-sm font-medium tracking-wide">Nexio Premium</span>
            </span>
            
            <h1 class="section-title landing-title max-w-2xl lg:max-w-none text-5xl md:text-6xl lg:text-6xl font-black tracking-tight mb-6 leading-[1.08]">
              The ultimate <br class="hidden lg:block hidden md:block" />
              <span class="text-gradient">streaming experience.</span>
            </h1>
            
            <p class="landing-summary mt-4 max-w-2xl lg:max-w-none text-soft text-lg md:text-xl leading-relaxed">
              A bleeding-edge streaming platform built for true cinephiles. Seamless playback, zero-compromise audio, and an interface that feels alive.
            </p>
            
            <div class="landing-actions mt-10 flex flex-wrap justify-center lg:justify-start gap-4">
              <NuxtLink class="primary-btn pulse-btn px-8 py-4 text-[1rem] md:text-lg" to="/account">Manage Account</NuxtLink>
              <a href="#features" class="ghost-btn px-8 py-4 text-[1rem] md:text-lg border border-white/10 hover:bg-white/5 transition-colors">Explore Features</a>
            </div>
          </div>

          <!-- Right: Hero Image Showcase -->
          <div class="lg:col-span-7 relative w-full perspective-[2000px] mt-12 lg:mt-0">
            <div class="hero-frame-side w-full relative rounded-2xl md:rounded-[32px] overflow-hidden border border-white/10 shadow-[0_30px_100px_rgba(0,0,0,0.6)] transform lg:scale-105 lg:translate-x-4 transition-transform duration-1000 ease-out hover:scale-100 lg:hover:translate-x-0" style="transform: rotateY(-10deg) rotateX(4deg);">
              <div class="absolute inset-0 bg-gradient-to-tr from-white/5 to-transparent z-20 pointer-events-none"></div>
              <img src="/modernhome.webp" alt="Nexio Dashboard" class="w-full h-auto object-cover" />
            </div>
          </div>

        </div>
      </section>

      <!-- Integration Partner Marquee -->
      <section class="integration-marquee scroll-reveal mb-24 py-12 border-y border-outline-variant/[0.15] bg-surface-container-lowest backdrop-blur-md relative z-10">
        <div class="text-center mb-8">
          <span class="text-dim text-xs uppercase tracking-[0.2em] font-semibold text-white/40">Powered by unique and powerfull native integrations</span>
        </div>
        <div class="marquee-container">
          <div class="marquee-content">
            <!-- First set -->
            <img src="/integrations/trakt.webp" class="partner-logo" alt="Trakt" />
            <img src="/integrations/real-debrid.webp" class="partner-logo" alt="Real Debrid" />
            <img src="/integrations/premiumize.svg" class="partner-logo" alt="Premiumize" />
            <img src="/integrations/mdblist.webp" class="partner-logo mdblist-logo" alt="MDBList" />
            <img src="/integrations/tmdb.webp" class="partner-logo tmdb-logo" alt="TMDB" />
            <img src="/integrations/gemini.webp" class="partner-logo" alt="Gemini" />
            <img src="/integrations/rpdb.webp" class="partner-logo rpdb-logo" alt="RPDB" />
            <img src="/integrations/topposter.svg" class="partner-logo topposter-logo" alt="TOP Posters" />
            <img src="/integrations/anime-skip.svg" class="partner-logo anime-skip-logo" alt="Anime Skip" />
            <!-- Duplicate set for infinite scroll effect -->
            <img src="/integrations/trakt.webp" class="partner-logo" alt="Trakt" />
            <img src="/integrations/real-debrid.webp" class="partner-logo" alt="Real Debrid" />
            <img src="/integrations/premiumize.svg" class="partner-logo" alt="Premiumize" />
            <img src="/integrations/mdblist.webp" class="partner-logo mdblist-logo" alt="MDBList" />
            <img src="/integrations/tmdb.webp" class="partner-logo tmdb-logo" alt="TMDB" />
            <img src="/integrations/gemini.webp" class="partner-logo" alt="Gemini" />
            <img src="/integrations/rpdb.webp" class="partner-logo rpdb-logo" alt="RPDB" />
            <img src="/integrations/topposter.svg" class="partner-logo topposter-logo" alt="TOP Posters" />
            <img src="/integrations/anime-skip.svg" class="partner-logo anime-skip-logo" alt="Anime Skip" />
          </div>
        </div>
      </section>

      <!-- Bento Box Feature Grid -->
      <section id="features" class="max-w-7xl mx-auto px-4 mb-32 z-10 w-full relative">
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6 auto-rows-auto lg:auto-rows-[480px]">
          
          <!-- Bento 1: Interface (Spans 2 columns) -->
          <article class="bento-card lg:col-span-2 group overflow-hidden scroll-reveal relative flex flex-col min-h-[450px]">
  
            <div class="p-8 md:p-12 relative z-20 w-full md:w-[50%] pointer-events-none">
              <span class="badge mb-6 inline-block">Interface</span>
              
              <h3 class="text-3xl md:text-4xl font-bold mb-4 tracking-tight">Living, breathing<br class="hidden md:block lg:hidden" /> design.</h3>
              
              <p class="text-soft text-lg leading-relaxed text-shadow-sm">Fluid micro-animations, rich metadata layouts, and completely personalized catalogs adapting to your context.</p>
            </div>

            <div class="hidden md:block absolute top-1/2 -translate-y-1/2 right-6 lg:right-8 w-[45%] z-10">
              <img 
                src="/detail1.webp" 
                alt="Interface preview" 
                class="w-full h-auto rounded-xl shadow-[0_30px_60px_rgba(0,0,0,0.8)] border border-white/10 group-hover:scale-[1.02] transition-transform duration-700" 
              />
            </div>
            
          </article>

          <!-- Bento 2: Audiophile (Spans 1 column) -->
          <article class="bento-card lg:col-span-1 group overflow-hidden scroll-reveal scroll-reveal-right relative flex flex-col justify-between pb-8 md:pb-12">  
            <div class="p-8 md:p-12 pb-2 relative z-20 pointer-events-none">
              <span class="badge mb-6 inline-block">Audiophile</span>
              <h3 class="text-3xl md:text-4xl font-bold mb-4 tracking-tight">Bit-Perfect Audio</h3>
              <p class="text-soft text-lg leading-relaxed text-shadow-sm">Deliver uncompressed, studio-quality sound straight to your AV receiver. Our custom Media3 engine bypasses system processing to guarantee flawless Dolby TrueHD and Atmos playback.</p>
            </div>            
            <div class="relative w-full z-20 overflow-hidden pt-6 shrink-0">
                <div class="audio-marquee-container">
                  <div class="audio-marquee-content">
                    <img src="/audio/dolby-atmos.png" class="audio-logo h-6 md:h-8 w-auto object-contain" alt="Dolby Atmos" />
                    <img src="/audio/truehd.png" class="audio-logo h-6 md:h-8 w-auto object-contain" alt="Dolby TrueHD" />
                    <img src="/audio/dtsx.png" class="audio-logo h-6 md:h-8 w-auto object-contain" alt="DTS:X" />
                    <img src="/audio/dtshd.png" class="audio-logo h-6 md:h-8 w-auto object-contain" alt="DTS-HD" />
                    <img src="/audio/dts.png" class="audio-logo h-6 md:h-8 w-auto object-contain" alt="DTS" />
                    <img src="/audio/dolby digital.png" class="audio-logo h-6 md:h-8 w-auto object-contain" alt="Dolby Digital" />
                    
                    <img src="/audio/dolby-atmos.png" class="audio-logo h-6 md:h-8 w-auto object-contain" alt="Dolby Atmos" />
                    </div>
                </div>
              </div>
          </article>

          <!-- Bento 3: Engine (Spans 1 column) -->
          <article class="bento-card lg:col-span-1 group overflow-hidden scroll-reveal scroll-reveal-left relative flex flex-col">
            <div class="p-8 md:p-12 relative z-20 pointer-events-none">
              <span class="badge mb-6 inline-block">Engine</span>
              <h3 class="text-3xl md:text-4xl font-bold mb-4 tracking-tight">Media3 Core</h3>
              <p class="text-soft text-lg leading-relaxed text-shadow-sm mb-4">Real-time Dolby Vision conversion, dynamic frame rate matching, a custom libdovi fork, and a custom FFmpeg build for VC-1 software decoding.</p>
            </div>
          </article>

          <!-- Bento 4: Performance (Spans 2 columns) -->
          <article class="bento-card lg:col-span-2 group overflow-hidden scroll-reveal relative flex flex-col min-h-[450px]">
  
            <div class="p-8 md:p-12 relative z-20 w-full md:w-[50%] pointer-events-none">
              <span class="badge mb-6 inline-block">Performance</span>
              
              <h3 class="text-3xl md:text-4xl font-bold mb-4 tracking-tight">Unparalleled Performance</h3>
              
              <p class="text-soft text-lg leading-relaxed text-shadow-sm">Establish parallel connections for maximum throughput, paired with a custom disk cache implementation to prevent in-stream buffers completely.</p>
            </div>

            <div class="hidden md:block absolute top-1/2 -translate-y-1/2 right-6 lg:right-8 w-[45%] z-10">
              <img 
                src="/speed.webp" 
                alt="Performance preview" 
                class="w-full h-auto rounded-xl shadow-[0_30px_60px_rgba(0,0,0,0.8)] border border-white/10 group-hover:-translate-y-2 group-hover:-translate-x-2 transition-transform duration-700" 
              />
            </div>
            
          </article>

        </div>
      </section>

      <!-- Closing CTA -->
      <section class="closing-banner glass scroll-reveal scroll-reveal-wide p-12 lg:p-20 flex flex-col text-center items-center gap-8 mb-24 relative overflow-hidden backdrop-blur-2xl max-w-5xl mx-auto rounded-[40px]">
        <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-full h-full bg-gradient-to-b from-primary/[0.03] to-transparent z-0"></div>
        
        <div class="relative z-10 max-w-2xl">
          <img src="/app_logo_mark.webp" class="w-16 h-16 mx-auto mb-6 object-contain" alt="Nexio Logo" />
          <h2 class="section-title text-4xl lg:text-5xl leading-tight mb-5">Take control of your library today.</h2>
          <p class="text-soft text-lg leading-relaxed mb-8">
            The account portal syncs your configurations, secrets, addons, and viewing states across every television in your home instantly. Sign in once, deploy everywhere.
          </p>
          <NuxtLink class="primary-btn pulse-btn shadow-glow px-10 py-4 text-lg font-bold tracking-wide inline-block" to="/account">Launch Nexio Portal</NuxtLink>
        </div>
      </section>
      
    </div>
  </PublicShell>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import PublicShell from '~/components/portal/PublicShell.vue'
import { usePortalStore } from '~/composables/usePortalStore'

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
        if (!entry.isIntersecting) return
        entry.target.classList.add('is-visible')
        revealObserver?.unobserve(entry.target)
      })
    },
    { root: null, rootMargin: '0px 0px -10% 0px', threshold: 0.15 }
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
/* Core Page Transition */
.landing-page {
  display: flex;
  flex-direction: column;
  opacity: 0;
  transition: opacity 0.5s ease;
  overflow-x: hidden;
}

.landing-page.is-ready {
  opacity: 1;
}

/* Animations */
.reveal {
  opacity: 0;
  transform: translateY(30px) scale(0.98);
  filter: blur(8px);
  transition: opacity 0.8s cubic-bezier(0.2, 0.8, 0.2, 1), transform 0.9s cubic-bezier(0.2, 0.8, 0.2, 1), filter 0.9s ease;
  transition-delay: var(--reveal-delay, 0ms);
}

.landing-page.is-ready .reveal {
  opacity: 1;
  transform: translateY(0) scale(1);
  filter: blur(0);
}

.scroll-reveal {
  opacity: 0;
  transform: translate3d(0, 30px, 0) scale(0.98);
  filter: blur(8px);
  transition: all 0.9s cubic-bezier(0.22, 1, 0.36, 1);
  will-change: opacity, transform, filter;
}

.scroll-reveal-left { transform: translate3d(-30px, 30px, 0) scale(0.98); }
.scroll-reveal-right { transform: translate3d(30px, 30px, 0) scale(0.98); }
.scroll-reveal-wide { transform: translate3d(0, 40px, 0) scale(0.97); }

.scroll-reveal.is-visible {
  opacity: 1;
  transform: translate3d(0, 0, 0) scale(1);
  filter: blur(0);
}

@keyframes pulseGlow {
  0% { box-shadow: 0 0 0 0 rgba(186, 158, 255, 0.4); }
  70% { box-shadow: 0 0 0 10px rgba(186, 158, 255, 0); }
  100% { box-shadow: 0 0 0 0 rgba(186, 158, 255, 0); }
}

/* Typography & Badges */
.text-gradient {
  background: linear-gradient(135deg, var(--primary), var(--primary-dim));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  display: inline-block;
}

.text-soft { color: var(--text-soft); }
.text-dim { color: var(--text-dim); }

.premium-badge {
  background: var(--surface-container-low);
  border: 1px solid var(--stroke);
  padding: 0.6rem 1.25rem;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  backdrop-filter: blur(12px);
}

.brand-icon {
  width: 18px;
  height: 18px;
  object-fit: contain;
  filter: grayscale(100%) brightness(100);
}

.shadow-glow {
  box-shadow: var(--shadow);
}

.pulse-btn:hover {
  animation: pulseGlow 1.5s infinite;
}

/* Centered Hero */
.landing-title {
  font-size: clamp(3.2rem, 6.5vw, 5.5rem);
  line-height: 1.05;
  margin: 0;
  letter-spacing: -0.02em;
  font-weight: 800;
}

.hero-frame-centered {
  position: relative;
  transform: perspective(1200px) rotateX(8deg);
  transform-style: preserve-3d;
  transition: transform 0.8s cubic-bezier(0.2, 0.8, 0.2, 1);
  will-change: transform;
}

.hero-frame-centered::before {
  content: '';
  position: absolute;
  inset: -2px;
  background: linear-gradient(to bottom, rgba(255,255,255,0.1), transparent);
  border-radius: 34px;
  z-index: -1;
  pointer-events: none;
}

.hero-visual-container:hover .hero-frame-centered {
  transform: perspective(1200px) rotateX(2deg);
}

/* Integration Marquee */
.integration-marquee {
  overflow: hidden;
  position: relative;
  mask-image: linear-gradient(to right, transparent, black 15%, black 85%, transparent);
}

.marquee-container {
  display: flex;
  overflow: hidden;
  user-select: none;
  width: 100%;
}

.marquee-content {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-around;
  gap: 5rem;
  min-width: 100%;
  animation: scroll 40s linear infinite;
  padding: 0 2.5rem;
}

@keyframes scroll {
  0% { transform: translateX(0); }
  100% { transform: translateX(calc(-50% - 2.5rem)); }
}

.partner-logo {
  height: 40px;
  object-fit: contain;
  opacity: 0.4;
  transition: opacity 0.4s ease, filter 0.4s ease, transform 0.4s ease;
  filter: grayscale(100%) brightness(1.2);
  cursor: pointer;
}

.partner-logo:hover {
  opacity: 1;
  filter: none;
  transform: scale(1.05);
}

/* Specific Logo Adjustments */
.mdblist-logo { height: 28px; filter: grayscale(100%) brightness(2.5); }
.tmdb-logo { height: 22px; filter: grayscale(100%) brightness(2); }
.rpdb-logo { height: 44px; filter: grayscale(100%) brightness(0.8); }
.topposter-logo { height: 32px; filter: grayscale(100%) brightness(1.8); }
.anime-skip-logo { height: 34px; }

/* Audio Marquee */
.audio-marquee-container {
  display: flex;
  overflow: hidden;
  user-select: none;
  width: 100%;
  mask-image: linear-gradient(to right, transparent, black 10%, black 90%, transparent);
}

.audio-marquee-content {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-around;
  gap: 3rem;
  min-width: 100%;
  animation: scrollAudio 25s linear infinite;
  padding: 0 1.5rem;
}

@keyframes scrollAudio {
  0% { transform: translateX(0); }
  100% { transform: translateX(calc(-50% - 1.5rem)); }
}

.audio-logo {
  height: 28px;
  object-fit: contain;
  opacity: 0.5;
  filter: brightness(0) invert(1);
  transition: opacity 0.4s ease, transform 0.4s ease;
}

.audio-logo:hover {
  opacity: 1;
  transform: scale(1.08);
}

/* Bento Box Grid */
.bento-card {
  border-radius: var(--radius-xl);
  border: 1px solid var(--stroke);
  position: relative;
  background: var(--surface-container);
  transition: border-color 0.4s ease, box-shadow 0.4s ease;
}

.bento-card:hover {
  border-color: var(--stroke-strong);
  box-shadow: var(--shadow);
}

@media (max-width: 768px) {
  .landing-hero { padding-top: 4rem; padding-bottom: 2rem; }
  .grid { grid-template-columns: 1fr; auto-rows: minmax(350px, auto); }
  .bento-card { grid-column: span 1 !important; flex-direction: column !important; }
  .hero-frame-centered { transform: none; }
  .hero-visual-container:hover .hero-frame-centered { transform: none; }
  .marquee-content { gap: 3rem; }
  .partner-logo { height: 32px; }
}
</style>
