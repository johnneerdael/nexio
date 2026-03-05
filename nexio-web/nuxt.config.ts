export default defineNuxtConfig({
  modules: ['@nuxtjs/tailwindcss'],
  css: ['~/assets/css/main.css'],
  app: {
    head: {
      title: 'Nexio Portal',
      meta: [
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
        {
          name: 'description',
          content: 'Account portal, settings sync, addon management, and QR approval for Nexio.'
        }
      ]
    }
  },
  runtimeConfig: {
    supabaseUrl: '',
    supabaseAnonKey: '',
    supabaseServiceRoleKey: '',
    traktClientId: '',
    traktClientSecret: '',
    public: {
      tvLoginBaseUrl: ''
    }
  },
  future: {
    compatibilityVersion: 4
  },
  compatibilityDate: '2026-03-05'
})
