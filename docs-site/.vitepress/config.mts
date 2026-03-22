import { defineConfig } from 'vitepress'

// https://vitepress.dev/reference/site-config
export default defineConfig({
  base: '/nexio/',
  title: 'Nexio Documentation',
  description: 'Product and operational documentation for the Management Portal, TV App, and developer workflows.',
  themeConfig: {
    // https://vitepress.dev/reference/default-theme-config
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Management Portal', link: '/web/' },
      { text: 'TV App', link: '/android/' },
      { text: 'Developer', link: '/dev/architecture' }
    ],

    sidebar: {
      '/web/': [
        {
          text: 'Management Portal',
          items: [
            { text: 'Overview', link: '/web/' },
            { text: 'Get Started', link: '/web/get-started' },
            { text: 'Account', link: '/web/account' },
            { text: 'Security and Data', link: '/web/security-and-data' }
          ]
        },
        {
          text: 'Admin Workspaces',
          items: [
            { text: 'Addon Manager', link: '/web/admin-workspaces/addons' },
            { text: 'Catalog Inventory', link: '/web/admin-workspaces/catalogs' },
            { text: 'Integrations', link: '/web/admin-workspaces/integrations' },
            { text: 'Formatter Getting Started', link: '/web/admin-workspaces/formatter-getting-started' },
            { text: 'Formatter Reference', link: '/web/admin-workspaces/formatter' }
          ]
        }
      ],
      '/android/': [
        {
          text: 'TV App',
          items: [
            { text: 'Overview', link: '/android/' },
            { text: 'Getting Started', link: '/android/getting-started' }
          ]
        },
        {
          text: 'Screens',
          items: [
            { text: 'Home', link: '/android/screens/home' },
            { text: 'Catalog and Library', link: '/android/screens/catalog' },
            { text: 'Media Detail', link: '/android/screens/detail' },
            { text: 'Playback Interface', link: '/android/screens/player' },
            { text: 'Settings and Account', link: '/android/screens/settings' },
            { text: 'Search and Cast', link: '/android/screens/search-and-cast' }
          ]
        },
        {
          text: 'Technical',
          items: [
            { text: 'Media3', link: '/android/technical/media3' },
            { text: 'FFmpeg', link: '/android/technical/ffmpeg' },
            { text: 'libdovi', link: '/android/technical/libdovi' },
            { text: 'IEC Passthrough', link: '/android/technical/iec' }
          ]
        }
      ],
      '/dev/': [
        {
          text: 'Developer Guide',
          items: [
            { text: 'Architecture', link: '/dev/architecture' },
            { text: 'Deployment', link: '/dev/deployment' }
          ]
        }
      ]
    },

    socialLinks: [{ icon: 'github', link: 'https://github.com/jneerdael/nexio' }],

    footer: {
      message: 'Released under the GPL-3.0 License.',
      copyright: 'Copyright © 2024 Nexio contributors'
    }
  },
  appearance: true
})
