<template>
  <div class="space-y-6">
    <!-- General -->
    <SettingsSection title="General" description="Basic player and UI behavior during playback.">
      <SettingRow label="Skip Intro Enabled" description="Automatically skip intros when detected.">
        <BaseToggle v-model="settings.playback.general.skipIntroEnabled" />
      </SettingRow>
      
      <SettingRow label="Pause Overlay Enabled" description="Show info overlay when media is paused.">
        <BaseToggle v-model="settings.playback.general.pauseOverlayEnabled" />
      </SettingRow>

      <SettingRow label="Frame Rate Matching">
        <BaseSelect v-model="settings.playback.general.frameRateMatchingMode" :options="frameRateOptions" />
      </SettingRow>
    </SettingsSection>

    <!-- Stream Selection -->
    <SettingsSection title="Stream Selection" description="Configure stream resolution, auto-play, and binge watching.">
      <SettingRow label="Player Preference" description="Choose standard EXOPLAYER or an external app.">
        <BaseSelect v-model="settings.playback.streamSelection.playerPreference" :options="playerOptions" />
      </SettingRow>

      <SettingRow label="Auto-Play Mode" description="Automatically select a stream for TV shows.">
        <BaseSelect v-model="settings.playback.streamSelection.streamAutoPlayMode" :options="autoPlayOptions" />
      </SettingRow>

      <SettingRow label="Auto-Play Next Episode">
        <BaseToggle v-model="settings.playback.streamSelection.streamAutoPlayNextEpisodeEnabled" />
      </SettingRow>

      <template v-if="settings.playback.streamSelection.streamAutoPlayNextEpisodeEnabled">
        <SettingRow label="Threshold Mode" description="When to trigger the next episode in the player.">
          <BaseSelect v-model="settings.playback.streamSelection.nextEpisodeThresholdMode" :options="thresholdOptions" />
        </SettingRow>

        <SettingRow 
          v-if="settings.playback.streamSelection.nextEpisodeThresholdMode === 'PERCENTAGE'" 
          label="Next Episode Trigger (%)" 
          description="Percentage of runtime to hit before playing next."
        >
          <BaseSlider 
            v-model="settings.playback.streamSelection.nextEpisodeThresholdPercent" 
            :min="97.0" 
            :max="99.5" 
            :step="0.5" 
          />
        </SettingRow>

        <SettingRow 
          v-else 
          label="Next Episode Trigger (Minutes)" 
          description="Minutes before end credits to play next."
        >
          <BaseSlider 
            v-model="settings.playback.streamSelection.nextEpisodeThresholdMinutesBeforeEnd" 
            :min="1.0" 
            :max="3.5" 
            :step="0.5" 
          />
        </SettingRow>
      </template>
    </SettingsSection>

    <!-- Subtitles -->
    <SettingsSection title="Subtitles" description="Configure subtitle appearance.">
      <SettingRow label="Preferred Language">
        <div class="w-32">
          <input 
            v-model="settings.playback.subtitles.preferredLanguage" 
            type="text" 
            placeholder="e.g. en" 
            class="block w-full rounded-lg border border-zinc-800 bg-zinc-900/50 px-3 py-1.5 text-sm text-zinc-200 focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"
          />
        </div>
      </SettingRow>

      <SettingRow label="Vertical Offset" description="Move subtitles up or down (-20 to 50)">
        <BaseSlider 
          v-model="settings.playback.subtitles.verticalOffset" 
          :min="-20" 
          :max="50" 
          :step="1" 
        />
      </SettingRow>
      
      <SettingRow label="Subtitle Size" description="Size relative to screen (50 to 200)">
        <BaseSlider 
          v-model="settings.playback.subtitles.size" 
          :min="50" 
          :max="200" 
          :step="10" 
        />
      </SettingRow>
    </SettingsSection>

    <!-- Buffer & Network -->
    <SettingsSection title="Buffer & Network" description="Advanced network settings for optimized streaming.">
      <SettingRow label="Max Buffer Duration (ms)">
        <BaseSlider 
          v-model="settings.playback.bufferNetwork.maxBufferMs" 
          :min="5000" 
          :max="120000" 
          :step="5000" 
        />
      </SettingRow>

      <SettingRow label="VOD Cache Size (MB)">
        <BaseSlider 
          v-model="settings.playback.bufferNetwork.vodCacheSizeMb" 
          :min="100" 
          :max="65536" 
          :step="100" 
        />
      </SettingRow>

      <SettingRow label="Parallel Connections">
        <BaseToggle v-model="settings.playback.bufferNetwork.useParallelConnections" />
      </SettingRow>
    </SettingsSection>
  </div>
</template>

<script setup lang="ts">
import { useSettings } from '~/composables/useSettings'
import SettingsSection from './SettingsSection.vue'
import SettingRow from './SettingRow.vue'
import BaseToggle from './BaseToggle.vue'
import BaseSelect from './BaseSelect.vue'
import BaseSlider from './BaseSlider.vue'

const { settings } = useSettings()

const frameRateOptions = [
  { label: 'OFF', value: 'OFF' },
  { label: 'Start Only', value: 'START' },
  { label: 'Start and Stop', value: 'START_STOP' },
]

const playerOptions = [
  { label: 'Internal ExoPlayer', value: 'INTERNAL' },
  { label: 'External App', value: 'EXTERNAL' },
  { label: 'Ask Every Time', value: 'ASK_EVERY_TIME' },
]

const autoPlayOptions = [
  { label: 'Manual Selection', value: 'MANUAL' },
  { label: 'Play First Result', value: 'FIRST_STREAM' },
  { label: 'Regex Parsing Match', value: 'REGEX_MATCH' },
]

const thresholdOptions = [
  { label: 'Percentage of Runtime', value: 'PERCENTAGE' },
  { label: 'Minutes Before End', value: 'MINUTES_BEFORE_END' },
]
</script>
