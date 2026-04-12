# Supabase Settings Sync Guide

## Account Config Contract v7

Contract v7 prevents stale full-payload overwrites by using optimistic concurrency.

- Pull returns the latest global `revision` plus a settings-specific `settings_revision`.
- Web and Android store the last applied settings revision.
- Web and Android send changed account-config paths with each settings push.
- `sync_push_account_settings_v7` merges changed paths when no overlapping path changed after the client's base revision.
- `sync_push_account_settings_v7` returns `applied=false` when the same path, a parent path, or a child path changed elsewhere after the client's base revision.
- Untracked post-base `settings_public` writes, including older v6 writes, force a conservative conflict.
- Older contract versions keep using `sync_push_account_settings` and are not affected by v7.

This makes Supabase the source of truth for the newest accepted server revision. It rejects stale overlapping writes instead of letting a delayed client overwrite newer settings.

Subtitle translation providers share one secret slot: `translation_api_key` at `integration:subtitle-translation`. DashScope/Qwen-MT uses that generic secret just like OpenAI-compatible, Anthropic-compatible, and Gemini translation providers; provider selection lives in `integrations.subtitleTranslation.provider`.
