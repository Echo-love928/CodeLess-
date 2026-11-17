import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { setImmediate } from 'node:timers/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
import { useChatHistory } from '../src/composables/useChatHistory.ts'

// 执行真实页面的 setup 和挂载钩子，用可控 API 与 SSE 验证初始化流程。
const require = createRequire(import.meta.url)
const source = readFileSync(new URL('../src/pages/app/AppChatPage.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = ts.transpileModule(compileScript(descriptor, { id: 'app-chat-test' }).content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
const renderer = vue.createRenderer({
  createComment: () => ({}),
  createElement: () => ({}),
  createText: () => ({}),
  insert() {},
  remove() {},
  setText() {},
  setElementText() {},
  patchProp() {},
  parentNode: () => null,
  nextSibling: () => null,
})
const settle = async (predicate) => {
  for (let index = 0; index < 50; index++) {
    await setImmediate()
    if (predicate()) return
  }
  assert.fail('page did not settle')
}
const mountPage = (options = {}) => {
  const appId = '1990000000000000001'
  const userId = '1990000000000000002'
  const ownerId = options.otherOwner ? '1990000000000000003' : userId
  const app = { id: appId, userId: ownerId, initPrompt: '创建一个咖啡馆网站', codeGenType: 'html' }
  const historyCalls = []
  const generationCalls = []
  const errors = []
  const api = {
    getAppVoById: async () => ({ data: { code: 0, data: { ...app } } }),
    listAppChatHistory: async (params) => {
      historyCalls.push({ ...params })
      if (options.failHistory && historyCalls.length === 1) throw new Error('历史加载失败')
      return {
        data: {
          code: 0,
          data:
            typeof options.history === 'function'
              ? options.history(params)
              : (options.history ?? { records: [], totalRow: 0 }),
        },
      }
    },
  }
  const modules = {
    vue,
    'vue-router': { useRouter: () => ({ replace: async () => {}, push: async () => {} }) },
    'ant-design-vue': {
      message: { error: (text) => errors.push(text), info() {}, warning() {}, success() {} },
      Modal: {},
    },
    '@/api/appController': api,
    '@/api/chatHistoryController': api,
    '@/stores/loginUser': {
      useLoginUserStore: () => ({
        loginUser: { id: userId, userRole: options.admin ? 'admin' : 'user' },
      }),
    },
    '@/utils/markdown': { renderMarkdown: (value) => `<p>${value}</p>` },
    '@/config/urls': { API_BASE_URL: 'http://api.test', PREVIEW_BASE_URL: 'http://preview.test' },
    '@/utils/appId': { useAppId: () => vue.ref(appId), isValidAppId: () => true },
    '@/utils/appStatus': { isAppOnline: () => false },
    '@/composables/useChatHistory': { useChatHistory },
  }
  const mockRequire = (name) => {
    if (name in modules) return modules[name]
    if (name === '@ant-design/icons-vue' || name.startsWith('@/') || name.endsWith('.css'))
      return {}
    return require(name)
  }
  const fetch = async (url) => {
    if (!String(url).includes('/app/chat/gen/code')) return { ok: true }
    generationCalls.push(new URL(url))
    return {
      ok: true,
      headers: { get: () => 'text/event-stream' },
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode('data: "网站已生成"\n\nevent: done\ndata: {}\n\n'),
          )
          controller.close()
        },
      }),
    }
  }
  const window = {
    setInterval: () => 1,
    clearInterval() {},
    setTimeout: () => 1,
    clearTimeout() {},
  }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', 'window', 'fetch', compiled)(
    mockRequire,
    module,
    module.exports,
    window,
    fetch,
  )
  const component = module.exports.default
  component.render = () => null
  const mounted = renderer.createApp(component)
  const vm = mounted.mount({})
  return { mounted, state: vm.$.setupState, historyCalls, generationCalls, errors, appId }
}

test('an owned app with empty history automatically sends initPrompt without URL flags', async () => {
  const page = mountPage()
  try {
    await settle(() => !page.state.initializing && !page.state.generating)
    assert.equal(page.historyCalls.length, 1)
    assert.equal(page.historyCalls[0].pageSize, 10)
    assert.equal(page.generationCalls.length, 1)
    assert.equal(page.generationCalls[0].searchParams.get('message'), '创建一个咖啡馆网站')
    assert.equal(page.state.chatMessages.length, 2)
    assert.equal(page.state.previewReady, true)
  } finally {
    page.mounted.unmount()
  }
})

test('existing user and AI history renders in ascending order with a website and no automatic send', async () => {
  const page = mountPage({
    history: {
      records: [
        { id: '2', messageType: 'ai', message: 'AI reply', createTime: '2026-11-17T10:00:02' },
        { id: '1', messageType: 'user', message: 'user prompt', createTime: '2026-11-17T10:00:01' },
      ],
      totalRow: 2,
    },
  })
  try {
    await settle(() => !page.state.initializing)
    assert.deepEqual(
      page.state.chatMessages.map(({ role, content }) => [role, content]),
      [
        ['user', 'user prompt'],
        ['assistant', 'AI reply'],
      ],
    )
    assert.equal(page.generationCalls.length, 0)
    assert.equal(page.state.previewReady, true)
  } finally {
    page.mounted.unmount()
  }
})

test('an administrator viewing another owned app can load history without triggering generation', async () => {
  const page = mountPage({ otherOwner: true, admin: true })
  try {
    await settle(() => !page.state.initializing)
    assert.equal(page.historyCalls.length, 1)
    assert.equal(page.generationCalls.length, 0)
    assert.equal(page.state.canChat, false)
  } finally {
    page.mounted.unmount()
  }
})

test('a visitor can preview another app without requesting its private history', async () => {
  const page = mountPage({ otherOwner: true })
  try {
    await settle(() => !page.state.initializing)
    assert.equal(page.historyCalls.length, 0)
    assert.equal(page.generationCalls.length, 0)
    assert.equal(page.state.previewReady, true)
  } finally {
    page.mounted.unmount()
  }
})

test('history failure does not trigger generation; a successful empty retry triggers it once', async () => {
  const page = mountPage({ failHistory: true })
  try {
    await settle(() => !page.state.initializing)
    assert.equal(page.generationCalls.length, 0)
    assert.equal(page.state.historyLoaded, false)
    assert.equal(page.state.historyError, '历史加载失败')
    await page.state.loadHistory()
    await settle(() => !page.state.generating)
    assert.equal(page.historyCalls.length, 2)
    assert.equal(page.generationCalls.length, 1)
    assert.equal(page.state.historyError, '')
  } finally {
    page.mounted.unmount()
  }
})

test('loading older history after sending keeps the live conversation and uses the original cursor', async () => {
  const record = (index) => ({
    id: String(index),
    messageType: index % 2 ? 'user' : 'ai',
    message: `message ${index}`,
    createTime: `2026-11-17T10:00:${String(index).padStart(2, '0')}`,
  })
  const page = mountPage({
    history: (params) =>
      params.lastCreateTime
        ? { records: [record(1)], totalRow: 1 }
        : { records: Array.from({ length: 10 }, (_, index) => record(11 - index)), totalRow: 11 },
  })
  try {
    await settle(() => !page.state.initializing)
    await page.state.sendMessage('改成蓝色')
    const list = {
      get scrollHeight() {
        return page.state.chatMessages.length * 100
      },
      scrollTop: 50,
      clientHeight: 500,
    }
    page.state.messageListRef = list
    await page.state.loadHistory()
    assert.equal(list.scrollTop, 150)
    assert.equal(page.historyCalls[1].lastCreateTime, record(2).createTime)
    assert.equal(page.state.chatMessages.length, 13)
    assert.equal(page.state.chatMessages[0].content, 'message 1')
    assert.equal(page.state.chatMessages.at(-2).content, '改成蓝色')
    assert.equal(page.generationCalls.length, 1)
  } finally {
    page.mounted.unmount()
  }
})
