update public.account_settings_public
set settings_payload = jsonb_set(
  jsonb_set(
    settings_payload,
    '{playback,streamSelection,filterEpisodeMismatchStreamsEnabled}',
    coalesce(settings_payload #> '{playback,streamSelection,filterEpisodeMismatchStreamsEnabled}', 'false'::jsonb),
    true
  ),
  '{playback,streamSelection,filterMovieYearMismatchStreamsEnabled}',
  coalesce(settings_payload #> '{playback,streamSelection,filterMovieYearMismatchStreamsEnabled}', 'false'::jsonb),
  true
)
where true;
