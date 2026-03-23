// @ts-nocheck
/**
 * Main result interface matching the Go Result struct
 * Using JavaScript/TypeScript camelCase naming conventions
 */
export interface ParsedResult {
  audio?: string[];
  bitDepth?: string;
  channels?: string[];
  codec?: string;
  commentary?: boolean;
  complete?: boolean;
  container?: string;
  convert?: boolean;
  date?: string;
  documentary?: boolean;
  ppv?: boolean;
  dubbed?: boolean;
  editions?: string[];
  episodeCode?: string;
  episodes?: number[];
  extended?: boolean;
  extension?: string;
  group?: string;
  hdr?: string[];
  hardcoded?: boolean;
  languages?: string[];
  network?: string;
  proper?: boolean;
  quality?: string;
  region?: string;
  releaseTypes?: string[];
  repack?: boolean;
  resolution?: string;
  retail?: boolean;
  seasons?: number[];
  site?: string;
  size?: string;
  subbed?: boolean;
  threeD?: string;
  regraded?: boolean;
  title?: string;
  uncensored?: boolean;
  unrated?: boolean;
  upscaled?: boolean;
  volumes?: number[];
  year?: string;
}

/**
 * Internal parse metadata
 */
export interface ParseMeta {
  mIndex: number;
  mValue: string;
  value: any;
  remove: boolean;
  processed: boolean;
}

/**
 * Value set for handling unique collections
 */
export class ValueSet<T> {
  private existMap: Map<T, boolean> = new Map();
  private _values: T[] = [];

  append(v: T): ValueSet<T> {
    if (!this.existMap.has(v)) {
      this.existMap.set(v, true);
      this._values.push(v);
    }
    return this;
  }

  exists(v: T): boolean {
    return this.existMap.has(v);
  }

  get values(): T[] {
    return this._values;
  }
}

/**
 * Handler validator function type
 */
export type HandlerMatchValidator = (input: string, idxs: number[]) => boolean;

/**
 * Handler processor function type
 */
export type HandlerProcessor = (
  title: string,
  m: ParseMeta,
  result: Map<string, ParseMeta>
) => ParseMeta;

/**
 * Handler transformer function type
 */
export type HandlerTransformer = (
  title: string,
  m: ParseMeta,
  result: Map<string, ParseMeta>
) => void;

/**
 * Handler configuration matching Go handler struct
 */
export interface Handler {
  field: string;
  pattern?: RegExp;
  validateMatch?: HandlerMatchValidator;
  transform?: HandlerTransformer;
  process?: HandlerProcessor;
  remove?: boolean;
  keepMatching?: boolean; // !skipIfAlreadyFound
  skipIfFirst?: boolean;
  skipIfBefore?: string[];
  skipFromTitle?: boolean;
  matchGroup?: number;
  valueGroup?: number;
}

/**
 * Result of parsing with map structure
 */
export type ParseResult = Map<string, ParseMeta>;
