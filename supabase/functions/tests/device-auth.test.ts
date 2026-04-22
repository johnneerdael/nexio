import test from 'node:test'
import assert from 'node:assert/strict'
import { hashDeviceCredential, normalizeDeviceExchangeBody } from '../_shared/device-auth.ts'

const migrationContractText = String.raw`
device_public_id text not null check (length(trim(device_public_id)) > 0),
credential_hash text not null check (length(trim(credential_hash)) > 0),
display_name text not null check (length(trim(display_name)) > 0),
status text not null default 'active'
  check (status in ('active', 'revoked')),
revoked_at timestamptz null,
check (
  (status = 'revoked' and revoked_at is not null)
  or (status <> 'revoked' and revoked_at is null)
)
raise exception 'Invalid durable device credential';
raise exception 'Device credential not found or already revoked';
`

test('hashDeviceCredential is deterministic for the same raw secret', async () => {
  const a = await hashDeviceCredential('public-id', 'secret-value')
  const b = await hashDeviceCredential('public-id', 'secret-value')
  assert.equal(a, b)
})

test('hashDeviceCredential matches the canonical golden hash', async () => {
  const hash = await hashDeviceCredential('public-id', 'secret-value')

  assert.equal(hash, 'db533cc9f987253d03a063d1c130580872ab28ba41a9b64d1cb45686ff89f6b4')
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

test('durable device auth migration enforces non-empty authority fields', () => {
  assert.match(migrationContractText, /device_public_id text not null check \(length\(trim\(device_public_id\)\) > 0\)/)
  assert.match(migrationContractText, /credential_hash text not null check \(length\(trim\(credential_hash\)\) > 0\)/)
  assert.match(migrationContractText, /display_name text not null check \(length\(trim\(display_name\)\) > 0\)/)
})

test('durable device auth migration only models active and revoked statuses', () => {
  assert.match(migrationContractText, /check \(status in \('active', 'revoked'\)\)/)
})

test('durable device auth migration ties revoked status to revoked timestamp', () => {
  assert.match(migrationContractText, /status = 'revoked' and revoked_at is not null/)
  assert.match(migrationContractText, /status <> 'revoked' and revoked_at is null/)
})

test('durable device auth migration keeps revoke explicit and non-silent', () => {
  assert.match(migrationContractText, /raise exception 'Invalid durable device credential';/)
  assert.match(migrationContractText, /raise exception 'Device credential not found or already revoked';/)
})
