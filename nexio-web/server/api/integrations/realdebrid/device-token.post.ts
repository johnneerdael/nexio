import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { secretRefs } from '~/server/utils/account-secrets'

type DeviceTokenBody = {
  deviceCode?: string
}

type DeviceCredentialsResponse = {
  client_id: string
  client_secret: string
}

type TokenResponse = {
  access_token: string
  refresh_token: string
  token_type: string
  expires_in: number
}

type UserResponse = {
  username?: string | null
}

const REAL_DEBRID_DEVICE_GRANT_TYPE = 'http://oauth.net/grant_type/device/1.0'
const REAL_DEBRID_PENDING_STATUSES = [400, 403, 404]

function compactErrorMessage(input: string) {
  return input.replace(/\s+/g, ' ').trim().slice(0, 240) || 'No response body.'
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  const user = await supabaseUser(event)
  const body = await readJsonBody<DeviceTokenBody>(event)
  const deviceCode = body.deviceCode?.trim()
  if (!deviceCode) {
    throw createError({ statusCode: 400, statusMessage: 'Real-Debrid device code is required.' })
  }

  const config = useRuntimeConfig()
  const clientId = String(config.realDebridClientId || '').trim()
  const clientSecret = String(config.realDebridClientSecret || '').trim()
  if (!clientId) {
    throw createError({ statusCode: 503, statusMessage: 'REAL_DEBRID_CLIENT_ID is not configured.' })
  }

  let userClientId = clientId
  let userClientSecret = clientSecret

  if (!clientSecret) {
    const credentialResponse = await fetch(
      `https://api.real-debrid.com/oauth/v2/device/credentials?client_id=${encodeURIComponent(clientId)}&code=${encodeURIComponent(deviceCode)}`
    )

    if (!credentialResponse.ok) {
      if (!REAL_DEBRID_PENDING_STATUSES.includes(credentialResponse.status) && credentialResponse.status !== 410) {
        throw createError({
          statusCode: 502,
          statusMessage: `Real-Debrid credential check failed (${credentialResponse.status}): ${compactErrorMessage(await credentialResponse.text())}`
        })
      }

      return okJson({
        status: credentialResponse.status,
        pending: REAL_DEBRID_PENDING_STATUSES.includes(credentialResponse.status),
        approved: false,
        expired: credentialResponse.status === 410
      })
    }

    const credentials = await credentialResponse.json() as DeviceCredentialsResponse
    if (!credentials.client_id?.trim() || !credentials.client_secret?.trim()) {
      return okJson({
        status: 200,
        pending: true,
        approved: false,
        expired: false
      })
    }

    userClientId = credentials.client_id
    userClientSecret = credentials.client_secret
  }

  const tokenResponse = await fetch('https://api.real-debrid.com/oauth/v2/token', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    body: new URLSearchParams({
      client_id: userClientId,
      client_secret: userClientSecret,
      code: deviceCode,
      grant_type: REAL_DEBRID_DEVICE_GRANT_TYPE
    })
  })

  if (!tokenResponse.ok) {
    if (!REAL_DEBRID_PENDING_STATUSES.includes(tokenResponse.status) && tokenResponse.status !== 410) {
      throw createError({
        statusCode: 502,
        statusMessage: `Real-Debrid token exchange failed (${tokenResponse.status}): ${compactErrorMessage(await tokenResponse.text())}`
      })
    }

    return okJson({
      status: tokenResponse.status,
      pending: REAL_DEBRID_PENDING_STATUSES.includes(tokenResponse.status),
      approved: false,
      expired: tokenResponse.status === 410
    })
  }

  const tokenPayload = await tokenResponse.json() as TokenResponse
  const userResponse = await fetch('https://api.real-debrid.com/rest/1.0/user', {
    headers: {
      Authorization: `Bearer ${tokenPayload.access_token}`
    }
  })

  if (!userResponse.ok) {
    throw createError({ statusCode: userResponse.status, statusMessage: await userResponse.text() })
  }

  const userPayload = await userResponse.json() as UserResponse

  await supabaseFetch('/rest/v1/rpc/service_set_account_secret', {
    method: 'POST',
    body: JSON.stringify({
      p_user_id: user.id,
      p_secret_type: 'realdebrid_access_token',
      p_secret_ref: secretRefs.realDebrid,
      p_secret_payload: {
        accessToken: tokenPayload.access_token,
        tokenType: tokenPayload.token_type,
        expiresIn: tokenPayload.expires_in,
        userClientId,
        userClientSecret
      },
      p_masked_preview: `Connected ••••${tokenPayload.access_token.slice(-4)}`,
      p_status: 'configured',
      p_source: 'web-realdebrid'
    })
  }, undefined, true)

  await supabaseFetch('/rest/v1/rpc/service_set_account_secret', {
    method: 'POST',
    body: JSON.stringify({
      p_user_id: user.id,
      p_secret_type: 'realdebrid_refresh_token',
      p_secret_ref: secretRefs.realDebrid,
      p_secret_payload: {
        refreshToken: tokenPayload.refresh_token
      },
      p_masked_preview: `Connected ••••${tokenPayload.refresh_token.slice(-4)}`,
      p_status: 'configured',
      p_source: 'web-realdebrid'
    })
  }, undefined, true)

  return okJson({
    status: 200,
    pending: false,
    approved: true,
    realDebridAuth: {
      connected: true,
      username: userPayload.username ?? '',
      pending: false,
      deviceCode: '',
      userCode: '',
      verificationUrl: '',
      expiresAt: null
    }
  })
})
