import { bearerToken, okJson, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { mapSecretRows } from '~/server/utils/account-secrets'
import { defaultAccountAddons, defaultSettings, demoDevices } from '~/utils/portal-defaults'
import type { AddonRecord, BootstrapPayload, LinkedDevice, PortalSettings, SecretType } from '~/types/portal'

type SnapshotRpcPayload = {
  user_id?: string
  revision?: number
  updated_at?: string | null
  settings?: PortalSettings
  addons?: Array<{
    id?: string
    url?: string
    manifest_url?: string | null
    name?: string | null
    description?: string | null
    enabled?: boolean | null
    sort_order?: number | null
    public_query_params?: Record<string, string> | null
    install_kind?: 'manifest' | 'configured' | null
    secret_ref?: string | null
  }>
}

type SecretRow = {
  id: string
  secret_type: SecretType
  secret_ref: string
  masked_preview?: string | null
  status?: string | null
  version?: number | null
  updated_at?: string | null
}

type DeviceRow = {
  id: string
  device_user_id?: string | null
  device_name?: string | null
  device_model?: string | null
  device_platform?: string | null
  last_seen_at?: string | null
  status?: 'online' | 'idle' | 'offline' | null
}

type RpcMutationResult = Array<{
  sync_revision?: number
  updated_at?: string | null
}>

function toAddonRecords(addons: SnapshotRpcPayload['addons']): AddonRecord[] {
  return (addons ?? []).map((addon, index) => ({
    id: addon.id ?? crypto.randomUUID(),
    url: addon.url ?? '',
    manifestUrl: addon.manifest_url ?? `${String(addon.url || '').replace(/\/$/, '')}/manifest.json`,
    name: addon.name ?? addon.url ?? 'Addon',
    enabled: addon.enabled ?? true,
    description: addon.description ?? undefined,
    publicQueryParams: addon.public_query_params ?? {},
    installKind: addon.install_kind ?? 'manifest',
    secretRef: addon.secret_ref ?? null,
    sortOrder: addon.sort_order ?? index
  })).filter((addon) => addon.url)
}

export default defineEventHandler(async (event) => {
  const token = bearerToken(event)
  const user = await supabaseUser(event)

  let settings = defaultSettings()
  let addons = defaultAccountAddons()
  let secretStatuses = [] as BootstrapPayload['snapshot']['secretStatuses']
  let syncRevision = 1
  let lastSyncedAt: string | null = null
  let linkedDevices: LinkedDevice[] = demoDevices()

  try {
    const snapshot = await supabaseFetch<SnapshotRpcPayload>('/rest/v1/rpc/sync_pull_account_snapshot', {
      method: 'POST',
      body: JSON.stringify({})
    }, token)

    const pulledSettings = snapshot.settings
    const pulledAddons = toAddonRecords(snapshot.addons)

    if (pulledSettings && (Object.keys(pulledSettings).length > 0 || pulledAddons.length > 0)) {
      settings = pulledSettings
      addons = pulledAddons.length > 0 ? pulledAddons : defaultAccountAddons()
      syncRevision = snapshot.revision ?? syncRevision
      lastSyncedAt = snapshot.updated_at ?? null
    } else {
      const seededSettings = await supabaseFetch<RpcMutationResult>('/rest/v1/rpc/sync_push_account_settings', {
        method: 'POST',
        body: JSON.stringify({
          p_settings_payload: settings,
          p_source: 'web-bootstrap'
        })
      }, token)

      const seededAddons = await supabaseFetch<RpcMutationResult>('/rest/v1/rpc/sync_push_account_addons', {
        method: 'POST',
        body: JSON.stringify({
          p_addons: defaultAccountAddons().map((addon, index) => ({
            url: addon.url,
            manifest_url: addon.manifestUrl,
            name: addon.name,
            description: addon.description ?? null,
            enabled: addon.enabled,
            public_query_params: addon.publicQueryParams ?? {},
            install_kind: addon.installKind ?? 'manifest',
            secret_ref: addon.secretRef ?? null,
            sort_order: index
          })),
          p_source: 'web-bootstrap'
        })
      }, token)

      const seededRevision = Math.max(
        seededSettings[0]?.sync_revision ?? 0,
        seededAddons[0]?.sync_revision ?? 0,
        syncRevision
      )
      syncRevision = seededRevision
      lastSyncedAt = seededAddons[0]?.updated_at ?? seededSettings[0]?.updated_at ?? new Date().toISOString()
    }
  } catch {
    // Keep the portal usable before the new Supabase migration lands.
  }

  try {
    const rows = await supabaseFetch<SecretRow[]>(`/rest/v1/account_secrets?select=id,secret_type,secret_ref,masked_preview,status,version,updated_at`, {}, token, false)
    secretStatuses = mapSecretRows(rows)
  } catch {
    // Secret metadata can stay empty until the secret contract is installed.
  }

  try {
    const rows = await supabaseFetch<DeviceRow[]>(`/rest/v1/linked_devices?owner_id=eq.${encodeURIComponent(user.id)}&select=id,device_user_id,device_name,device_model,device_platform,last_seen_at,status`, {}, token, true)
    if (rows.length > 0) {
      linkedDevices = rows.map((row) => ({
        id: row.device_user_id ?? row.id,
        deviceUserId: row.device_user_id ?? row.id,
        name: row.device_name ?? 'Linked TV',
        model: row.device_model ?? 'Unknown model',
        platform: row.device_platform ?? 'Android TV',
        lastSeenAt: row.last_seen_at ?? new Date().toISOString(),
        status: row.status ?? 'idle'
      }))
    }
  } catch {
    // Keep linked device fallback data in place until the schema is expanded.
  }

  const payload: BootstrapPayload = {
    session: null,
    snapshot: {
      settings,
      addons,
      secretStatuses,
      linkedDevices,
      syncRevision,
      lastSyncedAt
    }
  }

  return okJson(payload)
})
