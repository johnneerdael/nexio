# Nexio Web Hosting Guide

This is a quick guide for hosting `nexio-web` for `nexioapp.org`.

## Important Constraint
`nexio-web` is **not** a static site.

It contains server routes for:
- Supabase auth
- public snapshot bootstrap/persist
- secret set/delete/resolve flows
- Trakt device auth
- addon manifest inspection

That means you must run it as a **Node server** behind HTTPS.

Do not deploy it as plain static hosting on GitHub Pages, Netlify static export, or Cloudflare Pages static-only mode.

## Requirements
Server requirements:
- Linux VPS or container host
- Node.js 20+
- `npm`
- HTTPS reverse proxy

Recommended minimal stack:
- Ubuntu VPS
- Node.js 20 or 22
- `pm2` or `systemd`
- Caddy or Nginx for TLS and reverse proxy

## Required Environment Variables
Set these on the server:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY`
- `TRAKT_CLIENT_ID`
- `TRAKT_CLIENT_SECRET`
- `NUXT_PUBLIC_TV_LOGIN_BASE_URL`

Notes:
- `SUPABASE_SERVICE_ROLE_KEY` is server-only.
- Never expose it to the browser.
- `NUXT_PUBLIC_TV_LOGIN_BASE_URL` should be your public site URL, for example `https://nexioapp.org`.

## Build And Run
From the repo root:

```powershell
cd nexio-web
npm install
npm run build
node .output/server/index.mjs
```

Default Nuxt/Nitro bind behavior is controlled by environment. A typical production setup is:

```bash
PORT=3000
HOST=127.0.0.1
node .output/server/index.mjs
```

Then place Caddy or Nginx in front of it.

## Recommended Production Layout
1. App server listens on `127.0.0.1:3000`
2. Reverse proxy terminates HTTPS on `nexioapp.org`
3. Reverse proxy forwards traffic to the local Node process
4. Process manager restarts the app on crash/reboot

## Example Caddyfile
```caddy
nexioapp.org {
  encode gzip zstd
  reverse_proxy 127.0.0.1:3000
}
```

## Example PM2 Commands
```bash
cd /srv/nexio/NuvioTV/nexio-web
pm2 start .output/server/index.mjs --name nexio-web
pm2 save
pm2 startup
```

## Example systemd Service
```ini
[Unit]
Description=Nexio Web
After=network.target

[Service]
Type=simple
WorkingDirectory=/srv/nexio/NuvioTV/nexio-web
Environment=PORT=3000
Environment=HOST=127.0.0.1
Environment=SUPABASE_URL=...
Environment=SUPABASE_ANON_KEY=...
Environment=SUPABASE_SERVICE_ROLE_KEY=...
Environment=TRAKT_CLIENT_ID=...
Environment=TRAKT_CLIENT_SECRET=...
Environment=NUXT_PUBLIC_TV_LOGIN_BASE_URL=https://nexioapp.org
ExecStart=/usr/bin/node .output/server/index.mjs
Restart=always
RestartSec=5
User=www-data
Group=www-data

[Install]
WantedBy=multi-user.target
```

## Deployment Steps
1. Pull the repo on the server.
2. Configure the environment variables.
3. Run `npm install`.
4. Run `npm run build`.
5. Start `node .output/server/index.mjs` through `pm2` or `systemd`.
6. Put Caddy or Nginx in front of it.
7. Point `nexioapp.org` DNS to the server.

## Updating
For each deploy:

```bash
git pull
cd nexio-web
npm install
npm run build
pm2 restart nexio-web
```

If using `systemd`, replace the last command with:

```bash
sudo systemctl restart nexio-web
```

## Health Check
After deploy, verify:
- homepage loads
- `/account` loads
- login works
- bootstrap hits Supabase successfully
- saving settings works
- secret save/delete flows work
- Trakt device auth works
- TV QR approval page works

## Current Recommendation
For a straightforward production deployment of `nexioapp.org`, use:
- one VPS
- Caddy
- Node 20+
- `pm2`

That is enough for the current portal architecture.
