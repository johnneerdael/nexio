import type { AddonRecord, SecretType } from '~/types/portal'

export const secretRefs = {
  mdblist: 'integration:mdblist',
  rpdb: 'integration:rpdb',
  topPosters: 'integration:topposters',
  trakt: 'integration:trakt'
} as const

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

export type AddonSecretPayload = {
  kind: 'query_params' | 'query_token'
  params: Record<string, string>
}

export function normalizeAddonUrl(url: string): string {
  return url.trim().replace(/\/manifest\.json$/i, '').replace(/\/$/, '')
}

export function addonSecretRef(url: string): string {
  const normalized = normalizeAddonUrl(url)
  return `addon:${normalized.toLowerCase().replace(/^https?:\/\//, '').replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}`
}

export function parseAddonInstallUrl(rawUrl: string): {
  addon: AddonRecord
  secretType: SecretType | null
  secretRef: string | null
  secretPayload: AddonSecretPayload | null
} {
  const candidate = rawUrl.trim()
  if (!candidate) {
    throw new Error('Addon URL is required.')
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

  return {
    addon: {
      id: crypto.randomUUID(),
      url: normalized,
      manifestUrl: `${normalized}/manifest.json`,
      name: parsed.hostname.replace(/^www\./, ''),
      enabled: true,
      installKind: secretRef ? 'configured' : 'manifest',
      publicQueryParams,
      secretRef,
      sortOrder: 0
    },
    secretType: secretRef ? 'addon_credential' : null,
    secretRef,
    secretPayload: secretRef ? { kind: 'query_params', params: secretParams } : null
  }
}
