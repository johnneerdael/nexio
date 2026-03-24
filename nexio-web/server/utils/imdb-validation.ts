import { createError } from 'h3'
import { secretRefs } from '~/server/utils/account-secrets'
export function normalizeImdbBaseUrl(rawBaseUrl: string): string {
  return rawBaseUrl.trim().replace(/\/$/, '')
}

function buildImdbEndpointUrl(parsedBaseUrl: URL, pathAfterVersion: string): string {
  const normalizedBaseUrl = parsedBaseUrl.toString().replace(/\/$/, '')
  const normalizedPath = pathAfterVersion.replace(/^\/+/, '')
  const normalizedBasePath = parsedBaseUrl.pathname.replace(/\/+$/, '')
  const hasVersionPath = normalizedBasePath === '/v1' || normalizedBasePath.endsWith('/v1')

  return hasVersionPath
    ? `${normalizedBaseUrl}/${normalizedPath}`
    : `${normalizedBaseUrl}/v1/${normalizedPath}`
}

type ImdbSecretResolution = {
  apiKey?: string | null
}

type ValidateImdbConfigInput = {
  baseUrl?: string
  apiKey?: string
  userId?: string
  source?: string
  resolveSecret?: (input: {
    userId: string
    secretType: 'imdb_api_key'
    secretRef: string
    source: string
  }) => Promise<ImdbSecretResolution | null | undefined>
  fetchImpl?: typeof fetch
}

type ImdbValidationResponse = {
  status?: string
  message?: string
  error?: string | {
    code?: string
    message?: string
  }
}

function readImdbValidationMessage(payload: ImdbValidationResponse | null): string {
  if (!payload) return ''

  if (typeof payload.message === 'string' && payload.message.trim()) {
    return payload.message.trim()
  }

  if (typeof payload.error === 'string' && payload.error.trim()) {
    return payload.error.trim()
  }

  if (payload.error && typeof payload.error === 'object') {
    if (typeof payload.error.message === 'string' && payload.error.message.trim()) {
      return payload.error.message.trim()
    }
    if (typeof payload.error.code === 'string' && payload.error.code.trim()) {
      return payload.error.code.trim()
    }
  }

  if (typeof payload.status === 'string' && payload.status.trim()) {
    return payload.status.trim()
  }

  return ''
}

export async function validateImdbConfig(input: ValidateImdbConfigInput): Promise<{ valid: true; baseUrl: string }> {
  const normalizedBaseUrl = normalizeImdbBaseUrl(String(input.baseUrl || ''))

  if (!normalizedBaseUrl) {
    throw createError({ statusCode: 400, statusMessage: 'IMDb base URL is required.' })
  }

  let parsedBaseUrl: URL
  try {
    parsedBaseUrl = new URL(normalizedBaseUrl)
  } catch {
    throw createError({ statusCode: 400, statusMessage: 'IMDb base URL is invalid.' })
  }

  if (parsedBaseUrl.protocol !== 'http:' && parsedBaseUrl.protocol !== 'https:') {
    throw createError({ statusCode: 400, statusMessage: 'IMDb base URL must use http or https.' })
  }

  let apiKey = input.apiKey?.trim() ?? ''
  if (!apiKey) {
    if (!input.resolveSecret || !input.userId) {
      throw createError({ statusCode: 400, statusMessage: 'IMDb API key is required.' })
    }

    const payload = await input.resolveSecret({
      userId: input.userId,
      secretType: 'imdb_api_key',
      secretRef: secretRefs.imdb,
      source: input.source || 'web-imdb'
    })
    apiKey = String(payload?.apiKey || '').trim()
  }

  if (!apiKey) {
    throw createError({ statusCode: 400, statusMessage: 'IMDb API key is required.' })
  }

  let response: Response
  try {
    response = await (input.fetchImpl ?? fetch)(buildImdbEndpointUrl(parsedBaseUrl, 'meta/stats'), {
      method: 'GET',
      headers: new Headers({
        'X-API-Key': apiKey
      })
    })
  } catch {
    throw createError({ statusCode: 502, statusMessage: 'IMDb validation request failed.' })
  }

  if (!response.ok) {
    let payload: ImdbValidationResponse | null = null
    try {
      payload = await response.json() as ImdbValidationResponse
    } catch {
      payload = null
    }

    const message = readImdbValidationMessage(payload) || 'IMDb validation failed.'

    if (response.status >= 400 && response.status < 500) {
      throw createError({ statusCode: response.status, statusMessage: message || 'IMDb provider rejected the API key.' })
    }

    throw createError({ statusCode: 502, statusMessage: message || 'IMDb validation request failed.' })
  }

  return {
    valid: true,
    baseUrl: parsedBaseUrl.toString().replace(/\/$/, '')
  }
}
