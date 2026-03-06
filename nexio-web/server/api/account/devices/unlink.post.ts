import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'

type Body = {
  deviceUserId?: string
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<Body>(event)
  const token = bearerToken(event)
  const deviceUserId = body.deviceUserId?.trim()

  if (!deviceUserId) {
    throw createError({ statusCode: 400, statusMessage: 'Device user id is required.' })
  }

  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(deviceUserId)) {
    throw createError({ statusCode: 400, statusMessage: 'Invalid linked device id.' })
  }

  await supabaseUser(event)

  await supabaseFetch('/rest/v1/rpc/unlink_device', {
    method: 'POST',
    body: JSON.stringify({
      p_device_user_id: deviceUserId
    })
  }, token)

  return okJson({ ok: true })
})
