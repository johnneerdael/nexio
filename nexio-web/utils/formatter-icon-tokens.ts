export type FormatterIconScaleClass = 'inline' | 'title-prominent'

export type FormatterIconTokenDefinition = {
  id: string
  src: string
  fallbackLabel: string
  scaleClass: FormatterIconScaleClass
}
import {
  listFormatterIconTokens as listFormatterIconTokensImpl,
  resolveFormatterIconToken as resolveFormatterIconTokenImpl,
} from './formatter-icon-tokens.mjs'

export function resolveFormatterIconToken(id: string): FormatterIconTokenDefinition | undefined {
  return resolveFormatterIconTokenImpl(id) as FormatterIconTokenDefinition | undefined
}

export function listFormatterIconTokens(): FormatterIconTokenDefinition[] {
  return listFormatterIconTokensImpl() as FormatterIconTokenDefinition[]
}
