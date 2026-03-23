import { createError } from 'h3'
import { secretRefs } from '~/server/utils/account-secrets'

export function normalizeImdbBaseUrl(rawBaseUrl: string): string {
  return rawBaseUrl.trim().replace(/\/$/, '')
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
  error?: string
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
    response = await (input.fetchImpl ?? fetch)(`${parsedBaseUrl.toString().replace(/\/$/, '')}/v1/meta/stats`, {
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

    const message = String(
      payload?.message ||
      payload?.error ||
      payload?.status ||
      'IMDb validation failed.'
    ).trim()

    if (response.status === 401 || response.status === 403) {
      throw createError({ statusCode: response.status, statusMessage: message || 'IMDb provider rejected the API key.' })
    }

    throw createError({ statusCode: 502, statusMessage: message || 'IMDb validation request failed.' })
  }

  return {
    valid: true,
    baseUrl: parsedBaseUrl.toString().replace(/\/$/, '')
  }
}
