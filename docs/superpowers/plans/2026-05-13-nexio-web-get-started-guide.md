# Nexio Web Get Started Guide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a public installation-first `/get-started` guide to Nexio Web and update the landing hero CTAs to send users there before account management.

**Architecture:** Keep the guide content in a typed data module so the page, tests, route ids, screenshot templates, store badges, and external links cannot drift. Build one Nuxt page that renders a single active guide step, synchronizes the active step with `?step=<id>`, and uses the existing `PublicShell` plus local scoped CSS for the cinematic house style.

**Tech Stack:** Nuxt 4, Vue 3 `<script setup>`, TypeScript, Tailwind utility classes, scoped Vue CSS, Node `node:test` run through `tsx`.

---

## File Structure

- Create: `nexio-web/utils/get-started-guide.ts`
  - Owns all guide step copy, ids, links, Downloader codes, screenshot asset names, and store badge metadata.
  - Exports helpers to resolve and clamp step ids.
- Create: `nexio-web/tests/get-started-guide.test.ts`
  - Verifies the guide has exactly the approved ten steps, stable ids, install-first ordering, updated Downloader codes, external links, and screenshot fallback metadata.
- Create: `nexio-web/tests/get-started-page-source.test.ts`
  - Verifies the page source uses the guide utility, route query sync, public shell, and accessible step navigation controls.
- Create: `nexio-web/pages/get-started.vue`
  - Renders the public clickthrough wizard.
  - Uses `PublicShell`, `usePortalStore`, and route query state.
  - Does not call protected account APIs or mutate portal settings.
- Modify: `nexio-web/pages/index.vue`
  - Changes hero CTAs to `Get Started` then `Manage Account`.
  - Removes the `Explore Features` hero action.
- Create directory: `nexio-web/public/install/`
  - Holds linked store badge assets.
- Copy: `nexio-web/googleplay.webp` to `nexio-web/public/install/google-play-downloader.webp`
- Copy: `nexio-web/amazonappstore.webp` to `nexio-web/public/install/amazon-appstore-downloader.webp`

## Task 1: Add Guide Data Contract Tests

**Files:**
- Create: `nexio-web/tests/get-started-guide.test.ts`

- [ ] **Step 1: Write the failing test**

Create `tests/get-started-guide.test.ts` with:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  downloaderCodes,
  getStartedGuideSteps,
  resolveGuideStepId,
  storeBadges
} from '../utils/get-started-guide.ts'

test('get started guide exposes the approved step order', () => {
  assert.deepEqual(
    getStartedGuideSteps.map((step) => step.id),
    [
      'install',
      'account-sync',
      'profiles',
      'addons',
      'tracking',
      'integrations',
      'modern-home',
      'autoplay',
      'subtitles',
      'cache'
    ]
  )
})

test('install step is first and exposes current Downloader codes', () => {
  const installStep = getStartedGuideSteps[0]

  assert.equal(installStep.id, 'install')
  assert.equal(downloaderCodes.release, '3316080')
  assert.equal(downloaderCodes.earlyAccess, '7063421')
  assert.equal(
    installStep.actions.some((action) => action.label === 'Release code' && action.value === '3316080'),
    true
  )
  assert.equal(
    installStep.actions.some((action) => action.label === 'Early Access code' && action.value === '7063421'),
    true
  )
})

test('store badges link to Downloader app store listings', () => {
  assert.deepEqual(storeBadges, [
    {
      label: 'Get Downloader on Google Play',
      href: 'https://play.google.com/store/apps/details?id=com.esaba.downloader',
      src: '/install/google-play-downloader.webp',
      alt: 'Get it on Google Play'
    },
    {
      label: 'Get Downloader on Amazon Appstore',
      href: 'https://www.amazon.com/dp/B01N0BP507/?tag=aftvn-20',
      src: '/install/amazon-appstore-downloader.webp',
      alt: 'Get it on Amazon Appstore'
    }
  ])
})

test('guide includes required external references', () => {
  const addons = getStartedGuideSteps.find((step) => step.id === 'addons')!
  const integrations = getStartedGuideSteps.find((step) => step.id === 'integrations')!

  assert.equal(addons.links.some((link) => link.href === 'https://stremio-addons.net'), true)
  assert.equal(addons.links.some((link) => link.href === 'https://uptime.thepi.es/status/nexio'), true)
  assert.equal(
    integrations.body.some((paragraph) => paragraph.includes('google/gemini-2.5-flash-lite:nitro')),
    true
  )
  assert.equal(
    integrations.body.some((paragraph) => paragraph.includes('meta-llama/llama-4-scout:nitro')),
    true
  )
})

test('every guide step defines screenshot fallback metadata', () => {
  for (const step of getStartedGuideSteps) {
    assert.match(step.screenshot.src, /^\/get-started\/get-started-[a-z0-9-]+\.webp$/)
    assert.equal(step.screenshot.alt.length > 8, true)
    assert.equal(step.screenshot.templateLabel.length > 8, true)
  }
})

test('resolveGuideStepId accepts known ids and falls back to install', () => {
  assert.equal(resolveGuideStepId('addons'), 'addons')
  assert.equal(resolveGuideStepId('not-a-step'), 'install')
  assert.equal(resolveGuideStepId(['cache']), 'install')
  assert.equal(resolveGuideStepId(undefined), 'install')
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `nexio-web`:

```bash
npx tsx --test tests/get-started-guide.test.ts
```

Expected: FAIL with a module resolution error for `../utils/get-started-guide.ts`.

- [ ] **Step 3: Commit the failing test**

Run from `nexio-web`:

```bash
git add tests/get-started-guide.test.ts
git commit -m "test: cover get started guide contract"
```

## Task 2: Add Guide Data Utility And Store Badge Assets

**Files:**
- Create: `nexio-web/utils/get-started-guide.ts`
- Create directory: `nexio-web/public/install/`
- Copy: `nexio-web/googleplay.webp` to `nexio-web/public/install/google-play-downloader.webp`
- Copy: `nexio-web/amazonappstore.webp` to `nexio-web/public/install/amazon-appstore-downloader.webp`
- Test: `nexio-web/tests/get-started-guide.test.ts`

- [ ] **Step 1: Create the public install asset directory**

Run from `nexio-web`:

```bash
mkdir -p public/install
```

Expected: `public/install` exists.

- [ ] **Step 2: Copy the supplied store badge assets**

Run from `nexio-web`:

```bash
cp googleplay.webp public/install/google-play-downloader.webp
```

Run from `nexio-web`:

```bash
cp amazonappstore.webp public/install/amazon-appstore-downloader.webp
```

Expected: both commands exit 0 and the files exist under `public/install/`.

- [ ] **Step 3: Create the guide data module**

Create `utils/get-started-guide.ts` with:

```ts
export type GuideStepId =
  | 'install'
  | 'account-sync'
  | 'profiles'
  | 'addons'
  | 'tracking'
  | 'integrations'
  | 'modern-home'
  | 'autoplay'
  | 'subtitles'
  | 'cache'

export type GuideAction = {
  label: string
  value: string
  description: string
  tone?: 'primary' | 'secondary'
}

export type GuideLink = {
  label: string
  href: string
  description: string
}

export type GuideScreenshot = {
  src: string
  alt: string
  templateLabel: string
}

export type GuideStep = {
  id: GuideStepId
  eyebrow: string
  title: string
  summary: string
  body: string[]
  actions: GuideAction[]
  links: GuideLink[]
  screenshot: GuideScreenshot
}

export type StoreBadge = {
  label: string
  href: string
  src: string
  alt: string
}

export const downloaderCodes = {
  release: '3316080',
  earlyAccess: '7063421'
} as const

export const storeBadges: readonly StoreBadge[] = [
  {
    label: 'Get Downloader on Google Play',
    href: 'https://play.google.com/store/apps/details?id=com.esaba.downloader',
    src: '/install/google-play-downloader.webp',
    alt: 'Get it on Google Play'
  },
  {
    label: 'Get Downloader on Amazon Appstore',
    href: 'https://www.amazon.com/dp/B01N0BP507/?tag=aftvn-20',
    src: '/install/amazon-appstore-downloader.webp',
    alt: 'Get it on Amazon Appstore'
  }
]

export const getStartedGuideSteps: readonly GuideStep[] = [
  {
    id: 'install',
    eyebrow: 'Install',
    title: 'Install Nexio on your TV.',
    summary: 'Use Downloader by AFTVNews to install Nexio first. Account setup can come afterward when you are ready to sync settings across devices.',
    body: [
      'Install Downloader from the app store available on your device, then enter the Nexio code that matches the build you want.',
      'Choose Release for the stable channel. Choose Early Access when you want the newest build before it reaches the stable channel.',
      'After Nexio opens on your TV, continue through this guide to create an account and configure the features that sync from the website.'
    ],
    actions: [
      {
        label: 'Release code',
        value: downloaderCodes.release,
        description: 'Recommended for most users.',
        tone: 'primary'
      },
      {
        label: 'Early Access code',
        value: downloaderCodes.earlyAccess,
        description: 'For users who want the newest pre-release build.',
        tone: 'secondary'
      }
    ],
    links: [],
    screenshot: {
      src: '/get-started/get-started-install-downloader.webp',
      alt: 'Downloader app showing a Nexio install code entry screen',
      templateLabel: 'Downloader install and code entry'
    }
  },
  {
    id: 'account-sync',
    eyebrow: 'Account sync',
    title: 'Create one account for every screen.',
    summary: 'Your Nexio account connects the website and the Android TV app so configuration follows you across devices.',
    body: [
      'Configure account-level features from the website once, then link Nexio on each TV that should receive those settings.',
      'This is useful when Nexio is installed on multiple devices because addons, integrations, and selected options do not need to be rebuilt on every TV.',
      'You can install first and return to account setup later; the guide is public so the install path stays available without signing in.'
    ],
    actions: [
      {
        label: 'Best for',
        value: 'Multiple TVs',
        description: 'Set up once from the website and keep devices aligned.'
      },
      {
        label: 'Next action',
        value: 'Create account',
        description: 'Open the account portal when you are ready to sync settings.',
        tone: 'primary'
      }
    ],
    links: [
      {
        label: 'Open account portal',
        href: '/account',
        description: 'Create or manage the account that syncs Nexio settings.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-account-sync.webp',
      alt: 'Nexio account portal showing linked devices and synchronized settings',
      templateLabel: 'Account sync and linked devices'
    }
  },
  {
    id: 'profiles',
    eyebrow: 'Profiles',
    title: 'Separate a shared household when it helps.',
    summary: 'Profiles are optional. They are most useful when different people need separate tracking, PINs, or content boundaries.',
    body: [
      'A household can use profiles to separate adults, children, and guests without creating a different Nexio account for every TV.',
      'Kids profiles can be configured around children content, while adult profiles can still be protected with a PIN when privacy matters.',
      'Each profile can use its own picture and can link its own Trakt and SIMKL accounts for individual watched-content tracking.'
    ],
    actions: [
      {
        label: 'Recommended when',
        value: 'Multiple viewers',
        description: 'Use profiles when watch history or access should stay separate.'
      },
      {
        label: 'Optional when',
        value: 'Single viewer',
        description: 'A single-user setup can stay simpler with one default profile.'
      }
    ],
    links: [
      {
        label: 'Manage profiles',
        href: '/account',
        description: 'Open the portal and choose the Profiles workspace.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-profiles.webp',
      alt: 'Nexio profile dashboard with profile cards and profile settings',
      templateLabel: 'Profile dashboard and profile editor'
    }
  },
  {
    id: 'addons',
    eyebrow: 'Addons',
    title: 'Add catalogs and stream sources.',
    summary: 'Nexio supports Stremio addons and adds portal-side controls for parser selection, anime-specific sources, and addon management.',
    body: [
      'Addons can provide catalogs, metadata routes, and stream sources that appear inside Nexio.',
      'Dedicated anime addons can improve anime coverage, while parser selection controls how stream names are interpreted and grouped.',
      'Nexio also tracks recommended addon availability so you can see whether a source is healthy before relying on it.'
    ],
    actions: [
      {
        label: 'Supports',
        value: 'Stremio addons',
        description: 'Use compatible addon manifest URLs from the wider Stremio ecosystem.',
        tone: 'primary'
      },
      {
        label: 'Configure',
        value: 'Parser presets',
        description: 'Choose parsing behavior that matches the addon and content type.'
      }
    ],
    links: [
      {
        label: 'Browse Stremio addons',
        href: 'https://stremio-addons.net',
        description: 'Find public Stremio addon options.'
      },
      {
        label: 'Check Nexio addon status',
        href: 'https://uptime.thepi.es/status/nexio',
        description: 'Review tracked addon availability and recommendations.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-addons.webp',
      alt: 'Nexio addon manager showing addon configuration and parser controls',
      templateLabel: 'Addon manager and parser selection'
    }
  },
  {
    id: 'tracking',
    eyebrow: 'Tracking',
    title: 'Connect Trakt and SIMKL.',
    summary: 'Trakt and SIMKL keep watch progress, Continue Watching, and library state aligned with the services you already use.',
    body: [
      'Nexio can scrobble watched progress and use tracking data to power Continue Watching and library behavior.',
      'Trakt and SIMKL can run side by side for dual tracking, so one service does not need to replace the other.',
      'SIMKL is especially useful for anime tracking, while Trakt remains valuable for broad movie and series workflows.'
    ],
    actions: [
      {
        label: 'Trakt',
        value: 'Movies and shows',
        description: 'Use Trakt for broad watch history, progress, and list workflows.',
        tone: 'primary'
      },
      {
        label: 'SIMKL',
        value: 'Anime friendly',
        description: 'Use SIMKL alongside Trakt when anime tracking matters.'
      }
    ],
    links: [
      {
        label: 'Open integrations',
        href: '/account',
        description: 'Connect Trakt and SIMKL from the account portal.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-trakt-simkl.webp',
      alt: 'Nexio integrations panel showing Trakt and SIMKL connection controls',
      templateLabel: 'Trakt and SIMKL connection controls'
    }
  },
  {
    id: 'integrations',
    eyebrow: 'Integrations',
    title: 'Tune artwork, libraries, ratings, and translation.',
    summary: 'Integrations add polish and capability without making users manage every provider from the TV.',
    body: [
      'Premium posters from TOP Posters and RPDB improve poster artwork with ratings and richer visual signals.',
      'Debrid integrations such as EasyDebrid, TorBox, Real-Debrid, and Premiumize power playback and library-oriented workflows inside Nexio.',
      'MDBList can bring custom lists and ratings into discovery, while AI subtitle translation can produce high-quality subtitles when configured with a provider.',
      'For affordable high-quality high-performance translation, use OpenRouter with google/gemini-2.5-flash-lite:nitro or meta-llama/llama-4-scout:nitro.'
    ],
    actions: [
      {
        label: 'Premium posters',
        value: 'TOP Posters and RPDB',
        description: 'Improve poster artwork and ratings at a glance.'
      },
      {
        label: 'Debrid',
        value: 'EasyDebrid, TorBox, Real-Debrid, Premiumize',
        description: 'Power playback and library-oriented workflows.',
        tone: 'primary'
      },
      {
        label: 'AI subtitles',
        value: 'OpenRouter',
        description: 'Recommended provider path for efficient translation.'
      }
    ],
    links: [
      {
        label: 'Open integrations',
        href: '/account',
        description: 'Configure connected services and API keys from the portal.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-integrations.webp',
      alt: 'Nexio integrations overview with poster, debrid, MDBList, and translation settings',
      templateLabel: 'Grouped integrations overview'
    }
  },
  {
    id: 'modern-home',
    eyebrow: 'Modern Home',
    title: 'Shape the catalog rails on your home screen.',
    summary: 'Modern Home starts with useful defaults, then lets you add, remove, and reorder rails from the website.',
    body: [
      'Catalog rails decide what appears on the Nexio home screen and in what order.',
      'Start with the defaults, then add rails that match the household, remove ones that add noise, and drag important rows higher.',
      'Use catalog sources from integrations and addons to make the home screen reflect how the household actually watches.'
    ],
    actions: [
      {
        label: 'Default',
        value: 'Ready to use',
        description: 'Modern Home works before customization.'
      },
      {
        label: 'Customize',
        value: 'Add, remove, reorder',
        description: 'Tune rails from the portal when the defaults need adjustment.',
        tone: 'primary'
      }
    ],
    links: [
      {
        label: 'Manage catalog rails',
        href: '/account',
        description: 'Open the portal and choose the catalog management workspace.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-home-rails.webp',
      alt: 'Nexio catalog rail management screen with reorder controls',
      templateLabel: 'Catalog rail add, remove, and reorder controls'
    }
  },
  {
    id: 'autoplay',
    eyebrow: 'Autoplay',
    title: 'Let Nexio choose the right stream.',
    summary: 'Autoplay is enabled by default because it saves users from manually comparing streams and usually selects the best playable option.',
    body: [
      'Nexio compares available streams against device capability, configured services, quality, and your autoplay budget.',
      'You can tune the budget or disable Autoplay from settings when you prefer manual selection.',
      'When a selection looks wrong, troubleshooting data collection and the personalized device-level diagnostics link help explain why that stream was selected.'
    ],
    actions: [
      {
        label: 'Default',
        value: 'Enabled',
        description: 'Recommended because Nexio can compare candidates before playback.',
        tone: 'primary'
      },
      {
        label: 'Control',
        value: 'Budget or disable',
        description: 'Tune the automatic selection behavior from settings.'
      }
    ],
    links: [
      {
        label: 'Open playback settings',
        href: '/account',
        description: 'Configure stream selection and diagnostics from the portal.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-autoplay.webp',
      alt: 'Nexio autoplay settings and candidate diagnostics view',
      templateLabel: 'Autoplay budget and diagnostics'
    }
  },
  {
    id: 'subtitles',
    eyebrow: 'Subtitles',
    title: 'Set default languages and translation.',
    summary: 'Configure subtitle language preferences early so Nexio can pick the right subtitle automatically.',
    body: [
      'Choose the subtitle languages you prefer before watching across multiple devices.',
      'When auto-translate is enabled and a suitable subtitle is not available, Nexio can translate subtitles automatically.',
      'This is especially useful for international content and anime where exact subtitles may not exist for your preferred language.'
    ],
    actions: [
      {
        label: 'Configure',
        value: 'Default languages',
        description: 'Tell Nexio which subtitle languages to prefer.',
        tone: 'primary'
      },
      {
        label: 'Optional',
        value: 'Auto-translate',
        description: 'Translate subtitles automatically when no suitable match is available.'
      }
    ],
    links: [
      {
        label: 'Open subtitle settings',
        href: '/account',
        description: 'Configure language and translation preferences.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-subtitles.webp',
      alt: 'Nexio subtitle language and auto-translate settings',
      templateLabel: 'Subtitle languages and auto-translate settings'
    }
  },
  {
    id: 'cache',
    eyebrow: 'Video cache',
    title: 'Use caching when the storage setup fits.',
    summary: 'Video caching is an advanced performance option. Disk spool is recommended only when an external SD card is available.',
    body: [
      'Disk spool can reduce playback pressure, but it should be used with external SD card storage rather than constrained internal device storage.',
      'Parallel downloading can improve throughput when supported by the source and service configuration.',
      'VOD cache can help reduce buffering in compatible playback scenarios, especially when the stream benefits from local buffering.'
    ],
    actions: [
      {
        label: 'Disk spool',
        value: 'External SD recommended',
        description: 'Avoid using limited internal TV storage for heavy cache behavior.',
        tone: 'primary'
      },
      {
        label: 'Performance',
        value: 'Parallel download and VOD cache',
        description: 'Enable when the device and service setup can benefit.'
      }
    ],
    links: [
      {
        label: 'Open cache settings',
        href: '/account',
        description: 'Configure playback cache behavior from the portal.'
      }
    ],
    screenshot: {
      src: '/get-started/get-started-cache.webp',
      alt: 'Nexio cache and playback performance settings',
      templateLabel: 'Video cache and playback performance settings'
    }
  }
]

const guideStepIds = new Set(getStartedGuideSteps.map((step) => step.id))

export function resolveGuideStepId(value: unknown): GuideStepId {
  return typeof value === 'string' && guideStepIds.has(value as GuideStepId)
    ? value as GuideStepId
    : getStartedGuideSteps[0].id
}

export function guideStepIndex(id: GuideStepId) {
  return getStartedGuideSteps.findIndex((step) => step.id === id)
}
```

- [ ] **Step 4: Run the guide data test**

Run from `nexio-web`:

```bash
npx tsx --test tests/get-started-guide.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit the guide data and assets**

Run from `nexio-web`:

```bash
git add utils/get-started-guide.ts tests/get-started-guide.test.ts public/install/google-play-downloader.webp public/install/amazon-appstore-downloader.webp
git commit -m "feat: add get started guide data"
```

## Task 3: Add Page Source Contract Test

**Files:**
- Create: `nexio-web/tests/get-started-page-source.test.ts`

- [ ] **Step 1: Write the failing source test**

Create `tests/get-started-page-source.test.ts` with:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const pagePath = fileURLToPath(new URL('../pages/get-started.vue', import.meta.url))

function pageSource() {
  return readFileSync(pagePath, 'utf8')
}

test('get started page uses public shell and guide data module', () => {
  const source = pageSource()

  assert.match(source, /import PublicShell from '~\/components\/portal\/PublicShell\.vue'/)
  assert.match(source, /getStartedGuideSteps/)
  assert.match(source, /storeBadges/)
  assert.match(source, /usePortalStore/)
})

test('get started page syncs active step with route query', () => {
  const source = pageSource()

  assert.match(source, /useRoute\(\)/)
  assert.match(source, /useRouter\(\)/)
  assert.match(source, /route\.query\.step/)
  assert.match(source, /router\.replace/)
  assert.match(source, /resolveGuideStepId/)
})

test('get started page exposes accessible wizard navigation', () => {
  const source = pageSource()

  assert.match(source, /aria-current/)
  assert.match(source, /aria-label="Previous guide step"/)
  assert.match(source, /aria-label="Next guide step"/)
  assert.match(source, /Step \{\{ currentStepIndex \+ 1 \}\} of \{\{ getStartedGuideSteps\.length \}\}/)
})
```

- [ ] **Step 2: Run the source test to verify it fails**

Run from `nexio-web`:

```bash
npx tsx --test tests/get-started-page-source.test.ts
```

Expected: FAIL with `ENOENT` because `pages/get-started.vue` does not exist yet.

- [ ] **Step 3: Commit the failing page source test**

Run from `nexio-web`:

```bash
git add tests/get-started-page-source.test.ts
git commit -m "test: cover get started page wiring"
```

## Task 4: Build The Public Get Started Wizard Page

**Files:**
- Create: `nexio-web/pages/get-started.vue`
- Test: `nexio-web/tests/get-started-guide.test.ts`
- Test: `nexio-web/tests/get-started-page-source.test.ts`

- [ ] **Step 1: Create the page implementation**

Create `pages/get-started.vue` with:

```vue
<template>
  <PublicShell :signed-in="signedIn" :user-email="state.session?.user.email" @sign-out="signOut">
    <section class="get-started-page">
      <div class="guide-shell">
        <aside class="step-index" aria-label="Get Started steps">
          <p class="index-label">Setup path</p>
          <button
            v-for="(step, index) in getStartedGuideSteps"
            :key="step.id"
            class="step-pill"
            :class="{ 'is-active': step.id === currentStep.id }"
            type="button"
            :aria-current="step.id === currentStep.id ? 'step' : undefined"
            @click="selectStep(step.id)"
          >
            <span class="step-number">{{ index + 1 }}</span>
            <span>{{ step.eyebrow }}</span>
          </button>
        </aside>

        <article class="guide-panel">
          <div class="guide-light" aria-hidden="true" />

          <div class="guide-progress">
            <span>Step {{ currentStepIndex + 1 }} of {{ getStartedGuideSteps.length }}</span>
            <span>{{ currentStep.eyebrow }}</span>
          </div>

          <div class="guide-content">
            <section class="guide-copy" :aria-labelledby="`guide-title-${currentStep.id}`">
              <p class="guide-eyebrow">{{ currentStep.eyebrow }}</p>
              <h1 :id="`guide-title-${currentStep.id}`">{{ currentStep.title }}</h1>
              <p class="guide-summary">{{ currentStep.summary }}</p>

              <div class="guide-body">
                <p v-for="paragraph in currentStep.body" :key="paragraph">{{ paragraph }}</p>
              </div>

              <div v-if="currentStep.id === 'install'" class="store-badges" aria-label="Download Downloader by AFTVNews">
                <a
                  v-for="badge in storeBadges"
                  :key="badge.href"
                  class="store-badge"
                  :href="badge.href"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <img :src="badge.src" :alt="badge.alt" />
                </a>
              </div>

              <div class="action-grid">
                <div
                  v-for="action in currentStep.actions"
                  :key="`${action.label}-${action.value}`"
                  class="action-card"
                  :class="{ 'is-primary': action.tone === 'primary', 'is-secondary': action.tone === 'secondary' }"
                >
                  <span>{{ action.label }}</span>
                  <strong>{{ action.value }}</strong>
                  <p>{{ action.description }}</p>
                </div>
              </div>

              <div v-if="currentStep.links.length" class="guide-links">
                <a
                  v-for="link in currentStep.links"
                  :key="link.href"
                  class="guide-link"
                  :href="link.href"
                  :target="link.href.startsWith('http') ? '_blank' : undefined"
                  :rel="link.href.startsWith('http') ? 'noopener noreferrer' : undefined"
                >
                  <span>{{ link.label }}</span>
                  <small>{{ link.description }}</small>
                </a>
              </div>
            </section>

            <aside class="screenshot-frame" :aria-label="currentStep.screenshot.templateLabel">
              <img
                v-if="loadedScreenshots[currentStep.id]"
                class="guide-screenshot"
                :src="currentStep.screenshot.src"
                :alt="currentStep.screenshot.alt"
                @error="markScreenshotMissing(currentStep.id)"
              />
              <div v-else class="screenshot-template">
                <span class="material-symbols-outlined" aria-hidden="true">tv</span>
                <strong>{{ currentStep.screenshot.templateLabel }}</strong>
                <small>{{ currentStep.screenshot.src }}</small>
              </div>
            </aside>
          </div>

          <nav class="guide-nav" aria-label="Guide navigation">
            <button
              class="nav-button nav-button-ghost"
              type="button"
              aria-label="Previous guide step"
              :disabled="currentStepIndex === 0"
              @click="goPrevious"
            >
              Back
            </button>
            <button
              v-if="!isLastStep"
              class="nav-button nav-button-primary"
              type="button"
              aria-label="Next guide step"
              @click="goNext"
            >
              Next
            </button>
            <NuxtLink
              v-else
              class="nav-button nav-button-primary"
              aria-label="Open account portal"
              to="/account"
            >
              Open Account Portal
            </NuxtLink>
          </nav>
        </article>
      </div>
    </section>
  </PublicShell>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, watch } from 'vue'
import { useRoute, useRouter } from '#imports'
import PublicShell from '~/components/portal/PublicShell.vue'
import { usePortalStore } from '~/composables/usePortalStore'
import {
  getStartedGuideSteps,
  guideStepIndex,
  resolveGuideStepId,
  storeBadges,
  type GuideStepId
} from '~/utils/get-started-guide'

const route = useRoute()
const router = useRouter()
const { state, bootstrap, signedIn, signOut } = usePortalStore()

const loadedScreenshots = reactive(
  Object.fromEntries(getStartedGuideSteps.map((step) => [step.id, true])) as Record<GuideStepId, boolean>
)

const currentStepId = computed(() => resolveGuideStepId(route.query.step))
const currentStepIndex = computed(() => guideStepIndex(currentStepId.value))
const currentStep = computed(() => getStartedGuideSteps[currentStepIndex.value] ?? getStartedGuideSteps[0])
const isLastStep = computed(() => currentStepIndex.value === getStartedGuideSteps.length - 1)

watch(
  () => route.query.step,
  (step) => {
    const resolved = resolveGuideStepId(step)
    if (step !== resolved) {
      router.replace({ query: { ...route.query, step: resolved } })
    }
  },
  { immediate: true }
)

onMounted(() => {
  bootstrap()
})

function selectStep(stepId: GuideStepId) {
  router.replace({ query: { ...route.query, step: stepId } })
}

function goPrevious() {
  if (currentStepIndex.value <= 0) return
  selectStep(getStartedGuideSteps[currentStepIndex.value - 1].id)
}

function goNext() {
  if (isLastStep.value) return
  selectStep(getStartedGuideSteps[currentStepIndex.value + 1].id)
}

function markScreenshotMissing(stepId: GuideStepId) {
  loadedScreenshots[stepId] = false
}
</script>

<style scoped>
.get-started-page {
  min-height: calc(100vh - 5rem);
  padding: 2rem 1rem 4rem;
  background:
    radial-gradient(circle at 85% 10%, rgba(83, 221, 252, 0.08), transparent 30rem),
    radial-gradient(circle at 10% 80%, rgba(186, 158, 255, 0.08), transparent 28rem),
    var(--surface-container-lowest);
}

.guide-shell {
  display: grid;
  grid-template-columns: 14rem minmax(0, 1fr);
  gap: 1.5rem;
  width: min(1280px, 100%);
  margin: 0 auto;
}

.step-index {
  position: sticky;
  top: 6rem;
  align-self: start;
  display: grid;
  gap: 0.55rem;
  padding-top: 1rem;
}

.index-label,
.guide-eyebrow {
  margin: 0;
  color: var(--text-dim);
  font-size: 0.76rem;
  font-weight: 800;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.step-pill {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  min-height: 2.75rem;
  border: 0;
  border-radius: 999px;
  padding: 0.65rem 0.85rem;
  background: rgba(255, 255, 255, 0.045);
  color: var(--text-soft);
  text-align: left;
  transition: transform 180ms ease, background 180ms ease, color 180ms ease;
}

.step-pill:hover,
.step-pill:focus-visible {
  transform: translateY(-1px);
  color: var(--text);
  outline: none;
}

.step-pill.is-active {
  background: linear-gradient(135deg, var(--primary), var(--primary-dim));
  color: white;
  font-weight: 900;
}

.step-number {
  display: inline-grid;
  place-items: center;
  width: 1.4rem;
  height: 1.4rem;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.25);
  font-size: 0.78rem;
  font-weight: 900;
}

.guide-panel {
  position: relative;
  min-height: 42rem;
  overflow: hidden;
  border-radius: 1.5rem;
  padding: 2rem 2rem 5.5rem;
  background:
    linear-gradient(145deg, rgba(26, 26, 26, 0.96), rgba(5, 5, 5, 0.98)),
    var(--surface-container);
  box-shadow: 0 40px 120px rgba(0, 0, 0, 0.5);
}

.guide-light {
  position: absolute;
  inset: -8rem -8rem auto auto;
  width: 24rem;
  height: 24rem;
  border-radius: 999px;
  background: rgba(83, 221, 252, 0.12);
  filter: blur(90px);
  pointer-events: none;
}

.guide-progress {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 2rem;
  color: var(--text-dim);
  font-size: 0.85rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.guide-content {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(18rem, 26rem);
  gap: 2rem;
  align-items: center;
}

.guide-copy h1 {
  max-width: 44rem;
  margin: 0.45rem 0 1rem;
  font-family: var(--font-display);
  font-size: clamp(2.8rem, 7vw, 5.4rem);
  font-weight: 900;
  line-height: 0.98;
  letter-spacing: 0;
}

.guide-summary {
  max-width: 42rem;
  margin: 0;
  color: var(--text-soft);
  font-size: 1.12rem;
  line-height: 1.65;
}

.guide-body {
  display: grid;
  gap: 0.8rem;
  max-width: 42rem;
  margin-top: 1.25rem;
}

.guide-body p {
  margin: 0;
  color: rgba(218, 218, 224, 0.82);
  line-height: 1.65;
}

.store-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.9rem;
  margin-top: 1.5rem;
}

.store-badge {
  display: inline-flex;
  overflow: hidden;
  border-radius: 0.6rem;
  background: #000;
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.store-badge:hover,
.store-badge:focus-visible {
  transform: translateY(-1px);
  box-shadow: 0 0 0 2px rgba(186, 158, 255, 0.28);
  outline: none;
}

.store-badge img {
  width: 12rem;
  max-width: 42vw;
  height: auto;
  display: block;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.8rem;
  max-width: 42rem;
  margin-top: 1.5rem;
}

.action-card {
  min-height: 8rem;
  border-radius: 1rem;
  padding: 1rem;
  background: rgba(255, 255, 255, 0.055);
}

.action-card.is-primary {
  background: rgba(186, 158, 255, 0.14);
}

.action-card.is-secondary {
  background: rgba(83, 221, 252, 0.11);
}

.action-card span {
  display: block;
  color: var(--text-dim);
  font-size: 0.74rem;
  font-weight: 900;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.action-card strong {
  display: block;
  margin-top: 0.45rem;
  color: var(--text);
  font-size: clamp(1.2rem, 3vw, 2rem);
  line-height: 1.1;
}

.action-card p {
  margin: 0.55rem 0 0;
  color: var(--text-soft);
  font-size: 0.92rem;
  line-height: 1.45;
}

.guide-links {
  display: flex;
  flex-wrap: wrap;
  gap: 0.8rem;
  margin-top: 1.2rem;
}

.guide-link {
  display: grid;
  gap: 0.25rem;
  width: min(100%, 18rem);
  border-radius: 0.9rem;
  padding: 0.9rem 1rem;
  background: rgba(255, 255, 255, 0.045);
  transition: transform 180ms ease, background 180ms ease;
}

.guide-link:hover,
.guide-link:focus-visible {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.075);
  outline: none;
}

.guide-link span {
  font-weight: 900;
}

.guide-link small {
  color: var(--text-soft);
  line-height: 1.35;
}

.screenshot-frame {
  padding: 1rem;
  border-radius: 1.35rem;
  background: rgba(255, 255, 255, 0.055);
  box-shadow: 0 30px 90px rgba(0, 0, 0, 0.42);
}

.guide-screenshot,
.screenshot-template {
  width: 100%;
  aspect-ratio: 16 / 10;
  border-radius: 0.9rem;
}

.guide-screenshot {
  display: block;
  object-fit: cover;
}

.screenshot-template {
  display: grid;
  place-items: center;
  gap: 0.55rem;
  padding: 1.4rem;
  border: 1px dashed rgba(255, 255, 255, 0.18);
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.07), rgba(255, 255, 255, 0.02));
  color: var(--text-soft);
  text-align: center;
}

.screenshot-template .material-symbols-outlined {
  color: var(--secondary);
  font-size: 2.4rem;
}

.screenshot-template strong {
  color: var(--text);
}

.screenshot-template small {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: var(--text-dim);
}

.guide-nav {
  position: absolute;
  left: 50%;
  bottom: 1.5rem;
  z-index: 2;
  display: flex;
  gap: 0.75rem;
  transform: translateX(-50%);
}

.nav-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 7rem;
  min-height: 2.9rem;
  border: 0;
  border-radius: 999px;
  padding: 0.8rem 1.25rem;
  font-weight: 900;
  transition: transform 180ms ease, opacity 180ms ease, box-shadow 180ms ease;
}

.nav-button:hover:not(:disabled),
.nav-button:focus-visible {
  transform: translateY(-1px);
  outline: none;
}

.nav-button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.nav-button-primary {
  background: linear-gradient(135deg, var(--primary), var(--primary-dim));
  color: white;
}

.nav-button-primary:hover,
.nav-button-primary:focus-visible {
  box-shadow: 0 0 0 2px rgba(186, 158, 255, 0.25);
}

.nav-button-ghost {
  background: rgba(255, 255, 255, 0.07);
  color: var(--text-soft);
}

@media (max-width: 980px) {
  .guide-shell {
    grid-template-columns: 1fr;
  }

  .step-index {
    position: static;
    display: flex;
    overflow-x: auto;
    padding: 0.5rem 0;
  }

  .step-pill {
    flex: 0 0 auto;
  }

  .guide-content {
    grid-template-columns: 1fr;
  }

  .screenshot-frame {
    order: -1;
  }
}

@media (max-width: 640px) {
  .get-started-page {
    padding: 1rem 0.75rem 3rem;
  }

  .guide-panel {
    min-height: auto;
    padding: 1.25rem 1rem 5.25rem;
  }

  .guide-progress {
    align-items: flex-start;
    flex-direction: column;
    gap: 0.35rem;
  }

  .action-grid {
    grid-template-columns: 1fr;
  }

  .store-badge img {
    width: 10.5rem;
  }

  .guide-nav {
    width: calc(100% - 2rem);
  }

  .nav-button {
    flex: 1;
    min-width: 0;
  }
}
</style>
```

- [ ] **Step 2: Run page and guide tests**

Run from `nexio-web`:

```bash
npx tsx --test tests/get-started-guide.test.ts tests/get-started-page-source.test.ts
```

Expected: PASS.

- [ ] **Step 3: Run Nuxt build**

Run from `nexio-web`:

```bash
npm run build
```

Expected: PASS with a completed Nuxt build.

- [ ] **Step 4: Commit the public guide page**

Run from `nexio-web`:

```bash
git add pages/get-started.vue tests/get-started-page-source.test.ts
git commit -m "feat: add public get started guide"
```

## Task 5: Update Landing Hero CTAs

**Files:**
- Modify: `nexio-web/pages/index.vue`
- Test: `nexio-web/tests/landing-hero-cta.test.ts`

- [ ] **Step 1: Write a failing landing CTA test**

Create `tests/landing-hero-cta.test.ts` with:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const pagePath = fileURLToPath(new URL('../pages/index.vue', import.meta.url))

test('landing hero shows Get Started before Manage Account and removes Explore Features', () => {
  const source = readFileSync(pagePath, 'utf8')
  const getStartedIndex = source.indexOf('>Get Started<')
  const manageAccountIndex = source.indexOf('>Manage Account<')

  assert.notEqual(getStartedIndex, -1)
  assert.notEqual(manageAccountIndex, -1)
  assert.equal(getStartedIndex < manageAccountIndex, true)
  assert.equal(source.includes('>Explore Features<'), false)
  assert.match(source, /to="\/get-started"/)
  assert.match(source, /to="\/account"/)
})
```

- [ ] **Step 2: Run the landing CTA test to verify it fails**

Run from `nexio-web`:

```bash
npx tsx --test tests/landing-hero-cta.test.ts
```

Expected: FAIL because the current hero still has `Manage Account` first and `Explore Features`.

- [ ] **Step 3: Update the hero action markup**

In `pages/index.vue`, replace the current hero action block:

```vue
<div class="landing-actions mt-10 flex flex-wrap justify-center lg:justify-start gap-4">
  <NuxtLink class="primary-btn pulse-btn px-8 py-4 text-[1rem] md:text-lg" to="/account">Manage Account</NuxtLink>
  <a href="#features" class="ghost-btn px-8 py-4 text-[1rem] md:text-lg border border-white/10 hover:bg-white/5 transition-colors">Explore Features</a>
</div>
```

with:

```vue
<div class="landing-actions mt-10 flex flex-wrap justify-center lg:justify-start gap-4">
  <NuxtLink class="primary-btn pulse-btn px-8 py-4 text-[1rem] md:text-lg" to="/get-started">Get Started</NuxtLink>
  <NuxtLink class="ghost-btn px-8 py-4 text-[1rem] md:text-lg border border-white/10 hover:bg-white/5 transition-colors" to="/account">Manage Account</NuxtLink>
</div>
```

- [ ] **Step 4: Run the landing CTA test**

Run from `nexio-web`:

```bash
npx tsx --test tests/landing-hero-cta.test.ts
```

Expected: PASS.

- [ ] **Step 5: Run all get-started-related tests**

Run from `nexio-web`:

```bash
npx tsx --test tests/get-started-guide.test.ts tests/get-started-page-source.test.ts tests/landing-hero-cta.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit the landing CTA change**

Run from `nexio-web`:

```bash
git add pages/index.vue tests/landing-hero-cta.test.ts
git commit -m "feat: route landing users to get started guide"
```

## Task 6: Browser And Build Verification

**Files:**
- Verify: `nexio-web/pages/index.vue`
- Verify: `nexio-web/pages/get-started.vue`
- Verify: `nexio-web/utils/get-started-guide.ts`

- [ ] **Step 1: Run the focused tests**

Run from `nexio-web`:

```bash
npx tsx --test tests/get-started-guide.test.ts tests/get-started-page-source.test.ts tests/landing-hero-cta.test.ts
```

Expected: PASS.

- [ ] **Step 2: Run the Nuxt production build**

Run from `nexio-web`:

```bash
npm run build
```

Expected: PASS with a completed Nuxt build.

- [ ] **Step 3: Start the local dev server**

Run from `nexio-web`:

```bash
npm run dev
```

Expected: Nuxt prints a local URL, usually `http://localhost:3000`. Keep this process running for browser verification.

- [ ] **Step 4: Verify the landing hero in the browser**

Open the local Nuxt URL in the browser.

Expected:

- The hero shows `Get Started` as the first CTA.
- The hero shows `Manage Account` as the second CTA.
- `Explore Features` is not present in the hero actions.
- Selecting `Get Started` navigates to `/get-started`.

- [ ] **Step 5: Verify `/get-started` default state**

Open:

```text
http://localhost:3000/get-started
```

Expected:

- The page redirects or replaces the URL to include `?step=install`.
- The first guide step is `Install`.
- Release code is `3316080`.
- Early Access code is `7063421`.
- Google Play and Amazon Appstore badges render and link to the Downloader listings.
- A screenshot template renders if `/get-started/get-started-install-downloader.webp` is absent.

- [ ] **Step 6: Verify direct step links**

Open:

```text
http://localhost:3000/get-started?step=addons
```

Expected:

- The active step is `Addons`.
- The step index marks `Addons` with `aria-current="step"`.
- The page includes links to `https://stremio-addons.net` and `https://uptime.thepi.es/status/nexio`.

Open:

```text
http://localhost:3000/get-started?step=cache
```

Expected:

- The active step is `Video cache`.
- The final navigation action is `Open Account Portal`.

- [ ] **Step 7: Verify responsive layout**

Use browser device emulation at `390x844` and `1280x720`.

Expected at `390x844`:

- Step index is horizontally scrollable and does not overlap content.
- Action cards stack in one column.
- Store badge text fits and badges do not overflow the viewport.
- Back/Next controls stay usable at the bottom of the guide panel.

Expected at `1280x720`:

- Step index sits beside the guide panel.
- The screenshot frame is visible.
- Body text and action cards do not overlap navigation controls.

- [ ] **Step 8: Stop the dev server**

Stop the running `npm run dev` process with `Ctrl-C`.

Expected: the server exits cleanly.

- [ ] **Step 9: Commit any verification-only fixes**

Only run this if browser verification required code fixes. Stage explicit paths only:

```bash
git add pages/get-started.vue pages/index.vue utils/get-started-guide.ts tests/get-started-guide.test.ts tests/get-started-page-source.test.ts tests/landing-hero-cta.test.ts public/install/google-play-downloader.webp public/install/amazon-appstore-downloader.webp
git commit -m "fix: polish get started guide verification issues"
```

Expected: commit includes only files changed for this feature.

## Final Integration Notes

- Work inside `nexio-web` for implementation commits because it is its own git repository.
- Do not stage root worktree files from `/Users/jneerdael/Scripts/nexio` while implementing the web feature.
- Do not use `git add -A`, `git add .`, `git commit -a`, or `git stash`.
- The untracked `.superpowers/` brainstorming directory in `nexio-web` is not part of this feature and must not be staged.
- The root design commit is already present in the parent repo as `5c34bde95`.

## Self-Review

- Spec coverage: Tasks 1-2 cover data, step order, Downloader codes, links, and screenshot metadata. Task 4 covers the public `/get-started` wizard, route query sync, step index, bottom navigation, screenshot templates, and store badges. Task 5 covers landing hero CTA order and removal of `Explore Features`. Task 6 covers build, browser, desktop, mobile, and direct-link verification.
- Placeholder scan: The plan uses concrete file paths, commands, code, and expected outcomes. It does not contain incomplete sections or deferred implementation instructions.
- Type consistency: `GuideStepId`, `GuideStep`, `storeBadges`, `getStartedGuideSteps`, `resolveGuideStepId`, and `guideStepIndex` are defined in Task 2 before use in Task 4. The tests import the same symbols and exact file paths used by the implementation.
