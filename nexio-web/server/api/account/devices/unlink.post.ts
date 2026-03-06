import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'

type Body = {
  deviceUserId?: string
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<Body>(event)
  const token = bearerToken(event)

  if (!body.deviceUserId?.trim()) {
    throw createError({ statusCode: 400, statusMessage: 'Device user id is required.' })
  }

  await supabaseUser(event)

  await supabaseFetch('/rest/v1/rpc/unlink_device', {
    method: 'POST',
    body: JSON.stringify({
      p_device_user_id: body.deviceUserId.trim()
    })
  }, token)

  return okJson({ ok: true })
})
