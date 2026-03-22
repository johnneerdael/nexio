update public.account_settings_public
set settings_payload =
  settings_payload
    #- '{playback,general,libmpvVideoOutputMode}'
    #- '{playback,general,libmpvGpuNextDolbyVisionReshapingEnabled}'
    #- '{playback,streamSelection,playerPreference}'
    #- '{playback,streamSelection,filterWebDolbyVisionStreamsEnabled}'
    #- '{playback,audio,libmpvAudioPassthroughEnabled}'
    #- '{playback,audio,experimentalDv7ToDv81Enabled}'
    #- '{playback,audio,experimentalDv7ToDv81PreserveMappingEnabled}'
    #- '{playback,audio,experimentalDtsIecPassthroughEnabled}'
    #- '{playback,audio,experimentalDv5ToDv81Enabled}'
where settings_payload ? 'playback';
