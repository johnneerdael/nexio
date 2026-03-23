import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeImdbBaseUrl } from '../server/utils/imdb-validation'

test('normalizeImdbBaseUrl trims input and removes one trailing slash', () => {
  assert.equal(normalizeImdbBaseUrl('  https://example.com/api/  '), 'https://example.com/api')
})

test('normalizeImdbBaseUrl preserves path prefixes', () => {
  assert.equal(normalizeImdbBaseUrl('https://example.com/custom/path/'), 'https://example.com/custom/path')
})

