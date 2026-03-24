<template>
  <div class="settings-workspace-container w-full max-w-[1600px] mx-auto pb-20 font-body">
    <PortalManagementHero
      eyebrow="System Connectivity"
      :title="props.title || 'Integrations Command Center'"
      :description="props.subtitle || 'Manage your external data sources, metadata providers, and streaming scrapers from a unified obsidian interface. Authorized tokens are stored securely.'"
      class="mb-12"
    >
      <template #right>
        <div class="flex h-full items-center xl:justify-end">
          <button @click="activeModal = 'add_integration'" class="flex items-center gap-2 rounded-2xl bg-gradient-to-tr from-primary to-primary-dim px-6 py-3.5 text-sm font-bold text-on-primary shadow-lg shadow-primary/20 transition-all duration-300 hover:scale-[1.02] active:scale-95">
            <span class="material-symbols-outlined text-[20px]">add_link</span>
            Add Integration
          </button>
        </div>
      </template>
    </PortalManagementHero>

    <!-- Integration Grid (Configured Only) -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 relative z-10">

      <!-- Trakt Card -->
      <div v-if="isConfigured.trakt" @click="activeModal = 'trakt'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-primary/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer relative overflow-hidden bg-surface-container">
        <div class="absolute top-0 right-0 w-32 h-32 bg-primary/5 rounded-full -mr-16 -mt-16 blur-3xl pointer-events-none"></div>
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
            <img src="/integrations/trakt.webp" alt="Trakt Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity" />
          </div>
          <div class="flex items-center gap-1.5 px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 text-[10px] font-bold uppercase tracking-wider border border-emerald-500/20">
            <span class="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
            Healthy
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">Trakt</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">Sync your watch history and collection metadata across all devices.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('disconnect-trakt')" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5 text-red-400 hover:bg-red-500/20"><span class="material-symbols-outlined text-[18px]">delete</span></button>
        </div>
      </div>

      <!-- Real-Debrid Card -->
      <div v-if="isConfigured.realdebrid" @click="activeModal = 'realdebrid'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-secondary/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
            <img src="/integrations/real-debrid.webp" alt="Real-Debrid Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity" />
          </div>
          <div :class="['flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider border', props.settings.integrations.debrid.realDebrid.connected ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-primary/10 text-primary border-primary/20']">
            {{ props.settings.integrations.debrid.realDebrid.connected ? 'Connected' : 'Pending' }}
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">Real-Debrid</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">Unrestricted cloud downloading and high-speed streaming integration.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('disconnect-realdebrid')" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5 text-red-400 hover:bg-red-500/20"><span class="material-symbols-outlined text-[18px]">delete</span></button>
        </div>
      </div>

      <!-- Premiumize Card -->
      <div v-if="isConfigured.premiumize" @click="activeModal = 'premiumize'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-sky-500/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
            <img src="/integrations/premiumize.svg" alt="Premiumize Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity" />
          </div>
          <div class="flex items-center gap-1.5 px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 text-[10px] font-bold uppercase tracking-wider border border-emerald-500/20">
            Healthy
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">Premiumize</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">All-in-one cloud storage and high-speed downloader service integration.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('clear-premiumize-key')" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5 text-red-400 hover:bg-red-500/20"><span class="material-symbols-outlined text-[18px]">delete</span></button>
        </div>
      </div>

      <!-- TMDB Card -->
      <div v-if="isConfigured.tmdb" @click="activeModal = 'tmdb'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-tertiary/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-[#0d253f]/50 flex items-center justify-center border border-white/5">
            <img src="/integrations/tmdb.webp" alt="TMDB Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity" />
          </div>
          <div :class="['flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider border', integrationEnabled('tmdb') ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-surface-container-highest/60 text-on-surface-variant border-outline-variant/20']">
            {{ integrationEnabled('tmdb') ? 'Connected' : 'Configured' }}
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">TMDB</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">Primary metadata provider for movie and television show information.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('update', 'integrations.tmdb.enabled', !settings.integrations.tmdb.enabled)" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5" :class="integrationEnabled('tmdb') ? 'text-red-400 hover:bg-red-500/20' : 'text-emerald-400 hover:bg-emerald-500/20'"><span class="material-symbols-outlined text-[18px]">power_settings_new</span></button>
        </div>
      </div>

      <!-- OMDB Card -->
      <div v-if="isConfigured.omdb" @click="activeModal = 'omdb'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-amber-500/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5 text-amber-300 font-black text-xs tracking-wide">
            OMDB
          </div>
          <div :class="['flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider border', integrationEnabled('omdb') ? 'bg-amber-500/10 text-amber-300 border-amber-500/20' : 'bg-surface-container-highest/60 text-on-surface-variant border-outline-variant/20']">
            {{ integrationEnabled('omdb') ? 'Active' : 'Configured' }}
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">OMDB</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">IMDb episode ratings source for episode rows and season rating views.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('update', 'integrations.omdb.enabled', !settings.integrations.omdb.enabled)" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5" :class="integrationEnabled('omdb') ? 'text-red-400 hover:bg-red-500/20' : 'text-emerald-400 hover:bg-emerald-500/20'"><span class="material-symbols-outlined text-[18px]">power_settings_new</span></button>
        </div>
      </div>

      <!-- IMDb Card -->
      <div v-if="isConfigured.imdb" @click="activeModal = 'imdb'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-amber-400/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5 text-white font-black text-[11px] tracking-[0.25em]">
            IMDb
          </div>
          <div :class="['flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider border', integrationEnabled('imdb') ? 'bg-amber-500/10 text-amber-200 border-amber-500/20' : 'bg-surface-container-highest/60 text-on-surface-variant border-outline-variant/20']">
            {{ integrationEnabled('imdb') ? 'Active' : 'Configured' }}
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">IMDb</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">Primary IMDb episode-ratings provider for the portal.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('update', 'integrations.imdb.enabled', !settings.integrations.imdb.enabled)" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5" :class="integrationEnabled('imdb') ? 'text-red-400 hover:bg-red-500/20' : 'text-emerald-400 hover:bg-emerald-500/20'"><span class="material-symbols-outlined text-[18px]">power_settings_new</span></button>
        </div>
      </div>

      <!-- MDBList Card -->
      <div v-if="isConfigured.mdblist" @click="activeModal = 'mdblist'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-primary/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
            <img src="/integrations/mdblist.webp" alt="MDBList Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity grayscale-[0.2]" />
          </div>
          <div :class="['flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider border', integrationEnabled('mdblist') ? 'bg-primary/10 text-primary border-primary/20' : 'bg-surface-container-highest/60 text-on-surface-variant border-outline-variant/20']">
            {{ integrationEnabled('mdblist') ? 'Active' : 'Configured' }}
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">MDBList</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">Advanced rating aggregation and dynamic movie list synchronization.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('update', 'integrations.mdblist.enabled', !settings.integrations.mdblist.enabled)" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5" :class="integrationEnabled('mdblist') ? 'text-red-400 hover:bg-red-500/20' : 'text-emerald-400 hover:bg-emerald-500/20'"><span class="material-symbols-outlined text-[18px]">power_settings_new</span></button>
        </div>
      </div>

      <!-- Anime-Skip Card -->
      <div v-if="isConfigured.animeskip" @click="activeModal = 'animeskip'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-orange-500/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
            <img src="/integrations/anime-skip.svg" alt="Anime-Skip Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity" />
          </div>
          <div :class="['flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider border', integrationEnabled('animeskip') ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-surface-container-highest/60 text-on-surface-variant border-outline-variant/20']">
            {{ integrationEnabled('animeskip') ? 'Operational' : 'Configured' }}
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">Anime-Skip</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">Skip intros, outros, and filler episodes automatically for anime titles.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('update', 'integrations.animeSkip.enabled', !settings.integrations.animeSkip.enabled)" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5" :class="integrationEnabled('animeskip') ? 'text-red-400 hover:bg-red-500/20' : 'text-emerald-400 hover:bg-emerald-500/20'"><span class="material-symbols-outlined text-[18px]">power_settings_new</span></button>
        </div>
      </div>

      <!-- Google Gemini Card -->
      <div v-if="isConfigured.gemini" @click="activeModal = 'gemini'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-blue-500/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
            <img src="/integrations/gemini.webp" alt="Google Gemini Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity" />
          </div>
          <div :class="['flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider border', integrationEnabled('gemini') ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-surface-container-highest/60 text-on-surface-variant border-outline-variant/20']">
            {{ integrationEnabled('gemini') ? 'Active' : 'Configured' }}
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">Google Gemini</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">AI-powered native subtitle translation across workflows.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('update', 'integrations.gemini.enabled', !settings.integrations.gemini.enabled)" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5" :class="integrationEnabled('gemini') ? 'text-red-400 hover:bg-red-500/20' : 'text-emerald-400 hover:bg-emerald-500/20'"><span class="material-symbols-outlined text-[18px]">power_settings_new</span></button>
        </div>
      </div>

      <!-- RPDB / TOP Posters unified card based on configuration -->
      <div v-if="isConfigured.rpdb || isConfigured.topposters" @click="activeModal = isConfigured.rpdb ? 'rpdb' : 'topposters'" class="glass-card p-6 rounded-xl border border-outline-variant/10 hover:border-yellow-500/30 hover:bg-surface-container-high transition-all duration-300 group cursor-pointer bg-surface-container">
        <div class="flex justify-between items-start mb-6">
          <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
            <img :src="isConfigured.rpdb ? '/integrations/rpdb.webp' : '/integrations/topposter.svg'" alt="Poster Provider Logo" class="w-8 h-8 object-contain opacity-90 group-hover:opacity-100 transition-opacity" />
          </div>
          <div class="flex items-center gap-1.5 px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 text-[10px] font-bold uppercase tracking-wider border border-emerald-500/20">
            Active
          </div>
        </div>
        <h3 class="text-xl font-bold font-headline mb-1 text-on-surface">{{ isConfigured.rpdb ? 'RPDB' : 'TOP Posters' }}</h3>
        <p class="text-sm text-on-surface-variant font-body mb-6">High quality rating posters to replace standard metadata artwork.</p>
        <div class="flex items-center gap-2 pt-4 border-t border-outline-variant/10">
          <button class="flex-1 bg-surface-container-highest/60 hover:bg-surface-container-highest text-white text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5">Settings</button>
          <button @click.stop="emit('update', isConfigured.rpdb ? 'integrations.posterRatings.rpdbEnabled' : 'integrations.posterRatings.topPostersEnabled', false)" class="px-3 bg-surface-container-highest/60 hover:bg-surface-container-highest text-xs font-semibold py-2.5 rounded-lg transition-colors border border-white/5 text-red-400 hover:bg-red-500/20"><span class="material-symbols-outlined text-[18px]">power_settings_new</span></button>
        </div>
      </div>

    </div>

    <!-- Empty State -->
    <div v-if="Object.values(isConfigured).every(v => !v)" class="py-24 flex flex-col items-center justify-center text-center opacity-60">
      <span class="material-symbols-outlined text-6xl text-on-surface-variant mb-4 pointer-events-none">cable</span>
      <h3 class="text-xl font-bold text-white mb-2 font-headline">No Integrations Configured</h3>
      <p class="text-on-surface-variant max-w-sm text-sm">Click 'Add Integration' above to link your first metadata or streaming account.</p>
    </div>

    <!-- Save Configuration Button -->
    <div class="mt-8 flex justify-end relative z-10">
      <button class="px-8 py-3 rounded-xl text-sm font-bold bg-primary text-on-primary shadow-lg shadow-primary/20 hover:scale-[1.02] transition-transform flex items-center gap-2" :disabled="busy" @click="emit('persist')">
        <span v-if="busy" class="material-symbols-outlined animate-spin text-[18px]">sync</span>
        <span v-else class="material-symbols-outlined text-[18px]">save</span>
        {{ busy ? 'Syncing Profile...' : 'Save Integrations' }}
      </button>
    </div>

    <!-- Modals -->
    <Teleport to="body">
      <!-- Modal Overlay -->
      <transition enter-active-class="transition duration-300 ease-out" enter-from-class="opacity-0" enter-to-class="opacity-100" leave-active-class="transition duration-200 ease-in" leave-from-class="opacity-100" leave-to-class="opacity-0">
        <div v-if="activeModal" class="fixed inset-0 z-[60] bg-surface-container-lowest/80 backdrop-blur-md flex items-center justify-center p-4 overflow-y-auto">

          <!-- Add Integration Picker Modal -->
          <div v-if="activeModal === 'add_integration'" class="w-full max-w-4xl bg-[#1a1919] border border-outline-variant/20 rounded-2xl overflow-hidden shadow-[0_0_100px_-20px_rgba(186,158,255,0.15)] flex flex-col relative" @click.stop>
            <div class="p-6 border-b border-outline-variant/10 flex justify-between items-center bg-surface-container/50">
              <h2 class="text-xl font-bold font-headline text-white">Select Integration</h2>
              <button @click="closeModal" class="w-8 h-8 rounded-full hover:bg-surface-bright flex items-center justify-center transition-colors">
                <span class="material-symbols-outlined text-on-surface-variant text-sm">close</span>
              </button>
            </div>
            <div class="p-6 grid grid-cols-1 sm:grid-cols-2 gap-4 max-h-[70vh] overflow-y-auto custom-scrollbar">

              <div v-if="!isConfigured.trakt" @click="activeModal = 'trakt'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-primary/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/trakt.webp" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-primary transition-colors">Trakt</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Sync watch history and collections</p></div>
              </div>

              <div v-if="!isConfigured.realdebrid" @click="activeModal = 'realdebrid'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-secondary/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/real-debrid.webp" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-secondary transition-colors">Real-Debrid</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">High-speed unrestricted downloader</p></div>
              </div>

              <div v-if="!isConfigured.premiumize" @click="activeModal = 'premiumize'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-sky-500/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/premiumize.svg" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-sky-400 transition-colors">Premiumize</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Cloud torrenting and fast transfers</p></div>
              </div>

              <div v-if="!isConfigured.tmdb" @click="activeModal = 'tmdb'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-tertiary/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-[#0d253f]/50 flex items-center justify-center border border-white/5"><img src="/integrations/tmdb.webp" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-tertiary transition-colors">TMDB</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Primary metadata and artwork</p></div>
              </div>

              <div v-if="!isConfigured.omdb" @click="activeModal = 'omdb'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-amber-500/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5 text-amber-300 font-black text-xs tracking-wide">OMDB</div>
                <div><h4 class="font-bold text-white group-hover:text-amber-300 transition-colors">OMDB</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">IMDb episode ratings provider</p></div>
              </div>

              <div v-if="!isConfigured.imdb" @click="activeModal = 'imdb'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-amber-400/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5 text-white font-black text-[10px] tracking-[0.2em]">IMDb</div>
                <div><h4 class="font-bold text-white group-hover:text-amber-200 transition-colors">IMDb</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Primary episode-ratings provider for the portal</p></div>
              </div>

              <div v-if="!isConfigured.mdblist" @click="activeModal = 'mdblist'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-primary/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/mdblist.webp" class="w-6 h-6 object-contain grayscale-[0.2]" /></div>
                <div><h4 class="font-bold text-white group-hover:text-primary transition-colors">MDBList</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Aggregated ratings & custom lists</p></div>
              </div>

              <div v-if="!isConfigured.animeskip" @click="activeModal = 'animeskip'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-orange-500/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/anime-skip.svg" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-orange-400 transition-colors">Anime-Skip</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Skip intros, outros automatically</p></div>
              </div>

              <div v-if="!isConfigured.gemini" @click="activeModal = 'gemini'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-blue-500/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/gemini.webp" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-blue-400 transition-colors">Google Gemini</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Native subtitle translation</p></div>
              </div>

              <div v-if="!isConfigured.rpdb" @click="activeModal = 'rpdb'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-yellow-500/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/rpdb.webp" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-yellow-500 transition-colors">RPDB</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Rating poster artwork integration</p></div>
              </div>

              <div v-if="!isConfigured.topposters" @click="activeModal = 'topposters'" class="flex items-center gap-4 p-4 rounded-xl border border-outline-variant/10 bg-surface-container hover:bg-surface-container-high hover:border-yellow-500/30 cursor-pointer transition-all group">
                <div class="w-12 h-12 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5"><img src="/integrations/topposter.svg" class="w-6 h-6 object-contain" /></div>
                <div><h4 class="font-bold text-white group-hover:text-yellow-500 transition-colors">TOP Posters</h4><p class="text-[11px] text-on-surface-variant line-clamp-1">Alternative rating poster artwork</p></div>
              </div>

            </div>
          </div>

          <!-- Trakt Modal -->
          <div v-else-if="activeModal === 'trakt'" class="w-full max-w-2xl bg-[#1a1919] border border-outline-variant/20 rounded-2xl overflow-hidden shadow-[0_0_100px_-20px_rgba(186,158,255,0.25)] flex flex-col relative" @click.stop>
            <div class="p-8 pb-6 border-b border-outline-variant/10 relative">
              <div class="absolute top-0 right-0 w-64 h-32 bg-red-500/10 blur-[100px] pointer-events-none"></div>
              <div class="flex justify-between items-start mb-4">
                <div class="flex items-center gap-4">
                  <div class="w-12 h-12 bg-surface-container-highest rounded-lg flex items-center justify-center border border-white/5">
                    <img src="/integrations/trakt.webp" class="w-8 h-8 object-contain" />
                  </div>
                  <div>
                    <h2 class="text-2xl font-headline font-extrabold text-white tracking-tight">Trakt Settings</h2>
                    <div class="flex items-center gap-2 mt-1" v-if="settings.integrations.traktAuth.connected">
                      <div class="w-2 h-2 rounded-full bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.6)]"></div>
                      <span class="text-xs text-on-surface-variant">Connected as <span class="text-white font-bold">{{ settings.integrations.traktAuth.username || 'User' }}</span></span>
                    </div>
                  </div>
                </div>
                <button @click="closeModal" class="w-10 h-10 rounded-full hover:bg-surface-container-highest flex items-center justify-center transition-colors">
                  <span class="material-symbols-outlined text-on-surface-variant">close</span>
                </button>
              </div>
            </div>

            <div class="flex-1 overflow-y-auto p-8 pt-6 max-h-[60vh] custom-scrollbar space-y-6">
              <div v-if="!settings.integrations.traktAuth.connected && !traktFlow" class="bg-surface-container-low p-6 rounded-xl border border-outline-variant/10 text-center">
                <p class="text-sm text-on-surface-variant mb-4">Connect your Trakt account via OAuth device flow.</p>
                <button @click="emit('start-trakt')" class="bg-red-500 text-white px-6 py-2 rounded-lg font-bold hover:bg-red-600 transition-colors shadow-lg shadow-red-500/20">Start Connection</button>
              </div>
              <div v-else-if="traktFlow" class="bg-surface-container-low p-6 rounded-xl border border-outline-variant/10 text-center">
                <p class="text-sm text-on-surface-variant mb-2">Visit <strong class="text-white">{{ traktFlow.verificationUrl }}</strong> and enter your code:</p>
                <p class="text-3xl font-mono text-primary font-bold tracking-widest my-4">{{ traktFlow.userCode }}</p>
                <button @click="emit('complete-trakt')" class="bg-primary text-on-primary px-6 py-2 rounded-lg font-bold shadow-lg shadow-primary/20">Check Status</button>
              </div>

              <template v-if="settings.integrations.traktAuth.connected">
                <div class="flex justify-end mb-6">
                  <button @click="emit('disconnect-trakt')" class="px-4 py-2 bg-transparent hover:bg-error/10 rounded-lg text-xs font-bold text-error transition-all active:scale-95 border border-error/10">Disconnect account</button>
                </div>

                <!-- Built-in Trakt Catalogs -->
                <div :class="['transition-all duration-300', expandedSections['traktBuiltIn'] ? '' : 'overflow-hidden']">
                  <button @click="toggleSection('traktBuiltIn')" class="w-full flex items-center justify-between border-b border-outline-variant/10 pb-3 group">
                    <div class="flex items-center gap-3">
                      <h3 class="text-[11px] font-bold uppercase tracking-[0.25em] text-on-surface-variant font-headline group-hover:text-on-surface transition-colors">Built-in Trakt catalogs</h3>
                      <span class="text-[10px] text-outline italic">{{ settings.catalogs.trakt.catalogOrder?.length || 0 }} discovered</span>
                    </div>
                    <span :class="['material-symbols-outlined text-outline transition-transform duration-300', expandedSections['traktBuiltIn'] ? 'rotate-180' : '']">expand_more</span>
                  </button>
                  <div :class="['pt-4 transition-all duration-300 ease-out overflow-hidden', expandedSections['traktBuiltIn'] ? 'max-h-[1000px] opacity-100' : 'max-h-0 opacity-0']">
                    <div class="space-y-1 pb-4">
                      <div v-for="catalog in settings.catalogs.trakt.catalogOrder" :key="catalog" class="flex items-center justify-between py-3 border-b border-outline-variant/5">
                        <span class="text-sm font-medium text-white">{{ traktCatalogLabels[catalog] || catalog }}</span>
                        <label class="relative inline-flex items-center cursor-pointer">
                          <input type="checkbox" :checked="settings.catalogs.trakt.catalogEnabledSet.includes(catalog)" @change="toggleCatalog(catalog)" class="sr-only peer">
                          <div class="w-11 h-6 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary"></div>
                        </label>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- Personal Lists -->
                <div v-if="traktPopularLists" :class="['mt-4 transition-all duration-300', expandedSections['traktPopular'] ? '' : 'overflow-hidden']">
                  <button @click="toggleSection('traktPopular')" class="w-full flex items-center justify-between border-b border-outline-variant/10 pb-3 group">
                    <div class="flex items-center gap-3">
                      <h3 class="text-[11px] font-bold uppercase tracking-[0.25em] text-on-surface-variant font-headline group-hover:text-on-surface transition-colors">Trending Lists</h3>
                      <span class="text-[10px] text-outline italic">{{ traktPopularLists.length }} discovered</span>
                    </div>
                    <span :class="['material-symbols-outlined text-outline transition-transform duration-300', expandedSections['traktPopular'] ? 'rotate-180' : '']">expand_more</span>
                  </button>
                  <div :class="['pt-4 transition-all duration-300 ease-out overflow-hidden', expandedSections['traktPopular'] ? 'max-h-[1000px] opacity-100' : 'max-h-0 opacity-0']">
                    <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 pb-4">
                      <div v-for="list in traktPopularLists" :key="list.key" class="flex items-center justify-between p-3 bg-surface-container-low/50 border border-outline-variant/10 rounded-lg">
                        <span class="text-xs font-medium truncate pr-2" :title="list.title">{{ list.title }}</span>
                        <label class="relative inline-flex items-center cursor-pointer shrink-0">
                          <input type="checkbox" :checked="settings.catalogs.trakt.selectedPopularListKeys.includes(list.key)" @change="emit('toggle-trakt-list', list.key)" class="sr-only peer">
                          <div class="w-9 h-5 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                        </label>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- Search Lists -->
                <div :class="['mt-4 transition-all duration-300', expandedSections['traktSearch'] ? '' : 'overflow-hidden']">
                  <button @click="toggleSection('traktSearch')" class="w-full flex items-center justify-between border-b border-outline-variant/10 pb-3 group">
                    <div class="flex items-center gap-3">
                      <h3 class="text-[11px] font-bold uppercase tracking-[0.25em] text-on-surface-variant font-headline group-hover:text-on-surface transition-colors">Search Community Lists</h3>
                    </div>
                    <span :class="['material-symbols-outlined text-outline transition-transform duration-300', expandedSections['traktSearch'] ? 'rotate-180' : '']">expand_more</span>
                  </button>
                  <div :class="['pt-4 transition-all duration-300 ease-out overflow-hidden', expandedSections['traktSearch'] ? 'max-h-[1000px] opacity-100' : 'max-h-0 opacity-0']">
                    <div class="flex gap-2 mb-4">
                      <input v-model="traktSearchQuery" @keydown.enter="emit('search-trakt-lists', traktSearchQuery)" class="flex-1 bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-2 text-sm placeholder:text-on-surface-variant/40" placeholder="Search for lists...">
                      <button @click="emit('search-trakt-lists', traktSearchQuery)" class="px-4 py-2 bg-surface-container-high hover:bg-surface-bright rounded-lg border border-outline-variant/10 text-sm font-bold transition-colors">Search</button>
                    </div>
                    <div v-if="traktSearchResults && traktSearchResults.length > 0" class="grid grid-cols-1 sm:grid-cols-2 gap-3 pb-4">
                      <div v-for="list in traktSearchResults" :key="list.key" class="flex items-center justify-between p-3 bg-surface-container-low/50 border border-outline-variant/10 rounded-lg">
                        <div class="flex flex-col min-w-0 pr-2">
                           <span class="text-xs font-medium truncate" :title="list.title">{{ list.title }}</span>
                           <span class="text-[10px] text-on-surface-variant line-clamp-1 italic">by {{ list.userId }} • {{ list.itemCount }} items</span>
                        </div>
                        <label class="relative inline-flex items-center cursor-pointer shrink-0">
                          <input type="checkbox" :checked="settings.catalogs.trakt.selectedPopularListKeys.includes(list.key)" @change="emit('toggle-trakt-list', list.key)" class="sr-only peer">
                          <div class="w-9 h-5 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                        </label>
                      </div>
                    </div>
                    <div v-else-if="traktSearchResults && traktSearchResults.length === 0 && traktSearchQuery" class="text-center text-on-surface-variant text-xs italic py-4">No lists found.</div>
                  </div>
                </div>
              </template>
            </div>

            <div class="p-6 bg-surface-container-low/50 border-t border-outline-variant/10 flex justify-end gap-4">
              <button @click="closeModal" class="px-6 py-2 rounded-lg text-sm font-bold text-on-surface-variant hover:text-white transition-colors">Discard changes</button>
              <button @click="saveAndClose" class="px-8 py-2 rounded-lg text-sm font-bold bg-primary text-on-primary shadow-lg shadow-primary/20 hover:scale-[1.02] transition-transform">Save Settings</button>
            </div>
          </div>

          <!-- MDBList Modal -->
          <div v-else-if="activeModal === 'mdblist'" class="w-full max-w-2xl bg-[#1a1919] border border-outline-variant/20 rounded-2xl overflow-hidden shadow-[0_0_100px_-20px_rgba(186,158,255,0.25)] flex flex-col relative" @click.stop>
            <div class="flex justify-between items-center p-6 border-b border-outline-variant/10 bg-surface-container-low/50">
              <div class="flex items-center gap-4">
                <div class="w-10 h-10 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
                  <img src="/integrations/mdblist.webp" class="w-6 h-6 object-contain grayscale-[0.2]" />
                </div>
                <h2 class="text-xl font-headline font-bold text-white">MDBList Settings</h2>
              </div>
              <button @click="closeModal" class="w-8 h-8 rounded-full hover:bg-surface-bright flex items-center justify-center"><span class="material-symbols-outlined text-sm">close</span></button>
            </div>

            <div class="flex-1 overflow-y-auto p-6 max-h-[60vh] custom-scrollbar space-y-6">

              <div class="flex items-center justify-between p-4 bg-surface-container rounded-xl border border-outline-variant/10">
                <span class="text-sm font-bold">Enable MDBList Integration</span>
                <label class="relative inline-flex items-center cursor-pointer">
                  <input type="checkbox" :checked="settings.integrations.mdblist.enabled" @change="emit('update', 'integrations.mdblist.enabled', !settings.integrations.mdblist.enabled)" class="sr-only peer">
                  <div class="w-11 h-6 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary"></div>
                </label>
              </div>

              <div class="space-y-2">
                <label class="flex justify-between items-center text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">
                  <span>MDBList API Key</span>
                  <a :href="apiLinkMap['mdblist']" target="_blank" class="text-primary hover:text-primary-dim lowercase tracking-normal flex items-center gap-1"><span class="material-symbols-outlined text-[12px]">open_in_new</span> get api key</a>
                </label>
                <div class="flex gap-2 relative">
                  <input :value="secretDrafts['integration:mdblist'] || ''" @input="emit('update-secret-draft', 'integration:mdblist', ($event.target as HTMLInputElement).value)" class="flex-1 bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm font-mono placeholder:text-on-surface-variant/40" placeholder="Enter API Key">
                </div>
                <div class="flex gap-2 mt-2">
                  <button class="px-4 py-1.5 bg-surface-bright hover:bg-surface-container-highest rounded border border-outline-variant/10 text-xs font-semibold active:scale-95 transition-all" @click="emit('save-secret', 'mdblist_api_key', 'integration:mdblist')">Save key</button>
                </div>
                <p v-if="secretStatuses['integration:mdblist']?.maskedPreview" class="text-[10px] text-primary/70 mt-1">Stored: {{ secretStatuses['integration:mdblist']?.maskedPreview }}</p>
              </div>

              <!-- Advanced Ratings -->
              <div :class="['transition-all duration-300', expandedSections['mdblistRatings'] ? '' : 'overflow-hidden']">
                  <button @click="toggleSection('mdblistRatings')" class="w-full flex items-center justify-between border-b border-outline-variant/10 pb-3 group">
                    <div class="flex items-center gap-3">
                      <h3 class="text-[11px] font-bold uppercase tracking-[0.25em] text-on-surface-variant font-headline group-hover:text-on-surface transition-colors">Settings</h3>
                      <span class="text-[10px] text-outline italic">Configure ratings sources.</span>
                    </div>
                    <span :class="['material-symbols-outlined text-outline transition-transform duration-300', expandedSections['mdblistRatings'] ? 'rotate-180' : '']">expand_more</span>
                  </button>
                  <div :class="['pt-4 transition-all duration-300 ease-out overflow-hidden', expandedSections['mdblistRatings'] ? 'max-h-[1000px] opacity-100' : 'max-h-0 opacity-0']">
                    <div class="grid grid-cols-2 lg:grid-cols-3 gap-3 pb-4">
                      <div v-for="field in mdblistFields" :key="field.path" class="flex items-center justify-between p-3 bg-surface-container-low/50 border border-outline-variant/10 rounded-lg">
                        <span class="text-xs font-medium pr-2 truncate" :title="field.label">{{ field.label }}</span>
                        <label class="relative inline-flex items-center cursor-pointer shrink-0">
                          <input type="checkbox" :checked="!!fieldValue(settings, field.path)" @change="emit('update', field.path, !fieldValue(settings, field.path))" class="sr-only peer">
                          <div class="w-9 h-5 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                        </label>
                      </div>
                    </div>
                  </div>
              </div>

              <!-- Personal Lists -->
              <div v-if="mdblistPersonalLists" :class="['mt-2 transition-all duration-300', expandedSections['mdblistPersonal'] ? '' : 'overflow-hidden']">
                  <button @click="toggleSection('mdblistPersonal')" class="w-full flex items-center justify-between border-b border-outline-variant/10 pb-3 group">
                    <div class="flex items-center gap-3">
                      <h3 class="text-[11px] font-bold uppercase tracking-[0.25em] text-on-surface-variant font-headline group-hover:text-on-surface transition-colors">Personal Lists</h3>
                      <span class="text-[10px] text-outline italic">{{ mdblistPersonalLists.length }} discovered</span>
                    </div>
                    <span :class="['material-symbols-outlined text-outline transition-transform duration-300', expandedSections['mdblistPersonal'] ? 'rotate-180' : '']">expand_more</span>
                  </button>
                  <div :class="['pt-4 transition-all duration-300 ease-out overflow-hidden', expandedSections['mdblistPersonal'] ? 'max-h-[1000px] opacity-100' : 'max-h-0 opacity-0']">
                    <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 pb-4">
                      <div v-for="list in mdblistPersonalLists" :key="list.key" class="flex items-center justify-between p-3 bg-surface-container-low/50 border border-outline-variant/10 rounded-lg">
                        <span class="text-xs font-medium truncate pr-2" :title="list.title">{{ list.title }}</span>
                        <label class="relative inline-flex items-center cursor-pointer shrink-0">
                          <input type="checkbox" :checked="!settings.catalogs.mdblist.hiddenPersonalListKeys.includes(list.key)" @change="emit('toggle-mdblist-personal-list', list.key, settings.catalogs.mdblist.hiddenPersonalListKeys.includes(list.key))" class="sr-only peer">
                          <div class="w-9 h-5 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                        </label>
                      </div>
                    </div>
                  </div>
              </div>

              <!-- Top Lists -->
              <div v-if="mdblistTopLists" :class="['mt-2 transition-all duration-300', expandedSections['mdblistTop'] ? '' : 'overflow-hidden']">
                  <button @click="toggleSection('mdblistTop')" class="w-full flex items-center justify-between border-b border-outline-variant/10 pb-3 group">
                    <div class="flex items-center gap-3">
                      <h3 class="text-[11px] font-bold uppercase tracking-[0.25em] text-on-surface-variant font-headline group-hover:text-on-surface transition-colors">Top Lists</h3>
                      <span class="text-[10px] text-outline italic">{{ mdblistTopLists.length }} discovered</span>
                    </div>
                    <span :class="['material-symbols-outlined text-outline transition-transform duration-300', expandedSections['mdblistTop'] ? 'rotate-180' : '']">expand_more</span>
                  </button>
                  <div :class="['pt-4 transition-all duration-300 ease-out overflow-hidden', expandedSections['mdblistTop'] ? 'max-h-[1000px] opacity-100' : 'max-h-0 opacity-0']">
                    <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 pb-4">
                      <div v-for="list in mdblistTopLists" :key="list.key" class="flex items-center justify-between p-3 bg-surface-container-low/50 border border-outline-variant/10 rounded-lg">
                        <span class="text-xs font-medium truncate pr-2" :title="list.title">{{ list.title }}</span>
                        <label class="relative inline-flex items-center cursor-pointer shrink-0">
                          <input type="checkbox" :checked="settings.catalogs.mdblist.selectedTopListKeys.includes(list.key)" @change="emit('toggle-mdblist-top-list', list.key, !settings.catalogs.mdblist.selectedTopListKeys.includes(list.key))" class="sr-only peer">
                          <div class="w-9 h-5 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                        </label>
                      </div>
                    </div>
                  </div>
              </div>

              <!-- Search Lists -->
              <div :class="['mt-2 transition-all duration-300', expandedSections['mdblistSearch'] ? '' : 'overflow-hidden']">
                  <button @click="toggleSection('mdblistSearch')" class="w-full flex items-center justify-between border-b border-outline-variant/10 pb-3 group">
                    <div class="flex items-center gap-3">
                      <h3 class="text-[11px] font-bold uppercase tracking-[0.25em] text-on-surface-variant font-headline group-hover:text-on-surface transition-colors">Search API Lists</h3>
                    </div>
                    <span :class="['material-symbols-outlined text-outline transition-transform duration-300', expandedSections['mdblistSearch'] ? 'rotate-180' : '']">expand_more</span>
                  </button>
                  <div :class="['pt-4 transition-all duration-300 ease-out overflow-hidden', expandedSections['mdblistSearch'] ? 'max-h-[1000px] opacity-100' : 'max-h-0 opacity-0']">
                    <div class="flex gap-2 mb-4">
                      <input v-model="mdblistSearchQuery" @keydown.enter="emit('search-mdblist-lists', mdblistSearchQuery)" class="flex-1 bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-2 text-sm placeholder:text-on-surface-variant/40" placeholder="Search for lists...">
                      <button @click="emit('search-mdblist-lists', mdblistSearchQuery)" class="px-4 py-2 bg-surface-container-high hover:bg-surface-bright rounded-lg border border-outline-variant/10 text-sm font-bold transition-colors">Search</button>
                    </div>
                    <div v-if="mdblistSearchResults && mdblistSearchResults.length > 0" class="grid grid-cols-1 sm:grid-cols-2 gap-3 pb-4">
                      <div v-for="list in mdblistSearchResults" :key="list.key" class="flex items-center justify-between p-3 bg-surface-container-low/50 border border-outline-variant/10 rounded-lg">
                        <div class="flex flex-col min-w-0 pr-2">
                           <span class="text-xs font-medium truncate" :title="list.title">{{ list.title }}</span>
                           <span class="text-[10px] text-on-surface-variant line-clamp-1 italic">by {{ list.owner }} • {{ list.itemCount }} items</span>
                        </div>
                        <label class="relative inline-flex items-center cursor-pointer shrink-0">
                          <input type="checkbox" :checked="settings.catalogs.mdblist.selectedTopListKeys.includes(list.key)" @change="emit('toggle-mdblist-top-list', list.key, !settings.catalogs.mdblist.selectedTopListKeys.includes(list.key))" class="sr-only peer">
                          <div class="w-9 h-5 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                        </label>
                      </div>
                    </div>
                    <div v-else-if="mdblistSearchResults && mdblistSearchResults.length === 0 && mdblistSearchQuery" class="text-center text-on-surface-variant text-xs italic py-4">No lists found.</div>
                  </div>
              </div>

            </div>

            <div class="p-6 bg-surface-container-low/50 border-t border-outline-variant/10 flex justify-end gap-4">
              <button @click="closeModal" class="px-6 py-2 rounded-lg text-sm font-bold text-on-surface-variant hover:text-white transition-colors">Close</button>
              <button @click="saveAndClose" class="px-8 py-2 rounded-lg text-sm font-bold bg-primary text-on-primary shadow-lg shadow-primary/20 hover:scale-[1.02] transition-transform">Save Configuration</button>
            </div>
          </div>

          <!-- Basic Modal Fallback for others -->
          <div v-else class="w-full max-w-lg bg-[#1a1919] border border-outline-variant/20 rounded-2xl overflow-hidden shadow-2xl flex flex-col relative" @click.stop>
            <div class="flex justify-between items-center p-6 border-b border-outline-variant/10 bg-surface-container-low/50">
              <div class="flex items-center gap-4">
                <div class="w-10 h-10 rounded-lg bg-surface-container-highest flex items-center justify-center border border-white/5">
                   <img v-if="activeModal === 'realdebrid'" src="/integrations/real-debrid.webp" class="w-6 h-6 object-contain" />
                   <img v-else-if="activeModal === 'premiumize'" src="/integrations/premiumize.svg" class="w-6 h-6 object-contain" />
                   <img v-else-if="activeModal === 'tmdb'" src="/integrations/tmdb.webp" class="w-6 h-6 object-contain" />
                   <span v-else-if="activeModal === 'omdb'" class="text-amber-300 font-black text-xs tracking-wide">OMDB</span>
                   <span v-else-if="activeModal === 'imdb'" class="text-white font-black text-[10px] tracking-[0.2em]">IMDb</span>
                   <img v-else-if="activeModal === 'animeskip'" src="/integrations/anime-skip.svg" class="w-6 h-6 object-contain" />
                   <img v-else-if="activeModal === 'gemini'" src="/integrations/gemini.webp" class="w-6 h-6 object-contain" />
                   <img v-else-if="activeModal === 'rpdb'" src="/integrations/rpdb.webp" class="w-6 h-6 object-contain" />
                   <img v-else-if="activeModal === 'topposters'" src="/integrations/topposter.svg" class="w-6 h-6 object-contain" />
                </div>
                <h2 class="text-xl font-headline font-bold text-white capitalize">
                  {{ activeModal === 'animeskip' ? 'Anime-Skip' : activeModal === 'topposters' ? 'TOP Posters' : activeModal === 'realdebrid' ? 'Real-Debrid' : activeModal === 'tmdb' ? 'TMDB' : activeModal === 'omdb' ? 'OMDB' : activeModal === 'imdb' ? 'IMDb' : activeModal === 'rpdb' ? 'RPDB' : activeModal === 'gemini' ? 'Google Gemini' : activeModal }} Settings
                </h2>
              </div>
              <button @click="closeModal" class="w-8 h-8 rounded-full hover:bg-surface-bright flex items-center justify-center"><span class="material-symbols-outlined text-sm">close</span></button>
            </div>
            <div class="p-6 space-y-4 max-h-[60vh] overflow-y-auto custom-scrollbar">

              <!-- Real Debrid Special Case -->
              <template v-if="activeModal === 'realdebrid'">
                <div v-if="!settings.integrations.debrid.realDebrid.connected && !settings.integrations.debrid.realDebrid.pending" class="text-center">
                  <p class="text-sm text-on-surface-variant mb-4">Connect your Real-Debrid account via device flow.</p>
                  <button @click="emit('start-realdebrid')" class="bg-secondary text-on-secondary px-6 py-2 rounded-lg font-bold hover:bg-secondary/80 transition-colors shadow-lg shadow-secondary/20">Start Device Auth</button>
                </div>
                <div v-else-if="settings.integrations.debrid.realDebrid.pending" class="text-center">
                   <p class="text-sm text-on-surface-variant mb-2">Visit <strong class="text-white">{{ settings.integrations.debrid.realDebrid.verificationUrl || 'https://real-debrid.com/device' }}</strong></p>
                   <p class="text-3xl font-mono text-secondary font-bold tracking-widest my-4">{{ settings.integrations.debrid.realDebrid.userCode }}</p>
                   <button @click="emit('complete-realdebrid')" class="bg-secondary text-on-secondary px-6 py-2 rounded-lg font-bold shadow-lg shadow-secondary/20">Check Status</button>
                </div>
                <div v-else class="text-center">
                   <p class="text-sm text-secondary font-bold mb-4">Account Connected</p>
                   <button @click="emit('disconnect-realdebrid')" class="bg-error/10 text-error px-6 py-2 rounded-lg font-bold hover:bg-error/20 transition-colors border border-error/20">Disconnect Account</button>
                </div>
              </template>

              <!-- Generic Content otherwise -->
              <template v-else>
                <!-- Generic Toggle -->
                <div class="flex items-center justify-between p-4 bg-surface-container rounded-xl border border-outline-variant/10" v-if="activeModal && hasEnableToggle(activeModal)">
                  <span class="text-sm font-bold capitalize">Enable Integration</span>
                  <label class="relative inline-flex items-center cursor-pointer">
                    <input type="checkbox" :checked="integrationEnabled(activeModal)" @change="toggleGenericIntegration(activeModal)" class="sr-only peer">
                    <div class="w-11 h-6 bg-surface-container-highest rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary"></div>
                  </label>
                </div>

                <div v-if="activeModal === 'imdb'" class="space-y-4">
                  <div class="p-4 bg-surface-container-low rounded-xl border border-outline-variant/10">
                    <p class="text-sm text-on-surface-variant">
                      Configure the primary IMDb episode-ratings provider used by the portal. The API key is stored as a secret and the base URL is synced with your account.
                    </p>
                  </div>

                  <div class="space-y-2">
                    <label class="flex justify-between items-center text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">
                      <span>Provider base URL</span>
                    </label>
                    <input
                      :value="settings.integrations.imdb.baseUrl"
                      @input="emit('update', 'integrations.imdb.baseUrl', ($event.target as HTMLInputElement).value)"
                      class="w-full bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm placeholder:text-on-surface-variant/40"
                      placeholder="https://ratings.example.com"
                    >
                    <p class="text-[10px] text-on-surface-variant">
                      Any trailing slash is normalized when the provider is validated.
                    </p>
                  </div>

                  <div class="space-y-2">
                    <label class="flex justify-between items-center text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">
                      <span>IMDb API key</span>
                    </label>
                    <input
                      :value="secretDrafts['integration:imdb'] || ''"
                      @input="emit('update-secret-draft', 'integration:imdb', ($event.target as HTMLInputElement).value)"
                      class="w-full bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm font-mono placeholder:text-on-surface-variant/40"
                      placeholder="Enter IMDb API key"
                    >
                    <div class="flex gap-2 flex-wrap">
                      <button class="px-4 py-1.5 bg-surface-bright hover:bg-surface-container-highest rounded border border-outline-variant/10 text-xs font-semibold active:scale-95 transition-all" @click="emit('save-secret', 'imdb_api_key', 'integration:imdb')">Save key</button>
                      <button v-if="secretStatuses['integration:imdb']" class="px-4 py-1.5 bg-surface-bright hover:bg-surface-container-highest rounded border border-outline-variant/10 text-xs font-semibold active:scale-95 transition-all text-error" @click="emit('delete-secret', 'imdb_api_key', 'integration:imdb')">Delete key</button>
                      <button class="px-4 py-1.5 bg-primary/10 hover:bg-primary/20 rounded border border-primary/20 text-xs font-semibold active:scale-95 transition-all text-primary disabled:opacity-60 disabled:cursor-wait" :disabled="imdbValidating" @click="emit('validate-imdb')">
                        {{ imdbValidating ? 'Validating...' : 'Validate provider' }}
                      </button>
                    </div>
                    <p v-if="secretStatuses['integration:imdb']?.maskedPreview" class="text-[10px] text-primary/70 mt-1">Stored: {{ secretStatuses['integration:imdb']?.maskedPreview }}</p>
                    <p v-if="imdbError" class="text-[10px] text-error mt-1">{{ imdbError }}</p>
                    <p v-else-if="imdbValid" class="text-[10px] text-emerald-400 mt-1">Provider validated successfully.</p>
                  </div>
                </div>

                <!-- Generic API Key Input -->
                <div class="space-y-2" v-if="activeModal && requiresSecret(activeModal) && activeModal !== 'imdb'">
                  <label class="flex justify-between items-center text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">
                    <span>API / Secret Key</span>
                    <a v-if="apiLinkMap[activeModal]" :href="apiLinkMap[activeModal]" target="_blank" class="text-primary hover:text-primary-dim lowercase tracking-normal flex items-center gap-1"><span class="material-symbols-outlined text-[12px]">open_in_new</span> get api key</a>
                  </label>
                  <input :value="secretDrafts['integration:' + activeModal] || ''" @input="emit('update-secret-draft', 'integration:' + activeModal, ($event.target as HTMLInputElement).value)" class="w-full bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm font-mono placeholder:text-on-surface-variant/40" placeholder="Enter token...">
                  <button class="px-4 py-1.5 bg-surface-bright hover:bg-surface-container-highest rounded border border-outline-variant/10 text-xs font-semibold mt-2 transition-colors" @click="saveGenericSecret(activeModal)">Save Token</button>
                  <p v-if="secretStatuses['integration:' + activeModal]?.maskedPreview" class="text-[10px] text-primary/70 mt-1">Stored: {{ secretStatuses['integration:' + activeModal]?.maskedPreview }}</p>
                </div>

                <!-- Anime-Skip Client ID Input -->
                <div class="space-y-2" v-if="activeModal === 'animeskip'">
                  <label class="flex justify-between items-center text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">
                    <span>Client ID</span>
                    <a :href="apiLinkMap['animeskip']" target="_blank" class="text-primary hover:text-primary-dim lowercase tracking-normal flex items-center gap-1"><span class="material-symbols-outlined text-[12px]">open_in_new</span> get api key</a>
                  </label>
                  <input :value="animeSkipClientId" @input="emit('update', 'integrations.animeSkip.clientId', ($event.target as HTMLInputElement).value)" class="w-full bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm font-mono placeholder:text-on-surface-variant/40" placeholder="Paste Anime Skip client ID...">
                </div>

                <div v-if="activeModal === 'tmdb' && tmdbFields && tmdbFields.length" class="pt-4 border-t border-outline-variant/10">
                  <h4 class="text-xs font-bold mb-3">Advanced Settings</h4>
                  <div class="grid grid-cols-2 sm:grid-cols-3 gap-2">
                    <button v-for="field in tmdbFields" :key="field.path" @click="emit('update', field.path, !fieldValue(settings, field.path))" :class="['px-3 py-2 rounded-lg text-[10px] font-bold transition-all border text-left', fieldValue(settings, field.path) ? 'bg-primary/10 text-primary border-primary/30' : 'bg-surface-container text-on-surface-variant border-outline-variant/10 hover:bg-surface-container-high']">
                      {{ field.label }}
                    </button>
                  </div>
                </div>
              </template>
            </div>

            <div class="p-6 bg-surface-container-low/50 border-t border-outline-variant/10 flex justify-end gap-4">
              <button @click="closeModal" class="px-6 py-2 rounded-lg text-sm font-bold text-on-surface-variant hover:text-white transition-colors">Done</button>
              <button @click="saveAndClose" class="px-8 py-2 rounded-lg text-sm font-bold bg-primary text-on-primary shadow-lg shadow-primary/20 hover:scale-[1.02] transition-transform">Save</button>
            </div>
          </div>

        </div>
      </transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import PortalField from '~/components/portal/PortalField.vue'
import { accountGroups, fieldValue, traktCatalogLabels, type PortalGroup } from '~/utils/portal-metadata'
import type { CatalogId, MDBListListOption, PortalSettings, SecretMetadata, SecretType, TraktDeviceFlow, TraktPopularListOption } from '~/types/portal'

const props = withDefaults(defineProps<{
  title: string
  subtitle: string
  groups: PortalGroup[]
  settings: PortalSettings
  traktFlow: TraktDeviceFlow | null
  traktPopularLists?: TraktPopularListOption[]
  traktSearchResults?: TraktPopularListOption[]
  mdblistPersonalLists?: MDBListListOption[]
  mdblistTopLists?: MDBListListOption[]
  mdblistSearchResults?: MDBListListOption[]
  secretStatuses?: Record<string, SecretMetadata>
  secretDrafts?: Record<string, string>
  mdblistValidating?: boolean
  mdblistError?: string | null
  imdbValidating?: boolean
  imdbValid?: boolean
  imdbError?: string | null
  showTrakt?: boolean
  showIntegrations?: boolean
  busy?: boolean
}>(), {
  traktPopularLists: () => [],
  traktSearchResults: () => [],
  mdblistPersonalLists: () => [],
  mdblistTopLists: () => [],
  mdblistSearchResults: () => [],
  secretStatuses: () => ({}),
  secretDrafts: () => ({})
})

const emit = defineEmits<{
  persist: []
  update: [path: string, value: unknown]
  'start-trakt': []
  'complete-trakt': []
  'start-realdebrid': []
  'complete-realdebrid': []
  'disconnect-realdebrid': []
  'save-premiumize-key': [apiKey: string]
  'clear-premiumize-key': []
  'refresh-premiumize': []
  'refresh-trakt-lists': []
  'disconnect-trakt': []
  'toggle-trakt-list': [key: string]
  'save-tmdb-key': [apiKey: string]
  'clear-tmdb-key': []
  'update-secret-draft': [secretRef: string, value: string]
  'save-secret': [secretType: SecretType, secretRef: string]
  'delete-secret': [secretType: SecretType, secretRef: string]
  'validate-mdblist': []
  'validate-imdb': []
  'toggle-mdblist-personal-list': [key: string, currentlyHidden: boolean]
  'toggle-mdblist-top-list': [key: string, shouldSelect: boolean]
  'search-trakt-lists': [query: string]
  'search-mdblist-lists': [query: string]
}>()

const activeModal = ref<string | null>(null)
const traktSearchQuery = ref('')
const mdblistSearchQuery = ref('')

const expandedSections = ref<Record<string, boolean>>({
  mdblistRatings: false,
  mdblistPersonal: false,
  mdblistTop: false,
  mdblistSearch: false,
  traktBuiltIn: false,
  traktPopular: false,
  traktSearch: false
})

function toggleSection(section: string) {
  expandedSections.value[section] = !expandedSections.value[section]
}

function closeModal() {
  activeModal.value = null
}

function saveAndClose() {
  emit('persist')
  closeModal()
}

const isConfigured = computed(() => {
  return {
    trakt: props.settings.integrations.traktAuth.connected,
    realdebrid: props.settings.integrations.debrid.realDebrid.connected || props.settings.integrations.debrid.realDebrid.pending,
    premiumize: !!props.settings.integrations.debrid.premiumize.customerId || !!props.secretStatuses['integration:premiumize'],
    tmdb: props.settings.integrations.tmdb.enabled || !!props.secretStatuses['integration:tmdb'],
    omdb: props.settings.integrations.omdb.enabled || !!props.secretStatuses['integration:omdb'],
    imdb:
      props.settings.integrations.imdb.enabled ||
      !!props.settings.integrations.imdb.baseUrl.trim() ||
      !!props.secretStatuses['integration:imdb'],
    mdblist: props.settings.integrations.mdblist.enabled || !!props.secretStatuses['integration:mdblist'],
    animeskip: props.settings.integrations.animeSkip.enabled,
    gemini: props.settings.integrations.gemini.enabled || !!props.secretStatuses['integration:gemini'],
    rpdb: props.settings.integrations.posterRatings.rpdbEnabled || !!props.secretStatuses['integration:rpdb'],
    topposters: props.settings.integrations.posterRatings.topPostersEnabled || !!props.secretStatuses['integration:topposters']
  }
})

const hasEnableToggle = (id: string) => ['tmdb', 'omdb', 'imdb', 'mdblist', 'animeskip', 'gemini'].includes(id)
const requiresSecret = (id: string) => ['tmdb', 'omdb', 'imdb', 'mdblist', 'gemini', 'premiumize', 'rpdb', 'topposters'].includes(id)

function integrationEnabled(id: string) {
  if (id === 'tmdb') return props.settings.integrations.tmdb.enabled
  if (id === 'omdb') return props.settings.integrations.omdb.enabled
  if (id === 'imdb') return props.settings.integrations.imdb.enabled
  if (id === 'mdblist') return props.settings.integrations.mdblist.enabled
  if (id === 'animeskip') return props.settings.integrations.animeSkip.enabled
  if (id === 'gemini') return props.settings.integrations.gemini.enabled
  if (id === 'rpdb') return props.settings.integrations.posterRatings.rpdbEnabled
  if (id === 'topposters') return props.settings.integrations.posterRatings.topPostersEnabled
  return false
}

function toggleGenericIntegration(id: string) {
  if (id === 'tmdb') emit('update', 'integrations.tmdb.enabled', !props.settings.integrations.tmdb.enabled)
  else if (id === 'omdb') emit('update', 'integrations.omdb.enabled', !props.settings.integrations.omdb.enabled)
  else if (id === 'imdb') emit('update', 'integrations.imdb.enabled', !props.settings.integrations.imdb.enabled)
  else if (id === 'animeskip') emit('update', 'integrations.animeSkip.enabled', !props.settings.integrations.animeSkip.enabled)
  else if (id === 'gemini') emit('update', 'integrations.gemini.enabled', !props.settings.integrations.gemini.enabled)
  else if (id === 'rpdb') emit('update', 'integrations.posterRatings.rpdbEnabled', !props.settings.integrations.posterRatings.rpdbEnabled)
  else if (id === 'topposters') emit('update', 'integrations.posterRatings.topPostersEnabled', !props.settings.integrations.posterRatings.topPostersEnabled)
}

function saveGenericSecret(id: string) {
  if (id === 'tmdb') emit('save-secret', 'tmdb_api_key', 'integration:tmdb')
  if (id === 'gemini') emit('save-secret', 'gemini_api_key', 'integration:gemini')
  if (id === 'omdb') emit('save-secret', 'omdb_api_key', 'integration:omdb')
  if (id === 'imdb') emit('save-secret', 'imdb_api_key', 'integration:imdb')
  if (id === 'mdblist') emit('save-secret', 'mdblist_api_key', 'integration:mdblist')
  if (id === 'rpdb') emit('save-secret', 'rpdb_api_key', 'integration:rpdb')
  if (id === 'topposters') emit('save-secret', 'top_posters_api_key', 'integration:topposters')
  if (id === 'premiumize') {
     const val = props.secretDrafts['integration:premiumize']
     if(val) emit('save-premiumize-key', val)
  }
}

const tmdbFields = accountGroups?.integrations
  ?.find((group) => group.id === 'tmdb')
  ?.fields.filter((field) => field.path !== 'integrations.tmdb.enabled') ?? []

const mdblistFields = accountGroups?.integrations
  ?.find((group) => group.id === 'mdblist')
  ?.fields.filter((field) => !['integrations.mdblist.enabled', 'catalogs.mdblist.hiddenPersonalListKeys', 'catalogs.mdblist.selectedTopListKeys', 'catalogs.mdblist.catalogOrder'].includes(field.path)) ?? []

const animeSkipClientId = computed(() => {
  const value = fieldValue(props.settings, 'integrations.animeSkip.clientId')
  return typeof value === 'string' ? value : ''
})

const apiLinkMap: Record<string, string> = {
  gemini: 'https://aistudio.google.com/api-keys',
  premiumize: 'https://www.premiumize.me/account',
  rpdb: 'https://ratingposterdb.com/login',
  topposters: 'https://api.top-streaming.stream/user/dashboard',
  tmdb: 'https://www.themoviedb.org/settings/api',
  omdb: 'https://www.omdbapi.com/apikey.aspx',
  imdb: 'https://www.imdb.com/',
  mdblist: 'https://mdblist.com/preferences/',
  animeskip: 'https://anime-skip.com/account/api-clients'
}

function toggleCatalog(catalog: CatalogId) {
  const enabled = props.settings.catalogs.trakt.catalogEnabledSet.includes(catalog)
  const next = enabled
    ? props.settings.catalogs.trakt.catalogEnabledSet.filter((entry) => entry !== catalog)
    : [...props.settings.catalogs.trakt.catalogEnabledSet, catalog]

  emit('update', 'catalogs.trakt.catalogEnabledSet', next)
}
watch(activeModal, (newVal) => {
  if (newVal === 'trakt' && props.settings.integrations.traktAuth.connected) {
    emit('refresh-trakt-lists')
  } else if (newVal === 'mdblist') {
    emit('validate-mdblist')
  }
})

watch(() => props.settings.integrations.traktAuth.connected, (connected) => {
  if (connected && activeModal.value === 'trakt') {
    emit('refresh-trakt-lists')
  }
})

onMounted(() => {
  if (props.settings.integrations.traktAuth.connected) {
    emit('refresh-trakt-lists')
  }
  if (props.settings.integrations.mdblist.enabled || Object.keys(props.secretStatuses).some(k => k.includes('mdblist'))) {
    emit('validate-mdblist')
  }
})
</script>
<style scoped>
.glass-card {
  background: rgba(38, 38, 38, 0.6);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
}
.custom-scrollbar::-webkit-scrollbar {
  width: 4px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #494847;
  border-radius: 10px;
}
</style>
