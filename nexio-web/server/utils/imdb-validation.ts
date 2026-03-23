export function normalizeImdbBaseUrl(rawBaseUrl: string): string {
  return rawBaseUrl.trim().replace(/\/$/, '')
}

