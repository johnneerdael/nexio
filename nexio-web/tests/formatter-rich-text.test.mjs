import test from 'node:test'
import assert from 'node:assert/strict'

test('inline icon tokens keep title badges prominent', async () => {
  try {
    const mod = await import('../components/portal/formatter/rich-text.ts')
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
