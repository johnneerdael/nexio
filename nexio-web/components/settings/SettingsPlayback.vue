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
    </SettingsSection>

    <!-- Stream Selection -->
    <SettingsSection title="Stream Selection" description="Configure stream resolution, auto-play, and binge watching.">
      <SettingRow label="Player Preference" description="Choose standard EXOPLAYER or an external app.">
        <BaseSelect v-model="settings.playback.streamSelection.playerPreference" :options="playerOptions" />
      </SettingRow>

      <SettingRow label="Uniform Stream Formatting" description="Use the normalized stream parser and formatter across addons.">
        <BaseToggle v-model="settings.playback.streamSelection.uniformStreamFormattingEnabled" />
      </SettingRow>

      <SettingRow label="Group Streams Across Addons" description="Show one merged stream list instead of addon tabs.">
        <BaseToggle v-model="settings.playback.streamSelection.groupStreamsAcrossAddonsEnabled" />
      </SettingRow>

      <SettingRow label="Deduplicate Grouped Streams" description="Hide likely duplicates when grouped mode is enabled.">
        <BaseToggle
          v-model="settings.playback.streamSelection.deduplicateGroupedStreamsEnabled"
          :disabled="!settings.playback.streamSelection.groupStreamsAcrossAddonsEnabled"
        />
      </SettingRow>

      <SettingRow label="Filter WEB-DL Dolby Vision" description="Hide WEB-DL streams tagged DV or DoVi.">
        <BaseToggle v-model="settings.playback.streamSelection.filterWebDolbyVisionStreamsEnabled" />
      </SettingRow>

      <SettingRow label="Filter Wrong Episodes" description="Hide episodic streams whose parsed season or episode does not match the requested episode.">
        <BaseToggle v-model="settings.playback.streamSelection.filterEpisodeMismatchStreamsEnabled" />
      </SettingRow>

      <SettingRow label="Filter Wrong Movie Year" description="Hide movie-like streams whose parsed year does not match the requested title year.">
        <BaseToggle v-model="settings.playback.streamSelection.filterMovieYearMismatchStreamsEnabled" />
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

    <!-- Video & Audio -->
    <SettingsSection title="Video & Audio" description="Video compatibility and audio controls.">
      <div class="space-y-4">
        <div class="text-xs font-semibold uppercase tracking-[0.2em] text-zinc-500">Video</div>

        <SettingRow label="Frame Rate Matching" description="Switch refresh rate to match the content.">
          <BaseSelect v-model="settings.playback.general.frameRateMatchingMode" :options="frameRateOptions" />
        </SettingRow>

        <SettingRow label="Resolution Matching" description="Allow the app to switch output resolution when needed.">
          <BaseToggle v-model="settings.playback.general.resolutionMatchingEnabled" />
        </SettingRow>

        <SettingRow label="Tunneled Playback" description="Enable tunneling on supported devices.">
          <BaseToggle v-model="settings.playback.audio.tunnelingEnabled" />
        </SettingRow>

        <SettingRow label="DV7 - Experimental DV8.1" description="Use the experimental DV7 to DV8.1 conversion path.">
          <BaseToggle v-model="settings.playback.audio.experimentalDv7ToDv81Enabled" />
        </SettingRow>

        <SettingRow label="DV7 - Preserve Mapping" description="Keep Dolby Vision mapping metadata when DV8.1 conversion is enabled.">
          <BaseToggle
            v-model="settings.playback.audio.experimentalDv7ToDv81PreserveMappingEnabled"
            :disabled="!settings.playback.audio.experimentalDv7ToDv81Enabled"
          />
        </SettingRow>

        <SettingRow label="DV5 - Compatibility Remap" description="Use the DV5 compatibility remap path when DV8.1 conversion is enabled.">
          <BaseToggle
            v-model="settings.playback.audio.experimentalDv5ToDv81Enabled"
            :disabled="!settings.playback.audio.experimentalDv7ToDv81Enabled"
          />
        </SettingRow>
      </div>

      <div class="space-y-4 pt-4">
        <div class="text-xs font-semibold uppercase tracking-[0.2em] text-zinc-500">Audio</div>

        <SettingRow label="Decoder Priority" description="Renderer preference for playback codecs.">
          <BaseSelect v-model="settings.playback.audio.decoderPriority" :options="decoderPriorityOptions" />
        </SettingRow>

        <SettingRow label="Fire OS - Experimental Audio Compatibility" description="Enable the Fire OS experimental IEC compatibility path.">
          <BaseToggle v-model="settings.playback.audio.experimentalDtsIecPassthroughEnabled" />
        </SettingRow>
      </div>
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

const decoderPriorityOptions = [
  { label: 'Device only', value: 0 },
  { label: 'Prefer device', value: 1 },
  { label: 'Prefer app', value: 2 },
]

const thresholdOptions = [
  { label: 'Percentage of Runtime', value: 'PERCENTAGE' },
  { label: 'Minutes Before End', value: 'MINUTES_BEFORE_END' },
]
</script>
