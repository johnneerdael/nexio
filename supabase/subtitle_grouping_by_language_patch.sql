-- Incremental patch to normalize synced subtitle grouping for existing accounts.
-- Safe to run multiple times.

update public.account_settings_public
set settings_payload = jsonb_set(
  jsonb_set(
    jsonb_set(
      coalesce(settings_payload, '{}'::jsonb),
      '{playback}',
      coalesce(settings_payload->'playback', '{}'::jsonb),
      true
    ),
    '{playback,subtitles}',
    coalesce(settings_payload#>'{playback,subtitles}', '{}'::jsonb),
    true
  ),
  '{playback,subtitles,subtitleOrganizationMode}',
  to_jsonb('BY_LANGUAGE'::text),
  true
)
where settings_payload#>>'{playback,subtitles,subtitleOrganizationMode}' is distinct from 'BY_LANGUAGE';
