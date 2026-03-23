import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const richTextModulePath = '../components/portal/formatter/rich-text.mjs'
const currentDir = path.dirname(fileURLToPath(import.meta.url))
const formatterComponentPath = path.resolve(currentDir, '../components/portal/formatter/FormatterRichText.vue')
const formatterWorkspacePath = path.resolve(currentDir, '../components/portal/FormatterWorkspace.vue')

test('inline icon tokens keep title badges prominent', async () => {
  try {
    const mod = await import(richTextModulePath)
    assert.equal(typeof mod.parseFormatterRichText, 'function')
    assert.equal(
      mod.parseFormatterRichText('[[icon:4k]]', 'title')[0].scaleClass,
      'title-prominent',
    )
  } catch (error) {
    const detail = error instanceof Error ? `${error.name}: ${error.message}` : String(error)
    assert.fail(`formatter rich-text helper contract failed for components/portal/formatter/rich-text.ts: ${detail}`)
  }
})

test('unknown tokens preserve the original token text for debugging', async () => {
  const mod = await import(richTextModulePath)
  assert.deepEqual(
    mod.parseFormatterRichText('Title [[icon:unknown_badge]]', 'detail'),
    [
      { type: 'text', text: 'Title [[icon:unknown_badge]]' },
    ],
  )
})

test('plain text fallback maps known tokens to labels and unknown tokens to ids', async () => {
  const mod = await import(richTextModulePath)
  assert.equal(
    mod.formatterRichTextToPlainText('[[icon:4k]] [[icon:netflix]] [[icon:unknown_badge]]'),
    '4K Netflix [[icon:unknown_badge]]',
  )
  assert.equal(
    mod.formatterRichTextToPlainText('[[icon:netflix]] Netflix • [[icon:4k]]'),
    ' Netflix • 4K',
  )
})

test('known tokens include icon metadata and sizing classes', async () => {
  const mod = await import(richTextModulePath)
  assert.deepEqual(
    mod.parseFormatterRichText('[[icon:4k]] [[icon:netflix]] [[icon:atmos]] [[icon:dovi]]', 'title').map((segment) =>
      segment.type === 'icon'
        ? { id: segment.id, src: segment.src, scaleClass: segment.scaleClass }
        : { type: segment.type, text: segment.text },
    ),
    [
      { id: '4k', src: '/formatter-icons/4k.png', scaleClass: 'title-prominent' },
      { type: 'text', text: ' ' },
      { id: 'netflix', src: '/formatter-icons/netflix.png', scaleClass: 'inline' },
      { type: 'text', text: ' ' },
      { id: 'atmos', src: '/formatter-icons/atmos.png', scaleClass: 'inline' },
      { type: 'text', text: ' ' },
      { id: 'dovi', src: '/formatter-icons/dovi.png', scaleClass: 'inline' },
    ],
  )
})

test('formatter rich text component defines title and detail icon sizing', async () => {
  const source = await fs.readFile(formatterComponentPath, 'utf8')
  assert.match(source, /formatter-rich-text--detail[\s\S]*--formatter-icon-inline-height:\s*1em;/)
  assert.match(source, /formatter-rich-text--title[\s\S]*--formatter-icon-inline-height:\s*1\.02em;/)
  assert.match(source, /formatter-rich-text--title[\s\S]*--formatter-icon-title-height:\s*1\.12em;/)
  assert.match(source, /height:\s*var\(--formatter-icon-inline-height\);/)
  assert.match(source, /height:\s*var\(--formatter-icon-title-height\);/)
})

test('formatter workspace preview uses formatter rich text renderer', async () => {
  const source = await fs.readFile(formatterWorkspacePath, 'utf8')
  assert.match(source, /<FormatterRichText :text="formattedOutput\.title" variant="title" \/>/)
  assert.match(source, /<FormatterRichText :text="line" variant="detail" \/>/)
  assert.doesNotMatch(source, /\.filter\(\(line\) => line.length > 0\)/)
})

test('universal formatter template uses icon tokens and removes star fallback', async () => {
  const source = await fs.readFile(
    path.resolve(currentDir, '../utils/formatter-templates.ts'),
    'utf8',
  )
  assert.match(source, /\[\[icon:4k\]\]/)
  assert.match(source, /\[\[icon:netflix\]\] Netflix/)
  assert.match(source, /\[\[icon:atmos\]\]/)
  assert.match(source, /\[\[icon:truehd\]\]/)
  assert.match(source, /\[\[icon:stereo\]\]/)
  assert.doesNotMatch(source, /☆☆☆☆☆/)
})

test('formatter rich text component falls back to plain text on image error', async () => {
  const source = await fs.readFile(formatterComponentPath, 'utf8')
  assert.match(source, /@error="markFailed\(index\)"/)
  assert.match(source, /<span v-else>{{ fallbackText\(index, segment\) }}<\/span>/)
  assert.match(source, /alt=""/)
  assert.match(source, /aria-hidden="true"/)
})
