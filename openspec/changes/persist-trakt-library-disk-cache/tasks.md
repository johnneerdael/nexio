## 1. OpenSpec

- [ ] 1.1 Add the `library-playback` OpenSpec delta covering disk-backed Trakt library restore,
      first-sync-only blocking loading, warm-cache refresh behavior, and compact debrid readable
      rows.

## 2. Trakt Library Snapshot Persistence

- [ ] 2.1 Add a persisted `TraktLibrarySnapshotStore` for Trakt Library snapshot state and hydrated
      metadata.
- [ ] 2.2 Restore persisted Trakt library snapshot state during `TraktLibraryService` startup.
- [ ] 2.3 Remove observer-triggered Trakt live refresh from Library flows so Library renders from
      restored snapshot state first.

## 3. Refresh and Mutation Behavior

- [ ] 3.1 Keep the full-screen Library loading state only for the first authenticated Trakt sync
      when no cache exists yet.
- [ ] 3.2 Persist successful Trakt refresh results back to disk without blanking warm-cache Library
      content during refresh.
- [ ] 3.3 Persist optimistic Trakt list mutations and roll back both memory and disk state on
      failure.
- [ ] 3.4 Clear persisted Trakt library snapshot state when Trakt auth is lost.

## 4. Debrid Readable Row Presentation

- [ ] 4.1 Update the readable debrid Library row to render only the filename/title as a compact
      single-line item.
- [ ] 4.2 Reduce debrid readable-row padding and spacing so more items fit on screen.

## 5. Validation

- [ ] 5.1 Add focused tests for persisted Trakt snapshot restore, first uncached sync behavior,
      warm-cache refresh failure retention, and optimistic mutation persistence/rollback.
- [ ] 5.2 Add focused coverage for the compact debrid readable-row presentation.
- [ ] 5.3 Run focused verification and `openspec validate persist-trakt-library-disk-cache --strict`.
