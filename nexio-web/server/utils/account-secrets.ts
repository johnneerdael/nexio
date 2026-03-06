import { createError } from 'h3'
import type { AddonRecord, SecretMetadata, SecretType } from '~/types/portal'

export const secretRefs = {
  tmdb: 'integration:tmdb',
  mdblist: 'integration:mdblist',
  rpdb: 'integration:rpdb',
  topPosters: 'integration:topposters',
  trakt: 'integration:trakt'
} as const

export type AddonSecretPayload = {
  kind: 'query_params' | 'query_token'
  params: Record<string, string>
}

export type AddonTransportPayload = {
  addon_id?: string
  addon_name?: string | null
  base_url?: string | null
  manifest_url?: string | null
  public_query_params?: Record<string, string> | null
  secret_payload?: AddonSecretPayload | null
}

const sensitiveQueryKeys = new Set([
  'access_token',
  'api_key',
  'apikey',
  'auth',
  'authorization',
  'debrid_api_key',
  'key',
  'password',
  'premiumize',
  'rd',
  'rd_key',
  'refresh_token',
  'token',
  'user',
  'username'
])

function last4(value: string): string {
  const clean = value.trim()
  return clean.slice(Math.max(0, clean.length - 4))
}

export function normalizeAddonUrl(url: string): string {
  return url.trim().replace(/\/manifest\.json$/i, '').replace(/\/$/, '')
}

export function addonSecretRef(url: string): string {
  const normalized = normalizeAddonUrl(url)
  return `addon:${normalized.toLowerCase().replace(/^https?:\/\//, '').replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}`
}

export function maskSecretPreview(secretType: SecretType, payload: unknown): string {
  const data = payload as Record<string, any>

  if (secretType === 'addon_credential') {
    const params = (data?.params ?? {}) as Record<string, string>
    const firstValue = Object.values(params).find((entry) => String(entry).trim().length > 0)
    return firstValue ? `Configured ••••${last4(String(firstValue))}` : 'Configured'
  }

  if (secretType === 'trakt_access_token' || secretType === 'trakt_refresh_token') {
    const token = String(data?.token ?? data?.accessToken ?? data?.refreshToken ?? '').trim()
    return token ? `Connected ••••${last4(token)}` : 'Connected'
  }

  const key = String(data?.apiKey ?? '').trim()
  return key ? `Stored ••••${last4(key)}` : 'Stored'
}

export function parseAddonInstallUrl(rawUrl: string): {
  addon: AddonRecord
  secretType: SecretType | null
  secretRef: string | null
  secretPayload: AddonSecretPayload | null
} {
  const candidate = rawUrl.trim()
  if (!candidate) {
    throw createError({ statusCode: 400, statusMessage: 'Addon URL is required.' })
  }

  const parsed = new URL(candidate)
  const normalized = normalizeAddonUrl(`${parsed.origin}${parsed.pathname}`)
  const publicQueryParams: Record<string, string> = {}
  const secretParams: Record<string, string> = {}

  parsed.searchParams.forEach((value, key) => {
    if (sensitiveQueryKeys.has(key.trim().toLowerCase())) {
      secretParams[key] = value
      return
    }
    publicQueryParams[key] = value
  })

  const secretRef = Object.keys(secretParams).length > 0 ? addonSecretRef(normalized) : null
  const manifestUrl = `${normalized}/manifest.json`
  const addon: AddonRecord = {
    id: crypto.randomUUID(),
    url: normalized,
    manifestUrl,
    name: parsed.hostname.replace(/^www\./, ''),
    enabled: true,
    installKind: secretRef ? 'configured' : 'manifest',
    publicQueryParams,
    secretRef,
    sortOrder: 0
  }

  return {
    addon,
    secretType: secretRef ? 'addon_credential' : null,
    secretRef,
    secretPayload: secretRef ? { kind: 'query_params', params: secretParams } : null
  }
}

export function buildResolvedManifestUrl(input: {
  manifestUrl?: string | null
  baseUrl?: string | null
  publicQueryParams?: Record<string, string> | null
  secretPayload?: AddonSecretPayload | null
}) {
  const base = String(input.manifestUrl || '').trim() || `${normalizeAddonUrl(String(input.baseUrl || ''))}/manifest.json`
  const params = new URLSearchParams()
  Object.entries(input.publicQueryParams ?? {}).forEach(([key, value]) => {
    if (value?.trim()) {
      params.set(key, value)
    }
  })

  if (input.secretPayload?.params) {
    Object.entries(input.secretPayload.params).forEach(([key, value]) => {
      if (value?.trim()) {
        params.set(key, value)
      }
    })
  }

  const query = params.toString()
  return query ? `${base}?${query}` : base
}

export function mapSecretRows(rows: Array<{
  id: string
  secret_type: SecretType
  secret_ref: string
  masked_preview?: string | null
  status?: string | null
  version?: number | null
  updated_at?: string | null
}>): SecretMetadata[] {
  return rows.map((row) => ({
    id: row.id,
    secretType: row.secret_type,
    secretRef: row.secret_ref,
    maskedPreview: row.masked_preview ?? null,
    status: (row.status as SecretMetadata['status']) ?? 'configured',
    version: row.version ?? 1,
    updatedAt: row.updated_at ?? null
  }))
}
