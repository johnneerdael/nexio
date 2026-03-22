import { defineConfig } from 'vitepress'

// https://vitepress.dev/reference/site-config
export default defineConfig({
  title: "NEXIO Documentation",
  description: "The ultimate documentation portal for NEXIO Android and Web apps.",
  themeConfig: {
    // https://vitepress.dev/reference/default-theme-config
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Web App', link: '/web/' },
      { text: 'Android App', link: '/android/' },
      { text: 'Developer Guide', link: '/dev/' }
    ],

    sidebar: {
      '/web/': [
        {
          text: 'Introduction',
          items: [
            { text: 'Overview', link: '/web/' },
            { text: 'Security & Data', link: '/web/security-and-data' },
            { text: 'Account Management', link: '/web/account' }
          ]
        },
        {
          text: 'Admin Workspaces',
          items: [
            { text: 'Addon Manager', link: '/web/admin-workspaces/addons' },
            { text: 'Integrations', link: '/web/admin-workspaces/integrations' },
            { text: 'Catalog Inventory', link: '/web/admin-workspaces/catalogs' },
            { text: 'Custom Formatter', link: '/web/admin-workspaces/formatter' }
          ]
        }
      ],
      '/android/': [
        {
          text: 'Introduction',
          items: [
            { text: 'Overview', link: '/android/' }
          ]
        },
        {
          text: 'App Screens',
          items: [
            { text: 'Home & Feed Browser', link: '/android/screens/home' },
            { text: 'Catalog & Library', link: '/android/screens/catalog' },
            { text: 'Media Detail', link: '/android/screens/detail' },
            { text: 'Playback Interface', link: '/android/screens/player' },
            { text: 'Settings & Account', link: '/android/screens/settings' },
            { text: 'Search & Cast', link: '/android/screens/search-and-cast' }
          ]
        },
        {
          text: 'Technical Deep Dives',
          items: [
            { text: 'libdovi', link: '/android/technical/libdovi' },
            { text: 'ffmpeg', link: '/android/technical/ffmpeg' },
            { text: 'Media3 / ExoPlayer', link: '/android/technical/media3' },
            { text: 'IEC Passthrough', link: '/android/technical/iec' }
          ]
        }
      ],
      '/dev/': [
        {
          text: 'Developer Guide',
          items: [
            { text: 'Architecture Overview', link: '/dev/architecture' },
            { text: 'Deployment Guide', link: '/dev/deployment' }
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/jneerdael/nexio' }
    ],

    footer: {
      message: 'Released under the GPL-3.0 License.',
      copyright: 'Copyright © 2024 NEXIO Team'
    }
  },
  appearance: 'dark' // Default to dark mode
})
