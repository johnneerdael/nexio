import test from 'node:test'
import assert from 'node:assert/strict'
import { defaultSettings } from '../utils/portal-defaults'
import {
  integrationSecretDeletions,
  integrationSecretDeletion,
  resetIntegrationSettings
} from '../utils/integration-delete'

test('integrationSecretDeletion maps imdb to the synced secret ref', () => {
  assert.deepEqual(integrationSecretDeletion('imdb'), {
    secretType: 'imdb_api_key',
    secretRef: 'integration:imdb'
  })
})

test('integrationSecretDeletion maps topposters to the synced secret ref', () => {
  assert.deepEqual(integrationSecretDeletion('topposters'), {
    secretType: 'top_posters_api_key',
    secretRef: 'integration:topposters'
  })
})

test('integrationSecretDeletions clears both poster-provider secrets together', () => {
  assert.deepEqual(integrationSecretDeletions('rpdb'), [
    {
      secretType: 'rpdb_api_key',
      secretRef: 'integration:rpdb'
    },
    {
      secretType: 'top_posters_api_key',
      secretRef: 'integration:topposters'
    }
  ])
})

test('resetIntegrationSettings clears imdb base url and enabled state', () => {
  const settings = defaultSettings()
  settings.integrations.imdb.enabled = true
  settings.integrations.imdb.baseUrl = 'https://api.nexioapp.org/v1'

  const next = resetIntegrationSettings(settings, 'imdb')

  assert.deepEqual(next.integrations.imdb, defaultSettings().integrations.imdb)
})

test('resetIntegrationSettings clears mdblist integration and catalog selections', () => {
  const settings = defaultSettings()
  settings.integrations.mdblist.enabled = true
  settings.catalogs.mdblist.catalogOrder = ['abc']
  settings.catalogs.mdblist.selectedTopListKeys = ['top-1']

  const next = resetIntegrationSettings(settings, 'mdblist')

  assert.deepEqual(next.integrations.mdblist, defaultSettings().integrations.mdblist)
  assert.deepEqual(next.catalogs.mdblist, defaultSettings().catalogs.mdblist)
})

test('resetIntegrationSettings resets poster provider flags together', () => {
  const settings = defaultSettings()
  settings.integrations.posterRatings.rpdbEnabled = true
  settings.integrations.posterRatings.topPostersEnabled = true

  const next = resetIntegrationSettings(settings, 'rpdb')

  assert.deepEqual(next.integrations.posterRatings, defaultSettings().integrations.posterRatings)
})
