import test from 'node:test'
import assert from 'node:assert/strict'
import { hashDeviceCredential, normalizeDeviceExchangeBody } from '../_shared/device-auth.ts'

test('hashDeviceCredential is deterministic for the same raw secret', async () => {
  const a = await hashDeviceCredential('public-id', 'secret-value')
  const b = await hashDeviceCredential('public-id', 'secret-value')
  assert.equal(a, b)
})

test('hashDeviceCredential depends on the credential inputs', async () => {
  const base = await hashDeviceCredential('public-id', 'secret-value')
  const differentSecret = await hashDeviceCredential('public-id', 'other-secret')
  const differentPublicId = await hashDeviceCredential('other-public-id', 'secret-value')

  assert.notEqual(base, differentSecret)
  assert.notEqual(base, differentPublicId)
})

test('hashDeviceCredential does not collide for colon-containing credential pairs', async () => {
  const a = await hashDeviceCredential('foo', 'bar:baz')
  const b = await hashDeviceCredential('foo:bar', 'baz')

  assert.notEqual(a, b)
})

test('normalizeDeviceExchangeBody returns trimmed credential strings', () => {
  assert.deepEqual(
    normalizeDeviceExchangeBody({
      device_public_id: '  public-id  ',
      device_secret: '  secret-value  ',
    }),
    { devicePublicId: 'public-id', deviceSecret: 'secret-value' },
  )
})

test('normalizeDeviceExchangeBody rejects blank credential inputs', () => {
  assert.throws(
    () => normalizeDeviceExchangeBody({ device_public_id: ' ', device_secret: '' }),
    /Invalid durable device credential/,
  )
})

test('normalizeDeviceExchangeBody rejects non-object bodies', () => {
  for (const body of [null, [], 7, true]) {
    assert.throws(
      () => normalizeDeviceExchangeBody(body as never),
      /Invalid durable device credential/,
    )
  }
})

test('normalizeDeviceExchangeBody rejects malformed credential field types', () => {
  for (const body of [
    { device_public_id: { nested: 'value' }, device_secret: 'secret-value' },
    { device_public_id: 'public-id', device_secret: ['secret-value'] },
    { device_public_id: 7, device_secret: 'secret-value' },
    { device_public_id: 'public-id', device_secret: false },
  ]) {
    assert.throws(
      () => normalizeDeviceExchangeBody(body as never),
      /Invalid durable device credential/,
    )
  }
})

test('normalizeDeviceExchangeBody rejects missing credential fields', () => {
  for (const body of [
    {},
    { device_public_id: 'public-id' },
    { device_secret: 'secret-value' },
  ]) {
    assert.throws(
      () => normalizeDeviceExchangeBody(body as never),
      /Invalid durable device credential/,
    )
  }
})
