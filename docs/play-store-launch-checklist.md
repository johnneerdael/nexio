# NEXIO Play Store Production Launch Checklist

> Not legal advice. This is a practical launch-prep checklist for getting NEXIO to a **production Play Store submission** with the lowest avoidable review risk.

## Executive summary

NEXIO is already in a strong place technically for Android TV submission, but getting approved on Google Play is not just about the APK or App Bundle building successfully. The biggest launch risks are **policy framing**, **store listing wording**, **privacy/Data safety accuracy**, and a few repo-level items that should be cleaned up before submission.

The safest strategy is to position NEXIO on Play as a **personal media playback and organization app for Android TV**, with strong account-based integrations, metadata enrichment, and playback optimization. Avoid store copy that makes the app sound like a shortcut for acquiring or unlocking media from third-party sources.

---

## 1. Current NEXIO status: what already looks good

### Technical positives already visible in the repo
- `targetSdk = 36` and `compileSdk = 36` in `app/build.gradle.kts`
- `minSdk = 26`
- Android TV launcher support is present:
  - `android.intent.category.LEANBACK_LAUNCHER`
  - `android:banner="@drawable/tv_banner"`
  - touchscreen marked not required
- Android TV banner assets already exist in the repo
- Privacy policy entry exists in-app in `AboutScreen`
- The docs site already has:
  - creator setup guide: `https://johnneerdael.github.io/nexio/start-here/`
  - features page: `https://johnneerdael.github.io/nexio/features/`

### Immediate interpretation
NEXIO already looks like a real Android TV product, not a half-adapted mobile app. That helps. The work left is mostly about **submission hygiene**, **policy-safe framing**, and **release hardening**.

---

## 2. Repo blockers and pre-submission fixes

These are the first things I would address before opening a production Play submission.

### Blocker A — Remove or rotate release signing secrets
**Current finding:** `app/build.gradle.kts` contains a hardcoded release signing config with alias, passwords, and a keystore path.

#### Why this matters
- This is a release-security problem regardless of Play submission.
- If the signing material is exposed, you should treat it as compromised.
- Even if Play App Signing is used, your upload-key handling still needs to be clean.

#### Action
- Remove hardcoded release signing credentials from the repo.
- Rotate the signing key / upload key if the existing secret material has been exposed publicly.
- Move signing secrets to secure local/CI secret storage.
- Use **Play App Signing** with a separate upload key.

### Blocker B — Reconsider `REQUEST_INSTALL_PACKAGES` for the Play build
**Current finding:** `android.permission.REQUEST_INSTALL_PACKAGES` is declared in `AndroidManifest.xml`.

#### Why this matters
This permission is high-friction in review because it is associated with side-loading and app installation behavior. NEXIO uses a GitHub Releases-backed in-app updater, but on Google Play that mechanism is usually unnecessary and may invite questions.

#### Action
- Create a **Play flavor/build** that disables or removes the in-app updater and this permission.
- If you keep it, prepare a very strong policy justification — but the safer route is removing it from the Play-distributed build.

### Blocker C — Reconsider `android:usesCleartextTraffic="true"`
**Current finding:** the app manifest enables cleartext traffic.

#### Why this matters
This is not automatically forbidden, but it increases review risk and security scrutiny. If it is not required for production Play traffic, disable it.

#### Action
- Audit whether production traffic truly needs cleartext HTTP.
- If not required, set `usesCleartextTraffic` to `false` for the Play build.
- If required for a narrow case, scope it via network security config instead of a blanket allow.

### Blocker D — Review the exported debug receiver
**Current finding:** `TransportValidationReceiver` is exported in the production manifest.

#### Why this matters
A debug-oriented exported receiver in a release build is an unnecessary review and security liability.

#### Action
- Remove it from release / Play builds.
- Or gate it behind a debug-only manifest source set.

### Blocker E — Validate microphone permission necessity for Play listing
**Current finding:** `RECORD_AUDIO` is declared and used for voice search.

#### Why this matters
This is valid if used only for user-triggered voice search, but it must be explained consistently in:
- Play listing
- Data safety
- privacy policy
- in-app permission UX

#### Action
- Keep it only if voice search is part of the Play build.
- Make sure store disclosures clearly say microphone is used for optional voice search only.

### Blocker F — Verify privacy policy URL and ownership alignment
**Current finding:** in-app privacy policy currently points to `https://johnneerdael.github.io/NEXIOStreaming/#privacy-policy`.

#### Why this matters
Play requires a public privacy policy, and policy text should align with the entity/app identity used in the store listing.

#### Action
- Verify the URL is live, public, non-PDF, and stable.
- Make sure the policy names **NEXIO** or the exact publishing entity used in the Play listing.
- Ensure the policy covers account sync, debrid integrations, Trakt, TMDB, Gemini, telemetry, and updater behavior if retained.

---

## 3. Full production submission path

## Phase 1 — Developer account and release model
- Confirm whether the Play Console account is **personal** or **organization**.
- Complete all required identity / payments / developer verification steps early.
- Decide whether NEXIO will ship from:
  - one production package only
  - or separate Play/non-Play distributions if risky permissions/features are removed for Play.

### If your account is a new personal account
Officially, developers with personal accounts created after **November 13, 2023** must complete closed testing requirements before production access. Google says you need **at least 12 opted-in testers for 14 continuous days** before you can apply for production access.

## Phase 2 — Release artifact preparation
- Build a **release AAB** for Play.
- Use **Play App Signing**.
- Confirm versioning strategy (`versionCode`, `versionName`) for production.
- Verify the release build is truly non-debuggable.
- Remove debug-only tooling and exported debug surfaces from the Play build.

## Phase 3 — Android TV packaging and quality
- Confirm Android TV app quality requirements are met.
- Verify TV launcher assets are correct for the production listing.
- Confirm the app behaves correctly with remote-only navigation.
- Make sure onboarding, playback, dialogs, and settings all work in landscape without touch assumptions.

## Phase 4 — Store listing and declaration prep
- App title
- Short description
- Full description
- Category / app type
- Contact email
- Website
- Privacy policy URL
- Screenshots
- Android TV screenshots
- Android TV banner
- Optional promo assets

## Phase 5 — Testing gates
- Internal test first
- Closed test next
- If required by account type, satisfy the 12 testers / 14 days gate
- Only then submit production

## Phase 6 — Production submission
- Finish App content declarations
- Finish Data safety
- Upload AAB
- Create production release notes
- Start with staged rollout if possible
- Monitor review feedback carefully

---

## 4. Play listing strategy for NEXIO

## Recommended positioning
Position NEXIO as:
- an **Android TV media playback and organization app**
- with **account-backed integrations**
- **metadata enrichment**
- **Continue Watching / Up Next / discovery rails**
- **playback optimization for TV devices**

That is truthful and substantially safer than leading with addon/debrid language.

## Recommended wording themes
Use themes like:
- personal media playback on Android TV
- account-connected discovery and watch progress
- metadata, ratings, posters, and trailers
- benchmark-aware playback optimization
- TV-first playback tuning
- synced setup across TV app and portal

### Example safer framing
- “A premium Android TV media app with account-backed setup, Trakt-powered discovery, metadata enrichment, and advanced playback tuning.”
- “Built for Android TV and Fire TV users who want cleaner playback, better metadata, and less manual stream friction.”
- “Includes TV-first playback controls, benchmark-aware autoplay, and a companion portal for setup and account syncing.”

## Wording to avoid in the Play listing
Avoid leading with phrases like:
- “stream anything for free”
- “unlimited streaming sources”
- “use Stremio addons”
- “connect debrid to unlock content”
- “watch copyrighted content from any source”
- “best app for torrent streaming”
- “replace Stremio / Nuvio / Kodi”

Avoid screenshots or copy that overemphasize:
- raw stream source chaos
- addon installation for third-party content acquisition
- debrid as a content access shortcut
- anything that reads like bypass language

## Best practical framing for NEXIO
Lead with:
- Android TV experience
- playback quality
- account sync
- Trakt depth
- metadata/trailers/posters
- playback tuning
- lean-back autoplay

Mention debrid/service-wrap later, carefully, as **optional playback integrations** rather than the app’s first identity.

---

## 5. Stremio comparison: what to learn and what not to copy blindly

## Public signals
From public sources:
- Stremio’s official site still presents itself broadly as a streaming/media platform.
- Their public web presence still exposes an **Addon SDK** and feature-driven positioning.
- Their official blog also says they were **back on Google Play on January 13, 2026** after a **19-day suspension**.

## What this suggests
### Likely helpful lessons
- Keep public messaging product-like, not pirate-like.
- Separate “we are a platform/app” from “we do not host content.”
- Avoid claiming ownership of third-party content availability.

### What it does **not** prove
It does **not** prove that copying Stremio’s wording is enough.

Their public record shows that even a large app with broad recognition can still be suspended and later reinstated. That means:
- wording helps
- product behavior still matters
- review outcomes can change over time

## Best NEXIO takeaway
Do **not** market NEXIO on Play primarily as a Stremio-addon app.

Instead:
- present it as a TV playback and organization product
- if needed, describe integrations in neutral terms
- let your website/docs explain the deeper ecosystem in more detail than the Play listing does

---

## 6. Android TV-specific checklist

Before Play submission, verify all of this:

- [ ] Android TV launcher activity works and is discoverable
- [ ] `LEANBACK_LAUNCHER` is present
- [ ] touchscreen is not required
- [ ] banner asset is present and correct
- [ ] at least one Android TV screenshot is prepared for the Play listing
- [ ] Android TV is mentioned in the app description
- [ ] app is opted into Android TV form factor in Play Console
- [ ] remote navigation works end-to-end without touch-only assumptions
- [ ] startup, login, browsing, playback, settings, and error recovery all behave well on TV
- [ ] app meets Android TV quality guidance

---

## 7. Data safety and App content checklist for NEXIO

Based on the repo, the privacy/data review should explicitly account for:

- [ ] account authentication and sync via Supabase
- [ ] Trakt authentication, sync, scrobbling, watch progress, lists
- [ ] TMDB / MDBList / poster-provider API usage
- [ ] optional Gemini subtitle translation
- [ ] optional telemetry / shadow autoplay collection if enabled
- [ ] Android TV recommendations / channels metadata
- [ ] microphone access for voice search
- [ ] updater behavior if retained in the Play build

## What to prepare
- [ ] A privacy policy that clearly explains all of the above
- [ ] A Data safety form that matches actual app behavior exactly
- [ ] App content declarations completed truthfully
- [ ] Permission justifications that are user-facing and easy to understand
- [ ] In-app disclosures for any sensitive/optional data uses where required

---

## 8. NEXIO-specific listing copy guidance

## Recommended short-description themes
Focus on combinations like:
- Android TV media playback
- Trakt-powered discovery and progress sync
- metadata, trailers, ratings, and posters
- benchmark-aware autoplay and playback tuning
- account-backed setup and personalization

## Full-description structure recommendation
1. One short paragraph: what NEXIO is
2. One short paragraph: who it is for
3. Bullet list of standout features
4. Android TV / Fire TV optimization
5. Account and portal sync
6. Optional integrations
7. Legal/content disclaimer

## Features worth highlighting safely
These are strong differentiators you can talk about without making the listing riskier than necessary:
- benchmark-aware autoplay
- config benchmarking and playback tuning
- Trakt-powered Continue Watching / Up Next / discovery
- advanced metadata, ratings, and poster enrichment
- trailer-first browsing and YouTube trailer login
- account-backed portal control
- Dolby Vision fallback safety in autoplay on non-DV displays
- advanced audio-path work for enthusiasts (carefully worded)

## Features to word carefully
- debrid integrations
- Service Wrap
- addon ecosystem compatibility
- updater / install-packages behavior
- any torrent-adjacent wording

---

## 9. Recommended execution order for NEXIO

### First pass: remove preventable review risk
- [ ] Remove hardcoded release signing secrets from the repo
- [ ] Decide whether to create a Play-specific build flavor
- [ ] Remove or disable `REQUEST_INSTALL_PACKAGES` in the Play build
- [ ] Remove or gate exported debug receiver from release
- [ ] Revisit `usesCleartextTraffic=true`

### Second pass: harden policy and disclosures
- [ ] Update privacy policy
- [ ] Map actual app behavior into Data safety answers
- [ ] Write Play listing copy using safer positioning
- [ ] Prepare “wording to avoid” rules for whoever fills out Play Console

### Third pass: TV store assets and screenshots
- [ ] Capture Android TV screenshots from the current app
- [ ] Verify banner and icon quality
- [ ] Prepare listing art that matches actual functionality

### Fourth pass: release flow
- [ ] Build signed release AAB
- [ ] Upload to internal test
- [ ] Run closed test
- [ ] If required, meet the personal-account tester gate
- [ ] Submit production release
- [ ] Start staged rollout

---

## 10. Practical recommendation

If you want the **highest chance of approval**, my strongest recommendation is:

1. ship a **Play-friendly build flavor**
2. remove the in-app updater / install-packages permission from that flavor
3. keep the Play listing focused on **Android TV playback, organization, Trakt, metadata, and tuning**
4. avoid letting the Play page become the deepest explanation of the addon/debrid ecosystem
5. make your privacy policy and Data safety answers more complete than you think they need to be

That approach is much safer than trying to talk your way through review with wording alone.

---

## Sources

Official Google / Android sources:
- Create and set up your app / app dashboard: https://support.google.com/googleplay/android-developer/answer/9859152
- Set up your app on the app dashboard: https://support.google.com/googleplay/android-developer/answer/9859454
- App testing requirements for new personal developer accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Set up an open, closed, or internal test: https://support.google.com/googleplay/android-developer/answer/9845334
- Sign your app / Play App Signing: https://developer.android.com/studio/publish/app-signing
- Publish your app / AAB requirements: https://developer.android.com/studio/publish/
- Google Play target API policy: https://support.google.com/googleplay/android-developer/answer/11917020
- Add preview assets to showcase your app: https://support.google.com/googleplay/android-developer/answer/9866151
- Best practices for your store listing: https://support.google.com/googleplay/android-developer/answer/13393723
- Distribute to Android TV: https://developer.android.com/training/tv/publishing/distribute
- Android TV app quality: https://developer.android.com/docs/quality-guidelines/tv-app-quality
- TV icon/banner guidance: https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines
- User Data / privacy policy requirements: https://support.google.com/googleplay/android-developer/answer/10144311
- Required information to create a Play Console developer account: https://support.google.com/googleplay/android-developer/answer/13628312

Public Stremio context:
- Stremio official site: https://www.stremio.com/
- Stremio blog, “We’re Back on Google Play” (January 13, 2026): https://blog.stremio.com/were-back-on-google-play/
