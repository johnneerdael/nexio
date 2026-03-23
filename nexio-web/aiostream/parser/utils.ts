const umlautMap: Record<string, string> = {
  Ä: 'Ae',
  ä: 'ae',
  Ö: 'Oe',
  ö: 'oe',
  Ü: 'Ue',
  ü: 'ue',
  ß: 'ss',
}

export function normaliseTitle(title: string) {
  return title
    .replace(/[ÄäÖöÜüß]/g, (c) => umlautMap[c]!)
    .replace(/&/g, 'and')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^\p{L}\p{N}+]/gu, '')
    .toLowerCase()
}

export function cleanTitle(title: string) {
  let cleaned = title.replace(/[ÄäÖöÜüß]/g, (c) => umlautMap[c]!).normalize('NFD')
  for (const char of ['♪', '♫', '★', '☆', '♡', '♥', '-']) {
    cleaned = cleaned.replaceAll(char, ' ')
  }
  return cleaned
    .replace(/&/g, 'and')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^\p{L}\p{N}\s]/gu, '')
    .replace(/\s+/g, ' ')
    .toLowerCase()
    .trim()
}

export function parseDuration(durationString: string, output: 'ms' | 's' = 'ms'): number | undefined {
  const regex =
    /(?<![^\s\[(_\-,.])(?:(\d+)h[:\s]?(\d+)m[:\s]?(\d+)s|(\d+)h[:\s]?(\d+)m|(\d+)m[:\s]?(\d+)s|(\d+)h|(\d+)m|(\d+)s)(?=[\s\)\]_.\-,]|$)/gi
  const match = regex.exec(durationString)
  if (!match) return undefined

  const hours = parseInt(match[1] || match[4] || match[8] || '0', 10)
  const minutes = parseInt(match[2] || match[5] || match[6] || match[9] || '0', 10)
  const seconds = parseInt(match[3] || match[7] || match[10] || '0', 10)
  const totalMilliseconds = (hours * 3600 + minutes * 60 + seconds) * 1000
  return output === 's' ? Math.floor(totalMilliseconds / 1000) : totalMilliseconds
}

export function parseAgeString(ageString: string): number | undefined {
  const match = ageString.match(/^(\d+)([a-zA-Z])$/)
  if (!match) return undefined
  const value = parseInt(match[1] || '0', 10)
  switch ((match[2] || '').toLowerCase()) {
    case 'd':
      return value * 24
    case 'h':
      return value
    case 'm':
      return value / 60
    case 'y':
      return value * 24 * 365
    default:
      return undefined
  }
}

export function parseBitrate(bitrateString: string): number | undefined {
  const match = bitrateString.match(/^(\d+(\.\d+)?)\s*(bps|kbps|mbps|gbps|tbps)$/i)
  if (!match) {
    const trimmed = bitrateString.trim()
    if (!/^\d+(\.\d+)?$/.test(trimmed)) return undefined
    return parseFloat(trimmed)
  }
  const num = parseFloat(match[1] || '0')
  const unit = (match[3] || '').toLowerCase()
  const multiplier =
    unit === 'tbps' ? 1_000_000_000_000 :
    unit === 'gbps' ? 1_000_000_000 :
    unit === 'mbps' ? 1_000_000 :
    unit === 'kbps' ? 1_000 :
    1
  return Math.round(num * multiplier)
}
