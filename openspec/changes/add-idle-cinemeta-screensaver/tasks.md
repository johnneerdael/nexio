## 1. Implementation
- [x] 1.1 Add a global idle-state controller that resets on remote input and can show/dismiss a root-level screensaver overlay without blocking normal startup or navigation work.
- [x] 1.2 Add a screensaver content pipeline that fetches fresh top 5 `Popular - Movie` and top 5 `Popular - Series` items from the stock Cinemeta catalogs on cold boot, even if those rows are hidden from Home.
- [x] 1.3 Add an in-memory screensaver slide model with the artwork and deep-link metadata required for idle rotation.
- [x] 1.4 Build the screensaver UI with cached image prefetching, randomized rotation, cross-fade transitions, subtle Ken Burns motion, and metadata/logo rendering.
- [x] 1.5 Wire instant dismiss for any key press and `OK/Select` deep-link navigation to the selected title detail screen.
- [x] 1.6 Add targeted tests for the screensaver candidate selection and cold-boot refresh behavior, plus verification for idle trigger/dismiss flow where practical.
