import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody } from '~/server/utils/supabase'
import {
  normalizeImportedAddonCandidates,
  requireCredentials,
  toImportedAddonCandidate
} from '~/server/utils/migration-addons'

type StremioLoginResponse = {
  result?: {
    authKey?: string
  }
  error?: string
}

type StremioAddonCollectionResponse = {
  result?: {
    addons?: Array<{
      manifest?: {
        id?: string
        name?: string
        description?: string
        logo?: string
      }
      transportUrl?: string
      name?: string
    }>
  }
}

const STREMIO_API = 'https://api.strem.io'
const STREMIO_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Stremio/4.4.159'

export default defineEventHandler(async (event) => {
  bearerToken(event)

  const body = await readJsonBody<{ email?: string; password?: string }>(event)
  const { email, password } = requireCredentials(body.email, body.password)

  const loginResponse = await fetch(`${STREMIO_API}/api/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': STREMIO_UA
    },
    body: JSON.stringify({ email, password, facebook: false, type: 'login' })
  })

  const loginPayload = (await loginResponse.json()) as StremioLoginResponse
  if (!loginResponse.ok || !loginPayload?.result?.authKey) {
    throw createError({
      statusCode: 401,
      statusMessage: loginPayload?.error || `Stremio login failed (${loginResponse.status}).`
    })
  }

  const authKey = loginPayload.result.authKey

  const addonsResponse = await fetch(`${STREMIO_API}/api/addonCollectionGet`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': STREMIO_UA
    },
    body: JSON.stringify({ authKey, type: 'AddonCollection', id: 'addon_collection' })
  })

  if (!addonsResponse.ok) {
    throw createError({ statusCode: addonsResponse.status, statusMessage: 'Failed to pull Stremio addons.' })
  }

  const addonsPayload = (await addonsResponse.json()) as StremioAddonCollectionResponse
  const rawAddons = addonsPayload?.result?.addons ?? []

  const imported = normalizeImportedAddonCandidates(
    rawAddons
      .map((addon, index) => {
        const manifestUrl = addon.transportUrl
          ? addon.transportUrl.endsWith('manifest.json')
            ? addon.transportUrl
            : `${addon.transportUrl.replace(/\/$/, '')}/manifest.json`
          : ''

        return toImportedAddonCandidate(
          {
            url: manifestUrl,
            manifestUrl,
            name: addon.manifest?.name ?? addon.name ?? 'Addon',
            description: addon.manifest?.description,
            logo: addon.manifest?.logo,
            enabled: true
          },
          index
        )
      })
      .filter((entry): entry is NonNullable<typeof entry> => Boolean(entry))
  )

  return okJson({
    source: 'stremio' as const,
    addons: imported
  })
})
