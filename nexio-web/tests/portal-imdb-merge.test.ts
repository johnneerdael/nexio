import test from 'node:test'
import assert from 'node:assert/strict'
import { sanitizePortalSettings } from '../composables/usePortalStore'

test('sanitizePortalSettings merges imdb defaults into partial settings', () => {
  const settings = sanitizePortalSettings({
    integrations: {
      imdb: {
        enabled: true
      }
    }
  } as never)

  assert.equal(settings.integrations.imdb.enabled, true)
  assert.equal(settings.integrations.imdb.baseUrl, '')
})

