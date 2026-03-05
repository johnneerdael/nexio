import { computed, watch } from 'vue'
import { parseAddonInstallUrl, secretRefs } from '~/utils/account-secrets'
import {
  defaultAccountAddons,
  defaultSettings,
  defaultSyncExclusions,
  demoDevices
} from '~/utils/portal-defaults'
import type {
  AddonCatalogRecord,
  AddonManifestInspection,
  AddonRecord,
  BootstrapPayload,
  LinkedDevice,
  MDBListListOption,
  PluginRepository,
  PortalSession,
  PortalSettings,
  SecretMetadata,
  SecretType,
  TraktDeviceFlow,
  TraktPopularListOption
} from '~/types/portal'

type PersistPayload = {
  settings: PortalSettings
  addons: AddonRecord[]
}

type SecretSetPayload = {
  secretType: SecretType
  secretRef: string
  secretPayload: Record<string, unknown>
}

type MDBListDiscoveryState = {
  validating: boolean
  valid: boolean
  error: string | null
  personalLists: MDBListListOption[]
  topLists: MDBListListOption[]
}

type TraktDiscoveryState = {
  loading: boolean
  error: string | null
  popularLists: TraktPopularListOption[]
}

type StoreState = {
  bootstrapped: boolean
  loading: boolean
  saving: boolean
  demoMode: boolean
  error: string | null
  syncRevision: number
  lastSyncedAt: string | null
  session: PortalSession | null
  settings: PortalSettings
  addons: AddonRecord[]
  secretStatuses: SecretMetadata[]
  secretDrafts: Record<string, string>
  linkedDevices: LinkedDevice[]
  traktFlow: TraktDeviceFlow | null
  addonInspections: Record<string, AddonManifestInspection>
  mdblistDiscovery: MDBListDiscoveryState
  traktDiscovery: TraktDiscoveryState
}

const STORAGE_KEY = 'nexio.portal.demo.snapshot'
const SESSION_KEY = 'nexio.portal.session'

let remoteSignature = ''
let persistTimer: ReturnType<typeof setTimeout> | null = null

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function readLocalState(): Partial<StoreState> {
  if (!process.client) {
    return {}
  }

  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) {
    return {}
  }

  try {
    return JSON.parse(raw) as Partial<StoreState>
  } catch {
    return {}
  }
}

function readSession(): PortalSession | null {
  if (!process.client) {
    return null
  }

  const raw = localStorage.getItem(SESSION_KEY)
  if (!raw) {
    return null
  }

  try {
    return JSON.parse(raw) as PortalSession
  } catch {
    return null
  }
}

function writeSession(session: PortalSession | null) {
  if (!process.client) {
    return
  }

  if (!session) {
    localStorage.removeItem(SESSION_KEY)
    return
  }

  localStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

function normalizeAddonUrl(url: string): string {
  return url.trim().replace(/\/manifest\.json$/i, '').replace(/\/$/, '')
}

function snapshotSignature(settings: PortalSettings, addons: AddonRecord[]): string {
  return JSON.stringify({
    settings,
    addons: addons.map((addon) => ({
      url: addon.url,
      manifestUrl: addon.manifestUrl,
      name: addon.name,
      description: addon.description ?? '',
      enabled: addon.enabled,
      sortOrder: addon.sortOrder
    }))
  })
}

function normalizeSnapshot(source: Partial<StoreState>): StoreState {
  return {
    bootstrapped: false,
    loading: false,
    saving: false,
    demoMode: true,
    error: null,
    syncRevision: source.syncRevision ?? 1,
    lastSyncedAt: source.lastSyncedAt ?? new Date().toISOString(),
    session: source.session ?? readSession(),
    settings: clone(source.settings ?? defaultSettings()),
    addons: clone(source.addons ?? defaultAccountAddons()),
    secretStatuses: clone(source.secretStatuses ?? []),
    secretDrafts: {},
    linkedDevices: clone(source.linkedDevices ?? demoDevices()),
    traktFlow: null,
    addonInspections: clone(source.addonInspections ?? {}),
    mdblistDiscovery: clone(source.mdblistDiscovery ?? {
      validating: false,
      valid: false,
      error: null,
      personalLists: [],
      topLists: []
    }),
    traktDiscovery: clone(source.traktDiscovery ?? {
      loading: false,
      error: null,
      popularLists: []
    })
  }
}

function useInternalStore() {
  return useState<StoreState>('portal-store', () => normalizeSnapshot(readLocalState()))
}

function accessToken(session: PortalSession | null): string | null {
  return session?.accessToken ?? null
}

function secretKey(secretType: SecretType, secretRef: string) {
  return `${secretType}:${secretRef}`
}

async function apiFetch<T>(path: string, options: RequestInit = {}, token?: string | null): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(path, {
    ...options,
    headers
  })

  if (!response.ok) {
    const message = await response.text()
    throw new Error(message || `${response.status} ${response.statusText}`)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

function traktBuiltInCatalogs(settings: PortalSettings): AddonCatalogRecord[] {
  const labels: Record<string, { name: string; type: string }> = {
    trakt_up_next: { name: 'Trakt Up Next', type: 'series' },
    trakt_trending_movies: { name: 'Trakt Trending Movies', type: 'movie' },
    trakt_trending_shows: { name: 'Trakt Trending Shows', type: 'series' },
    trakt_popular_movies: { name: 'Trakt Popular Movies', type: 'movie' },
    trakt_popular_shows: { name: 'Trakt Popular Shows', type: 'series' },
    trakt_recommended_movies: { name: 'Trakt Recommended Movies', type: 'movie' },
    trakt_recommended_shows: { name: 'Trakt Recommended Shows', type: 'series' },
    trakt_calendar_next_7_days: { name: 'Trakt Calendar (Next 7 Days)', type: 'series' }
  }

  return settings.trakt.catalogOrder
    .filter((catalogId) => settings.trakt.catalogEnabledSet.includes(catalogId))
    .map((catalogId) => ({
      key: catalogId,
      disableKey: '',
      addonId: 'trakt',
      addonName: 'Trakt',
      addonUrl: 'trakt',
      catalogId,
      catalogName: labels[catalogId]?.name ?? catalogId,
      type: labels[catalogId]?.type ?? 'series',
      source: 'trakt' as const,
      isSearchOnly: false
    }))
}

function traktPopularListCatalogs(
  settings: PortalSettings,
  lists: TraktPopularListOption[]
): AddonCatalogRecord[] {
  const selected = new Set(settings.trakt.selectedPopularListKeys)
  return lists
    .filter((list) => selected.has(list.key))
    .flatMap((list) => ([
      {
        key: `trakt_${list.catalogIdBase}_movies`,
        disableKey: '',
        addonId: 'trakt',
        addonName: 'Trakt',
        addonUrl: 'trakt',
        catalogId: `${list.catalogIdBase}_movies`,
        catalogName: `${list.title} (Movies)`,
        type: 'movie',
        source: 'trakt' as const,
        isSearchOnly: false
      },
      {
        key: `trakt_${list.catalogIdBase}_shows`,
        disableKey: '',
        addonId: 'trakt',
        addonName: 'Trakt',
        addonUrl: 'trakt',
        catalogId: `${list.catalogIdBase}_shows`,
        catalogName: `${list.title} (Shows)`,
        type: 'series',
        source: 'trakt' as const,
        isSearchOnly: false
      }
    ]))
}

function slugify(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'custom'
}

function mdblistCatalogs(
  settings: PortalSettings,
  personalLists: MDBListListOption[],
  topLists: MDBListListOption[]
): AddonCatalogRecord[] {
  const hidden = new Set(settings.integrations.mdblist.hiddenPersonalListKeys)
  const selectedTop = new Set(settings.integrations.mdblist.selectedTopListKeys)

  const active = [
    ...personalLists.filter((list) => !hidden.has(list.key)),
    ...topLists.filter((list) => selectedTop.has(list.key))
  ]

  const order = settings.integrations.mdblist.catalogOrder
  const ordered = order.length > 0
    ? [
        ...order.map((key) => active.find((list) => list.key === key)).filter(Boolean) as MDBListListOption[],
        ...active.filter((list) => !order.includes(list.key))
      ]
    : active

  return ordered.flatMap((list) => {
    const base = `mdblist_list_${slugify(list.key)}`
    return [
      {
        key: `mdblist_${base}_movies`,
        disableKey: '',
        addonId: 'mdblist',
        addonName: 'MDBList',
        addonUrl: 'mdblist',
        catalogId: `${base}_movies`,
        catalogName: `${list.title} (Movies)`,
        type: 'movie',
        source: 'mdblist' as const,
        isSearchOnly: false
      },
      {
        key: `mdblist_${base}_shows`,
        disableKey: '',
        addonId: 'mdblist',
        addonName: 'MDBList',
        addonUrl: 'mdblist',
        catalogId: `${base}_shows`,
        catalogName: `${list.title} (Shows)`,
        type: 'series',
        source: 'mdblist' as const,
        isSearchOnly: false
      }
    ]
  })
}

export function usePortalStore() {
  const state = useInternalStore()

  function secretStatus(secretRef: string, secretType?: SecretType) {
    return state.value.secretStatuses.find((entry) =>
      entry.secretRef === secretRef && (!secretType || entry.secretType === secretType)
    ) ?? null
  }

  function upsertSecretStatus(secret: SecretMetadata) {
    const key = secretKey(secret.secretType, secret.secretRef)
    const next = new Map(state.value.secretStatuses.map((entry) => [secretKey(entry.secretType, entry.secretRef), entry]))
    next.set(key, secret)
    state.value.secretStatuses = [...next.values()]
  }

  function removeSecretStatus(secretType: SecretType, secretRef: string) {
    state.value.secretStatuses = state.value.secretStatuses.filter((entry) =>
      !(entry.secretType === secretType && entry.secretRef === secretRef)
    )
  }

  function setSecretDraft(secretRef: string, value: string) {
    state.value.secretDrafts = {
      ...state.value.secretDrafts,
      [secretRef]: value
    }
  }

  async function inspectAddons() {
    if (state.value.addons.length === 0) {
      state.value.addonInspections = {}
      return
    }

    try {
      const response = await apiFetch<{ inspections: AddonManifestInspection[] }>('/api/addons/inspect', {
        method: 'POST',
        body: JSON.stringify({ addons: state.value.addons })
      }, accessToken(state.value.session))

      const nextInspections = { ...state.value.addonInspections }
      response.inspections.forEach((inspection) => {
        nextInspections[inspection.addonUrl] = inspection
      })
      state.value.addonInspections = nextInspections

      state.value.addons = state.value.addons.map((addon) => {
        const inspection = nextInspections[normalizeAddonUrl(addon.url)]
        if (!inspection) {
          return addon
        }

        return {
          ...addon,
          manifestUrl: inspection.manifestUrl,
          name: inspection.addonName || addon.name,
          description: inspection.description ?? addon.description,
          logo: inspection.logo ?? addon.logo
        }
      })
    } catch (error) {
      state.value.error = error instanceof Error ? error.message : 'Addon inspection failed.'
    }
  }

  async function bootstrap() {
    if (state.value.bootstrapped || state.value.loading) {
      return
    }

    state.value.loading = true
    state.value.error = null

    try {
      const session = readSession()
      if (!session) {
        state.value = {
          ...normalizeSnapshot(readLocalState()),
          bootstrapped: true
        }
        remoteSignature = snapshotSignature(state.value.settings, state.value.addons)
        await inspectAddons()
        return
      }

      const payload = await apiFetch<BootstrapPayload>('/api/account/bootstrap', {}, accessToken(session))
      state.value.bootstrapped = true
      state.value.demoMode = !payload.session
      state.value.session = payload.session ?? session
      state.value.settings = clone(payload.snapshot.settings)
      state.value.addons = clone(payload.snapshot.addons)
      state.value.secretStatuses = clone(payload.snapshot.secretStatuses)
      state.value.linkedDevices = clone(payload.snapshot.linkedDevices)
      state.value.syncRevision = payload.snapshot.syncRevision
      state.value.lastSyncedAt = payload.snapshot.lastSyncedAt
      writeSession(state.value.session)
      remoteSignature = snapshotSignature(state.value.settings, state.value.addons)

      await inspectAddons()

      if (state.value.settings.integrations.mdblist.enabled && secretStatus(secretRefs.mdblist)?.status === 'configured') {
        await validateMDBList().catch(() => undefined)
      }

      if (state.value.settings.integrations.traktAuth.connected) {
        await refreshTraktPopularLists().catch(() => undefined)
      }
    } catch (error) {
      state.value.bootstrapped = true
      state.value.demoMode = true
      state.value.error = error instanceof Error ? error.message : 'Failed to load portal state.'
    } finally {
      state.value.loading = false
    }
  }

  async function signIn(email: string, password: string) {
    state.value.loading = true
    state.value.error = null

    try {
      const session = await apiFetch<PortalSession>('/api/auth/sign-in', {
        method: 'POST',
        body: JSON.stringify({ email, password })
      })

      state.value.session = session
      writeSession(session)
      state.value.bootstrapped = false
      await bootstrap()
    } catch (error) {
      state.value.error = error instanceof Error ? error.message : 'Unable to sign in.'
      throw error
    } finally {
      state.value.loading = false
    }
  }

  async function signUp(email: string, password: string) {
    state.value.loading = true
    state.value.error = null

    try {
      const session = await apiFetch<PortalSession>('/api/auth/sign-up', {
        method: 'POST',
        body: JSON.stringify({ email, password })
      })

      state.value.session = session
      writeSession(session)
      state.value.bootstrapped = false
      await bootstrap()
    } catch (error) {
      state.value.error = error instanceof Error ? error.message : 'Unable to create account.'
      throw error
    } finally {
      state.value.loading = false
    }
  }

  async function signOut() {
    const token = accessToken(state.value.session)
    try {
      if (token) {
        await apiFetch('/api/auth/sign-out', { method: 'POST' }, token)
      }
    } catch {
      // Keep local sign-out deterministic even if remote logout fails.
    }

    state.value.session = null
    state.value.bootstrapped = false
    state.value.traktFlow = null
    state.value.addonInspections = {}
    state.value.secretDrafts = {}
    state.value.mdblistDiscovery = {
      validating: false,
      valid: false,
      error: null,
      personalLists: [],
      topLists: []
    }
    state.value.traktDiscovery = {
      loading: false,
      error: null,
      popularLists: []
    }
    remoteSignature = ''
    writeSession(null)
    state.value = {
      ...normalizeSnapshot(readLocalState()),
      bootstrapped: true
    }
  }

  function updateSetting(path: string, nextValue: unknown) {
    const keys = path.split('.')
    const draft = clone(state.value.settings) as Record<string, any>
    let cursor = draft

    for (const key of keys.slice(0, -1)) {
      cursor[key] = clone(cursor[key])
      cursor = cursor[key]
    }

    const leaf = keys[keys.length - 1]
    if (Array.isArray(cursor[leaf]) && typeof nextValue === 'string') {
      cursor[leaf] = nextValue
        .split(',')
        .map((entry) => entry.trim())
        .filter(Boolean)
    } else if (typeof cursor[leaf] === 'number' && typeof nextValue === 'string') {
      cursor[leaf] = Number(nextValue)
    } else if (nextValue === '') {
      cursor[leaf] = null
    } else {
      cursor[leaf] = nextValue
    }

    if (path === 'integrations.posterRatings.rpdbEnabled' && nextValue === true) {
      draft.integrations.posterRatings.topPostersEnabled = false
    }

    if (path === 'integrations.posterRatings.topPostersEnabled' && nextValue === true) {
      draft.integrations.posterRatings.rpdbEnabled = false
    }

    state.value.settings = draft as PortalSettings
  }

  async function addAddon(url: string) {
    const parsed = parseAddonInstallUrl(url)
    if (parsed.secretType && !state.value.session) {
      throw new Error('Sign in before adding credentialed addons.')
    }
    if (state.value.addons.some((addon) => normalizeAddonUrl(addon.url) === parsed.addon.url)) {
      return
    }

    state.value.addons = [
      ...state.value.addons,
      {
        ...parsed.addon,
        sortOrder: state.value.addons.length
      }
    ]

    if (parsed.secretType && parsed.secretRef && parsed.secretPayload) {
      await saveSecret({
        secretType: parsed.secretType,
        secretRef: parsed.secretRef,
        secretPayload: parsed.secretPayload
      })
    }

    await inspectAddons()
  }

  function removeAddon(id: string) {
    const addon = state.value.addons.find((entry) => entry.id === id)
    if (addon) {
      delete state.value.addonInspections[normalizeAddonUrl(addon.url)]
      if (addon.secretRef) {
        deleteSecret('addon_credential', addon.secretRef).catch(() => undefined)
      }
    }

    state.value.addons = state.value.addons
      .filter((entry) => entry.id !== id)
      .map((entry, index) => ({ ...entry, sortOrder: index }))
  }

  function moveAddon(id: string, direction: -1 | 1) {
    const next = [...state.value.addons]
    const index = next.findIndex((addon) => addon.id === id)
    if (index === -1) {
      return
    }

    const target = Math.max(0, Math.min(next.length - 1, index + direction))
    if (target === index) {
      return
    }

    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    state.value.addons = next.map((addon, itemIndex) => ({ ...addon, sortOrder: itemIndex }))
  }

  function toggleAddon(id: string) {
    state.value.addons = state.value.addons.map((addon) =>
      addon.id === id ? { ...addon, enabled: !addon.enabled } : addon
    )
  }

  function addRepository(url: string, label: string) {
    const cleanUrl = url.trim()
    const cleanLabel = label.trim() || 'Repository'
    if (!cleanUrl) {
      return
    }

    state.value.settings.plugins.repositories = [
      ...state.value.settings.plugins.repositories,
      {
        id: crypto.randomUUID(),
        label: cleanLabel,
        url: cleanUrl,
        enabledScraperIds: []
      }
    ]
  }

  function removeRepository(id: string) {
    state.value.settings.plugins.repositories = state.value.settings.plugins.repositories.filter(
      (repository) => repository.id !== id
    )
  }

  function setRepositoryScrapers(id: string, value: string) {
    state.value.settings.plugins.repositories = state.value.settings.plugins.repositories.map(
      (repository) =>
        repository.id === id
          ? {
              ...repository,
              enabledScraperIds: value
                .split(',')
                .map((entry) => entry.trim())
                .filter(Boolean)
            }
          : repository
    )
  }

  async function persistSnapshot() {
    const token = accessToken(state.value.session)
    const payload: PersistPayload = {
      settings: state.value.settings,
      addons: state.value.addons
    }

    state.value.saving = true
    state.value.error = null

    try {
      if (!token) {
        state.value.demoMode = true
        state.value.syncRevision += 1
        state.value.lastSyncedAt = new Date().toISOString()
        remoteSignature = snapshotSignature(state.value.settings, state.value.addons)
        return
      }

      const response = await apiFetch<{ syncRevision: number; lastSyncedAt: string }>('/api/account/persist', {
        method: 'POST',
        body: JSON.stringify(payload)
      }, token)

      state.value.demoMode = false
      state.value.syncRevision = response.syncRevision
      state.value.lastSyncedAt = response.lastSyncedAt
      remoteSignature = snapshotSignature(state.value.settings, state.value.addons)
    } catch (error) {
      state.value.demoMode = true
      state.value.error = error instanceof Error ? error.message : 'Failed to sync to Supabase.'
      throw error
    } finally {
      state.value.saving = false
    }
  }

  async function saveSecret(payload: SecretSetPayload) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before saving account secrets.')
    }

    const response = await apiFetch<{ secret: SecretMetadata }>('/api/account/secrets/set', {
      method: 'POST',
      body: JSON.stringify(payload)
    }, token)

    upsertSecretStatus(response.secret)
    setSecretDraft(payload.secretRef, '')
    state.value.lastSyncedAt = response.secret.updatedAt
  }

  async function deleteSecret(secretType: SecretType, secretRef: string) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before deleting account secrets.')
    }

    await apiFetch('/api/account/secrets/delete', {
      method: 'POST',
      body: JSON.stringify({ secretType, secretRef })
    }, token)

    removeSecretStatus(secretType, secretRef)
    setSecretDraft(secretRef, '')
  }

  async function approveTvLogin(code: string, nonce: string) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before approving a TV login.')
    }

    return await apiFetch<{ message: string }>('/api/account/approve-tv-login', {
      method: 'POST',
      body: JSON.stringify({ code, nonce })
    }, token)
  }

  async function validateMDBList() {
    state.value.mdblistDiscovery.validating = true
    state.value.mdblistDiscovery.error = null

    try {
      const token = accessToken(state.value.session)
      if (!token) {
        throw new Error('Sign in before validating MDBList.')
      }

      const apiKey = state.value.secretDrafts[secretRefs.mdblist]?.trim() || undefined
      const response = await apiFetch<{
        valid: boolean
        personalLists: MDBListListOption[]
        topLists: MDBListListOption[]
      }>('/api/integrations/mdblist/validate', {
        method: 'POST',
        body: JSON.stringify({ apiKey })
      }, token)

      state.value.mdblistDiscovery = {
        validating: false,
        valid: response.valid,
        error: null,
        personalLists: response.personalLists,
        topLists: response.topLists
      }
    } catch (error) {
      state.value.mdblistDiscovery = {
        validating: false,
        valid: false,
        error: error instanceof Error ? error.message : 'MDBList validation failed.',
        personalLists: [],
        topLists: []
      }
      throw error
    }
  }

  function setMDBListPersonalListEnabled(key: string, enabled: boolean) {
    const next = new Set(state.value.settings.integrations.mdblist.hiddenPersonalListKeys)
    if (enabled) {
      next.delete(key)
    } else {
      next.add(key)
    }
    state.value.settings.integrations.mdblist.hiddenPersonalListKeys = [...next]
  }

  function setMDBListTopListSelected(key: string, selected: boolean) {
    const next = new Set(state.value.settings.integrations.mdblist.selectedTopListKeys)
    if (selected) {
      next.add(key)
    } else {
      next.delete(key)
    }
    state.value.settings.integrations.mdblist.selectedTopListKeys = [...next]
  }

  async function saveDraftSecret(secretType: SecretType, secretRef: string, payloadFactory?: (value: string) => Record<string, unknown>) {
    const value = state.value.secretDrafts[secretRef]?.trim() ?? ''
    if (!value) {
      throw new Error('Enter a value before saving this secret.')
    }

    await saveSecret({
      secretType,
      secretRef,
      secretPayload: payloadFactory ? payloadFactory(value) : { apiKey: value }
    })
  }

  async function startTraktDeviceFlow() {
    state.value.error = null
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before starting Trakt authentication.')
    }
    const flow = await apiFetch<TraktDeviceFlow>('/api/integrations/trakt/device-code', {
      method: 'POST',
      body: JSON.stringify({})
    }, token)
    state.value.traktFlow = flow
    state.value.settings.integrations.traktAuth.pending = true
  }

  async function completeTraktDeviceFlow() {
    const deviceCode = state.value.traktFlow?.deviceCode
    if (!deviceCode) {
      return
    }

    const response = await apiFetch<{
      pending: boolean
      approved: boolean
      traktAuth?: PortalSettings['integrations']['traktAuth']
    }>('/api/integrations/trakt/device-token', {
      method: 'POST',
      body: JSON.stringify({ deviceCode })
    }, accessToken(state.value.session))

    if (response.approved && response.traktAuth) {
      state.value.settings.integrations.traktAuth = response.traktAuth
      state.value.traktFlow = null
      await refreshTraktPopularLists()
      return
    }

    state.value.settings.integrations.traktAuth.pending = response.pending
  }

  async function refreshTraktPopularLists() {
    const token = accessToken(state.value.session)
    if (!token || !state.value.settings.integrations.traktAuth.connected) {
      state.value.traktDiscovery = {
        loading: false,
        error: null,
        popularLists: []
      }
      return
    }

    state.value.traktDiscovery.loading = true
    state.value.traktDiscovery.error = null
    try {
      const response = await apiFetch<{ lists: TraktPopularListOption[] }>('/api/integrations/trakt/popular-lists', {
        method: 'POST',
        body: JSON.stringify({})
      }, token)

      state.value.traktDiscovery = {
        loading: false,
        error: null,
        popularLists: response.lists
      }
    } catch (error) {
      state.value.traktDiscovery = {
        loading: false,
        error: error instanceof Error ? error.message : 'Failed to load Trakt lists.',
        popularLists: []
      }
      throw error
    }
  }

  function toggleTraktPopularList(key: string) {
    const next = new Set(state.value.settings.trakt.selectedPopularListKeys)
    if (next.has(key)) {
      next.delete(key)
    } else {
      next.add(key)
    }
    state.value.settings.trakt.selectedPopularListKeys = [...next]
  }

  function disconnectTrakt() {
    deleteSecret('trakt_access_token', secretRefs.trakt).catch(() => undefined)
    deleteSecret('trakt_refresh_token', secretRefs.trakt).catch(() => undefined)
    state.value.settings.integrations.traktAuth = {
      connected: false,
      username: '',
      userSlug: '',
      connectedAt: null,
      pending: false
    }
    state.value.traktFlow = null
    state.value.traktDiscovery = {
      loading: false,
      error: null,
      popularLists: []
    }
  }

  if (process.client) {
    watch(
      state,
      () => {
        localStorage.setItem(
          STORAGE_KEY,
          JSON.stringify({
            settings: state.value.settings,
            addons: state.value.addons,
            secretStatuses: state.value.secretStatuses,
            linkedDevices: state.value.linkedDevices,
            syncRevision: state.value.syncRevision,
            lastSyncedAt: state.value.lastSyncedAt,
            addonInspections: state.value.addonInspections,
            mdblistDiscovery: state.value.mdblistDiscovery,
            traktDiscovery: state.value.traktDiscovery
          })
        )
        writeSession(state.value.session)
      },
      { deep: true }
    )

    watch(
      () => ({
        bootstrapped: state.value.bootstrapped,
        loading: state.value.loading,
        signedIn: Boolean(state.value.session),
        signature: snapshotSignature(state.value.settings, state.value.addons)
      }),
      ({ bootstrapped, loading, signedIn, signature }) => {
        if (!bootstrapped || loading || !signedIn || signature === remoteSignature) {
          return
        }

        if (persistTimer) {
          clearTimeout(persistTimer)
        }
        persistTimer = setTimeout(() => {
          persistSnapshot().catch(() => undefined)
        }, 500)
      },
      { deep: true }
    )
  }

  const syncScopeLabel = computed(() => `${state.value.addons.length} addons, ${defaultSyncExclusions.length} device-only exclusions`)
  const signedIn = computed(() => Boolean(state.value.session))
  const repositories = computed<PluginRepository[]>(() => state.value.settings.plugins.repositories)
  const secretStatusMap = computed<Record<string, SecretMetadata>>(() =>
    Object.fromEntries(state.value.secretStatuses.map((entry) => [entry.secretRef, entry]))
  )
  const catalogInventory = computed<AddonCatalogRecord[]>(() => {
    const disabled = new Set(state.value.settings.layout.disabledHomeCatalogKeys)
    const addonCatalogs = Object.values(state.value.addonInspections)
      .flatMap((inspection) => inspection.catalogs)
      .filter((catalog) => !catalog.isSearchOnly)
    const traktCatalogs = traktBuiltInCatalogs(state.value.settings)
    const traktPopularCatalogs = traktPopularListCatalogs(state.value.settings, state.value.traktDiscovery.popularLists)
    const mdblistDerivedCatalogs = mdblistCatalogs(
      state.value.settings,
      state.value.mdblistDiscovery.personalLists,
      state.value.mdblistDiscovery.topLists
    )

    return [...addonCatalogs, ...traktCatalogs, ...traktPopularCatalogs, ...mdblistDerivedCatalogs]
      .map((catalog) => ({
        ...catalog,
        disableKey: catalog.disableKey,
        addonName: catalog.addonName
      }))
      .sort((left, right) => {
        const leftIndex = state.value.settings.layout.homeCatalogOrderKeys.indexOf(left.key)
        const rightIndex = state.value.settings.layout.homeCatalogOrderKeys.indexOf(right.key)
        if (leftIndex === -1 && rightIndex === -1) {
          return left.catalogName.localeCompare(right.catalogName)
        }
        if (leftIndex === -1) {
          return 1
        }
        if (rightIndex === -1) {
          return -1
        }
        return leftIndex - rightIndex
      })
      .map((catalog) => ({
        ...catalog,
        disableKey: catalog.disableKey || '',
        key: catalog.key || `${catalog.addonId}_${catalog.catalogId}`,
        addonName: disabled.has(catalog.disableKey) ? `${catalog.addonName} · disabled` : catalog.addonName
      }))
  })

  return {
    state,
    syncScopeLabel,
    signedIn,
    repositories,
    secretStatusMap,
    catalogInventory,
    defaultSyncExclusions,
    bootstrap,
    signIn,
    signUp,
    signOut,
    updateSetting,
    addAddon,
    removeAddon,
    moveAddon,
    toggleAddon,
    addRepository,
    removeRepository,
    setRepositoryScrapers,
    persistSnapshot,
    secretStatus,
    setSecretDraft,
    saveDraftSecret,
    deleteSecret,
    approveTvLogin,
    validateMDBList,
    setMDBListPersonalListEnabled,
    setMDBListTopListSelected,
    startTraktDeviceFlow,
    completeTraktDeviceFlow,
    refreshTraktPopularLists,
    toggleTraktPopularList,
    disconnectTrakt
  }
}
