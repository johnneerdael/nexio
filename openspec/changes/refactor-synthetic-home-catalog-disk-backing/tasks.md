## 1. Implementation
- [x] 1.1 Add persisted storage/model support for Trakt and MDBList Home rows so they can be restored from disk before network refresh.
- [x] 1.2 Materialize Trakt and MDBList discovery results into persisted catalog rows using the same metadata hydration and staged publish rules as addon catalogs.
- [x] 1.3 Update Home row assembly to read synthetic rails from persisted row state instead of rebuilding live-only UI rows.
- [x] 1.4 Merge synthetic-row item references into shared metadata/image cleanup and locale invalidation flows.
- [x] 1.5 Add targeted tests for restore, staged publish, and cleanup behavior across addon, Trakt, and MDBList rows.

## 2. Validation
- [x] 2.1 Run `./gradlew :app:compileDebugKotlin`
- [x] 2.2 Run targeted Home persistence/caching tests covering the new synthetic-row path
- [x] 2.3 Run `openspec validate refactor-synthetic-home-catalog-disk-backing --strict`
