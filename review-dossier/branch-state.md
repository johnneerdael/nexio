# Branch State

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Branch:** `codex/integration-runtime-phase-a`
- **Base:** `main`
- **Commit count vs base:** 168
- **Files changed vs base (summary line):**  1037 files changed, 148329 insertions(+), 145337 deletions(-)
- **Submodule state:**
  ```
   b7ef92570255e8f594587457727ff96f3ef61dca dovi_tool (heads/nuvio-capi-preserve-mapping-mode)
   d7c778f30523a18f8322ef7e1dadbe9673c5b5fc media (heads/main)
   744bf159a6aa3f7b9d37b8fb10d9d7a23e7f4e4c nexio-web (heads/main)
   e81770cb7085021a83d6bacd9e4e28777c5e3103 tools/frontiersimulator (heads/main)
  ```
- **Worktree status (`git status --porcelain`):**
  ```
   M media
  ?? app/src/releaseProfileable/res/drawable-nodpi/
  ?? app/src/releaseProfileable/res/drawable/
  ```

## Pre-existing untracked items (out of audit scope)

| Item | Reason |
|---|---|
| `media` submodule untracked content | Pre-existing — present before this audit; not part of the architecture migration on this branch |
| `app/src/releaseProfileable/res/drawable-nodpi/tv_banner.png` | Asset for the `releaseProfileable` build variant added in commit `6c80bdb82` (before the runtime/profile/trace work began); intended for that commit but never committed |
| `app/src/releaseProfileable/res/drawable/app_logo_wordmark.png` | Same as above |
| `app/src/releaseProfileable/res/drawable/tv_banner.png` | Same as above |
