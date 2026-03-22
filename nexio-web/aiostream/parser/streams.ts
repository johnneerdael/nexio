import * as constants from '~/formatter/constants'
import { FULL_LANGUAGE_MAPPING } from '~/formatter/languages'
import { FileParser } from './file'
import { arrayMerge, mergeParsedFiles } from './merge'
import { parseAgeString, parseDuration } from './utils'
import type { ParsedFile, ParsedStream, PreviewAddon, PreviewSourceStream } from './types'

function parseBytesValue(value: string | number | undefined | null): number | undefined {
  if (typeof value === 'number') {
    return Number.isFinite(value) && value > 0 ? Math.round(value) : undefined
  }
  if (typeof value !== 'string') return undefined
  const match = value.trim().match(/(\d+(?:\.\d+)?)\s*(KB|MB|GB|TB)/i)
  if (!match) return undefined
  const num = parseFloat(match[1] || '0')
  const unit = (match[2] || '').toUpperCase()
  const multiplier =
    unit === 'TB' ? 1024 ** 4 :
    unit === 'GB' ? 1024 ** 3 :
    unit === 'MB' ? 1024 ** 2 :
    1024
  return Math.round(num * multiplier)
}

export class StreamParser {
  private count = 0

  constructor(protected readonly addon: PreviewAddon) {}

  protected get sizeRegex(): RegExp | undefined {
    return /(\d+(\.\d+)?)\s?(KB|MB|GB|TB)/i
  }

  protected get seedersRegex(): RegExp | undefined {
    return /[👥👤]\s*(\d+)/u
  }

  protected get indexerEmojis(): string[] {
    return ['🌐', '⚙️', '🔗', '🔎', '🔍', '☁️']
  }

  protected get indexerRegex(): RegExp | undefined {
    return new RegExp(
      `(?:${this.indexerEmojis.join('|')})\\s*([^\\p{Emoji_Presentation}\\n]*?)(?=\\p{Emoji_Presentation}|$|\\n)`,
      'u',
    )
  }

  parse(stream: PreviewSourceStream): ParsedStream | { skip: true } {
    const description = stream.description || stream.title || stream.name || ''

    const parsedStream: ParsedStream = {
      id: `${this.addon.instanceId || this.addon.name}-${this.count++}`,
      title: description,
      size: 0,
      resolution: '',
      quality: '',
      addonName: this.addon.name,
      addon: this.addon,
      proxied: false,
      filename: this.getFilename(stream),
      folderName: this.getFolder(stream),
      folderSize: this.getFolderSize(stream),
      indexer: this.getIndexer(description),
      service: this.getService(stream.name || description),
      duration: this.getDuration(description),
      library: this.addon.library ?? false,
      age: this.getAge(description),
      message: undefined,
      torrent: {
        infoHash: stream.infoHash,
        seeders: this.getSeeders(description),
        fileIdx: stream.fileIdx,
        private: false,
        freeleech: undefined,
        sources: stream.sources,
      },
      type: 'http',
    }

    const normaliseText = (text: string) =>
      text
        .replace(/\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|mpg|mpeg|3gp|3g2|m2ts|ts|vob|ogv|ogm|divx|xvid|rm|rmvb|asf|mxf|mka|mks|mk3d|webm|f4v|f4p|f4a|f4b)$/i, '')
        .replace(/[^\p{L}\p{N}+]/gu, '')
        .toLowerCase()
        .trim()

    if (
      parsedStream.folderName &&
      parsedStream.filename &&
      normaliseText(parsedStream.folderName) === normaliseText(parsedStream.filename)
    ) {
      parsedStream.folderName = undefined
    }

    parsedStream.size = this.getSize(stream, description, parsedStream.filename, parsedStream.folderName) || 0
    if (
      parsedStream.size &&
      parsedStream.folderSize &&
      Math.abs(parsedStream.size - parsedStream.folderSize) / parsedStream.size < 0.05
    ) {
      parsedStream.folderSize = undefined
    }

    parsedStream.type = this.getStreamType(stream, parsedStream.service)
    parsedStream.bitrate = this.getBitrate(parsedStream.size, parsedStream.duration)
    parsedStream.parsedFile = this.getParsedFile(parsedStream)
    parsedStream.title = parsedStream.parsedFile?.title || parsedStream.filename || description
    parsedStream.resolution = parsedStream.parsedFile?.resolution || ''
    parsedStream.quality = parsedStream.parsedFile?.quality || ''
    parsedStream.releaseGroup = parsedStream.parsedFile?.releaseGroup
    parsedStream.languages = parsedStream.parsedFile?.languages
    parsedStream.visualTags = parsedStream.parsedFile?.visualTags
    parsedStream.audioTags = parsedStream.parsedFile?.audioTags
    parsedStream.audioChannels = parsedStream.parsedFile?.audioChannels
    parsedStream.year = parsedStream.parsedFile?.year ? Number.parseInt(parsedStream.parsedFile.year as any, 10) : undefined
    parsedStream.seasonEpisode = [
      parsedStream.parsedFile?.seasons?.[0] != null
        ? `S${String(parsedStream.parsedFile.seasons[0]).padStart(2, '0')}`
        : undefined,
      parsedStream.parsedFile?.episodes?.[0] != null
        ? `E${String(parsedStream.parsedFile.episodes[0]).padStart(2, '0')}`
        : undefined,
    ].filter(Boolean)

    return parsedStream
  }

  protected getFilename(stream: PreviewSourceStream): string | undefined {
    let filename = stream.behaviorHints?.filename
    const description = stream.description || stream.title || stream.name
    if (filename) return filename
    if (!description) return undefined
    const potentialFilenames = description.split('\n').filter((line) => line.trim() !== '').splice(0, 5)
    for (const line of potentialFilenames) {
      const parsed = FileParser.parse(line)
      if (parsed.year || parsed.seasons?.length || parsed.episodes?.length) {
        filename = line
        break
      }
    }
    if (!filename) filename = description.split('\n')[0]
    return filename?.trim()?.replace(/^\p{Emoji_Presentation}+/gu, '')?.replace(/^[^\s:]+:\s*/, '')
  }

  protected getFolder(stream: PreviewSourceStream): string | undefined {
    return stream.behaviorHints?.folderName
  }

  protected getSize(
    stream: PreviewSourceStream,
    description: string,
    filename?: string,
    folderName?: string,
  ): number | undefined {
    let content = description
    if (filename) content = content.replace(filename, '')
    if (folderName) content = content.replace(folderName, '')
    return (
      parseBytesValue(stream.behaviorHints?.videoSize) ||
      parseBytesValue(stream.size) ||
      parseBytesValue(stream.sizeBytes) ||
      parseBytesValue(stream.sizebytes) ||
      parseBytesValue(content.match(this.sizeRegex!)?.[0]) ||
      parseBytesValue(stream.name?.match(this.sizeRegex!)?.[0])
    )
  }

  protected getFolderSize(stream: PreviewSourceStream): number | undefined {
    return parseBytesValue(stream.behaviorHints?.folderSize)
  }

  protected getSeeders(description: string): number | undefined {
    const regex = this.seedersRegex
    if (!regex) return undefined
    const match = description.match(regex)
    return match?.[1] ? parseInt(match[1], 10) : undefined
  }

  protected getAge(description: string): number | undefined {
    const match = description.match(/(?:🕒|⏳|📅)\s*([0-9]+[dhmy])/i)
    return match?.[1] ? parseAgeString(match[1]) : undefined
  }

  protected getIndexer(description: string): string | undefined {
    const regex = this.indexerRegex
    if (!regex) return undefined
    return description.match(regex)?.[1]?.trim()
  }

  protected getService(value: string): ParsedStream['service'] | undefined {
    const cleanString = value.replace(/web-?dl/i, '')
    const cachedSymbols = ['+', '⚡', '🚀', 'cached', '🌩️']
    const uncachedSymbols = ['⏳', 'download', 'UNCACHED', '☁️']
    let streamService: ParsedStream['service'] | undefined

    Object.values(constants.SERVICE_DETAILS as Record<string, any>).forEach((service) => {
      const regex = new RegExp(
        `(^|(?<![^ |[(_\\/\\-.]))(${service.knownNames.join('|')})(?=[ ⬇️⏳⚡☁️🌩️+/|\\)\\]_.-]|$|\\n)`,
        'im',
      )
      if (regex.test(cleanString)) {
        let cached = false
        if (uncachedSymbols.some((symbol) => value.includes(symbol))) cached = false
        else if (cachedSymbols.some((symbol) => value.includes(symbol))) cached = true
        streamService = { id: service.id, cached }
      }
    })

    return streamService
  }

  protected getDuration(description: string): number | undefined {
    return parseDuration(description)
  }

  protected getBitrate(size: number | undefined, duration: number | undefined): number | undefined {
    if (!size || !duration) return undefined
    const seconds = duration / 1000
    return seconds > 0 ? Math.round((size * 8) / seconds) : undefined
  }

  protected getStreamType(stream: PreviewSourceStream, service: ParsedStream['service']): ParsedStream['type'] {
    if (stream.url?.endsWith('.m3u8')) return 'live'
    if (service?.id === (constants as any).EASYNEWS_SERVICE) return 'usenet'
    if (service) return 'debrid'
    if (stream.url) return 'http'
    if (stream.infoHash) return 'p2p'
    if (stream.externalUrl) return 'external'
    if (stream.ytId) return 'youtube'
    if (stream.nzbUrl) return 'stremio-usenet'
    if (stream['7zipUrls']?.length || stream.rarUrls?.length || stream.tarUrls?.length || stream.tgzUrls?.length) {
      return 'archive'
    }
    return 'http'
  }

  protected getParsedFile(parsedStream: ParsedStream): ParsedFile | undefined {
    const folderParsed = parsedStream.folderName ? FileParser.parse(parsedStream.folderName) : undefined
    const fileParsed = parsedStream.filename ? FileParser.parse(parsedStream.filename) : undefined
    const merged = mergeParsedFiles(fileParsed, folderParsed, {
      languages: arrayMerge(arrayMerge(folderParsed?.languages, fileParsed?.languages), this.getLanguages(parsedStream)),
    })
    if (!merged) return undefined
    if (
      !merged.seasonPack &&
      merged.episodes &&
      merged.episodes.length > 0 &&
      parsedStream.folderSize &&
      parsedStream.size &&
      parsedStream.folderSize > parsedStream.size * 2
    ) {
      merged.seasonPack = true
    }
    if (!merged.seasonPack && merged.episodes && merged.episodes.length > 5) {
      merged.seasonPack = true
    }
    return merged
  }

  protected getLanguages(parsedStream: ParsedStream): string[] {
    const matches = [
      ...((parsedStream.filename || '').match(/[\u{1F1E6}-\u{1F1FF}]{2}/gu) || []),
      ...((parsedStream.folderName || '').match(/[\u{1F1E6}-\u{1F1FF}]{2}/gu) || []),
    ]
    return matches
      .map((flag) => {
        const possibleLanguages = FULL_LANGUAGE_MAPPING.filter((language) => (language as any).flag === flag)
        const language = possibleLanguages.find((entry) => (entry as any).flag_priority) || possibleLanguages[0]
        const name = (((language as any)?.internal_english_name || language?.english_name) as string | undefined)
          ?.split('(')?.[0]
          ?.trim()
        return name && (constants.LANGUAGES as readonly string[]).includes(name) ? name : undefined
      })
      .filter((value): value is string => Boolean(value))
  }
}
