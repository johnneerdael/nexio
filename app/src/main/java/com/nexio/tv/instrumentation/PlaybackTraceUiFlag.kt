package com.nexio.tv.instrumentation

/**
 * Compile-time gate for the playback-diagnostics UI in the debrid settings menu.
 *
 * Kept `false` until WP9 (the [StutterClassifier] + fixture test) lands. With
 * this `false`, the composable section in `DebridSettingsContent.kt` is elided
 * by the Kotlin compiler via constant folding, so the toggle cannot be flipped
 * from the UI even if a release build accidentally shipped the underlying
 * plumbing. When WP9 ships, flip this to `true` in the same changeset as the
 * classifier test so the UI becomes reachable atomically.
 *
 * Spec: see `.omc/specs/deep-interview-playback-instrumentation.md` amendment A5.
 */
internal const val PLAYBACK_TRACE_UI_ENABLED: Boolean = true
