export async function hashDeviceCredential(
  devicePublicId: string,
  deviceSecret: string,
): Promise<string> {
  const payload = new TextEncoder().encode(
    `${devicePublicId.trim()}:${deviceSecret.trim()}`,
  )
  const digest = await crypto.subtle.digest('SHA-256', payload)

  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return false
  }

  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

export function normalizeDeviceExchangeBody(body: unknown) {
  if (!isPlainObject(body)) {
    throw new Error('Invalid durable device credential')
  }

  if (
    typeof body.device_public_id !== 'string' ||
    typeof body.device_secret !== 'string'
  ) {
    throw new Error('Invalid durable device credential')
  }

  const devicePublicId = body.device_public_id.trim()
  const deviceSecret = body.device_secret.trim()

  if (!devicePublicId || !deviceSecret) {
    throw new Error('Invalid durable device credential')
  }

  return { devicePublicId, deviceSecret }
}
