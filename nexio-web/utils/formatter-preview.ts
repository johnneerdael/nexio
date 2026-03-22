import type { ParsedStream } from '~/formatter/stubs'
import * as constants from '~/formatter/constants'
import { StreamParser } from '~/aiostream/parser/streams'

export type FormatterPreviewPreset = {
  id: string
  label: string
  thumbnail?: string
  state: Partial<FormatterPreviewState>
}

export type FormatterPreviewState = {
  filename: string
  folderName: string
  indexer: string
  seeders?: number
  age: string
  addonName: string
  providerId: string
  type: string
  regexMatched: string
  message: string
  durationSeconds?: number
  fileSize?: number
  folderSize?: number
  cached: boolean
  library: boolean
  privateTorrent: boolean
  proxied: boolean
  regexScore?: number
  maxRegexScore?: number
  streamExpressionScore?: number
  maxSeScore?: number
  seMatched: string
  rankedRegexMatched: string
  rankedStreamExpressionsMatched: string
  seadex: boolean
  seadexBest: boolean
}

export type FormatterPreviewServiceOption = {
  value: string
  label: string
}

const AIO_FORMATTER_PREVIEW_SERVICES = [
  { value: 'realdebrid', label: 'Real-Debrid' },
  { value: 'debridlink', label: 'Debrid-Link' },
  { value: 'premiumize', label: 'Premiumize' },
  { value: 'alldebrid', label: 'AllDebrid' },
  { value: 'torbox', label: 'TorBox' },
  { value: 'easydebrid', label: 'EasyDebrid' },
  { value: 'debrider', label: 'Debrider' },
  { value: 'putio', label: 'put.io' },
  { value: 'pikpak', label: 'PikPak' },
  { value: 'offcloud', label: 'Offcloud' },
  { value: 'seedr', label: 'Seedr' },
  { value: 'easynews', label: 'Easynews' },
  { value: 'nzbdav', label: 'NzbDAV' },
  { value: 'altmount', label: 'AltMount' },
  { value: 'stremio_nntp', label: 'Stremio NNTP' },
  { value: 'stremthru_newz', label: 'StremThru Newz' },
] as const satisfies readonly FormatterPreviewServiceOption[]

export const FORMATTER_PREVIEW_SERVICE_OPTIONS: FormatterPreviewServiceOption[] =
  AIO_FORMATTER_PREVIEW_SERVICES.map((service) => ({ ...service }))

const FORMATTER_PREVIEW_SERVICE_DETAIL_MAP = Object.fromEntries(
  AIO_FORMATTER_PREVIEW_SERVICES.map((service) => [service.value, service]),
) as Record<string, FormatterPreviewServiceOption>

const DEFAULT_FILENAME =
  'Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv'
const DEFAULT_FOLDER =
  'Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP'

export const DEFAULT_FORMATTER_PREVIEW_STATE: FormatterPreviewState = {
  filename: DEFAULT_FILENAME,
  folderName: DEFAULT_FOLDER,
  indexer: 'RARBG',
  seeders: 125,
  age: '10d',
  addonName: 'Torrentio',
  providerId: 'realdebrid',
  type: 'debrid',
  regexMatched: '',
  message: 'This is a message',
  durationSeconds: 9_120,
  fileSize: 62_500_000_000,
  folderSize: 125_000_000_000,
  cached: true,
  library: false,
  privateTorrent: false,
  proxied: false,
  regexScore: 25,
  maxRegexScore: 50,
  streamExpressionScore: 150,
  maxSeScore: 100,
  seMatched: '',
  rankedRegexMatched: '',
  rankedStreamExpressionsMatched: '',
  seadex: false,
  seadexBest: false,
}

export const FORMATTER_PREVIEW_PRESETS: FormatterPreviewPreset[] = [
  {
    id: 'movie_remux',
    label: 'Movie Remux',
    thumbnail: 'https://media.themoviedb.org/t/p/w1000_and_h563_face/cOoVcVQ3i1m5b2xtqKBtoTSbxC1.jpg',
    state: {},
  },
  {
    id: 'show_webdl',
    label: 'Show WEB-DL',
    thumbnail: 'https://media.themoviedb.org/t/p/w1066_and_h600_face/hI9z1UuNhBthvkm3iJ8m3zv43Pf.jpg',
    state: {
      filename: 'Shrinking.S03E01.1080p.ATVP.WEB-DL.DDP5.1.Atmos.H.264-FLUX.mkv',
      folderName: 'Shrinking.S03E01.1080p.ATVP.WEB-DL.DDP5.1.Atmos-H.264-FLUX',
      indexer: 'Torrentio',
      seeders: 450,
      age: '2d',
      addonName: 'Comet',
      providerId: 'premiumize',
      type: 'debrid',
      durationSeconds: 1_800,
      fileSize: 1_850_000_000,
      folderSize: 1_850_000_000,
      cached: true,
      message: '',
    },
  },
  {
    id: 'anime_pack',
    label: 'Anime Pack',
    thumbnail: 'https://media.themoviedb.org/t/p/w1000_and_h563_face/c0y8ScAJnSs5xMZ9b1lm2k9gSwz.jpg',
    state: {
      filename: 'Frieren.Beyond.Journeys.End.S01.1080p.BluRay.FLAC.2.0-Judas.mkv',
      folderName: 'Frieren.Beyond.Journeys.End.S01.1080p.BluRay.FLAC.2.0-Judas',
      indexer: 'Nyaa',
      addonName: 'Torrentio',
      providerId: 'realdebrid',
      type: 'debrid',
      seeders: 212,
      age: '21d',
      durationSeconds: 1_500,
      fileSize: 3_800_000_000,
      folderSize: 49_000_000_000,
      cached: true,
      seadex: true,
      seadexBest: true,
    },
  },
]

function parseCommaSeparated(value: string): string[] {
  return value
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean)
}

function formatDurationLine(durationSeconds?: number): string | undefined {
  if (!durationSeconds || durationSeconds <= 0) return undefined
  const hours = Math.floor(durationSeconds / 3600)
  const minutes = Math.floor((durationSeconds % 3600) / 60)
  const seconds = durationSeconds % 60
  if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`
  if (minutes > 0) return `${minutes}m ${seconds}s`
  return `${seconds}s`
}

function buildPreviewName(state: FormatterPreviewState): string {
  const service = state.providerId !== 'none'
    ? FORMATTER_PREVIEW_SERVICE_DETAIL_MAP[state.providerId]
    : undefined
  const serviceName = service?.label || 'Direct'
  if (state.type === 'p2p') {
    return `${state.addonName} P2P`
  }
  if (state.type === 'debrid' || state.type === 'usenet') {
    return `${serviceName}${state.cached ? '+' : ' download'} ${state.addonName}`
  }
  return `${serviceName} ${state.addonName}`
}

function buildPreviewDescription(state: FormatterPreviewState): string {
  const lines = [
    state.folderName,
    state.filename,
    state.fileSize ? `💾 ${(state.fileSize / 1024 ** 3).toFixed(2)} GB` : undefined,
    state.seeders != null ? `👤 ${state.seeders}` : undefined,
    state.age ? `📅 ${state.age}` : undefined,
    state.indexer ? `🔍 ${state.indexer}` : undefined,
    formatDurationLine(state.durationSeconds),
    state.message || undefined,
  ]

  return lines.filter(Boolean).join('\n')
}

export function buildFormatterPreviewStream(state: FormatterPreviewState): ParsedStream {
  const addon = {
    instanceId: 'preview-addon',
    preset: {
      type: 'custom',
      id: 'custom',
      options: {},
    },
    enabled: true,
    name: state.addonName,
    manifestUrl: 'https://nexio.app/manifest.json',
    timeout: 10_000,
    library: state.library,
  }

  const parser = new StreamParser(addon)
  const sourceStream = {
    name: buildPreviewName(state),
    title: state.filename,
    description: buildPreviewDescription(state),
    url: state.type === 'http' || state.type === 'debrid' || state.type === 'live'
      ? 'https://nexio.app/video.mkv'
      : undefined,
    infoHash: state.type === 'p2p' ? '1234567890abcdef1234567890abcdef12345678' : undefined,
    externalUrl: state.type === 'external' ? 'https://nexio.app/external' : undefined,
    ytId: state.type === 'youtube' ? 'dQw4w9WgXcQ' : undefined,
    nzbUrl: state.type === 'stremio-usenet' || state.type === 'usenet'
      ? 'https://nexio.app/file.nzb'
      : undefined,
    behaviorHints: {
      filename: state.filename,
      folderName: state.folderName,
      folderSize: state.folderSize,
      videoSize: state.fileSize,
    },
  }

  const parsed = parser.parse(sourceStream)
  if ('skip' in parsed) {
    throw new Error('Preview stream was unexpectedly skipped by the parser')
  }

  return {
    ...parsed,
    size: state.fileSize || parsed.size || 0,
    folderSize: state.folderSize ?? parsed.folderSize,
    indexer: state.indexer || parsed.indexer,
    age: parsed.age,
    seeders: state.seeders ?? parsed.seeders,
    duration: state.durationSeconds ? state.durationSeconds * 1000 : parsed.duration,
    library: state.library,
    regexMatched: state.regexMatched ? { name: state.regexMatched, index: 0 } : undefined,
    rankedRegexesMatched: parseCommaSeparated(state.rankedRegexMatched),
    regexScore: state.regexScore,
    torrent: {
      ...(parsed.torrent || {}),
      infoHash: state.type === 'p2p'
        ? '1234567890abcdef1234567890abcdef12345678'
        : parsed.torrent?.infoHash,
      seeders: state.seeders ?? parsed.torrent?.seeders,
      private: state.privateTorrent,
    },
    type: state.type,
    bitrate:
      state.fileSize && state.durationSeconds
        ? Math.floor((state.fileSize * 8) / state.durationSeconds)
        : parsed.bitrate,
    message: state.message || parsed.message,
    proxied: state.proxied,
    seadex: {
      isSeadex: state.seadex,
      isBest: state.seadex && state.seadexBest,
    },
    streamExpressionScore: state.streamExpressionScore,
    streamExpressionMatched: state.seMatched ? { name: state.seMatched, index: 0 } : undefined,
    rankedStreamExpressionsMatched: parseCommaSeparated(state.rankedStreamExpressionsMatched),
    service:
      state.providerId === 'none'
        ? undefined
        : {
          id: state.providerId,
          cached: state.cached,
        },
  }
}
