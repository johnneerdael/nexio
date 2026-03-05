# NEXIO Settings Inventory

## Hierarchy
- Settings
  - Account
  - Appearance
  - Layout
  - Plugins
  - Integration
    - TMDB
    - MDBList
    - Anime-Skip
    - Poster Ratings (RPDB/TOPPosters)
  - Playback
    - General
    - Stream Selection
    - Audio & Trailer
    - Subtitles
    - Buffer & Network
  - Trakt
  - About
  - Debug (debug builds only)

## Appearance
- theme (`CRIMSON|OCEAN|VIOLET|EMERALD|AMBER|ROSE|WHITE`, default `WHITE`)
- font (`INTER|DM_SANS|OPEN_SANS`, default `INTER`)
- locale_tag (nullable, default `null` meaning system)

## Layout
- selected_layout (`MODERN|GRID|CLASSIC`, default `MODERN`)
- modern_landscape_posters_enabled (default `false`)
- hero_catalog_keys (string[])
- sidebar_collapsed_by_default (default `false`)
- modern_sidebar_enabled (default `false`)
- modern_sidebar_blur_enabled (default `false`)
- hero_section_enabled (default `true`)
- search_discover_enabled (default `true`)
- poster_labels_enabled (default `true`)
- catalog_addon_name_enabled (default `true`)
- catalog_type_suffix_enabled (default `true`)
- hide_unreleased_content (default `false`)
- blur_unwatched_episodes (default `false`)
- prefer_external_meta_addon_detail (default `false`)
- focused_poster_backdrop_expand_enabled (default `false`)
- focused_poster_backdrop_expand_delay_seconds (`0..10`, default `3`)
- poster_card_width_dp (`104|112|120|126|134|140`, default `126`)
- poster_card_corner_radius_dp (`0|4|8|12|16`, default `12`)

## Plugins
- plugins_enabled (default `true`)
- repositories (managed list)
- scraper enabled states (managed list)

## Integration / TMDB
- tmdb_enabled (default `false`)
- tmdb_language (default `en`)
- tmdb_use_artwork (default `true`)
- tmdb_use_basic_info (default `true`)
- tmdb_use_details (default `true`)
- tmdb_use_credits (default `true`)
- tmdb_use_productions (default `true`)
- tmdb_use_networks (default `true`)
- tmdb_use_episodes (default `true`)
- tmdb_use_more_like_this (default `true`)
- tmdb_use_collections (default `true`)

## Integration / MDBList
- mdblist_enabled (default `false`)
- mdblist_api_key
- mdblist_show_trakt (default `true`)
- mdblist_show_imdb (default `true`)
- mdblist_show_tmdb (default `true`)
- mdblist_show_letterboxd (default `true`)
- mdblist_show_tomatoes (default `true`)
- mdblist_show_audience (default `true`)
- mdblist_show_metacritic (default `true`)
- mdblist_hidden_personal_list_keys (string[])
- mdblist_selected_top_list_keys (string[])
- mdblist_catalog_order_csv (ordered list keys)

## Integration / Anime-Skip
- animeskip_enabled (default `false`)
- animeskip_client_id

## Integration / Poster Ratings
- poster_ratings_rpdb_enabled (default `false`)
- poster_ratings_rpdb_api_key
- poster_ratings_top_enabled (default `false`)
- poster_ratings_top_api_key
- Rule: RPDB and TOPPosters are mutually exclusive.

## Playback / General
- loading_overlay_enabled (default `true`)
- pause_overlay_enabled (default `true`)
- osd_clock_enabled (default `true`)
- skip_intro_enabled (default `true`)
- frame_rate_matching_mode (`OFF|START|START_STOP`, default `OFF`)
- resolution_matching_enabled (default `false`)

## Playback / Stream Selection
- player_preference (`INTERNAL|EXTERNAL|ASK_EVERY_TIME`, default `INTERNAL`)
- stream_reuse_last_link_enabled (default `false`)
- stream_reuse_last_link_cache_hours (`1|6|12|24|48|72|168`, default `24`)
- stream_auto_play_mode (`MANUAL|FIRST_STREAM|REGEX_MATCH`, default `MANUAL`)
- stream_auto_play_source (`ALL_SOURCES|INSTALLED_ADDONS_ONLY|ENABLED_PLUGINS_ONLY`, default `ALL_SOURCES`)
- stream_auto_play_selected_addons (string[])
- stream_auto_play_selected_plugins (string[])
- stream_auto_play_regex
- stream_auto_play_next_episode_enabled (default `false`)
- stream_auto_play_prefer_binge_group_for_next_episode (default `true`)
- next_episode_threshold_mode (`PERCENTAGE|MINUTES_BEFORE_END`, default `PERCENTAGE`)
- next_episode_threshold_percent (`97.0..99.5 step 0.5`, default `99.0`)
- next_episode_threshold_minutes_before_end (`1.0..3.5 step 0.5`, default `2.0`)

## Playback / Audio & Trailer
- preferred_audio_language (default `device`)
- secondary_preferred_audio_language (nullable)
- skip_silence (default `false`)
- decoder_priority (`0..2`, default `1`)
- tunneling_enabled (default `false`)
- map_dv7_to_hevc (default `false`)
- experimental_dv7_to_dv81_enabled (default `false`)
- experimental_dts_iec_passthrough_enabled (default `false`)
- experimental_dv7_to_dv81_preserve_mapping_enabled (default `false`, depends on dv7->dv81 enabled)
- experimental_dv5_to_dv81_enabled (default `false`, depends on dv7->dv81 enabled)

## Playback / Subtitles
- subtitle_preferred_language (default `en`)
- subtitle_secondary_language (nullable)
- subtitle_organization_mode (`NONE|BY_LANGUAGE|BY_ADDON`, default `NONE`)
- addon_subtitle_startup_mode (`FAST_STARTUP|PREFERRED_ONLY|ALL_SUBTITLES`, default `ALL_SUBTITLES`)
- subtitle_size (`50..200 step 10`, default `120`)
- subtitle_vertical_offset (`-20..50`, default `5`)
- subtitle_bold (default `false`)
- subtitle_text_color (ARGB int, default white)
- subtitle_background_color (ARGB int, default transparent)
- subtitle_outline_enabled (default `true`)
- subtitle_outline_color (ARGB int, default black)
- use_libass (default `false`, currently disabled in UI)

## Playback / Buffer & Network
- min_buffer_ms (`5000..120000`, default `20000`)
- max_buffer_ms (`5000..120000`, default `50000`, should be `>= min_buffer_ms`)
- buffer_for_playback_ms (`1000..60000`, default `3000`)
- buffer_for_playback_after_rebuffer_ms (`1000..120000`, default `5000`)
- target_buffer_size_mb (default `100`)
- back_buffer_duration_ms (`0..120000`, default `0`)
- vod_cache_size_mode (`AUTO|MANUAL`, default `AUTO`)
- vod_cache_size_mb (`100..65536`, default `500`)
- use_parallel_connections (default `false`)
- parallel_connection_count (`2..4`, default `2`)
- parallel_chunk_size_mb (`8..128`, default `16`)
- enable_buffer_logs (default `false`)

## Trakt
- continue_watching_days_cap (`0|14|30|60|90|180|365`, default `60`)
- show_unaired_next_up (default `true`)
- catalog_enabled_set (built-in catalog ids)
- catalog_order_csv (ordered built-in catalog ids)
- selected_popular_list_keys (string[])

Built-in Trakt catalog ids:
- trakt_up_next
- trakt_trending_movies
- trakt_trending_shows
- trakt_popular_movies
- trakt_popular_shows
- trakt_recommended_movies
- trakt_recommended_shows
- trakt_calendar_next_7_days

## Debug
- account_tab_enabled (default `false`)
- sync_code_features_enabled (default `false`)
- buffer_logs_enabled (default `false`, mapped to player setting)
