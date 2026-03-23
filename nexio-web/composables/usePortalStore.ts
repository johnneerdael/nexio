import { createClient, type RealtimeChannel, type SupabaseClient } from '@supabase/supabase-js'
import { computed, watch } from 'vue'
import { normalizeAddonManifestUrl, normalizeAddonUrl, parseAddonInstallUrl, recommendParserPresetForAddonUrl, secretRefs } from '~/utils/account-secrets'
import {
  defaultAccountAddons,
  defaultSettings
} from '~/utils/portal-defaults'
import { traktCatalogLabels } from '~/utils/portal-metadata'
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
  searchResults: MDBListListOption[]
}

type TraktDiscoveryState = {
  loading: boolean
  error: string | null
  popularLists: TraktPopularListOption[]
  searchResults: TraktPopularListOption[]
}

type ImportedAddonCandidate = {
  id: string
  url: string
  manifestUrl: string
  name: string
  description?: string
  logo?: string
  parserPreset: AddonRecord['parserPreset']
  enabled: boolean
  publicQueryParams: Record<string, string>
}

type MigrationState = {
  pulling: boolean
  committing: boolean
  error: string | null
  importedAddons: ImportedAddonCandidate[]
  lastPulledAt: string | null
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
  migration: MigrationState
}

const STORAGE_KEY = 'nexio.portal.demo.snapshot'
const SESSION_KEY = 'nexio.portal.session'

let remoteSignature = ''
let persistTimer: ReturnType<typeof setTimeout> | null = null
let remoteBootstrapTimer: ReturnType<typeof setTimeout> | null = null
let traktPollTimer: ReturnType<typeof setTimeout> | null = null
let traktPollInFlight = false
let realDebridPollTimer: ReturnType<typeof setTimeout> | null = null
let realDebridPollInFlight = false
let realtimeClient: SupabaseClient | null = null
let realtimeChannel: RealtimeChannel | null = null
let realtimeUserId = ''
let realtimeToken = ''
const REAL_DEBRID_POLL_INTERVAL_MS = 5000

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function cleanErrorMessage(error: unknown, fallback: string): string {
  const msg = error instanceof Error ? error.message.trim() : fallback
  const lower = msg.toLowerCase()
  if (lower.includes('jwt expired') || lower.includes('missing sub claim') || lower.includes('invalid claim')) return 'Your session has expired or is invalid. Please sign in again.'
  if (lower.includes('invalid login credentials')) return 'Invalid email or password.'
  if (lower.includes('user not found')) return 'User not found.'
  if (lower.includes('rate limit')) return 'Too many attempts. Please try again later.'
  return msg
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

function isDemoModeForSession(session: PortalSession | null) {
  return !session
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

function addonInstallCandidate(addon: AddonRecord): string {
  const base = normalizeAddonManifestUrl(addon.url, addon.manifestUrl)
  const query = new URLSearchParams(addon.publicQueryParams ?? {}).toString()
  return query ? `${base}?${query}` : base
}

function sanitizeAddonRecord(addon: AddonRecord, index: number): AddonRecord {
  const normalizedUrl = normalizeAddonUrl(addon.url)
  return {
    ...addon,
    url: normalizedUrl,
    manifestUrl: normalizeAddonManifestUrl(normalizedUrl, addon.manifestUrl),
    parserPreset: addon.parserPreset ?? 'GENERIC',
    publicQueryParams: { ...(addon.publicQueryParams ?? {}) },
    sortOrder: addon.sortOrder ?? index
  }
}

function snapshotSignature(settings: PortalSettings, addons: AddonRecord[]): string {
  return JSON.stringify({
    settings,
    addons: addons.map((addon) => ({
      url: addon.url,
      manifestUrl: addon.manifestUrl,
      parserPreset: addon.parserPreset,
      name: addon.name,
      description: addon.description ?? '',
      enabled: addon.enabled,
      publicQueryParams: addon.publicQueryParams,
      installKind: addon.installKind,
      secretRef: addon.secretRef,
      sortOrder: addon.sortOrder
    }))
  })
}

export function sanitizePortalSettings(input?: Partial<PortalSettings> | null): PortalSettings {
  const defaults = defaultSettings()
  const legacyInput = input as Partial<{
    layout: PortalSettings['catalogs']['home']
    trakt: PortalSettings['catalogs']['trakt']
    integrations: {
      mdblist: PortalSettings['integrations']['mdblist'] & PortalSettings['catalogs']['mdblist']
    }
  }>

  return {
    schemaVersion:
      typeof input?.schemaVersion === 'number'
        ? input.schemaVersion
        : defaults.schemaVersion,
    integrations: {
      debrid: {
        premiumize: {
          ...defaults.integrations.debrid.premiumize,
          ...(input?.integrations?.debrid?.premiumize ?? {})
        },
        realDebrid: {
          ...defaults.integrations.debrid.realDebrid,
          ...(input?.integrations?.debrid?.realDebrid ?? {})
        }
      },
      tmdb: {
        ...defaults.integrations.tmdb,
        ...(input?.integrations?.tmdb ?? {})
      },
      omdb: {
        ...defaults.integrations.omdb,
        ...(input?.integrations?.omdb ?? {})
      },
      imdb: {
        ...defaults.integrations.imdb,
        ...(input?.integrations?.imdb ?? {})
      },
      mdblist: {
        ...defaults.integrations.mdblist,
        enabled: input?.integrations?.mdblist?.enabled ?? defaults.integrations.mdblist.enabled,
        showTrakt: input?.integrations?.mdblist?.showTrakt ?? defaults.integrations.mdblist.showTrakt,
        showImdb: input?.integrations?.mdblist?.showImdb ?? defaults.integrations.mdblist.showImdb,
        showTmdb: input?.integrations?.mdblist?.showTmdb ?? defaults.integrations.mdblist.showTmdb,
        showLetterboxd:
          input?.integrations?.mdblist?.showLetterboxd ?? defaults.integrations.mdblist.showLetterboxd,
        showTomatoes:
          input?.integrations?.mdblist?.showTomatoes ?? defaults.integrations.mdblist.showTomatoes,
        showAudience:
          input?.integrations?.mdblist?.showAudience ?? defaults.integrations.mdblist.showAudience,
        showMetacritic:
          input?.integrations?.mdblist?.showMetacritic ?? defaults.integrations.mdblist.showMetacritic
      },
      animeSkip: {
        ...defaults.integrations.animeSkip,
        ...(input?.integrations?.animeSkip ?? {})
      },
      gemini: {
        ...defaults.integrations.gemini,
        ...(input?.integrations?.gemini ?? {})
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
    catalogs: {
      home: {
        heroCatalogKeys:
          input?.catalogs?.home?.heroCatalogKeys
          ?? legacyInput.layout?.heroCatalogKeys
          ?? defaults.catalogs.home.heroCatalogKeys,
        homeCatalogOrderKeys:
          input?.catalogs?.home?.homeCatalogOrderKeys
          ?? legacyInput.layout?.homeCatalogOrderKeys
          ?? defaults.catalogs.home.homeCatalogOrderKeys,
        disabledHomeCatalogKeys:
          input?.catalogs?.home?.disabledHomeCatalogKeys
          ?? legacyInput.layout?.disabledHomeCatalogKeys
          ?? defaults.catalogs.home.disabledHomeCatalogKeys
      },
      trakt: {
        catalogEnabledSet:
          input?.catalogs?.trakt?.catalogEnabledSet
          ?? legacyInput.trakt?.catalogEnabledSet
          ?? defaults.catalogs.trakt.catalogEnabledSet,
        catalogOrder:
          input?.catalogs?.trakt?.catalogOrder
          ?? legacyInput.trakt?.catalogOrder
          ?? defaults.catalogs.trakt.catalogOrder,
        selectedPopularListKeys:
          input?.catalogs?.trakt?.selectedPopularListKeys
          ?? legacyInput.trakt?.selectedPopularListKeys
          ?? defaults.catalogs.trakt.selectedPopularListKeys
      },
      mdblist: {
        hiddenPersonalListKeys:
          input?.catalogs?.mdblist?.hiddenPersonalListKeys
          ?? legacyInput.integrations?.mdblist?.hiddenPersonalListKeys
          ?? defaults.catalogs.mdblist.hiddenPersonalListKeys,
        selectedTopListKeys:
          input?.catalogs?.mdblist?.selectedTopListKeys
          ?? legacyInput.integrations?.mdblist?.selectedTopListKeys
          ?? defaults.catalogs.mdblist.selectedTopListKeys,
        catalogOrder:
          input?.catalogs?.mdblist?.catalogOrder
          ?? legacyInput.integrations?.mdblist?.catalogOrder
          ?? defaults.catalogs.mdblist.catalogOrder
      }
    },
    formatter: {
      ...defaults.formatter,
      ...(input?.formatter ?? {})
    }
  }
}

function normalizeSnapshot(source: Partial<StoreState>): StoreState {
  const session = source.session ?? readSession()

  return {
    bootstrapped: false,
    loading: false,
    saving: false,
    demoMode: isDemoModeForSession(session),
    error: null,
    syncRevision: source.syncRevision ?? 1,
    lastSyncedAt: source.lastSyncedAt ?? new Date().toISOString(),
    session,
    settings: sanitizePortalSettings(clone(source.settings ?? defaultSettings())),
    addons: clone(source.addons ?? defaultAccountAddons()).map((addon, index) => sanitizeAddonRecord(addon, index)),
    secretStatuses: clone(source.secretStatuses ?? []),
    secretDrafts: {},
    linkedDevices: clone(source.linkedDevices ?? []),
    traktFlow: clone(source.traktFlow ?? null),
    addonInspections: clone(source.addonInspections ?? {}),
    mdblistDiscovery: clone(source.mdblistDiscovery ?? {
      validating: false,
      valid: false,
      error: null,
      personalLists: [],
      topLists: [],
      searchResults: []
    }),
    traktDiscovery: clone(source.traktDiscovery ?? {
      loading: false,
      error: null,
      popularLists: [],
      searchResults: []
    }),
    migration: clone(source.migration ?? {
      pulling: false,
      committing: false,
      error: null,
      importedAddons: [],
      lastPulledAt: null
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

function clearRealDebridPollTimer() {
  if (realDebridPollTimer) {
    clearTimeout(realDebridPollTimer)
    realDebridPollTimer = null
  }
}

function clearTraktPollTimer() {
  if (traktPollTimer) {
    clearTimeout(traktPollTimer)
    traktPollTimer = null
  }
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

function orderedCatalogs(catalogs: AddonCatalogRecord[], settings: PortalSettings): AddonCatalogRecord[] {
  const order = settings.catalogs.home.homeCatalogOrderKeys
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

function buildTraktCatalogs(state: StoreState): AddonCatalogRecord[] {
  if (!state.settings.integrations.traktAuth.connected) {
    return []
  }

  const enabledBuiltIns = state.settings.catalogs.trakt.catalogOrder
    .filter((key) => state.settings.catalogs.trakt.catalogEnabledSet.includes(key))
    .map((key) => ({
      key,
      disableKey: '',
      addonId: 'trakt',
      addonName: 'Trakt',
      addonUrl: 'trakt://catalogs',
      catalogId: key,
      catalogName: traktCatalogLabels[key],
      type: 'built-in',
      source: 'trakt' as const,
      isSearchOnly: false
    }))

  const selectedPopular = state.traktDiscovery.popularLists
    .filter((list) => state.settings.catalogs.trakt.selectedPopularListKeys.includes(list.key))
    .map((list) => ({
      key: list.key,
      disableKey: '',
      addonId: 'trakt',
      addonName: 'Trakt',
      addonUrl: 'trakt://lists',
      catalogId: list.catalogIdBase || list.key,
      catalogName: list.title,
      type: 'popular list',
      source: 'trakt' as const,
      isSearchOnly: false
    }))

  return [...enabledBuiltIns, ...selectedPopular]
}

function buildMDBListCatalogs(state: StoreState): AddonCatalogRecord[] {
  const visiblePersonal = state.mdblistDiscovery.personalLists
    .filter((list) => !state.settings.catalogs.mdblist.hiddenPersonalListKeys.includes(list.key))
    .map((list) => ({
      key: list.key,
      disableKey: '',
      addonId: 'mdblist',
      addonName: 'MDBList',
      addonUrl: 'mdblist://lists',
      catalogId: list.listId,
      catalogName: list.title,
      type: 'personal list',
      source: 'mdblist' as const,
      isSearchOnly: false
    }))

  const selectedTop = state.mdblistDiscovery.topLists
    .filter((list) => state.settings.catalogs.mdblist.selectedTopListKeys.includes(list.key))
    .map((list) => ({
      key: list.key,
      disableKey: '',
      addonId: 'mdblist',
      addonName: 'MDBList',
      addonUrl: 'mdblist://top-lists',
      catalogId: list.listId,
      catalogName: list.title,
      type: 'top list',
      source: 'mdblist' as const,
      isSearchOnly: false
    }))

  return [...visiblePersonal, ...selectedTop]
}

export function usePortalStore() {
  const state = useInternalStore()
  const runtimeConfig = useRuntimeConfig()

  function clearRemoteBootstrapTimer() {
    if (!remoteBootstrapTimer) {
      return
    }
    clearTimeout(remoteBootstrapTimer)
    remoteBootstrapTimer = null
  }

  function stopRealtimeSubscription() {
    clearRemoteBootstrapTimer()
    const channel = realtimeChannel
    realtimeChannel = null
    realtimeUserId = ''
    realtimeToken = ''

    if (realtimeClient && channel) {
      void realtimeClient.removeChannel(channel)
    }
  }

  function scheduleRemoteBootstrap(revision?: number) {
    if (typeof revision === 'number' && Number.isFinite(revision) && revision <= state.value.syncRevision) {
      return
    }

    clearRemoteBootstrapTimer()
    remoteBootstrapTimer = setTimeout(() => {
      bootstrap(true).catch(() => undefined)
    }, 250)
  }

  async function ensureRealtimeSubscription() {
    if (!process.client) {
      return
    }

    const session = state.value.session
    const token = accessToken(session)
    const userId = session?.user.id ?? ''
    const supabaseUrl = runtimeConfig.public.supabaseUrl?.trim()
    const supabaseAnonKey = runtimeConfig.public.supabaseAnonKey?.trim()

    if (!token || !userId || !supabaseUrl || !supabaseAnonKey) {
      stopRealtimeSubscription()
      return
    }

    if (!realtimeClient) {
      realtimeClient = createClient(supabaseUrl, supabaseAnonKey, {
        auth: {
          persistSession: false,
          autoRefreshToken: false,
          detectSessionInUrl: false
        }
      })
    }

    if (realtimeToken !== token) {
      realtimeClient.realtime.setAuth(token)
      realtimeToken = token
    }

    if (realtimeChannel && realtimeUserId === userId) {
      return
    }

    stopRealtimeSubscription()
    realtimeUserId = userId

    realtimeChannel = realtimeClient
      .channel(`account-sync:${userId}`)
      .on(
        'postgres_changes',
        {
          event: 'INSERT',
          schema: 'public',
          table: 'account_sync_events',
          filter: `user_id=eq.${userId}`
        },
        (payload) => {
          const revision = Number((payload.new as { revision?: number | string } | null)?.revision ?? 0)
          scheduleRemoteBootstrap(Number.isFinite(revision) ? revision : undefined)
        }
      )
      .subscribe()
  }

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
          name: inspection.addonName || addon.name,
          description: inspection.description ?? addon.description,
          logo: inspection.logo ?? addon.logo
        }
      })
    } catch (error) {
      state.value.error = cleanErrorMessage(error, 'Addon inspection failed.')
    }
  }

  async function migrateLegacyAddonSecrets() {
    if (!state.value.session || state.value.addons.length === 0) {
      return
    }

    let changed = false
    const nextAddons = [...state.value.addons]

    for (let index = 0; index < nextAddons.length; index += 1) {
      const addon = nextAddons[index]
      if (!addon) {
        continue
      }
      const parsed = parseAddonInstallUrl(addonInstallCandidate(addon))
      const needsSanitizing =
        parsed.addon.url !== addon.url ||
        parsed.addon.manifestUrl !== addon.manifestUrl ||
        JSON.stringify(parsed.addon.publicQueryParams ?? {}) !== JSON.stringify(addon.publicQueryParams ?? {}) ||
        (parsed.secretRef ?? null) !== (addon.secretRef ?? null)

      if (!parsed.secretType || !parsed.secretRef || !parsed.secretPayload || !needsSanitizing) {
        continue
      }

      nextAddons[index] = {
        ...addon,
        url: parsed.addon.url,
        manifestUrl: parsed.addon.manifestUrl,
        publicQueryParams: parsed.addon.publicQueryParams,
        installKind: 'configured',
        secretRef: parsed.secretRef
      }
      changed = true

      if (!secretStatus(parsed.secretRef, 'addon_credential')) {
        await saveSecret({
          secretType: 'addon_credential',
          secretRef: parsed.secretRef,
          secretPayload: parsed.secretPayload
        })
      }
    }

    if (!changed) {
      return
    }

    state.value.addons = nextAddons
    await persistSnapshot()
  }

  async function bootstrap(force = false) {
    if (!force && (state.value.bootstrapped || state.value.loading)) {
      return
    }

    state.value.loading = true
    state.value.error = null

    try {
      const session = readSession()
      if (!session) {
        stopRealtimeSubscription()
        state.value = {
          ...normalizeSnapshot(readLocalState()),
          bootstrapped: true,
          demoMode: true
        }
        remoteSignature = snapshotSignature(state.value.settings, state.value.addons)
        await inspectAddons()
        return
      }

      const payload = await apiFetch<BootstrapPayload>('/api/account/bootstrap', {}, accessToken(session))
      const resolvedSession = payload.session ?? session
      state.value.bootstrapped = true
      state.value.session = resolvedSession
      state.value.demoMode = isDemoModeForSession(resolvedSession)
      state.value.settings = sanitizePortalSettings(clone(payload.snapshot.settings))
      state.value.addons = clone(payload.snapshot.addons).map((addon, index) => sanitizeAddonRecord(addon, index))
      state.value.secretStatuses = clone(payload.snapshot.secretStatuses)
      state.value.linkedDevices = clone(payload.snapshot.linkedDevices)
      state.value.syncRevision = payload.snapshot.syncRevision
      state.value.lastSyncedAt = payload.snapshot.lastSyncedAt
      writeSession(state.value.session)

      await migrateLegacyAddonSecrets()
      remoteSignature = snapshotSignature(state.value.settings, state.value.addons)
      await ensureRealtimeSubscription()

      await inspectAddons()

      if (
        secretStatus(secretRefs.premiumize)?.status === 'configured' &&
        !state.value.settings.integrations.debrid.premiumize.customerId
      ) {
        await refreshPremiumizeStatus().catch(() => undefined)
      }

      if (
        secretStatus(secretRefs.mdblist)?.status === 'configured' &&
        state.value.mdblistDiscovery.personalLists.length === 0 &&
        state.value.mdblistDiscovery.topLists.length === 0
      ) {
        await validateMDBList().catch(() => undefined)
      }

      if (
        state.value.settings.integrations.traktAuth.connected &&
        state.value.traktDiscovery.popularLists.length === 0
      ) {
        await refreshTraktPopularLists().catch(() => undefined)
      }
    } catch (error) {
      state.value.bootstrapped = true
      state.value.demoMode = isDemoModeForSession(state.value.session)
      state.value.error = cleanErrorMessage(error, 'Failed to load portal state.')
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
      state.value.error = cleanErrorMessage(error, 'Unable to sign in.')
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
      state.value.error = cleanErrorMessage(error, 'Unable to create account.')
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
    clearTraktPollTimer()
    clearRealDebridPollTimer()
    state.value.traktFlow = null
    state.value.addonInspections = {}
    state.value.secretDrafts = {}
    state.value.mdblistDiscovery = {
      validating: false,
      valid: false,
      error: null,
      personalLists: [],
      topLists: [],
      searchResults: []
    }
    state.value.traktDiscovery = {
      loading: false,
      error: null,
      popularLists: [],
      searchResults: []
    }
    stopRealtimeSubscription()
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
      await bootstrap(true)
    } catch (error) {
      state.value.error = cleanErrorMessage(error, 'Unable to complete sign in.')
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
    if (leaf === undefined) {
      return
    }
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

  async function addAddon(url: string, parserPreset: AddonRecord['parserPreset'] = 'GENERIC') {
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
        ...sanitizeAddonRecord({
          ...parsed.addon,
          parserPreset: recommendParserPresetForAddonUrl(parsed.addon.manifestUrl) ?? parserPreset
        }, state.value.addons.length),
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
    if (!item) {
      return
    }
    next.splice(target, 0, item)
    state.value.addons = next.map((addon, itemIndex) => ({ ...addon, sortOrder: itemIndex }))
  }

  function reorderAddons(orderedIds: string[]) {
    const currentAddons = [...state.value.addons]
    const nextOrder: AddonRecord[] = []
    const addonMap = new Map(currentAddons.map((a) => [a.id, a]))

    for (const id of orderedIds) {
      const addon = addonMap.get(id)
      if (addon) {
        nextOrder.push(addon)
        addonMap.delete(id)
      }
    }

    // Append any unreferenced addons to the end to prevent data loss
    nextOrder.push(...addonMap.values())

    state.value.addons = nextOrder.map((addon, index) => ({ ...addon, sortOrder: index }))
  }

  function toggleAddon(id: string) {
    state.value.addons = state.value.addons.map((addon) =>
      addon.id === id ? { ...addon, enabled: !addon.enabled } : addon
    )
  }

  function updateAddonParserPreset(id: string, parserPreset: AddonRecord['parserPreset']) {
    state.value.addons = state.value.addons.map((addon) =>
      addon.id === id ? { ...addon, parserPreset } : addon
    )
  }

  function moveCatalog(key: string, direction: -1 | 1) {
    const availableKeys = catalogInventory.value.map((catalog) => catalog.key)

    if (!availableKeys.includes(key)) {
      return
    }

    const fullOrder = [
      ...state.value.settings.catalogs.home.homeCatalogOrderKeys.filter((catalogKey) => availableKeys.includes(catalogKey)),
      ...availableKeys.filter((catalogKey) => !state.value.settings.catalogs.home.homeCatalogOrderKeys.includes(catalogKey))
    ]
    const currentIndex = fullOrder.indexOf(key)
    if (currentIndex === -1) {
      return
    }

    const targetIndex = Math.max(0, Math.min(fullOrder.length - 1, currentIndex + direction))
    if (targetIndex === currentIndex) {
      return
    }

    const nextOrder = [...fullOrder]
    const currentValue = nextOrder[currentIndex]
    const targetValue = nextOrder[targetIndex]
    if (currentValue === undefined || targetValue === undefined) {
      return
    }
    nextOrder[currentIndex] = targetValue
    nextOrder[targetIndex] = currentValue
    state.value.settings.catalogs.home.homeCatalogOrderKeys = nextOrder
  }

  function reorderCatalogs(orderedVisibleKeys: string[]) {
    const availableKeys = catalogInventory.value.map((catalog) => catalog.key)
    const visibleKeys = orderedVisibleKeys.filter((key) => availableKeys.includes(key))
    if (visibleKeys.length === 0) {
      return
    }

    const fullOrder = [
      ...state.value.settings.catalogs.home.homeCatalogOrderKeys.filter((catalogKey) => availableKeys.includes(catalogKey)),
      ...availableKeys.filter((catalogKey) => !state.value.settings.catalogs.home.homeCatalogOrderKeys.includes(catalogKey))
    ]
    const visibleKeySet = new Set(visibleKeys)
    const hiddenKeys = fullOrder.filter((key) => !visibleKeySet.has(key))
    const nextOrder: string[] = []
    let visibleIndex = 0
    let hiddenIndex = 0

    for (const key of fullOrder) {
      if (visibleKeySet.has(key)) {
        nextOrder.push(visibleKeys[visibleIndex] ?? key)
        visibleIndex += 1
      } else {
        nextOrder.push(hiddenKeys[hiddenIndex] ?? key)
        hiddenIndex += 1
      }
    }

    const trailingVisible = visibleKeys.slice(visibleIndex)
    const trailingHidden = hiddenKeys.slice(hiddenIndex)
    state.value.settings.catalogs.home.homeCatalogOrderKeys = [...nextOrder, ...trailingVisible, ...trailingHidden]
  }

  function toggleCatalog(identifier: string, legacyKey?: string) {
    const disabled = new Set(state.value.settings.catalogs.home.disabledHomeCatalogKeys)
    const canonicalKey = identifier.trim()
    const fallbackKey = legacyKey?.trim() || ''

    const currentlyDisabled =
      disabled.has(canonicalKey) ||
      (fallbackKey ? disabled.has(fallbackKey) : false)

    if (currentlyDisabled) {
      disabled.delete(canonicalKey)
      if (fallbackKey) {
        disabled.delete(fallbackKey)
      }
    } else {
      disabled.add(canonicalKey)
    }
    state.value.settings.catalogs.home.disabledHomeCatalogKeys = [...disabled]
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

      state.value.demoMode = isDemoModeForSession(state.value.session)
      state.value.syncRevision = response.syncRevision
      state.value.lastSyncedAt = response.lastSyncedAt
      remoteSignature = snapshotSignature(state.value.settings, state.value.addons)
    } catch (error) {
      state.value.demoMode = isDemoModeForSession(state.value.session)
      state.value.error = cleanErrorMessage(error, 'Failed to sync to Nexio Live.')
      throw error
    } finally {
      state.value.saving = false
    }
  }

  async function pullAddonMigration(source: 'stremio' | 'nuvio', email: string, password: string) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before importing addons.')
    }

    state.value.migration.pulling = true
    state.value.migration.error = null

    try {
      const response = await apiFetch<{ source: 'stremio' | 'nuvio'; addons: ImportedAddonCandidate[] }>(
        `/api/migration/${source}/pull`,
        {
          method: 'POST',
          body: JSON.stringify({ email, password })
        },
        token
      )

      state.value.migration.importedAddons = response.addons
      state.value.migration.lastPulledAt = new Date().toISOString()
    } catch (error) {
      const message = cleanErrorMessage(error, 'Addon import pull failed.')
      state.value.migration.error = message
      throw new Error(message)
    } finally {
      state.value.migration.pulling = false
    }
  }

  async function commitAddonMigration(selectedUrls?: string[]) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before committing imported addons.')
    }

    const selectedSet = new Set((selectedUrls ?? []).map((url) => normalizeAddonUrl(url)))
    const imported = state.value.migration.importedAddons.filter((addon) =>
      selectedSet.size === 0 || selectedSet.has(normalizeAddonUrl(addon.url))
    )

    if (imported.length === 0) {
      throw new Error('No imported addons selected.')
    }

    state.value.migration.committing = true
    state.value.migration.error = null

    try {
      const response = await apiFetch<{ syncRevision: number; lastSyncedAt: string }>('/api/migration/commit', {
        method: 'POST',
        body: JSON.stringify({ importedAddons: imported })
      }, token)

      state.value.syncRevision = response.syncRevision
      state.value.lastSyncedAt = response.lastSyncedAt
      state.value.migration.importedAddons = []
      await bootstrap(true)
    } catch (error) {
      const message = cleanErrorMessage(error, 'Addon import commit failed.')
      state.value.migration.error = message
      throw new Error(message)
    } finally {
      state.value.migration.committing = false
    }
  }

  async function flushPendingSnapshotIfNeeded() {
    const token = accessToken(state.value.session)
    if (!token) {
      return
    }

    const localSignature = snapshotSignature(state.value.settings, state.value.addons)
    if (localSignature === remoteSignature) {
      return
    }

    if (persistTimer) {
      clearTimeout(persistTimer)
      persistTimer = null
    }

    await persistSnapshot()
  }

  async function saveTmdbApiKey(apiKey: string) {
    await saveSecret({
      secretType: 'tmdb_api_key',
      secretRef: secretRefs.tmdb,
      secretPayload: { apiKey: apiKey.trim() }
    })
  }

  async function clearTmdbApiKey() {
    await deleteSecret('tmdb_api_key', secretRefs.tmdb)
  }

  async function savePremiumizeApiKey(apiKey: string) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before saving Premiumize.')
    }

    const trimmed = apiKey.trim()
    const validation = await apiFetch<{
      valid: boolean
      customerId: number | null
      premiumUntil: number | null
    }>('/api/integrations/premiumize/validate', {
      method: 'POST',
      body: JSON.stringify({ apiKey: trimmed })
    }, token)

    await saveSecret({
      secretType: 'premiumize_api_key',
      secretRef: secretRefs.premiumize,
      secretPayload: { apiKey: trimmed }
    })

    state.value.settings.integrations.debrid.premiumize = {
      configured: validation.valid,
      customerId: validation.customerId
    }
  }

  async function clearPremiumizeApiKey() {
    await deleteSecret('premiumize_api_key', secretRefs.premiumize)
    state.value.settings.integrations.debrid.premiumize = {
      configured: false,
      customerId: null
    }
  }

  async function refreshPremiumizeStatus() {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before refreshing Premiumize.')
    }

    const validation = await apiFetch<{
      valid: boolean
      customerId: number | null
      premiumUntil: number | null
    }>('/api/integrations/premiumize/validate', {
      method: 'POST',
      body: JSON.stringify({})
    }, token)

    state.value.settings.integrations.debrid.premiumize = {
      configured: validation.valid,
      customerId: validation.customerId
    }
  }

  async function saveSecret(payload: SecretSetPayload) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before saving account secrets.')
    }

    await flushPendingSnapshotIfNeeded()

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

    await flushPendingSnapshotIfNeeded()

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
    const previous = clone(state.value.mdblistDiscovery)
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
        topLists: response.topLists,
        searchResults: previous.searchResults
      }
    } catch (error) {
      state.value.mdblistDiscovery = {
        validating: false,
        valid: previous.valid,
        error: cleanErrorMessage(error, 'MDBList validation failed.'),
        personalLists: previous.personalLists,
        topLists: previous.topLists,
        searchResults: previous.searchResults
      }
      throw error
    }
  }

  async function validateOMDB() {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before validating OMDB.')
    }

    const apiKey = state.value.secretDrafts[secretRefs.omdb]?.trim() || undefined
    await apiFetch<{ valid: boolean }>('/api/integrations/omdb/validate', {
      method: 'POST',
      body: JSON.stringify({ apiKey })
    }, token)
  }

  async function validateIMDb() {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before validating IMDb.')
    }

    state.value.error = null

    try {
      const apiKey = state.value.secretDrafts[secretRefs.imdb]?.trim() || undefined
      const response = await apiFetch<{ valid: boolean; baseUrl: string }>('/api/integrations/imdb/validate', {
        method: 'POST',
        body: JSON.stringify({
          baseUrl: state.value.settings.integrations.imdb.baseUrl,
          apiKey
        })
      }, token)

      if (response.baseUrl) {
        state.value.settings.integrations.imdb.baseUrl = response.baseUrl
      }
    } catch (error) {
      state.value.error = cleanErrorMessage(error, 'IMDb validation failed.')
      throw error
    }
  }

  async function searchMDBListLists(query: string) {
    if (!query) {
      state.value.mdblistDiscovery.searchResults = []
      return
    }

    const token = accessToken(state.value.session)
    if (!token) return

    try {
      const response = await apiFetch<{ lists: MDBListListOption[] }>('/api/integrations/mdblist/search-lists', {
        method: 'POST',
        body: JSON.stringify({ query })
      }, token)
      state.value.mdblistDiscovery.searchResults = response.lists
    } catch {
      state.value.mdblistDiscovery.searchResults = []
    }
  }

  function setMDBListPersonalListEnabled(key: string, enabled: boolean) {
    const next = new Set(state.value.settings.catalogs.mdblist.hiddenPersonalListKeys)
    if (enabled) {
      next.delete(key)
    } else {
      next.add(key)
    }
    state.value.settings.catalogs.mdblist.hiddenPersonalListKeys = [...next]
  }

  function setMDBListTopListSelected(key: string, selected: boolean) {
    const next = new Set(state.value.settings.catalogs.mdblist.selectedTopListKeys)
    if (selected) {
      next.add(key)
    } else {
      next.delete(key)
    }
    state.value.settings.catalogs.mdblist.selectedTopListKeys = [...next]
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

    if (secretType === 'mdblist_api_key') {
      await validateMDBList().catch(() => undefined)
    }

    if (secretType === 'omdb_api_key') {
      await validateOMDB().catch(() => undefined)
    }

    if (secretType === 'imdb_api_key') {
      await validateIMDb().catch(() => undefined)
    }
  }

  async function startTraktDeviceFlow() {
    state.value.error = null
    const token = accessToken(state.value.session)
    if (!token) {
      state.value.error = 'Sign in before starting Trakt authentication.'
      return
    }

    try {
      const flow = await apiFetch<TraktDeviceFlow>('/api/integrations/trakt/device-code', {
        method: 'POST',
        body: JSON.stringify({})
      }, token)
      state.value.traktFlow = flow
      state.value.settings.integrations.traktAuth.pending = true
      scheduleTraktAutoPoll(flow.interval * 1000)
    } catch (error) {
      state.value.settings.integrations.traktAuth.pending = false
      state.value.traktFlow = null
      state.value.error = cleanErrorMessage(error, 'Failed to start Trakt authentication.')
    }
  }

  function clearTraktPendingFlow() {
    state.value.traktFlow = null
    state.value.settings.integrations.traktAuth.pending = false
  }

  function scheduleTraktAutoPoll(delayMs?: number) {
    clearTraktPollTimer()
    if (!process.client || traktPollInFlight) {
      return
    }

    const flow = state.value.traktFlow
    if (!accessToken(state.value.session) || !state.value.settings.integrations.traktAuth.pending || !flow?.deviceCode) {
      return
    }

    const expiresAt = new Date(flow.startedAt).getTime() + (flow.expiresIn * 1000)
    if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
      clearTraktPendingFlow()
      return
    }

    const intervalMs = Math.max(1000, delayMs ?? flow.interval * 1000)
    traktPollTimer = setTimeout(() => {
      traktPollTimer = null
      completeTraktDeviceFlow({ auto: true }).catch(() => undefined)
    }, intervalMs)
  }

  async function startRealDebridDeviceFlow() {
    state.value.error = null
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before starting Real-Debrid authentication.')
    }

    const flow = await apiFetch<{
      deviceCode: string
      userCode: string
      verificationUrl: string
      expiresIn: number
      interval?: number
      startedAt: string
      expiresAt: number
    }>('/api/integrations/realdebrid/device-code', {
      method: 'POST',
      body: JSON.stringify({})
    }, token)

    state.value.settings.integrations.debrid.realDebrid = {
      connected: false,
      username: '',
      pending: true,
      deviceCode: flow.deviceCode,
      userCode: flow.userCode,
      verificationUrl: flow.verificationUrl,
      expiresAt: flow.expiresAt
    }
    if (process.client && flow.verificationUrl) {
      window.open(flow.verificationUrl, '_blank', 'noopener,noreferrer')
    }
    scheduleRealDebridAutoPoll()
  }

  function clearRealDebridPendingFlow() {
    state.value.settings.integrations.debrid.realDebrid = {
      connected: false,
      username: '',
      pending: false,
      deviceCode: '',
      userCode: '',
      verificationUrl: '',
      expiresAt: null
    }
  }

  function scheduleRealDebridAutoPoll(delayMs = REAL_DEBRID_POLL_INTERVAL_MS) {
    clearRealDebridPollTimer()
    if (!process.client || realDebridPollInFlight) {
      return
    }

    const auth = state.value.settings.integrations.debrid.realDebrid
    if (!accessToken(state.value.session) || !auth.pending || !auth.deviceCode?.trim()) {
      return
    }

    if (auth.expiresAt && auth.expiresAt <= Date.now()) {
      clearRealDebridPendingFlow()
      return
    }

    realDebridPollTimer = setTimeout(() => {
      realDebridPollTimer = null
      completeRealDebridDeviceFlow({ auto: true }).catch(() => undefined)
    }, delayMs)
  }

  async function completeRealDebridDeviceFlow(options: { auto?: boolean } = {}) {
    const token = accessToken(state.value.session)
    if (!token) {
      throw new Error('Sign in before completing Real-Debrid authentication.')
    }

    state.value.error = null
    clearRealDebridPollTimer()
    const deviceCode = state.value.settings.integrations.debrid.realDebrid.deviceCode?.trim()
    if (!deviceCode) {
      return
    }

    if (realDebridPollInFlight) {
      return
    }

    realDebridPollInFlight = true
    let shouldRetry = false

    try {
      const response = await apiFetch<{
        pending: boolean
        approved: boolean
        expired?: boolean
        realDebridAuth?: PortalSettings['integrations']['debrid']['realDebrid']
      }>('/api/integrations/realdebrid/device-token', {
        method: 'POST',
        body: JSON.stringify({ deviceCode })
      }, token)

      if (response.approved && response.realDebridAuth) {
        clearRealDebridPollTimer()
        state.value.settings.integrations.debrid.realDebrid = response.realDebridAuth
        return
      }

      if (response.expired) {
        clearRealDebridPollTimer()
        clearRealDebridPendingFlow()
        return
      }

      state.value.settings.integrations.debrid.realDebrid.pending = response.pending
      if (response.pending) {
        scheduleRealDebridAutoPoll()
      } else {
        clearRealDebridPollTimer()
      }
    } catch (error) {
      if (!options.auto) {
        throw error
      }
      state.value.error = cleanErrorMessage(error, 'Failed to check Real-Debrid approval.')
      shouldRetry = true
    } finally {
      realDebridPollInFlight = false
    }

    if (shouldRetry) {
      scheduleRealDebridAutoPoll()
    }
  }

  async function completeTraktDeviceFlow(options: { auto?: boolean } = {}) {
    const deviceCode = state.value.traktFlow?.deviceCode
    if (!deviceCode) {
      return
    }

    state.value.error = null
    clearTraktPollTimer()
    if (traktPollInFlight) {
      return
    }

    const token = accessToken(state.value.session)
    if (!token) {
      clearTraktPendingFlow()
      state.value.error = 'Sign in before completing Trakt authentication.'
      return
    }

    traktPollInFlight = true
    let shouldRetry = false

    try {
      const response = await apiFetch<{
        pending: boolean
        approved: boolean
        traktAuth?: PortalSettings['integrations']['traktAuth']
      }>('/api/integrations/trakt/device-token', {
        method: 'POST',
        body: JSON.stringify({ deviceCode })
      }, token)

      if (response.approved && response.traktAuth) {
        clearTraktPollTimer()
        state.value.settings.integrations.traktAuth = response.traktAuth
        state.value.traktFlow = null
        await refreshTraktPopularLists()
        return
      }

      state.value.settings.integrations.traktAuth.pending = response.pending
      if (response.pending) {
        scheduleTraktAutoPoll()
      } else {
        clearTraktPendingFlow()
      }
    } catch (error) {
      const message = cleanErrorMessage(error, 'Failed to check Trakt approval.')
      state.value.error = message
      if (options.auto) {
        shouldRetry = true
      } else {
        clearTraktPendingFlow()
      }
    } finally {
      traktPollInFlight = false
    }

    if (shouldRetry) {
      scheduleTraktAutoPoll()
    }
  }

  async function refreshTraktPopularLists() {
    const token = accessToken(state.value.session)
    if (!token || !state.value.settings.integrations.traktAuth.connected) {
      return
    }

    const previous = clone(state.value.traktDiscovery)
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
        popularLists: response.lists,
        searchResults: previous.searchResults
      }
    } catch (error) {
      state.value.traktDiscovery = {
        loading: false,
        error: cleanErrorMessage(error, 'Failed to load Trakt lists.'),
        popularLists: previous.popularLists,
        searchResults: previous.searchResults
      }
      throw error
    }
  }

  async function searchTraktLists(query: string) {
    if (!query) {
      state.value.traktDiscovery.searchResults = []
      return
    }

    const token = accessToken(state.value.session)
    if (!token || !state.value.settings.integrations.traktAuth.connected) return

    try {
      const response = await apiFetch<{ lists: TraktPopularListOption[] }>('/api/integrations/trakt/search-lists', {
        method: 'POST',
        body: JSON.stringify({ query })
      }, token)
      state.value.traktDiscovery.searchResults = response.lists
    } catch {
      state.value.traktDiscovery.searchResults = []
    }
  }

  function toggleTraktPopularList(key: string) {
    const next = new Set(state.value.settings.catalogs.trakt.selectedPopularListKeys)
    if (next.has(key)) {
      next.delete(key)
    } else {
      next.add(key)
    }
    state.value.settings.catalogs.trakt.selectedPopularListKeys = [...next]
  }

  function disconnectTrakt() {
    clearTraktPollTimer()
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
      popularLists: [],
      searchResults: []
    }
  }

  function disconnectRealDebrid() {
    clearRealDebridPollTimer()
    deleteSecret('realdebrid_access_token', secretRefs.realDebrid).catch(() => undefined)
    deleteSecret('realdebrid_refresh_token', secretRefs.realDebrid).catch(() => undefined)
    clearRealDebridPendingFlow()
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
            traktFlow: state.value.traktFlow,
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
        signedIn: Boolean(state.value.session),
        pending: state.value.settings.integrations.traktAuth.pending,
        deviceCode: state.value.traktFlow?.deviceCode ?? '',
        startedAt: state.value.traktFlow?.startedAt ?? '',
        expiresIn: state.value.traktFlow?.expiresIn ?? 0
      }),
      ({ signedIn, pending, deviceCode, startedAt, expiresIn }) => {
        if (!signedIn || !pending || !deviceCode) {
          clearTraktPollTimer()
          return
        }

        const expiresAt = new Date(startedAt).getTime() + (expiresIn * 1000)
        if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
          clearTraktPollTimer()
          clearTraktPendingFlow()
          return
        }

        if (!traktPollTimer && !traktPollInFlight) {
          scheduleTraktAutoPoll()
        }
      },
      { immediate: true }
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

    watch(
      () => ({
        signedIn: Boolean(state.value.session),
        pending: state.value.settings.integrations.debrid.realDebrid.pending,
        deviceCode: state.value.settings.integrations.debrid.realDebrid.deviceCode,
        expiresAt: state.value.settings.integrations.debrid.realDebrid.expiresAt
      }),
      ({ signedIn, pending, deviceCode, expiresAt }) => {
        if (!signedIn || !pending || !deviceCode?.trim()) {
          clearRealDebridPollTimer()
          return
        }

        if (expiresAt && expiresAt <= Date.now()) {
          clearRealDebridPollTimer()
          clearRealDebridPendingFlow()
          return
        }

        if (!realDebridPollTimer && !realDebridPollInFlight) {
          scheduleRealDebridAutoPoll()
        }
      },
      { immediate: true }
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
    const traktCatalogs = buildTraktCatalogs(state.value)
    const mdblistCatalogs = buildMDBListCatalogs(state.value)
    return orderedCatalogs([...addonCatalogs, ...traktCatalogs, ...mdblistCatalogs], state.value.settings)
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
    reorderAddons,
    toggleAddon,
    updateAddonParserPreset,
    moveCatalog,
    reorderCatalogs,
    toggleCatalog,
    unlinkDevice,
    persistSnapshot,
    saveTmdbApiKey,
    clearTmdbApiKey,
    savePremiumizeApiKey,
    clearPremiumizeApiKey,
    refreshPremiumizeStatus,
    secretStatus,
    setSecretDraft,
    saveDraftSecret,
    deleteSecret,
    approveTvLogin,
    validateMDBList,
    validateIMDb,
    searchMDBListLists,
    setMDBListPersonalListEnabled,
    setMDBListTopListSelected,
    startTraktDeviceFlow,
    completeTraktDeviceFlow,
    startRealDebridDeviceFlow,
    completeRealDebridDeviceFlow,
    refreshTraktPopularLists,
    searchTraktLists,
    toggleTraktPopularList,
    disconnectTrakt,
    disconnectRealDebrid,
    pullAddonMigration,
    commitAddonMigration
  }
}
