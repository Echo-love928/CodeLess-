import test from 'node:test'
import assert from 'node:assert/strict'
import {
  assertMarkdownExport,
  chatHistoryExportFileName,
  downloadMarkdown,
} from '../src/utils/chatHistoryExport.ts'

test('export file names preserve Unicode and large IDs while removing unsafe path characters', () => {
  const fileName = chatHistoryExportFileName('../我的应用: "测试"\r\n/🌟', '1990000000000000001')
  assert.ok(!/[\\/:*?"<>|\p{Cc}]/u.test(fileName))
  assert.ok(fileName.includes('我的应用'))
  assert.ok(fileName.endsWith('-1990000000000000001.md'))
  assert.equal(chatHistoryExportFileName('🌟'.repeat(100), '1'), `${'🌟'.repeat(60)}-对话记录-1.md`)
  assert.equal(chatHistoryExportFileName(' ', '1'), '未命名应用-对话记录-1.md')
})

test('accepts Markdown without changing Chinese text or source code', async () => {
  const text = '# 对话\n\n```js\nconsole.log("你好 🌟")\n```\n'
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  await assertMarkdownExport(blob)
  assert.equal(await blob.text(), text)
})

test('rejects JSON API errors and non-Markdown gateway responses', async () => {
  await assert.rejects(
    assertMarkdownExport(
      new Blob(['{"code":40100,"message":"请先登录"}'], { type: 'application/json' }),
    ),
    /请先登录/,
  )
  await assert.rejects(
    assertMarkdownExport(new Blob(['<html>Bad Gateway</html>'], { type: 'text/html' })),
    /导出失败/,
  )
})

test('downloads the Blob using a temporary link and revokes the URL after clicking', (t) => {
  const originalDocument = globalThis.document
  const originalWindow = globalThis.window
  const events = []
  let cleanup
  const link = {
    href: '',
    download: '',
    click: () => events.push('click'),
    remove: () => events.push('remove'),
  }
  globalThis.document = {
    createElement: () => link,
    body: { appendChild: () => events.push('append') },
  }
  globalThis.window = {
    setTimeout: (fn, delay) => {
      cleanup = fn
      assert.equal(delay, 1000)
    },
  }
  t.after(() => {
    globalThis.document = originalDocument
    globalThis.window = originalWindow
  })
  const blob = new Blob(['# history'], { type: 'text/markdown' })
  t.mock.method(URL, 'createObjectURL', (value) => {
    assert.equal(value, blob)
    return 'blob:test-export'
  })
  t.mock.method(URL, 'revokeObjectURL', (url) => {
    assert.equal(url, 'blob:test-export')
    events.push('revoke')
  })
  downloadMarkdown(blob, '对话记录.md')
  assert.equal(link.href, 'blob:test-export')
  assert.equal(link.download, '对话记录.md')
  assert.deepEqual(events, ['append', 'click', 'remove'])
  cleanup()
  assert.deepEqual(events, ['append', 'click', 'remove', 'revoke'])
})
