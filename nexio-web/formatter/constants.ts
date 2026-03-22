// import { Option, Resource } from '../db/schemas.js';

export enum ErrorCode {
  // User API
  USER_ALREADY_EXISTS = 'USER_ALREADY_EXISTS',
  USER_INVALID_DETAILS = 'USER_INVALID_DETAILS',
  USER_INVALID_CONFIG = 'USER_INVALID_CONFIG',
  USER_NEW_PASSWORD_TOO_SHORT = 'USER_NEW_PASSWORD_TOO_SHORT',
  USER_NEW_PASSWORD_TOO_SIMPLE = 'USER_NEW_PASSWORD_TOO_SIMPLE',
  ADDON_PASSWORD_INVALID = 'ADDON_PASSWORD_INVALID',
  // Database
  DATABASE_ERROR = 'DATABASE_ERROR',
  // Encryption
  ENCRYPTION_ERROR = 'ENCRYPTION_ERROR',
  // Format API
  FORMAT_INVALID_FORMATTER = 'FORMAT_INVALID_FORMATTER',
  FORMAT_INVALID_STREAM = 'FORMAT_INVALID_STREAM',
  FORMAT_ERROR = 'FORMAT_ERROR',
  // Other
  MISSING_REQUIRED_FIELDS = 'MISSING_REQUIRED_FIELDS',
  INTERNAL_SERVER_ERROR = 'INTERNAL_SERVER_ERROR',
  METHOD_NOT_ALLOWED = 'METHOD_NOT_ALLOWED',
  RATE_LIMIT_EXCEEDED = 'RATE_LIMIT_EXCEEDED',
  BAD_REQUEST = 'BAD_REQUEST',
  UNAUTHORIZED = 'UNAUTHORIZED',
  FORBIDDEN = 'FORBIDDEN',
}

interface ErrorDetails {
  statusCode: number;
  message: string;
}

export const ErrorMap: Record<ErrorCode, ErrorDetails> = {
  [ErrorCode.MISSING_REQUIRED_FIELDS]: {
    statusCode: 400,
    message: 'Required fields are missing',
  },
  [ErrorCode.USER_ALREADY_EXISTS]: {
    statusCode: 409,
    message: 'User already exists',
  },
  [ErrorCode.USER_INVALID_DETAILS]: {
    statusCode: 400,
    message: 'Invalid UUID or password',
  },
  [ErrorCode.USER_INVALID_CONFIG]: {
    statusCode: 400,
    message: 'The config for this user is invalid',
  },
  [ErrorCode.USER_NEW_PASSWORD_TOO_SHORT]: {
    statusCode: 400,
    message: 'New password is too short',
  },
  [ErrorCode.USER_NEW_PASSWORD_TOO_SIMPLE]: {
    statusCode: 400,
    message: 'New password is too simple',
  },
  [ErrorCode.ADDON_PASSWORD_INVALID]: {
    statusCode: 401,
    message: 'Invalid addon password',
  },
  [ErrorCode.DATABASE_ERROR]: {
    statusCode: 500,
    message: 'A database error occurred',
  },
  [ErrorCode.ENCRYPTION_ERROR]: {
    statusCode: 500,
    message: 'An error occurred in the encryption service',
  },
  [ErrorCode.INTERNAL_SERVER_ERROR]: {
    statusCode: 500,
    message: 'An unexpected error occurred',
  },
  [ErrorCode.METHOD_NOT_ALLOWED]: {
    statusCode: 405,
    message: 'Method not allowed',
  },
  [ErrorCode.RATE_LIMIT_EXCEEDED]: {
    statusCode: 429,
    message: 'Too many requests from this IP, please try again later.',
  },
  [ErrorCode.FORMAT_INVALID_FORMATTER]: {
    statusCode: 400,
    message: 'Invalid formatter',
  },
  [ErrorCode.FORMAT_INVALID_STREAM]: {
    statusCode: 400,
    message: 'Invalid stream',
  },
  [ErrorCode.FORMAT_ERROR]: {
    statusCode: 500,
    message: 'An error occurred while formatting the stream',
  },
  [ErrorCode.BAD_REQUEST]: {
    statusCode: 400,
    message: 'Bad request',
  },
  [ErrorCode.UNAUTHORIZED]: {
    statusCode: 401,
    message: 'Unauthorized',
  },
  [ErrorCode.FORBIDDEN]: {
    statusCode: 403,
    message: 'Forbidden',
  },
};

export class APIError extends Error {
  constructor(
    public code: ErrorCode,
    public statusCode: number = ErrorMap[code].statusCode,
    message?: string
  ) {
    super(message || ErrorMap[code].message);
    this.name = 'APIError';
  }
}

const HEADERS_FOR_IP_FORWARDING = [
  'X-Client-IP',
  'X-Forwarded-For',
  'X-Real-IP',
  'True-Client-IP',
  'X-Forwarded',
  'Forwarded-For',
];

export const INTERNAL_SECRET_HEADER = 'X-AIOStreams-Internal-Secret';

export const PUBLIC_NZB_PROXY_USERNAME = 'public_nzb_proxy_user';

const API_VERSION = 1;

export const REDIS_PREFIX = 'aiostreams:';

export const DEFAULT_PRECACHE_SELECTOR =
  'count(cached(streams)) == 0 ? uncached(streams) : []';

export const DEFAULT_PRELOAD_SELECTOR = 'slice(streams, 0, 2)';

export const GDRIVE_FORMATTER = 'gdrive';
export const LIGHT_GDRIVE_FORMATTER = 'lightgdrive';
export const MINIMALISTIC_GDRIVE_FORMATTER = 'minimalisticgdrive';
export const TORRENTIO_FORMATTER = 'torrentio';
export const TORBOX_FORMATTER = 'torbox';
export const PRISM_FORMATTER = 'prism';
export const TAMTARO_FORMATTER = 'tamtaro';
export const CUSTOM_FORMATTER = 'custom';

export const FORMATTERS = [
  GDRIVE_FORMATTER,
  PRISM_FORMATTER,
  TAMTARO_FORMATTER,
  LIGHT_GDRIVE_FORMATTER,
  MINIMALISTIC_GDRIVE_FORMATTER,
  TORRENTIO_FORMATTER,
  TORBOX_FORMATTER,
  CUSTOM_FORMATTER,
] as const;

export type FormatterDetail = {
  id: FormatterType;
  name: string;
  description: string;
};

export const FORMATTER_DETAILS: Record<FormatterType, FormatterDetail> = {
  [GDRIVE_FORMATTER]: {
    id: GDRIVE_FORMATTER,
    name: 'Google Drive',
    description: 'Uses the formatting from the Stremio GDrive addon',
  },
  [PRISM_FORMATTER]: {
    id: PRISM_FORMATTER,
    name: 'Prism',
    description: 'An aesthetic formatter with every detail within 5 lines.',
  },
  [TAMTARO_FORMATTER]: {
    id: TAMTARO_FORMATTER,
    name: 'Tamtaro',
    description:
      "From Tamtaro's setup. Minimal and clean, yet comprehensive for stream selection. Smartly detects status for cached (⚡/⏳), proxied (⛊/⛉), library (☁︎/▤), and season packs (⧉/◧). The last line in sᴍᴀʟʟ ᴄᴀᴘs highlights special attributes like Usenet's health (☑ ɴᴢʙ), SeaDex (ᴀʟᴛ/ʙᴇsᴛ ʀᴇʟᴇᴀsᴇ), SEL scores (ʀᴇᴍᴜx ᴛ𝟷 ₁,₉₅₀), networks (ɴᴇᴛғʟɪx) and special editions (ᴅɪʀᴇᴄᴛᴏʀ's ᴄᴜᴛ).",
  },
  [LIGHT_GDRIVE_FORMATTER]: {
    id: LIGHT_GDRIVE_FORMATTER,
    name: 'Light Google Drive',
    description:
      'A lighter version of the GDrive formatter, focused on asthetics',
  },
  [MINIMALISTIC_GDRIVE_FORMATTER]: {
    id: MINIMALISTIC_GDRIVE_FORMATTER,
    name: 'Minimalistic',
    description: 'A minimalistic formatter which shows only the bare minimum',
  },
  [TORRENTIO_FORMATTER]: {
    id: TORRENTIO_FORMATTER,
    name: 'Torrentio',
    description: 'Uses the formatting from the Torrentio addon',
  },
  [TORBOX_FORMATTER]: {
    id: TORBOX_FORMATTER,
    name: 'Torbox',
    description: 'Uses the formatting from the TorBox Stremio addon',
  },
  [CUSTOM_FORMATTER]: {
    id: CUSTOM_FORMATTER,
    name: 'Custom',
    description: 'Define your own formatter',
  },
};

export type FormatterType = (typeof FORMATTERS)[number];

const REALDEBRID_SERVICE = 'realdebrid';
const DEBRIDLINK_SERVICE = 'debridlink';
const PREMIUMIZE_SERVICE = 'premiumize';
const ALLDEBRID_SERVICE = 'alldebrid';
const TORBOX_SERVICE = 'torbox';
const EASYDEBRID_SERVICE = 'easydebrid';
const DEBRIDER_SERVICE = 'debrider';
const PUTIO_SERVICE = 'putio';
const PIKPAK_SERVICE = 'pikpak';
const OFFCLOUD_SERVICE = 'offcloud';
const SEEDR_SERVICE = 'seedr';
const EASYNEWS_SERVICE = 'easynews';
const NZBDAV_SERVICE = 'nzbdav';
const ALTMOUNT_SERVICE = 'altmount';
const STREMIO_NNTP_SERVICE = 'stremio_nntp';
const STREMTHRU_NEWZ_SERVICE = 'stremthru_newz';

const SERVICES = [
  REALDEBRID_SERVICE,
  DEBRIDLINK_SERVICE,
  PREMIUMIZE_SERVICE,
  ALLDEBRID_SERVICE,
  TORBOX_SERVICE,
  EASYDEBRID_SERVICE,
  DEBRIDER_SERVICE,
  PUTIO_SERVICE,
  PIKPAK_SERVICE,
  OFFCLOUD_SERVICE,
  SEEDR_SERVICE,
  EASYNEWS_SERVICE,
  NZBDAV_SERVICE,
  ALTMOUNT_SERVICE,
  STREMIO_NNTP_SERVICE,
  STREMTHRU_NEWZ_SERVICE,
] as const;

export const BUILTIN_SUPPORTED_SERVICES = [
  REALDEBRID_SERVICE,
  DEBRIDLINK_SERVICE,
  PREMIUMIZE_SERVICE,
  ALLDEBRID_SERVICE,
  TORBOX_SERVICE,
  EASYDEBRID_SERVICE,
  DEBRIDER_SERVICE,
  PIKPAK_SERVICE,
  OFFCLOUD_SERVICE,
  NZBDAV_SERVICE,
  ALTMOUNT_SERVICE,
  STREMIO_NNTP_SERVICE,
  EASYNEWS_SERVICE,
  STREMTHRU_NEWZ_SERVICE,
] as const;

export type ServiceId = (typeof SERVICES)[number];
export type BuiltinServiceId = (typeof BUILTIN_SUPPORTED_SERVICES)[number];

export const MEDIAFLOW_SERVICE = 'mediaflow' as const;
export const STREMTHRU_SERVICE = 'stremthru' as const;
export const BUILTIN_SERVICE = 'builtin' as const;

export const PROXY_SERVICES = [
  BUILTIN_SERVICE,
  STREMTHRU_SERVICE,
  MEDIAFLOW_SERVICE,
] as const;
export type ProxyServiceId = (typeof PROXY_SERVICES)[number];

export const PROXY_SERVICE_DETAILS: Record<
  ProxyServiceId,
  {
    id: ProxyServiceId;
    name: string;
    description: string;
    credentialDescription: string;
  }
> = {
  [BUILTIN_SERVICE]: {
    id: BUILTIN_SERVICE,
    name: 'Builtin Proxy',
    description: 'A proxy service that is built into the core of AIOStreams',
    credentialDescription:
      'A valid username:password pair for this AIOStreams instance, defined in the `AIOSTREAMS_AUTH` environment variable.',
  },
  [STREMTHRU_SERVICE]: {
    id: STREMTHRU_SERVICE,
    name: 'StremThru',
    description:
      '[StremThru](https://github.com/MunifTanjim/stremthru) is a feature packed companion to Stremio which also offers a HTTP proxy, written in Go.',
    credentialDescription:
      'A valid username:password pair for your StremThru instance, defined in the `STREMTHRU_PROXY_AUTH` environment variable.',
  },
  [MEDIAFLOW_SERVICE]: {
    id: MEDIAFLOW_SERVICE,
    name: 'MediaFlow Proxy',
    description:
      '[MediaFlow Proxy](https://github.com/mhdzumair/mediaflow-proxy) is a high performance proxy server which supports HTTP, HLS, and more.',
    credentialDescription:
      'The value of your MediaFlow Proxy instance `API_PASSWORD` environment variable.',
  },
};

const SERVICE_DETAILS: any = {};

const TOP_LEVEL_OPTION_DETAILS: Record<
  | 'tmdbApiKey'
  | 'tmdbAccessToken'
  | 'rpdbApiKey'
  | 'tvdbApiKey'
  | 'topPosterApiKey'
  | 'aioratingsApiKey'
  | 'aioratingsProfileId',
  {
    name: string;
    description: string;
  }
> = {
  tmdbApiKey: {
    name: 'TMDB API Key',
    description:
      'Get your free API key from [here](https://www.themoviedb.org/settings/api). Make sure to copy the 32 character API Key and not the Read Access Token.',
  },
  tmdbAccessToken: {
    name: 'TMDB Access Token',
    description:
      'Get your free access token from [here](https://www.themoviedb.org/settings/api). Make sure to copy the Read Access Token and not the 32 character API Key.',
  },
  rpdbApiKey: {
    name: 'RPDB API Key',
    description:
      'Get your free API key from [here](https://ratingposterdb.com/api-key/) for posters with ratings.',
  },
  topPosterApiKey: {
    name: 'Top Poster API Key',
    description:
      'Get your free API key from [here](https://api.top-streaming.stream/user/register) for posters with ratings.',
  },
  tvdbApiKey: {
    name: 'TVDB API Key',
    description:
      'Sign up for a free API Key at [TVDB](https://www.thetvdb.com/api-information) and then get it from your [dashboard](https://www.thetvdb.com/dashboard/account/apikeys).',
  },
  aioratingsApiKey: {
    name: 'AIOratings API Key',
    description:
      'Get your API key from [here](https://aioratings.com) for custom posters with ratings.',
  },
  aioratingsProfileId: {
    name: 'AIOratings Profile ID',
    description:
      'Use "default" for the default profile, or enter a custom profile UUID from your AIOratings dashboard.',
  },
};

export const DEDUPLICATOR_KEYS = [
  'filename',
  'infoHash',
  'smartDetect',
] as const;

export const DEDUPLICATOR_LIBRARY_BEHAVIOURS = [
  'ignore',
  'prefer',
  'exclusive',
] as const;

export const SMART_DETECT_ATTRIBUTES = [
  'size',
  'bitrate',
  'resolution',
  'quality',
  'encode',
  'releaseGroup',
  'edition',
  'remastered',
  'network',
  'container',
  'visualTags',
  'audioTags',
  'audioChannels',
  'languages',
] as const;

export type SmartDetectAttribute = (typeof SMART_DETECT_ATTRIBUTES)[number];

export const DEFAULT_SMART_DETECT_ATTRIBUTES: SmartDetectAttribute[] = [
  'size',
  'resolution',
  'quality',
  'visualTags',
  'audioTags',
  'audioChannels',
  'languages',
  'encode',
  'edition',
  'network',
  'remastered',
];

export const AUTO_PLAY_ATTRIBUTES = [
  'service',
  'addon',
  'proxied',
  'resolution',
  'quality',
  'encode',
  'audioTags',
  'visualTags',
  'languages',
  'releaseGroup',
  'type',
  'infoHash',
  'size',
] as const;

export const DEFAULT_AUTO_PLAY_ATTRIBUTES: (typeof AUTO_PLAY_ATTRIBUTES)[number][] =
  ['resolution', 'quality', 'releaseGroup'] as const;

export const AUTO_PLAY_METHODS = [
  'matchingFile',
  'matchingIndex',
  'firstFile',
] as const;
export type AutoPlayMethod = (typeof AUTO_PLAY_METHODS)[number];
export const AUTO_PLAY_METHOD_DETAILS: Record<
  AutoPlayMethod,
  {
    name: string;
    description: string;
  }
> = {
  matchingFile: {
    name: 'Matching File',
    description:
      'Auto-play the stream that matches the (customisable) attributes of the previous episode.',
  },
  matchingIndex: {
    name: 'Matching Index',
    description:
      'Auto-play the stream in the same position in the result list (assuming it exists) i.e. if you play the second stream, the second stream for the next episode will also be played.',
  },
  firstFile: {
    name: 'First File',
    description: 'Always auto-play the first stream in the result list.',
  },
} as const;

const RESOLUTIONS = [
  '2160p',
  '1440p',
  '1080p',
  '720p',
  '576p',
  '480p',
  '360p',
  '240p',
  '144p',
  'Unknown',
] as const;

const QUALITIES = [
  'BluRay REMUX',
  'BluRay',
  'WEB-DL',
  'WEBRip',
  'HDRip',
  'HC HD-Rip',
  'DVDRip',
  'HDTV',
  'CAM',
  'TS',
  'TC',
  'SCR',
  'Unknown',
] as const;

export const FAKE_VISUAL_TAGS = ['HDR+DV', 'DV Only', 'HDR Only'] as const;
export type FakeVisualTag = (typeof FAKE_VISUAL_TAGS)[number];

const VISUAL_TAGS = [
  ...FAKE_VISUAL_TAGS,
  'HDR10+',
  'HDR10',
  'DV',
  'HDR',
  'HLG',
  '10bit',
  '3D',
  'IMAX',
  'AI',
  'SDR',
  'H-OU',
  'H-SBS',
  'Unknown',
] as const;

const AUDIO_TAGS = [
  'Atmos',
  'DD+',
  'DD',
  'DTS:X',
  'DTS-HD MA',
  'DTS-HD',
  'DTS-ES',
  'DTS',
  'TrueHD',
  'OPUS',
  'FLAC',
  'AAC',
  'Unknown',
] as const;

const AUDIO_CHANNELS = ['2.0', '5.1', '6.1', '7.1', 'Unknown'] as const;

// Passthrough stages that can be selectively bypassed
const PASSTHROUGH_STAGES = [
  'filter', // bypass main filtering (shouldKeepStream)
  'language', // bypass language filtering specifically
  'dedup', // bypass deduplication
  'limit', // bypass result limiting
  'excluded', // bypass excluded stream expressions
  'required', // bypass required stream expressions
  'title', // bypass title matching
  'year', // bypass year matching
  'episode', // bypass season/episode matching
  'digitalRelease', // bypass early digital release filter
] as const;

const ENCODES = [
  'AV1',
  'HEVC',
  'AVC',
  'XviD',
  'DivX',
  // 'H-OU',
  // 'H-SBS',
  'Unknown',
] as const;

const SORT_CRITERIA = [
  'quality',
  'resolution',
  'language',
  'visualTag',
  'audioTag',
  'audioChannel',
  'streamType',
  'encode',
  'size',
  'service',
  'seeders',
  'private',
  'age',
  'addon',
  'regexPatterns',
  'cached',
  'library',
  'keyword',
  'streamExpressionMatched',
  'streamExpressionScore',
  'regexScore',
  'seadex',
  'bitrate',
  'releaseGroup',
] as const;

export const MIN_SIZE = 0;
export const MAX_SIZE = 100 * 1000 * 1000 * 1000; // 100GB

export const MIN_BITRATE = 0;
export const MAX_BITRATE = 250 * 1000 * 1000; // 250 Mbps

export const MIN_SEEDERS = 0;
export const MAX_SEEDERS = 1000;

export const MIN_AGE_HOURS = 0;
export const MAX_AGE_HOURS = 6480 * 24; // 6480 days (approx 18 years)

export const DEFAULT_POSTERS = [
  'aHR0cHM6Ly93d3cucG5nbWFydC5jb20vZmlsZXMvMTEvUmlja3JvbGxpbmctUE5HLVBpYy5wbmc=',
];

export const DEFAULT_YT_ID = 'eHZGWmpvNVBnRzA=';

export const SORT_CRITERIA_DETAILS: Record<
  (typeof SORT_CRITERIA)[number],
  {
    name: string;
    description: string;
    defaultDirection: 'asc' | 'desc';
    ascendingDescription: string;
    descendingDescription: string;
  }
> = {
  quality: {
    name: 'Quality',
    description: 'Sort by the quality of the stream',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred quality list are preferred',
    descendingDescription:
      'Streams that are in your preferred quality list are preferred',
  },
  resolution: {
    name: 'Resolution',
    description: 'Sort by the resolution of the stream',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred resolution list are preferred',
    descendingDescription:
      'Streams that are in your preferred resolution list are preferred',
  },
  language: {
    name: 'Language',
    description: 'Sort by the language of the stream',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred language list are preferred',
    descendingDescription:
      'Streams that are in your preferred language list are preferred',
  },
  visualTag: {
    name: 'Visual Tag',
    description: 'Sort by the visual tags of the stream',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred visual tag list are preferred',
    descendingDescription:
      'Streams that are in your preferred visual tag list are preferred',
  },
  audioTag: {
    name: 'Audio Tag',
    description: 'Sort by the audio tags of the stream',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred audio tag list are preferred',
    descendingDescription:
      'Streams that are in your preferred audio tag list are preferred',
  },
  audioChannel: {
    name: 'Audio Channel',
    description: 'Sort by the audio channels of the stream',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred audio channel list are preferred',
    descendingDescription:
      'Streams that are in your preferred audio channel list are preferred',
  },
  streamType: {
    name: 'Stream Type',
    description: 'Whether the stream is of a preferred stream type',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred stream type list are preferred',
    descendingDescription:
      'Streams that are in your preferred stream type list are preferred',
  },
  encode: {
    name: 'Encode',
    description: 'Whether the stream is of a preferred encode',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not in your preferred encode list are preferred',
    descendingDescription:
      'Streams that are in your preferred encode list are preferred',
  },
  size: {
    name: 'Size',
    description: 'Sort by the size of the stream',
    defaultDirection: 'desc',
    ascendingDescription: 'Streams that are smaller are sorted first',
    descendingDescription: 'Streams that are larger are sorted first',
  },
  service: {
    name: 'Service',
    description: 'Sort by the service order',
    defaultDirection: 'desc',
    ascendingDescription: 'Streams without a service are preferred',
    descendingDescription:
      'Streams are ordered by the order of your service list, with non-service streams at the bottom',
  },
  seeders: {
    name: 'Seeders',
    description: 'Sort by the number of seeders',
    defaultDirection: 'desc',
    ascendingDescription: 'Streams with fewer seeders are preferred',
    descendingDescription: 'Streams with more seeders are preferred',
  },
  private: {
    name: 'Private',
    description: 'Whether the stream is from a private tracker or not',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that are not from private trackers are preferred',
    descendingDescription:
      'Streams that are from private trackers are preferred',
  },
  age: {
    name: 'Age',
    description: 'Sort by the age of the stream',
    defaultDirection: 'desc',
    ascendingDescription: 'Newer streams are preferred',
    descendingDescription: 'Older streams are preferred',
  },
  addon: {
    name: 'Addon',
    description: 'Sort by the addon order',
    defaultDirection: 'desc',
    ascendingDescription: 'Streams are sorted by the order of your addon list',
    descendingDescription: 'Streams are sorted by the order of your addon list',
  },
  regexPatterns: {
    name: 'Regex Patterns',
    description:
      'Whether the stream matches any of your preferred regex patterns',
    defaultDirection: 'desc',
    ascendingDescription:
      'Streams that do not match your preferred regex patterns are preferred',
    descendingDescription:
      'Streams that match your preferred regex patterns are preferred',
  },
  cached: {
    name: 'Cached',
    defaultDirection: 'desc',
    description: 'Whether the stream is cached or not',
    ascendingDescription: 'Streams that are not cached are preferred',
    descendingDescription: 'Streams that are cached are preferred',
  },
  library: {
    name: 'Library',
    defaultDirection: 'desc',
    description:
      'Whether the stream is in your library (e.g. debrid account) or not',
    ascendingDescription: 'Streams that are not in your library are preferred',
    descendingDescription: 'Streams that are in your library are preferred',
  },
  keyword: {
    name: 'Keyword',
    defaultDirection: 'desc',
    description: 'Sort by the keyword of the stream',
    ascendingDescription:
      'Streams that do not match any of your keywords are preferred',
    descendingDescription:
      'Streams that match any of your keywords are preferred',
  },
  streamExpressionMatched: {
    name: 'Stream Expressions',
    defaultDirection: 'desc',
    description:
      'Whether the stream matches any of your preferred stream expressions',
    ascendingDescription:
      'Streams that do not match your preferred stream expressions are preferred while the ones that do are ranked by the order of your preferred stream expressions',
    descendingDescription:
      'Streams that match your preferred stream expressions are preferred and ranked by the order of your preferred stream expressions',
  },
  seadex: {
    name: 'SeaDex',
    defaultDirection: 'desc',
    description:
      'Whether the stream is a SeaDex release (curated best anime releases from releases.moe)',
    ascendingDescription: 'Streams that are not listed on SeaDex are preferred',
    descendingDescription:
      'Streams that are marked as the Best release on SeaDex are preferred, followed by the Alternative release',
  },
  bitrate: {
    name: 'Bitrate (Estimate)',
    defaultDirection: 'desc',
    description: 'Sort by the bitrate of the stream',
    ascendingDescription: 'Streams with lower bitrate are preferred',
    descendingDescription: 'Streams with higher bitrate are preferred',
  },
  regexScore: {
    name: 'Ranked Regex Score',
    defaultDirection: 'desc',
    description: 'Sort by the computed score from ranked regex patterns',
    ascendingDescription: 'Streams with lower regex scores are preferred',
    descendingDescription: 'Streams with higher regex scores are preferred',
  },
  streamExpressionScore: {
    name: 'Stream Expression Score',
    defaultDirection: 'desc',
    description: 'Sort by the computed score from ranked stream expressions',
    ascendingDescription: 'Streams with lower expression scores are preferred',
    descendingDescription:
      'Streams with higher expression scores are preferred',
  },
  releaseGroup: {
    name: 'Release Group',
    defaultDirection: 'desc',
    description: 'Sort by the release group of the stream',
    ascendingDescription:
      'Streams that are not in your preferred release group list are preferred',
    descendingDescription:
      'Streams that are in your preferred release group list are preferred',
  },
} as const;

const SORT_DIRECTIONS = ['asc', 'desc'] as const;

export const P2P_STREAM_TYPE = 'p2p' as const;
export const LIVE_STREAM_TYPE = 'live' as const;
export const STREMIO_USENET_STREAM_TYPE = 'stremio-usenet' as const;
export const ARCHIVE_STREAM_TYPE = 'archive' as const;
export const USENET_STREAM_TYPE = 'usenet' as const;
export const DEBRID_STREAM_TYPE = 'debrid' as const;
export const HTTP_STREAM_TYPE = 'http' as const;
export const INFO_STREAM_TYPE = 'info' as const;
export const EXTERNAL_STREAM_TYPE = 'external' as const;
export const YOUTUBE_STREAM_TYPE = 'youtube' as const;
export const ERROR_STREAM_TYPE = 'error' as const;
export const STATISTIC_STREAM_TYPE = 'statistic' as const;

const STREAM_TYPES = [
  P2P_STREAM_TYPE,
  LIVE_STREAM_TYPE,
  STREMIO_USENET_STREAM_TYPE,
  ARCHIVE_STREAM_TYPE,
  USENET_STREAM_TYPE,
  DEBRID_STREAM_TYPE,
  HTTP_STREAM_TYPE,
  EXTERNAL_STREAM_TYPE,
  YOUTUBE_STREAM_TYPE,
  ERROR_STREAM_TYPE,
  STATISTIC_STREAM_TYPE,
  INFO_STREAM_TYPE,
] as const;

export type StreamType = (typeof STREAM_TYPES)[number];

const STREAM_RESOURCE = 'stream' as const;
const SUBTITLES_RESOURCE = 'subtitles' as const;
const CATALOG_RESOURCE = 'catalog' as const;
const META_RESOURCE = 'meta' as const;
const ADDON_CATALOG_RESOURCE = 'addon_catalog' as const;

export const MOVIE_TYPE = 'movie' as const;
export const SERIES_TYPE = 'series' as const;
export const CHANNEL_TYPE = 'channel' as const;
export const TV_TYPE = 'tv' as const;
export const ANIME_TYPE = 'anime' as const;

export const TYPES = [
  MOVIE_TYPE,
  SERIES_TYPE,
  CHANNEL_TYPE,
  TV_TYPE,
  ANIME_TYPE,
] as const;

export const TYPE_LABELS: Record<(typeof TYPES)[number], string> = {
  [MOVIE_TYPE]: 'Movie',
  [SERIES_TYPE]: 'Series',
  [CHANNEL_TYPE]: 'Channel',
  [TV_TYPE]: 'TV',
  [ANIME_TYPE]: 'Anime',
};

const RESOURCES = [
  STREAM_RESOURCE,
  SUBTITLES_RESOURCE,
  CATALOG_RESOURCE,
  META_RESOURCE,
  ADDON_CATALOG_RESOURCE,
] as const;

export const RESOURCE_LABELS: any = {};

// export const PRESET_CATEGORY_STREAMS = 'streams' as const;
// econst PRESET_CATEGORY_SUBTITLES = 'subtitles' as const;
// const PRESET_CATEGORY_META_CATALOGS = 'meta_catalogs' as const;
// const PRESET_CATEGORY_MISC = 'misc' as const;
export enum PresetCategory {
  STREAMS = 'streams',
  SUBTITLES = 'subtitles',
  META_CATALOGS = 'meta_catalogs',
  MISC = 'misc',
}

export const PRESET_CATEGORIES = [
  PresetCategory.STREAMS,
  PresetCategory.SUBTITLES,
  PresetCategory.META_CATALOGS,
  PresetCategory.MISC,
] as const;

const LANGUAGES = [
  'English',
  'Japanese',
  'Chinese',
  'Russian',
  'Arabic',
  'Portuguese',
  'Spanish',
  'French',
  'German',
  'Italian',
  'Korean',
  'Hindi',
  'Bengali',
  'Punjabi',
  'Marathi',
  'Gujarati',
  'Tamil',
  'Telugu',
  'Kannada',
  'Malayalam',
  'Thai',
  'Vietnamese',
  'Indonesian',
  'Turkish',
  'Hebrew',
  'Persian',
  'Ukrainian',
  'Greek',
  'Lithuanian',
  'Latvian',
  'Estonian',
  'Polish',
  'Czech',
  'Slovak',
  'Hungarian',
  'Romanian',
  'Bulgarian',
  'Serbian',
  'Croatian',
  'Slovenian',
  'Dutch',
  'Danish',
  'Finnish',
  'Swedish',
  'Norwegian',
  'Malay',
  'Latino',
  'Dual Audio',
  'Dubbed',
  'Multi',
  'Original',
  'Unknown',
] as const;

export const SNIPPETS = [
  {
    name: 'Year + Season + Episode',
    description:
      'Outputs a nicely formatted year along with the season and episode number',
    value:
      '{stream.year::exists["({stream.year}) "||""]}{stream.seasonEpisode::exists["{stream.seasonEpisode::join(\' • \')}"||""]}',
  },
  {
    name: 'File Size',
    description: 'Outputs the file size of the stream',
    value: '{stream.size::>0["{stream.size::bytes}"||""]}',
  },
  {
    name: 'Duration',
    description: 'Outputs the duration of the stream',
    value: '{stream.duration::>0["{stream.duration::time}"||""]}',
  },
  {
    name: 'P2P marker',
    description: 'Displays a [P2P] marker if the stream is a P2P stream',
    value: '{stream.type::=p2p["[P2P]"||""]}',
  },
  {
    name: 'Languages',
    description:
      'Outputs the languages of the stream. Tip: use stream.languageEmojis if you prefer the flags',
    value:
      '{stream.languages::exists["{stream.languages::join(\' • \')}"||""]}',
  },
];

export {
  API_VERSION,
  SERVICES,
  RESOLUTIONS,
  QUALITIES,
  VISUAL_TAGS,
  AUDIO_TAGS,
  AUDIO_CHANNELS,
  ENCODES,
  PASSTHROUGH_STAGES,
  SORT_CRITERIA,
  SORT_DIRECTIONS,
  STREAM_TYPES,
  LANGUAGES,
  RESOURCES,
  STREAM_RESOURCE,
  SUBTITLES_RESOURCE,
  CATALOG_RESOURCE,
  META_RESOURCE,
  ADDON_CATALOG_RESOURCE,
  REALDEBRID_SERVICE,
  PREMIUMIZE_SERVICE,
  ALLDEBRID_SERVICE,
  DEBRIDLINK_SERVICE,
  TORBOX_SERVICE,
  EASYDEBRID_SERVICE,
  DEBRIDER_SERVICE,
  PUTIO_SERVICE,
  PIKPAK_SERVICE,
  OFFCLOUD_SERVICE,
  SEEDR_SERVICE,
  NZBDAV_SERVICE,
  ALTMOUNT_SERVICE,
  STREMIO_NNTP_SERVICE,
  EASYNEWS_SERVICE,
  STREMTHRU_NEWZ_SERVICE,
  SERVICE_DETAILS,
  TOP_LEVEL_OPTION_DETAILS,
  HEADERS_FOR_IP_FORWARDING,
};
