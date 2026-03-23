const tokenList = [
  { id: 'netflix', src: '/formatter-icons/netflix.png', fallbackLabel: 'Netflix', scaleClass: 'inline' },
  { id: 'disneyplus', src: '/formatter-icons/disneyplus.png', fallbackLabel: 'Disney+', scaleClass: 'inline' },
  { id: 'hbo', src: '/formatter-icons/hbo.png', fallbackLabel: 'HBO Max', scaleClass: 'inline' },
  { id: 'max', src: '/formatter-icons/max.png', fallbackLabel: 'Max', scaleClass: 'inline' },
  { id: 'prime', src: '/formatter-icons/prime.png', fallbackLabel: 'Amazon', scaleClass: 'inline' },
  { id: 'appletv', src: '/formatter-icons/appletv.png', fallbackLabel: 'Apple TV+', scaleClass: 'inline' },
  { id: 'paramount', src: '/formatter-icons/paramount.png', fallbackLabel: 'Paramount+', scaleClass: 'inline' },
  { id: 'peacock', src: '/formatter-icons/peacock.png', fallbackLabel: 'Peacock', scaleClass: 'inline' },
  { id: 'crunchyroll', src: '/formatter-icons/crunchyroll.png', fallbackLabel: 'Crunchyroll', scaleClass: 'inline' },
  { id: 'atmos', src: '/formatter-icons/atmos.png', fallbackLabel: 'Dolby Atmos', scaleClass: 'inline' },
  { id: 'truehd', src: '/formatter-icons/truehd.png', fallbackLabel: 'Dolby TrueHD', scaleClass: 'inline' },
  { id: 'ddp', src: '/formatter-icons/ddp.png', fallbackLabel: 'Dolby Digital+', scaleClass: 'inline' },
  { id: 'dd', src: '/formatter-icons/dd.png', fallbackLabel: 'Dolby Digital', scaleClass: 'inline' },
  { id: 'dts', src: '/formatter-icons/dts.png', fallbackLabel: 'DTS', scaleClass: 'inline' },
  { id: 'dtshd', src: '/formatter-icons/dtshd.png', fallbackLabel: 'DTS-MA', scaleClass: 'inline' },
  { id: 'dtsx', src: '/formatter-icons/dtsx.png', fallbackLabel: 'DTS:X', scaleClass: 'inline' },
  { id: 'stereo', src: '/formatter-icons/stereo.png', fallbackLabel: 'Stereo', scaleClass: 'inline' },
  { id: 'dovi', src: '/formatter-icons/dovi.png', fallbackLabel: 'Dolby Vision', scaleClass: 'inline' },
  { id: 'hdr10', src: '/formatter-icons/hdr10.png', fallbackLabel: 'HDR10', scaleClass: 'inline' },
  { id: '4k', src: '/formatter-icons/4k.png', fallbackLabel: '4K', scaleClass: 'title-prominent' },
  { id: '2k', src: '/formatter-icons/2k.png', fallbackLabel: '2K', scaleClass: 'title-prominent' },
  { id: 'fullhd', src: '/formatter-icons/fullhd.png', fallbackLabel: 'Full HD', scaleClass: 'title-prominent' },
  { id: 'hd', src: '/formatter-icons/hd.png', fallbackLabel: 'HD', scaleClass: 'title-prominent' },
  { id: 'sd', src: '/formatter-icons/sd.png', fallbackLabel: 'SD', scaleClass: 'title-prominent' },
]

const tokenMap = new Map(tokenList.map((token) => [token.id, token]))

export function resolveFormatterIconToken(id) {
  return tokenMap.get(String(id).trim().toLowerCase())
}

export function listFormatterIconTokens() {
  return tokenList.slice()
}
