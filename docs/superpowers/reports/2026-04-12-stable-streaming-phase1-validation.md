# Stable Streaming Rollback Phase 1 Validation

- Baseline commit: e231bb8497c0397717bd5719f91083923f0ce5fe
- Expected state: no VOD cache, no parallel connections, no streaming-cache Phase 4 code.
- Required local setup: run `git submodule update --init --recursive` and create ignored `local.dev.properties` with `USE_MEDIA3_SOURCE=true` and `DOVI_NATIVE_ENABLED=false`.
- Validation command: `env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest`.
- Compile command: `env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin`.
