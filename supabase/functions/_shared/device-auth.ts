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

export function normalizeDeviceExchangeBody(body: Record<string, unknown>) {
  const devicePublicId = String(body.device_public_id ?? '').trim()
  const deviceSecret = String(body.device_secret ?? '').trim()

  if (!devicePublicId || !deviceSecret) {
    throw new Error('Invalid durable device credential')
  }

  return { devicePublicId, deviceSecret }
}
