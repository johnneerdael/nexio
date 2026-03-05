import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import type { SecretType } from '~/types/portal'

type SecretDeleteBody = {
  secretType?: SecretType
  secretRef?: string
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  const user = await supabaseUser(event)
  const body = await readJsonBody<SecretDeleteBody>(event)

  const secretType = body.secretType
  const secretRef = body.secretRef?.trim()
  if (!secretType || !secretRef) {
    throw createError({ statusCode: 400, statusMessage: 'secretType and secretRef are required.' })
  }

  await supabaseFetch(
    '/rest/v1/rpc/service_delete_account_secret',
    {
      method: 'POST',
      body: JSON.stringify({
        p_user_id: user.id,
        p_secret_type: secretType,
        p_secret_ref: secretRef,
        p_source: 'web'
      })
    },
    undefined,
    true
  )

  return okJson({ deleted: true })
})
