import { computed, watch } from 'vue'
import { parseAddonInstallUrl, secretRefs } from '~/utils/account-secrets'
import {
  defaultAccountAddons,
  defaultSettings,
  demoDevices
} from '~/utils/portal-defaults'
import type {
  AddonCatalogRecord,
  AddonManifestInspection,
  AddonRecord,
  BootstrapPayload,
  LinkedDevice,
  MDBListListOption,
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

function sanitizeSettings(input?: Partial<PortalSettings> | null): PortalSettings {
  const defaults = defaultSettings()

  return {
    appearance: {
      ...defaults.appearance,
      ...(input?.appearance ?? {})
    },
    layout: {
      ...defaults.layout,
      ...(input?.layout ?? {})
    },
    integrations: {
      tmdb: {
        ...defaults.integrations.tmdb,
        ...(input?.integrations?.tmdb ?? {})
      },
      mdblist: {
        ...defaults.integrations.mdblist,
        ...(input?.integrations?.mdblist ?? {})
      },
      animeSkip: {
        ...defaults.integrations.animeSkip,
        ...(input?.integrations?.animeSkip ?? {})
      },
      posterRatings: {
        ...defaults.integrations.posterRatings,
        ...(input?.integrations?.posterRatings ?? {})
      },
      traktAuth: {
        ...defaults.integrations.traktAuth,
        ...(input?.integrations?.traktAuth ?? {})
      }
    },
    playback: {
      general: {
        ...defaults.playback.general,
        ...(input?.playback?.general ?? {})
      },
      streamSelection: {
        ...defaults.playback.streamSelection,
        ...(input?.playback?.streamSelection ?? {})
      },
      audioTrailer: {
        ...defaults.playback.audioTrailer,
        ...(input?.playback?.audioTrailer ?? {})
      },
      subtitles: {
        ...defaults.playback.subtitles,
        ...(input?.playback?.subtitles ?? {})
      },
      bufferNetwork: {
        ...defaults.playback.bufferNetwork,
        ...(input?.playback?.bufferNetwork ?? {})
      }
    },
    trakt: {
      ...defaults.trakt,
      ...(input?.trakt ?? {})
    },
    debug: {
      ...defaults.debug,
      ...(input?.debug ?? {})
    }
  }
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
    settings: sanitizeSettings(clone(source.settings ?? defaultSettings())),
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
    const rawBody = await response.text()
    let message = rawBody || `${response.status} ${response.statusText}`

    try {
      const payload = JSON.parse(rawBody) as Record<string, unknown>
      const candidates = [
        payload.message,
        payload.statusMessage,
        payload.error_description,
        payload.error
      ]

      for (const candidate of candidates) {
        if (typeof candidate === 'string' && candidate.trim()) {
          message = candidate.trim()
          break
        }
      }
    } catch {
      // Keep plain text responses intact.
    }

    throw new Error(message)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

function orderedAddonCatalogs(catalogs: AddonCatalogRecord[], settings: PortalSettings): AddonCatalogRecord[] {
  const order = settings.layout.homeCatalogOrderKeys
  const orderIndex = new Map(order.map((key, index) => [key, index]))
  const uniqueCatalogs = new Map(catalogs.map((catalog) => [catalog.key, catalog]))

  return [...uniqueCatalogs.values()]
    .sort((left, right) => {
      const leftIndex = orderIndex.get(left.key)
      const rightIndex = orderIndex.get(right.key)
      if (leftIndex == null && rightIndex == null) {
        return left.catalogName.localeCompare(right.catalogName)
      }
      if (leftIndex == null) {
        return 1
      }
      if (rightIndex == null) {
        return -1
      }
      return leftIndex - rightIndex
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
      state.value.settings = sanitizeSettings(clone(payload.snapshot.settings))
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

  async function completeOAuthSession(session: PortalSession) {
    state.value.loading = true
    state.value.error = null

    try {
      state.value.session = session
      writeSession(session)
      state.value.bootstrapped = false
      await bootstrap()
    } catch (error) {
      state.value.error = error instanceof Error ? error.message : 'Unable to complete sign in.'
      throw error
    } finally {
      state.value.loading = false
    }
  }

  function startGoogleSignIn(nextPath = '/account') {
    if (!process.client) {
      return
    }

    const next = nextPath.startsWith('/') ? nextPath : '/account'
    window.location.assign(`/api/auth/google?next=${encodeURIComponent(next)}`)
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

  function moveCatalog(key: string, direction: -1 | 1) {
    const availableKeys = orderedAddonCatalogs(
      Object.values(state.value.addonInspections)
        .flatMap((inspection) => inspection.catalogs)
        .filter((catalog) => !catalog.isSearchOnly),
      state.value.settings
    ).map((catalog) => catalog.key)

    if (!availableKeys.includes(key)) {
      return
    }

    const fullOrder = [
      ...state.value.settings.layout.homeCatalogOrderKeys,
      ...availableKeys.filter((catalogKey) => !state.value.settings.layout.homeCatalogOrderKeys.includes(catalogKey))
    ]
    const addonPositions = fullOrder
      .map((catalogKey, index) => availableKeys.includes(catalogKey) ? index : -1)
      .filter((index) => index !== -1)
    const currentAddonIndex = addonPositions.findIndex((position) => fullOrder[position] === key)
    if (currentAddonIndex === -1) {
      return
    }

    const targetAddonIndex = Math.max(0, Math.min(addonPositions.length - 1, currentAddonIndex + direction))
    if (targetAddonIndex === currentAddonIndex) {
      return
    }

    const from = addonPositions[currentAddonIndex]
    const to = addonPositions[targetAddonIndex]
    const nextOrder = [...fullOrder]
    ;[nextOrder[from], nextOrder[to]] = [nextOrder[to], nextOrder[from]]
    state.value.settings.layout.homeCatalogOrderKeys = nextOrder
  }

  function toggleCatalog(key: string) {
    const disabled = new Set(state.value.settings.layout.disabledHomeCatalogKeys)
    if (disabled.has(key)) {
      disabled.delete(key)
    } else {
      disabled.add(key)
    }
    state.value.settings.layout.disabledHomeCatalogKeys = [...disabled]
  }

  async function unlinkDevice(deviceUserId: string) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before unlinking a TV.')
    }

    await apiFetch('/api/account/devices/unlink', {
      method: 'POST',
      body: JSON.stringify({ deviceUserId })
    }, token)

    state.value.linkedDevices = state.value.linkedDevices.filter((device) =>
      (device.deviceUserId ?? device.id) !== deviceUserId
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
      state.value.error = error instanceof Error ? error.message : 'Failed to sync to Nexio Live.'
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

  const signedIn = computed(() => Boolean(state.value.session))
  const secretStatusMap = computed<Record<string, SecretMetadata>>(() =>
    Object.fromEntries(state.value.secretStatuses.map((entry) => [entry.secretRef, entry]))
  )
  const catalogInventory = computed<AddonCatalogRecord[]>(() => {
    const addonCatalogs = Object.values(state.value.addonInspections)
      .flatMap((inspection) => inspection.catalogs)
      .filter((catalog) => !catalog.isSearchOnly)
    return orderedAddonCatalogs(addonCatalogs, state.value.settings)
      .map((catalog) => ({
        ...catalog,
        disableKey: catalog.disableKey || '',
        key: catalog.key || `${catalog.addonId}_${catalog.catalogId}`
      }))
  })

  return {
    state,
    signedIn,
    secretStatusMap,
    catalogInventory,
    bootstrap,
    signIn,
    signUp,
    completeOAuthSession,
    startGoogleSignIn,
    signOut,
    updateSetting,
    addAddon,
    removeAddon,
    moveAddon,
    toggleAddon,
    moveCatalog,
    toggleCatalog,
    unlinkDevice,
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
