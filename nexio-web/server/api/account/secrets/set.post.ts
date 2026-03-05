import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { maskSecretPreview } from '~/server/utils/account-secrets'
import type { SecretMetadata, SecretType } from '~/types/portal'

type SecretSetBody = {
  secretType?: SecretType
  secretRef?: string
  secretPayload?: Record<string, unknown>
  status?: 'configured' | 'error'
}

type RpcSecretResult = {
  id: string
  secret_type: SecretType
  secret_ref: string
  masked_preview?: string | null
  status?: SecretMetadata['status']
  version?: number
  updated_at?: string | null
}

export default defineEventHandler(async (event) => {
  const token = bearerToken(event)
  const user = await supabaseUser(event)
  const body = await readJsonBody<SecretSetBody>(event)

  const secretType = body.secretType
  const secretRef = body.secretRef?.trim()
  const secretPayload = body.secretPayload
  if (!secretType || !secretRef || !secretPayload) {
    throw createError({ statusCode: 400, statusMessage: 'secretType, secretRef, and secretPayload are required.' })
  }

  const result = await supabaseFetch<RpcSecretResult>(
    '/rest/v1/rpc/service_set_account_secret',
    {
      method: 'POST',
      body: JSON.stringify({
        p_user_id: user.id,
        p_secret_type: secretType,
        p_secret_ref: secretRef,
        p_secret_payload: secretPayload,
        p_masked_preview: maskSecretPreview(secretType, secretPayload),
        p_status: body.status ?? 'configured',
        p_source: 'web'
      })
    },
    undefined,
    true
  )

  return okJson({
    secret: {
      id: result.id,
      secretType: result.secret_type,
      secretRef: result.secret_ref,
      maskedPreview: result.masked_preview ?? null,
      status: result.status ?? 'configured',
      version: result.version ?? 1,
      updatedAt: result.updated_at ?? null
    }
  })
})
