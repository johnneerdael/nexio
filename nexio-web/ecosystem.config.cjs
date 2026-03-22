module.exports = {
  apps: [
    {
      name: "nexio-web",
      script: ".output/server/index.mjs",
      env: {
        NODE_ENV: "production",
        PORT: 11223,
        NUXT_SUPABASE_URL: "https://yjyuomfgkqwmjvnoxurn.supabase.co",
        NUXT_SUPABASE_ANON_KEY: "sb_publishable_ar3g_KtsfaCwXNqL5rJskw_gkVf3l6P",
        NUXT_PUBLIC_SUPABASE_URL: "https://yjyuomfgkqwmjvnoxurn.supabase.co",
        NUXT_PUBLIC_SUPABASE_ANON_KEY: "sb_publishable_ar3g_KtsfaCwXNqL5rJskw_gkVf3l6P",
        NUXT_SUPABASE_SERVICE_ROLE_KEY: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlqeXVvbWZna3F3bWp2bm94dXJuIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3Mjc0OTAzOSwiZXhwIjoyMDg4MzI1MDM5fQ.6L1XjFQxvwtDmVor0kN5Pbhl5KhiOV3uE_O8ABycp9o",
        NUXT_TRAKT_CLIENT_ID: "bb3dbc8ee94ce31a453b62e1497207673ae925f30620cec7efdcd50d9fbfa6fc",
        NUXT_TRAKT_CLIENT_SECRET: "b107687d5d684ee48af6591a82ca08cfee95cde332851617ab15e47d40868f83",
        NUXT_PUBLIC_TV_LOGIN_BASE_URL: "https://nexioapp.org",
        NUXT_REAL_DEBRID_CLIENT_ID: "VJCUKZ3K2Q6W4",
        NUXT_REAL_DEBRID_CLIENT_SECRET: "6a23cae813710246bcb49b35532bb9f16f7c6902"
      }
    }
  ]
}
