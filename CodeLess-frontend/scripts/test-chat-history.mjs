import test from 'node:test'
import assert from 'node:assert/strict'
import { useChatHistory } from '../src/composables/useChatHistory.ts'

const record = (index) => ({
  id: `19900000000000000${String(index).padStart(2, '0')}`,
  appId: '1990000000000000001',
  message: `message ${index}`,
  messageType: index % 2 ? 'user' : 'ai',
  createTime: `2026-11-17T10:00:${String(index).padStart(2, '0')}.123456789`,
})
const page = (from, count = 10) => Array.from({ length: count }, (_, index) => record(from - index))

test('loads recent records once, prepends older pages and preserves cursor precision and IDs', async () => {
  const cursors = []
  const history = useChatHistory(async (cursor) => {
    cursors.push(cursor)
    return cursor ? { records: page(10), totalRow: 10 } : { records: page(20), totalRow: 20 }
  })
  await history.load()
  assert.equal(history.loaded.value, true)
  assert.equal(history.hasMore.value, true)
  assert.deepEqual(
    history.records.value.map((item) => item.id),
    page(20)
      .reverse()
      .map((item) => item.id),
  )
  await history.load()
  assert.deepEqual(cursors, [undefined, record(11).createTime])
  assert.deepEqual(
    history.records.value.map((item) => item.id),
    page(20, 20)
      .reverse()
      .map((item) => item.id),
  )
  assert.equal(history.hasMore.value, false)
  await history.load()
  assert.equal(cursors.length, 2)
})

test('rejects concurrent loads while a page is pending', async () => {
  let resolvePage
  let calls = 0
  const history = useChatHistory(() => {
    calls++
    return new Promise((resolve) => {
      resolvePage = resolve
    })
  })
  const pending = history.load()
  assert.equal(history.loading.value, true)
  await history.load()
  assert.equal(calls, 1)
  resolvePage({ records: page(3, 3), totalRow: 3 })
  await pending
  assert.equal(history.loading.value, false)
  assert.equal(history.hasMore.value, false)
})

test('an initial failure is not mistaken for an empty history and can be retried', async () => {
  let calls = 0
  const history = useChatHistory(async () => {
    if (calls++ === 0) throw new Error('network unavailable')
    return { records: [], totalRow: 0 }
  })
  await assert.rejects(history.load(), /network unavailable/)
  assert.equal(history.loaded.value, false)
  assert.equal(history.loading.value, false)
  await history.load()
  assert.equal(history.loaded.value, true)
  assert.equal(history.hasMore.value, false)
  assert.deepEqual(history.records.value, [])
})

test('a failed older page preserves messages and retries the same cursor', async () => {
  const cursors = []
  const history = useChatHistory(async (cursor) => {
    cursors.push(cursor)
    if (cursors.length === 2) throw new Error('temporary failure')
    return { records: cursor ? page(5, 5) : page(15), totalRow: cursor ? 5 : 15 }
  })
  await history.load()
  await assert.rejects(history.load(), /temporary failure/)
  assert.equal(history.records.value.length, 10)
  assert.equal(history.hasMore.value, true)
  await history.load()
  assert.deepEqual(cursors, [undefined, record(6).createTime, record(6).createTime])
  assert.equal(history.records.value.length, 15)
})

test('overlapping results do not duplicate messages or loop on a repeated cursor', async () => {
  let calls = 0
  const history = useChatHistory(async () => {
    calls++
    return { records: page(20), totalRow: 30 }
  })
  await history.load()
  await history.load()
  assert.equal(history.records.value.length, 10)
  assert.equal(history.hasMore.value, false)
  await history.load()
  assert.equal(calls, 2)
})

test('a full final page uses the remaining total instead of showing another load button', async () => {
  const history = useChatHistory(async () => ({ records: page(10), totalRow: 10 }))
  await history.load()
  assert.equal(history.hasMore.value, false)
})
