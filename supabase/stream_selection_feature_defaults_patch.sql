update public.account_settings_public
set settings_payload = jsonb_set(
  jsonb_set(
    jsonb_set(
      jsonb_set(
        settings_payload,
        '{playback,streamSelection,uniformStreamFormattingEnabled}',
        coalesce(settings_payload #> '{playback,streamSelection,uniformStreamFormattingEnabled}', 'false'::jsonb),
        true
      ),
      '{playback,streamSelection,groupStreamsAcrossAddonsEnabled}',
      coalesce(settings_payload #> '{playback,streamSelection,groupStreamsAcrossAddonsEnabled}', 'false'::jsonb),
      true
    ),
    '{playback,streamSelection,deduplicateGroupedStreamsEnabled}',
    coalesce(settings_payload #> '{playback,streamSelection,deduplicateGroupedStreamsEnabled}', 'false'::jsonb),
    true
  ),
  '{playback,streamSelection,filterWebDolbyVisionStreamsEnabled}',
  coalesce(settings_payload #> '{playback,streamSelection,filterWebDolbyVisionStreamsEnabled}', 'false'::jsonb),
  true
)
where true;
