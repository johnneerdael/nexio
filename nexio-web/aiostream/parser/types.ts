import type { ParsedFile as FormatterParsedFile, ParsedStream as FormatterParsedStream } from '~/formatter/stubs'

export type ParsedFile = FormatterParsedFile

export type ParsedStream = FormatterParsedStream & {
  addon?: PreviewAddon
  service?: {
    id: string
    cached: boolean
  }
  torrent?: {
    infoHash?: string
    seeders?: number
    fileIdx?: number
    private?: boolean
    freeleech?: boolean
    sources?: string[]
  }
  parsedFile?: ParsedFile
  proxied?: boolean
  bitrate?: number
  folderName?: string
  folderSize?: number
  indexer?: string
  message?: string
  regexMatched?: { name: string; index: number }
  rankedRegexesMatched?: string[]
  streamExpressionMatched?: { name: string; index: number }
  rankedStreamExpressionsMatched?: string[]
  regexScore?: number
  streamExpressionScore?: number
  seadex?: {
    isSeadex: boolean
    isBest: boolean
  }
}

export type PreviewBehaviorHints = {
  filename?: string
  folderName?: string
  folderSize?: string | number
  videoSize?: number
  videoHash?: string
  notWebReady?: boolean
  proxyHeaders?: {
    request?: Record<string, string>
    response?: Record<string, string>
  }
}

export type PreviewSourceStream = {
  name?: string
  title?: string
  description?: string
  url?: string
  infoHash?: string
  fileIdx?: number
  externalUrl?: string
  ytId?: string
  nzbUrl?: string
  tarUrls?: string[]
  tgzUrls?: string[]
  '7zipUrls'?: string[]
  rarUrls?: string[]
  servers?: string[]
  sources?: string[]
  behaviorHints?: PreviewBehaviorHints
  size?: string | number
  sizeBytes?: number
  sizebytes?: number
}

export type PreviewAddon = {
  instanceId?: string
  preset: {
    id: string
    type: string
    options: Record<string, unknown>
  }
  manifestUrl: string
  enabled: boolean
  name: string
  timeout: number
  library?: boolean
}
