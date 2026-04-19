-- Add Kitsu OAuth token secret types to the account sync secret contract.
-- Public settings store username/auth state only; Kitsu passwords are never persisted.

alter table public.account_secrets
  drop constraint if exists account_secrets_secret_type_check;

alter table public.account_secrets
  add constraint account_secrets_secret_type_check
  check (
    secret_type = any (array[
      'addon_credential',
      'tmdb_api_key',
      'tvdb_api_key',
      'omdb_api_key',
      'imdb_api_key',
      'mdblist_api_key',
      'premiumize_api_key',
      'torbox_api_key',
      'easydebrid_api_key',
      'gemini_api_key',
      'translation_api_key',
      'rpdb_api_key',
      'top_posters_api_key',
      'realdebrid_access_token',
      'realdebrid_refresh_token',
      'simkl_access_token',
      'kitsu_access_token',
      'kitsu_refresh_token',
      'trakt_access_token',
      'trakt_refresh_token'
    ])
  );
