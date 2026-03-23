import {
  formatterRichTextToPlainText as formatterRichTextToPlainTextImpl,
  parseFormatterRichText as parseFormatterRichTextImpl,
} from './rich-text.mjs'
import type {
  FormatterIconScaleClass,
  FormatterIconTokenDefinition,
} from '../../../utils/formatter-icon-tokens'

export type FormatterRichTextVariant = 'title' | 'detail'

export type FormatterRichTextSegment =
  | { type: 'text'; text: string }
  | {
    type: 'icon'
    id: string
    src: string
    fallbackLabel: string
    scaleClass: FormatterIconScaleClass
    token: FormatterIconTokenDefinition
  }

export function parseFormatterRichText(
  text: string,
  _variant: FormatterRichTextVariant = 'detail',
): FormatterRichTextSegment[] {
  return parseFormatterRichTextImpl(text, _variant) as FormatterRichTextSegment[]
}

export function formatterRichTextToPlainText(text: string): string {
  return formatterRichTextToPlainTextImpl(text)
}
