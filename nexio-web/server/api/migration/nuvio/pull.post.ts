import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody } from '~/server/utils/supabase'
import {
  normalizeImportedAddonCandidates,
  requireCredentials,
  toImportedAddonCandidate
} from '~/server/utils/migration-addons'

type NuvioAuthResponse = {
  access_token?: string
  user?: {
    id?: string
  }
}

type NuvioAccountAddonRow = {
  base_url?: string | null
  manifest_url?: string | null
  name?: string | null
  description?: string | null
  enabled?: boolean | null
  sort_order?: number | null
  public_query_params?: Record<string, string> | null
}

type NuvioLegacyAddonRow = {
  url?: string | null
  name?: string | null
  sort_order?: number | null
}

function runtimeConfig() {
  const config = useRuntimeConfig()
  const url = String(config.migrationNuvioSupabaseUrl || config.supabaseUrl || '').replace(/\/$/, '')
  const anonKey = String(config.migrationNuvioSupabaseAnonKey || config.supabaseAnonKey || '')
  if (!url || !anonKey) {
    throw createError({
      statusCode: 503,
      statusMessage: 'Nuvio migration runtime config is missing.'
    })
  }

  return { url, anonKey }
}

async function queryAccountAddons(
  baseUrl: string,
  headers: Headers,
  userId: string
): Promise<NuvioAccountAddonRow[]> {
  const response = await fetch(
    `${baseUrl}/rest/v1/account_addons_public?select=base_url,manifest_url,name,description,enabled,sort_order,public_query_params&user_id=eq.${encodeURIComponent(userId)}&order=sort_order.asc`,
    { headers }
  )

  if (!response.ok) {
    return []
  }

  const payload = (await response.json()) as NuvioAccountAddonRow[]
  return Array.isArray(payload) ? payload : []
}

async function queryLegacyAddons(
  baseUrl: string,
  headers: Headers,
  userId: string
): Promise<NuvioLegacyAddonRow[]> {
  const response = await fetch(
    `${baseUrl}/rest/v1/addons?select=url,name,sort_order&user_id=eq.${encodeURIComponent(userId)}&order=sort_order.asc`,
    { headers }
  )

  if (!response.ok) {
    return []
  }

  const payload = (await response.json()) as NuvioLegacyAddonRow[]
  return Array.isArray(payload) ? payload : []
}

export default defineEventHandler(async (event) => {
  bearerToken(event)

  const body = await readJsonBody<{ email?: string; password?: string }>(event)
  const { email, password } = requireCredentials(body.email, body.password)
  const { url, anonKey } = runtimeConfig()

  const authResponse = await fetch(`${url}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: {
      apikey: anonKey,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ email, password })
  })

  const authPayload = (await authResponse.json()) as NuvioAuthResponse
  const accessToken = String(authPayload.access_token || '')
  const userId = String(authPayload.user?.id || '')

  if (!authResponse.ok || !accessToken || !userId) {
    throw createError({
      statusCode: 401,
      statusMessage: 'Nuvio login failed.'
    })
  }

  const headers = new Headers({
    apikey: anonKey,
    Authorization: `Bearer ${accessToken}`,
    'Content-Type': 'application/json'
  })

  const accountRows = await queryAccountAddons(url, headers, userId)

  const importedFromAccount = accountRows
    .map((row, index) =>
      toImportedAddonCandidate(
        {
          url: row.base_url,
          manifestUrl: row.manifest_url,
          name: row.name,
          description: row.description,
          enabled: row.enabled,
          publicQueryParams: row.public_query_params ?? {}
        },
        index
      )
    )
    .filter((entry): entry is NonNullable<typeof entry> => Boolean(entry))

  let imported = importedFromAccount

  if (imported.length === 0) {
    const legacyRows = await queryLegacyAddons(url, headers, userId)
    imported = legacyRows
      .map((row, index) =>
        toImportedAddonCandidate(
          {
            url: row.url,
            manifestUrl: row.url,
            name: row.name,
            enabled: true,
            publicQueryParams: {}
          },
          index
        )
      )
      .filter((entry): entry is NonNullable<typeof entry> => Boolean(entry))
  }

  return okJson({
    source: 'nuvio' as const,
    addons: normalizeImportedAddonCandidates(imported)
  })
})
