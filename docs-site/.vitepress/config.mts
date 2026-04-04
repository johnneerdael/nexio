import { defineConfig } from 'vitepress'

// https://vitepress.dev/reference/site-config
const startHereSection = {
  text: 'Getting Started',
  items: [
    { text: 'Creator Best-Practices Setup Guide', link: '/start-here/' }
  ]
}

const watchingSection = {
  text: 'Watching',
  items: [
    { text: 'Overview', link: '/watch/' },
    { text: 'Home and Continue Watching', link: '/watch/home-and-continue-watching' },
    { text: 'Details, Seasons, and Watching Flow', link: '/watch/details-seasons-and-watching-flow' },
    { text: 'Trailers and Recaps', link: '/watch/trailers-and-recaps' }
  ]
}

const playbackSection = {
  text: 'Playback',
  items: [
    { text: 'Overview', link: '/playback/' },
    { text: 'Playback Tuning', link: '/playback/playback-tuning' },
    { text: 'Subtitles and Auto-Translate', link: '/playback/subtitles-and-auto-translate' },
    { text: 'Audio Codecs and Device Advice', link: '/playback/audio-codecs-and-device-advice' },
    { text: 'Troubleshooting', link: '/troubleshooting/' }
  ]
}

const integrationsSection = {
  text: 'Integrations',
  items: [
    { text: 'Overview', link: '/integrations/' },
    { text: 'Debrid and Service Wrap', link: '/integrations/debrid-and-service-wrap' },
    { text: 'Library Integration', link: '/integrations/library-integration' },
    { text: 'Ratings and Metadata', link: '/integrations/ratings-and-metadata' },
    { text: 'Screensaver and Idle Experience', link: '/integrations/screensaver-and-idle-experience' },
    { text: 'Portal Integrations', link: '/web/admin-workspaces/integrations' }
  ]
}

const customizeSection = {
  text: 'Customize',
  items: [
    { text: 'Overview', link: '/customize/' },
    { text: 'Catalog Views and Personalization', link: '/customize/catalog-views-and-personalization' },
    { text: 'Universal Formatter', link: '/customize/universal-formatter' }
  ]
}

const customizeSupportSection = {
  text: 'Portal Support',
  items: [
    { text: 'Addon Manager', link: '/web/admin-workspaces/addons' },
    { text: 'Catalog Inventory', link: '/web/admin-workspaces/catalogs' },
    { text: 'Formatter Getting Started', link: '/web/admin-workspaces/formatter-getting-started' },
    { text: 'Formatter Reference', link: '/web/admin-workspaces/formatter' }
  ]
}

const advancedSection = {
  text: 'Advanced',
  items: [
    { text: 'Overview', link: '/advanced/' },
    { text: 'Options and Self-Hosting', link: '/advanced/options-and-self-hosting' },
    { text: 'Troubleshooting', link: '/troubleshooting/' },
    { text: 'Media3', link: '/android/technical/media3' },
    { text: 'FFmpeg', link: '/android/technical/ffmpeg' },
    { text: 'libdovi', link: '/android/technical/libdovi' },
    { text: 'IEC Passthrough', link: '/android/technical/iec' },
    { text: 'Developer Architecture', link: '/dev/architecture' }
  ]
}

const troubleshootingSection = {
  text: 'Troubleshooting',
  items: [
    { text: 'Overview', link: '/troubleshooting/' },
    { text: 'Getting Started', link: '/start-here/' },
    { text: 'Watching', link: '/watch/' },
    { text: 'Playback', link: '/playback/' },
    { text: 'Integrations', link: '/integrations/' },
    { text: 'Customize', link: '/customize/' },
    { text: 'Advanced', link: '/advanced/' }
  ]
}

const developerSection = {
  text: 'Developer',
  items: [
    { text: 'Architecture', link: '/dev/architecture' },
    { text: 'Deployment', link: '/dev/deployment' }
  ]
}

const androidTechnicalSection = {
  text: 'Android Technical',
  items: [
    { text: 'Media3', link: '/android/technical/media3' },
    { text: 'FFmpeg', link: '/android/technical/ffmpeg' },
    { text: 'libdovi', link: '/android/technical/libdovi' },
    { text: 'IEC Passthrough', link: '/android/technical/iec' }
  ]
}

const taskBasedSidebar = [
  startHereSection,
  watchingSection,
  playbackSection,
  integrationsSection,
  customizeSection,
  advancedSection,
  androidTechnicalSection,
  troubleshootingSection,
  developerSection
]

const webSidebar = [
  ...taskBasedSidebar,
  customizeSupportSection
]

const helpSection = {
  text: 'Need Help',
  items: [{ text: 'Troubleshooting', link: '/troubleshooting/' }]
}

const developerNotesSection = {
  text: 'Developer Notes',
  items: [{ text: 'Developer Architecture', link: '/dev/architecture' }]
}

export default defineConfig({
  base: '/nexio/',
  title: 'Nexio Documentation',
  description: 'One product: watch in the TV app, prepare setup in the companion website, and use developer docs when you need implementation detail.',
  themeConfig: {
    // https://vitepress.dev/reference/default-theme-config
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Getting Started', link: '/start-here/' },
      { text: 'Watching', link: '/watch/' },
      { text: 'Playback', link: '/playback/' },
      { text: 'Integrations', link: '/integrations/' },
      { text: 'Customize', link: '/customize/' },
      { text: 'Advanced', link: '/advanced/' },
      { text: 'Developer', link: '/dev/architecture' }
    ],

    sidebar: {
      '/web/': webSidebar,
      '/android/': taskBasedSidebar,
      '/start-here/': [startHereSection, helpSection],
      '/watch/': [watchingSection, helpSection],
      '/playback/': [playbackSection, developerNotesSection],
      '/integrations/': [integrationsSection, helpSection],
      '/customize/': [customizeSection, helpSection],
      '/advanced/': [advancedSection, androidTechnicalSection],
      '/troubleshooting/': [troubleshootingSection],
      '/dev/': [developerSection, androidTechnicalSection]
    },

    socialLinks: [{ icon: 'github', link: 'https://github.com/jneerdael/nexio' }],

    footer: {
      message: 'Released under the GPL-3.0 License.',
      copyright: 'Copyright © 2024 Nexio contributors'
    }
  },
  appearance: true
})
