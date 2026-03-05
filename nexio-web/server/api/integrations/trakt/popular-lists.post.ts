import { createError } from 'h3'
import { okJson, readJsonBody } from '~/server/utils/supabase'

type PopularListsBody = {
  accessToken?: string
}

type TraktPopularListApiResponse = Array<{
  user?: {
    username?: string
    ids?: {
      slug?: string
    }
  }
  list?: {
    name?: string
    item_count?: number
    ids?: {
      slug?: string
      trakt?: number
    }
    user?: {
      username?: string
      ids?: {
        slug?: string
      }
    }
  }
}>

function slugify(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'custom'
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<PopularListsBody>(event)
  const accessToken = body.accessToken?.trim()
  if (!accessToken) {
    throw createError({ statusCode: 400, statusMessage: 'Trakt access token is required.' })
  }

  const config = useRuntimeConfig()
  const clientId = String(config.traktClientId || '').trim()
  if (!clientId) {
    throw createError({ statusCode: 503, statusMessage: 'TRAKT_CLIENT_ID is not configured.' })
  }

  const response = await fetch('https://api.trakt.tv/lists/popular?page=1&limit=30', {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
      'trakt-api-key': clientId,
      'trakt-api-version': '2'
    }
  })

  if (!response.ok) {
    throw createError({ statusCode: response.status, statusMessage: await response.text() })
  }

  const payload = await response.json() as TraktPopularListApiResponse
  const lists = payload.flatMap((entry) => {
    const list = entry.list
    if (!list) {
      return []
    }

    const userId = entry.user?.ids?.slug || entry.user?.username || list.user?.ids?.slug || list.user?.username
    const listId = list.ids?.slug || String(list.ids?.trakt || '')
    if (!userId || !listId) {
      return []
    }

    const key = `${userId}/${listId}`
    return [{
      key,
      userId,
      listId,
      catalogIdBase: `trakt_list_${slugify(key)}`,
      title: list.name || key,
      itemCount: list.item_count || 0
    }]
  })

  return okJson({ lists })
})
