import test from 'node:test'
import assert from 'node:assert/strict'
import { validateImdbConfig } from '../server/utils/imdb-validation'

test('validateImdbConfig falls back to the saved secret when apiKey is omitted', async () => {
  const calls: Array<Record<string, unknown>> = []
  const result = await validateImdbConfig({
    baseUrl: 'https://ratings.example.com/',
    apiKey: '',
    userId: 'user-1',
    resolveSecret: async (args) => {
      calls.push(args)
      return { apiKey: 'saved-key' }
    },
    fetchImpl: async (input, init) => {
      assert.equal(input, 'https://ratings.example.com/v1/meta/stats')
      assert.equal(init?.headers instanceof Headers, true)
      assert.equal((init?.headers as Headers).get('X-API-Key'), 'saved-key')
      return new Response(JSON.stringify({ ok: true }), { status: 200 })
    }
  })

  assert.deepEqual(calls, [{
    userId: 'user-1',
    secretType: 'imdb_api_key',
    secretRef: 'integration:imdb',
    source: 'web-imdb'
  }])
  assert.deepEqual(result, { valid: true, baseUrl: 'https://ratings.example.com' })
})

test('validateImdbConfig rejects malformed base URLs', async () => {
  await assert.rejects(
    () => validateImdbConfig({
      baseUrl: 'not-a-url',
      apiKey: 'key',
      fetchImpl: async () => new Response('ok', { status: 200 })
    }),
    (error: unknown) => {
      assert.equal((error as { statusCode?: number }).statusCode, 400)
      assert.match(String((error as Error).message), /invalid/i)
      return true
    }
  )
})

test('validateImdbConfig rejects missing API keys', async () => {
  await assert.rejects(
    () => validateImdbConfig({
      baseUrl: 'https://ratings.example.com',
      apiKey: '',
      resolveSecret: async () => ({ apiKey: '' }),
      fetchImpl: async () => new Response('ok', { status: 200 })
    }),
    (error: unknown) => {
      assert.equal((error as { statusCode?: number }).statusCode, 400)
      assert.match(String((error as Error).message), /required/i)
      return true
    }
  )
})

test('validateImdbConfig reports upstream auth failures', async () => {
  await assert.rejects(
    () => validateImdbConfig({
      baseUrl: 'https://ratings.example.com',
      apiKey: 'bad-key',
      fetchImpl: async () => new Response(JSON.stringify({ error: 'Unauthorized' }), { status: 401 })
    }),
    (error: unknown) => {
      assert.equal((error as { statusCode?: number }).statusCode, 401)
      assert.match(String((error as Error).message), /unauthorized/i)
      return true
    }
  )
})

test('validateImdbConfig reports request failures', async () => {
  await assert.rejects(
    () => validateImdbConfig({
      baseUrl: 'https://ratings.example.com',
      apiKey: 'key',
      fetchImpl: async () => {
        throw new Error('network down')
      }
    }),
    (error: unknown) => {
      assert.equal((error as { statusCode?: number }).statusCode, 502)
      assert.match(String((error as Error).message), /request failed/i)
      return true
    }
  )
})

