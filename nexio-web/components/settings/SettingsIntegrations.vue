<template>
  <div class="space-y-6">
    <!-- TMDB -->
    <SettingsSection title="The Movie Database (TMDB)" description="Configure metadata fetched from TMDB.">
      <template #header-action>
        <BaseToggle v-model="settings.integrations.tmdb.enabled" />
      </template>
      
      <div v-if="settings.integrations.tmdb.enabled">
        <SettingRow label="Language" description="Preferred language for TMDB metadata.">
          <BaseSelect v-model="settings.integrations.tmdb.language" :options="languageOptions" />
        </SettingRow>
        
        <SettingRow label="Fetch Artwork" description="Download rich posters and backdrops.">
          <BaseToggle v-model="settings.integrations.tmdb.useArtwork" />
        </SettingRow>

        <SettingRow label="Fetch Credits" description="Include cast and crew information.">
          <BaseToggle v-model="settings.integrations.tmdb.useCredits" />
        </SettingRow>
      </div>
    </SettingsSection>

    <!-- MDBList -->
    <SettingsSection title="MDBList Integration" description="Enhance discovery with MDBList ratings and catalogs.">
      <template #header-action>
        <BaseToggle v-model="settings.integrations.mdblist.enabled" />
      </template>
      
      <div v-if="settings.integrations.mdblist.enabled">
        <SettingRow label="Secret Storage" description="MDBList API keys now live in the account secret channel, not the public settings payload." />

        <SettingRow label="Show Trakt Ratings">
          <BaseToggle v-model="settings.integrations.mdblist.showTrakt" />
        </SettingRow>
      </div>
    </SettingsSection>

    <!-- Anime-Skip -->
    <SettingsSection title="Anime-Skip" description="Automatically skip anime intros and outros.">
      <template #header-action>
        <BaseToggle v-model="settings.integrations.animeSkip.enabled" />
      </template>
      
      <div v-if="settings.integrations.animeSkip.enabled">
        <SettingRow label="Client ID" description="Your registered Anime-Skip developer client ID.">
          <input
            v-model="settings.integrations.animeSkip.clientId"
            type="text"
            placeholder="Anime-Skip Client ID"
            class="block w-full max-w-xs rounded-lg border border-zinc-800 bg-zinc-900/50 px-4 py-2 text-sm text-zinc-200 placeholder-zinc-500 focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
          />
        </SettingRow>
      </div>
    </SettingsSection>

    <!-- Poster Ratings -->
    <SettingsSection title="Poster Ratings" description="Overlay community ratings directly on posters.">
      <SettingRow label="RPDB Enabled" description="Use Rating Poster Database. Mutually exclusive with TOPPosters.">
        <BaseToggle v-model="settings.integrations.posterRatings.rpdbEnabled" />
      </SettingRow>
      
      <SettingRow v-if="settings.integrations.posterRatings.rpdbEnabled" label="RPDB Key" description="Stored through the account secret channel." />
      
      <SettingRow label="TOPPosters Enabled" description="Use TOPPosters Database. Mutually exclusive with RPDB.">
        <BaseToggle v-model="settings.integrations.posterRatings.topPostersEnabled" />
      </SettingRow>

      <SettingRow v-if="settings.integrations.posterRatings.topPostersEnabled" label="TOPPosters Key" description="Stored through the account secret channel." />
    </SettingsSection>
  </div>
</template>

<script setup lang="ts">
import { useSettings } from '~/composables/useSettings'
import SettingsSection from './SettingsSection.vue'
import SettingRow from './SettingRow.vue'
import BaseToggle from './BaseToggle.vue'
import BaseSelect from './BaseSelect.vue'

const { settings } = useSettings()

const languageOptions = [
  { label: 'English', value: 'en' },
  { label: 'Spanish', value: 'es' },
  { label: 'French', value: 'fr' },
  { label: 'German', value: 'de' },
  { label: 'Japanese', value: 'ja' },
]
</script>
