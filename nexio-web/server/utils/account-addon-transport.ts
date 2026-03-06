import type { AddonRecord } from '~/types/portal'
import { buildResolvedManifestUrl, normalizeAddonManifestUrl, normalizeAddonUrl, type AddonSecretPayload, type AddonTransportPayload } from '~/server/utils/account-secrets'
import { supabaseFetch } from '~/server/utils/supabase'

type AccountAddonRow = {
  id?: string
  name?: string | null
  base_url?: string | null
  manifest_url?: string | null
  public_query_params?: Record<string, string> | null
  secret_ref?: string | null
}

function hasTransportData(payload: AddonTransportPayload | null | undefined) {
  return Boolean(
    payload?.base_url ||
    payload?.manifest_url ||
    (payload?.secret_payload && Object.keys(payload.secret_payload).length > 0)
  )
}

async function resolveSecretPayload(userId: string, secretRef: string, source: string): Promise<AddonSecretPayload | null> {
  const payload = await supabaseFetch<AddonSecretPayload>(
    '/rest/v1/rpc/service_resolve_account_secret',
    {
      method: 'POST',
      body: JSON.stringify({
        p_user_id: userId,
        p_secret_type: 'addon_credential',
        p_secret_ref: secretRef,
        p_source: source
      })
    },
    undefined,
    true
  )

  return payload && Object.keys(payload).length > 0 ? payload : null
}

async function lookupAddonRow(userId: string, addon: Pick<AddonRecord, 'url' | 'secretRef'>) {
  const normalizedUrl = normalizeAddonUrl(addon.url)
  const filters = [
    'select=id,name,base_url,manifest_url,public_query_params,secret_ref',
    `user_id=eq.${encodeURIComponent(userId)}`,
    `base_url=eq.${encodeURIComponent(normalizedUrl)}`
  ]

  if (addon.secretRef) {
    filters.push(`secret_ref=eq.${encodeURIComponent(addon.secretRef)}`)
  } else {
    filters.push('secret_ref=is.null')
  }

  filters.push('limit=1')

  const rows = await supabaseFetch<AccountAddonRow[]>(
    `/rest/v1/account_addons_public?${filters.join('&')}`,
    {},
    undefined,
    true
  )

  return rows[0] ?? null
}

export async function resolveAddonTransport(
  userId: string,
  addon: Pick<AddonRecord, 'id' | 'url' | 'manifestUrl' | 'publicQueryParams' | 'secretRef'>,
  source: string
): Promise<AddonTransportPayload> {
  if (addon.id) {
    try {
      const payload = await supabaseFetch<AddonTransportPayload>(
        '/rest/v1/rpc/service_get_account_addon_transport',
        {
          method: 'POST',
          body: JSON.stringify({
            p_user_id: userId,
            p_addon_id: addon.id,
            p_source: source
          })
        },
        undefined,
        true
      )

      if (hasTransportData(payload)) {
        return payload
      }
    } catch {
      // Fall back to a stable lookup when the synced row UUID is stale or missing.
    }
  }

  const row = await lookupAddonRow(userId, addon)
  const secretPayload = row?.secret_ref
    ? await resolveSecretPayload(userId, row.secret_ref, source)
    : null

  return {
    addon_id: row?.id ?? addon.id,
    addon_name: row?.name ?? null,
    base_url: row?.base_url ?? normalizeAddonUrl(addon.url),
    manifest_url: normalizeAddonManifestUrl(
      row?.base_url ?? addon.url,
      row?.manifest_url ?? addon.manifestUrl ?? null
    ),
    public_query_params: row?.public_query_params ?? addon.publicQueryParams ?? {},
    secret_payload: secretPayload
  }
}

export function resolvedAddonManifestUrl(transport: AddonTransportPayload) {
  return buildResolvedManifestUrl({
    manifestUrl: transport.manifest_url,
    baseUrl: transport.base_url,
    publicQueryParams: transport.public_query_params ?? {},
    secretPayload: transport.secret_payload ?? null
  })
}
