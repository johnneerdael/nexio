
export interface ParsedFile {
  filename: string;
  size: number;
  episodes: any;
  seasons: any;
  languages?: any;
  subtitles?: any;
  folderSeasons?: any;
  folderEpisodes?: any;
  quality?: string;
  resolution?: string;
  subbed?: boolean;
  dubbed?: boolean;
  visualTags?: any;
  audioTags?: any;
  releaseGroup?: string;
  encode?: string;
  audioChannels?: any;
  year?: any;
  title?: string;
  date?: string;
  seasonPack?: boolean;
  editions?: any;
  regraded?: boolean;
  repack?: boolean;
  uncensored?: boolean;
  unrated?: boolean;
  upscaled?: boolean;
  network?: string;
  container?: string;
  extension?: string;
}

export interface ParsedStream {
  id: string;
  title: string;
  size: number;
  resolution: string;
  quality: string;
  addonName?: string;
  serviceNames?: string[];
  indexer?: string;
  releaseGroup?: string;
  mode?: string;
  languages?: any;
  streamTypes?: any;
  visualTags?: any;
  audioTags?: any;
  audioChannels?: any;
  encodes?: any;
  age?: number;
  seeders?: number;
  isCached?: boolean;
  isUncached?: boolean;
  isP2P?: boolean;
  isHttp?: boolean;
  isYoutube?: boolean;
  isLibrary?: boolean;
  isProxied?: boolean;
  isSeasonPack?: boolean;
  isAltRelease?: boolean;
  isBestRelease?: boolean;
  isRemux?: boolean;
  isNzb?: boolean;
  health?: number;
  score?: number;
  networks?: any;
  specialEditions?: any;
  duration?: number;
  year?: number;
  seasonEpisode?: any;
  
  parsedFile?: ParsedFile;
  filename?: string;
  folderName?: string;
  folderSize?: number;
  library?: boolean;
  regexMatched?: any;
  rankedRegexesMatched?: any;
  regexScore?: number;
  torrent?: any;
  type?: string;
  bitrate?: number;
  message?: string;
  proxied?: boolean;
  seadex?: any;
  streamExpressionScore?: number;
  streamExpressionMatched?: any;
  rankedStreamExpressionsMatched?: any;
  addon?: any;
  service?: any;
}

export interface UserData {
  formatter: {
    id: string;
    definition?: {
      name: string;
      description: string;
      nameTemplate: string;
      descriptionTemplate: string;
    };
  };
  preferredLanguages?: string[];
  requiredLanguages?: string[];
  includedLanguages?: string[];
  addonName?: string;
}

export const createLogger = (name?: string) => ({
  info: (...args: any[]) => {},
  debug: (...args: any[]) => {},
  warn: (...args: any[]) => {},
  error: (...args: any[]) => {},
});

export interface EnvType {
  MAX_FORMATTER_TEMPLATE_LENGTH: number;
  MAX_SEL_LENGTH: number;
  ADDON_NAME: string;
}

export const Env: EnvType = {
  MAX_FORMATTER_TEMPLATE_LENGTH: 5000,
  MAX_SEL_LENGTH: 3000,
  ADDON_NAME: 'Standalone',
};

export type Option = any;
export type Resource = any;
