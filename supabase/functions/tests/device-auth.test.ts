import test from 'node:test'
import assert from 'node:assert/strict'
import { hashDeviceCredential, normalizeDeviceExchangeBody } from '../_shared/device-auth.ts'

test('hashDeviceCredential is deterministic for the same raw secret', async () => {
  const a = await hashDeviceCredential('public-id', 'secret-value')
  const b = await hashDeviceCredential('public-id', 'secret-value')
  assert.equal(a, b)
})

test('normalizeDeviceExchangeBody rejects blank credential inputs', () => {
  assert.throws(
    () => normalizeDeviceExchangeBody({ device_public_id: ' ', device_secret: '' }),
    /Invalid durable device credential/
  )
})
