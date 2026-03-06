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
  kind: 'query_params' | 'path_segment' | 'composite'
  params?: Record<string, string>
  pathSegment?: string
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

function looksSensitivePathSegment(segment: string) {
  const value = segment.trim()
  if (value.length < 24) {
    return false
  }
  return /^[A-Za-z0-9._~+=-]+$/.test(value)
}

function last4(value: string): string {
  const clean = value.trim()
  return clean.slice(Math.max(0, clean.length - 4))
}

export function normalizeAddonUrl(url: string): string {
  return url.trim().replace(/\/manifest\.json$/i, '').replace(/\/$/, '')
}

export function normalizeAddonManifestUrl(baseUrl: string, manifestUrl?: string | null): string {
  const normalizedBaseUrl = normalizeAddonUrl(baseUrl)
  const candidate = String(manifestUrl || '').trim()
  if (candidate) {
    try {
      const parsed = new URL(candidate)
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
        return parsed.toString().replace(/\/$/, '')
      }
    } catch {
      // Fall back to the canonical manifest path derived from the base URL.
    }
  }

  return normalizedBaseUrl ? `${normalizedBaseUrl}/manifest.json` : ''
}

export function addonSecretRef(url: string): string {
  const normalized = normalizeAddonUrl(url)
  return `addon:${normalized.toLowerCase().replace(/^https?:\/\//, '').replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}`
}

export function maskSecretPreview(secretType: SecretType, payload: unknown): string {
  const data = payload as Record<string, any>

  if (secretType === 'addon_credential') {
    const params = (data?.params ?? {}) as Record<string, string>
    const pathSegment = String(data?.pathSegment ?? '').trim()
    const firstValue = Object.values(params).find((entry) => String(entry).trim().length > 0) || pathSegment
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
  const pathSegments = parsed.pathname.split('/').filter(Boolean)
  const hasManifestPath = pathSegments[pathSegments.length - 1]?.toLowerCase() === 'manifest.json'
  const pathSecretSegment = hasManifestPath ? pathSegments[pathSegments.length - 2] ?? null : null
  const hasPathSecret = Boolean(pathSecretSegment && looksSensitivePathSegment(pathSecretSegment))
  const publicPathSegments = hasPathSecret
    ? [...pathSegments.slice(0, -2), 'manifest.json']
    : pathSegments
  const publicPathname = publicPathSegments.length > 0 ? `/${publicPathSegments.join('/')}` : '/manifest.json'
  const normalized = normalizeAddonUrl(`${parsed.origin}${publicPathname}`)
  const publicQueryParams: Record<string, string> = {}
  const secretParams: Record<string, string> = {}

  parsed.searchParams.forEach((value, key) => {
    if (sensitiveQueryKeys.has(key.trim().toLowerCase())) {
      secretParams[key] = value
      return
    }
    publicQueryParams[key] = value
  })

  const hasQuerySecrets = Object.keys(secretParams).length > 0
  const secretRef = hasQuerySecrets || hasPathSecret ? addonSecretRef(normalized) : null
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
    secretPayload: secretRef
      ? {
          kind: hasQuerySecrets && hasPathSecret ? 'composite' : hasPathSecret ? 'path_segment' : 'query_params',
          ...(hasQuerySecrets ? { params: secretParams } : {}),
          ...(hasPathSecret ? { pathSegment: pathSecretSegment as string } : {})
        }
      : null
  }
}

export function buildResolvedManifestUrl(input: {
  manifestUrl?: string | null
  baseUrl?: string | null
  publicQueryParams?: Record<string, string> | null
  secretPayload?: AddonSecretPayload | null
}) {
  let base = normalizeAddonManifestUrl(String(input.baseUrl || ''), input.manifestUrl)
  const pathSegment = input.secretPayload?.pathSegment?.trim()
  if (pathSegment && /\/manifest\.json$/i.test(base)) {
    base = base.replace(/\/manifest\.json$/i, `/${pathSegment}/manifest.json`)
  }
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
