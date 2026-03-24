import { secretRefs } from '~/utils/account-secrets'
import { defaultSettings } from '~/utils/portal-defaults'
import type { PortalSettings, SecretType } from '~/types/portal'

export type DeletableIntegrationId =
  | 'tmdb'
  | 'omdb'
  | 'imdb'
  | 'mdblist'
  | 'animeskip'
  | 'gemini'
  | 'rpdb'
  | 'topposters'
  | 'premiumize'

export type IntegrationSecretDeletion = {
  secretType: SecretType
  secretRef: string
}

export function integrationSecretDeletions(id: DeletableIntegrationId): IntegrationSecretDeletion[] {
  switch (id) {
    case 'rpdb':
    case 'topposters':
      return [
        { secretType: 'rpdb_api_key', secretRef: secretRefs.rpdb },
        { secretType: 'top_posters_api_key', secretRef: secretRefs.topPosters }
      ]
    default: {
      const deletion = integrationSecretDeletion(id)
      return deletion ? [deletion] : []
    }
  }
}

export function integrationSecretDeletion(id: DeletableIntegrationId): IntegrationSecretDeletion | null {
  switch (id) {
    case 'tmdb':
      return { secretType: 'tmdb_api_key', secretRef: secretRefs.tmdb }
    case 'omdb':
      return { secretType: 'omdb_api_key', secretRef: secretRefs.omdb }
    case 'imdb':
      return { secretType: 'imdb_api_key', secretRef: secretRefs.imdb }
    case 'mdblist':
      return { secretType: 'mdblist_api_key', secretRef: secretRefs.mdblist }
    case 'gemini':
      return { secretType: 'gemini_api_key', secretRef: secretRefs.gemini }
    case 'rpdb':
      return { secretType: 'rpdb_api_key', secretRef: secretRefs.rpdb }
    case 'topposters':
      return { secretType: 'top_posters_api_key', secretRef: secretRefs.topPosters }
    case 'premiumize':
      return { secretType: 'premiumize_api_key', secretRef: secretRefs.premiumize }
    default:
      return null
  }
}

export function resetIntegrationSettings(settings: PortalSettings, id: DeletableIntegrationId): PortalSettings {
  const defaults = defaultSettings()
  const next = structuredClone(settings)

  switch (id) {
    case 'tmdb':
      next.integrations.tmdb = defaults.integrations.tmdb
      break
    case 'omdb':
      next.integrations.omdb = defaults.integrations.omdb
      break
    case 'imdb':
      next.integrations.imdb = defaults.integrations.imdb
      break
    case 'mdblist':
      next.integrations.mdblist = defaults.integrations.mdblist
      next.catalogs.mdblist = defaults.catalogs.mdblist
      break
    case 'animeskip':
      next.integrations.animeSkip = defaults.integrations.animeSkip
      break
    case 'gemini':
      next.integrations.gemini = defaults.integrations.gemini
      break
    case 'rpdb':
    case 'topposters':
      next.integrations.posterRatings = defaults.integrations.posterRatings
      break
    case 'premiumize':
      next.integrations.debrid.premiumize = defaults.integrations.debrid.premiumize
      break
  }

  return next
}
