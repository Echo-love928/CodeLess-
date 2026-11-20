import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { setImmediate } from 'node:timers/promises'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
import { useChatHistory } from '../src/composables/useChatHistory.ts'
import { assertMarkdownExport, chatHistoryExportFileName } from '../src/utils/chatHistoryExport.ts'

// 执行真实页面的 setup 和挂载钩子，用可控 API 与 SSE 验证初始化流程。
const require = createRequire(import.meta.url)
const compile = (source) =>
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText

const loadConfigModule = (path, imports = {}) => {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const output = compile(source.replaceAll('import.meta.env', 'env'))
  const module = { exports: {} }
  new Function('require', 'exports', 'env', output)(
    (name) => imports[name] ?? require(name),
    module.exports,
    {
      VITE_API_BASE_URL: 'http://api.test',
      VITE_PREVIEW_BASE_URL: 'http://preview.test/static/',
      VITE_DEPLOY_BASE_URL: 'http://deploy.test',
    },
  )
  return module.exports
}
const codeGenTypes = loadConfigModule('../src/constants/codeGenType.ts')
const urls = loadConfigModule('../src/config/urls.ts', { '@/constants/codeGenType': codeGenTypes })
const source = readFileSync(new URL('../src/pages/app/AppChatPage.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = compile(compileScript(descriptor, { id: 'app-chat-test' }).content)
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
  const app = {
    id: appId,
    userId: ownerId,
    appName: '测试应用',
    initPrompt: options.initPrompt ?? '创建一个咖啡馆网站',
    codeGenType: options.codeGenType ?? 'html',
  }
  const historyCalls = []
  const generationCalls = []
  const stopCalls = []
  const previewCalls = []
  const errors = []
  const exportCalls = []
  const downloads = []
  const api = {
    exportAppChatHistory: async (params) => {
      exportCalls.push({ ...params })
      if (options.exportFailure) throw new Error('网络连接失败')
      return {
        data:
          typeof options.exportBlob === 'function'
            ? await options.exportBlob()
            : (options.exportBlob ??
              new Blob(['# 完整历史'], { type: 'text/markdown;charset=utf-8' })),
      }
    },
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
    '@/config/urls': urls,
    '@/constants/codeGenType': codeGenTypes,
    '@/utils/appId': { useAppId: () => vue.ref(appId), isValidAppId: () => true },
    '@/utils/appStatus': { isAppOnline: () => false },
    '@/composables/useChatHistory': { useChatHistory },
    '@/utils/chatHistoryExport': {
      assertMarkdownExport,
      chatHistoryExportFileName,
      downloadMarkdown: (blob, fileName) => downloads.push({ blob, fileName }),
    },
  }
  const mockRequire = (name) => {
    if (name in modules) return modules[name]
    if (name === '@ant-design/icons-vue' || name.startsWith('@/') || name.endsWith('.css'))
      return {}
    return require(name)
  }
  const fetch = async (url) => {
    if (String(url).includes('/app/chat/stop')) {
      stopCalls.push(url)
      return { ok: true }
    }
    if (!String(url).includes('/app/chat/gen/code')) {
      previewCalls.push(new URL(url))
      return { ok: options.previewAvailable?.() ?? true }
    }
    generationCalls.push(new URL(url))
    return {
      ok: true,
      headers: { get: () => 'text/event-stream' },
      body: options.generationBody ?? new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              options.stream ?? 'data: "网站已生成"\n\nevent: done\ndata: {}\n\n',
            ),
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
  return {
    mounted,
    state: vm.$.setupState,
    historyCalls,
    generationCalls,
    stopCalls,
    previewCalls,
    errors,
    appId,
    exportCalls,
    downloads,
  }
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
    assert.equal(page.previewCalls.at(-1).pathname, `/static/html_${page.appId}/`)
  } finally {
    page.mounted.unmount()
  }
})

test('Vue generation shows tool progress and appends only AI text to the reply', async () => {
  const events = [
    { type: 'tool_request', name: 'writeFile', arguments: '{' },
    { type: 'tool_executed', result: '文件写入成功: src/App.vue' },
    { type: 'tool_request', name: 'writeFile', arguments: '{' },
    { type: 'ai_response', data: '网站已生成' },
    { type: 'ai_response', data: '。' },
  ]
  const stream = events
    .map((event) => `data: ${JSON.stringify({ d: JSON.stringify(event) })}\n\n`)
    .join('')
  const page = mountPage({
    codeGenType: 'vue_project',
    initPrompt: '',
    stream: `${stream}event: done\n\n`,
  })
  try {
    await settle(() => !page.state.initializing)
    const progress = []
    vue.watch(() => page.state.toolProgress, (value) => progress.push(value), { flush: 'sync' })
    await page.state.sendMessage('创建 Vue 项目')
    assert.deepEqual(progress, [
      '正在生成项目文件',
      '文件写入成功: src/App.vue',
      '正在生成项目文件（已写入 1 个）',
      '',
    ])
    assert.equal(page.state.chatMessages.at(-1).content, '网站已生成。')
    assert.equal(page.state.previewReady, true)
    assert.equal(new URL(page.state.previewUrl).pathname, `/static/vue_project_${page.appId}/dist/index.html`)
    assert.equal(page.previewCalls.at(-1).href, page.state.previewUrl)
  } finally {
    page.mounted.unmount()
  }
})

test('leaving the Vue chat page disconnects the stream without requesting a stop', async () => {
  let streamController
  const generationBody = new ReadableStream({
    start(controller) {
      streamController = controller
    },
  })
  const page = mountPage({ codeGenType: 'vue_project', initPrompt: '', generationBody })
  await settle(() => !page.state.initializing)
  const generation = page.state.sendMessage('创建 Vue 项目')
  await settle(() => page.state.generationAccepted)

  page.mounted.unmount()
  streamController.close()
  await generation

  assert.deepEqual(page.stopCalls, [])
  assert.deepEqual(page.errors, [])
})

test('refresh checks for the Vue build again after the generation stream completes', async () => {
  let built = false
  const page = mountPage({
    codeGenType: 'vue_project',
    previewAvailable: () => built,
    stream: 'event: done\n\n',
  })
  try {
    await settle(() => !page.state.initializing && !page.state.generating)
    assert.equal(page.state.previewReady, false)
    assert.deepEqual(page.errors, [])
    assert.match(page.state.chatMessages.at(-1).content, /构建完成后可刷新预览/)

    built = true
    await page.state.refreshPreview()
    assert.equal(page.state.previewReady, true)
    assert.equal(page.previewCalls.at(-1).href, page.state.previewUrl)
  } finally {
    page.mounted.unmount()
  }
})

test('the type options and preview helper include Vue without changing multi-file URLs', () => {
  assert.equal(codeGenTypes.getCodeGenTypeLabel('vue_project'), 'Vue 项目模式')
  assert.equal(
    urls.getStaticPreviewUrl('multi_file', '1990000000000000001'),
    'http://preview.test/static/multi_file_1990000000000000001/',
  )
})

const exportHistory = {
  records: [
    { id: '1', messageType: 'user', message: '已有需求', createTime: '2026-11-17T10:00:01' },
  ],
  totalRow: 25,
}

test('exports full server history even when only the first page has been loaded', async () => {
  const blob = new Blob(['# 全部 25 条历史\n\n```html\n<h1>你好</h1>\n```'], {
    type: 'text/markdown;charset=utf-8',
  })
  const page = mountPage({ history: exportHistory, exportBlob: blob })
  try {
    await settle(() => !page.state.initializing)
    await page.state.exportHistory()
    assert.deepEqual(page.exportCalls, [{ appId: page.appId }])
    assert.equal(page.historyCalls.length, 1)
    assert.equal(page.downloads.length, 1)
    assert.equal(page.downloads[0].blob, blob)
    assert.equal(page.downloads[0].fileName, `测试应用-对话记录-${page.appId}.md`)
    assert.equal(page.state.exporting, false)
  } finally {
    page.mounted.unmount()
  }
})

test('prevents duplicate exports while a download request is pending', async () => {
  let resolveExport
  const page = mountPage({
    history: exportHistory,
    exportBlob: () =>
      new Promise((resolve) => {
        resolveExport = resolve
      }),
  })
  try {
    await settle(() => !page.state.initializing)
    const pending = page.state.exportHistory()
    assert.equal(page.state.exporting, true)
    await page.state.exportHistory()
    assert.equal(page.exportCalls.length, 1)
    resolveExport(new Blob(['# history'], { type: 'text/markdown' }))
    await pending
    assert.equal(page.downloads.length, 1)
    assert.equal(page.state.exporting, false)
  } finally {
    page.mounted.unmount()
  }
})

test('a JSON permission error is shown instead of being downloaded as Markdown', async () => {
  const page = mountPage({
    history: exportHistory,
    exportBlob: new Blob([JSON.stringify({ code: 40300, message: '无权导出' })], {
      type: 'application/json',
    }),
  })
  try {
    await settle(() => !page.state.initializing)
    await page.state.exportHistory()
    assert.deepEqual(page.errors, ['无权导出'])
    assert.equal(page.downloads.length, 0)
    assert.equal(page.state.exporting, false)
  } finally {
    page.mounted.unmount()
  }
})

test('a failed export can be retried and never leaves the button loading', async () => {
  const options = { history: exportHistory, exportFailure: true }
  const page = mountPage(options)
  try {
    await settle(() => !page.state.initializing)
    await page.state.exportHistory()
    assert.equal(page.state.exporting, false)
    assert.equal(page.downloads.length, 0)
    options.exportFailure = false
    await page.state.exportHistory()
    assert.equal(page.exportCalls.length, 2)
    assert.equal(page.downloads.length, 1)
  } finally {
    page.mounted.unmount()
  }
})

test('visitors cannot export, while administrators can export another app history', async () => {
  for (const admin of [false, true]) {
    const page = mountPage({ history: exportHistory, otherOwner: true, admin })
    try {
      await settle(() => !page.state.initializing)
      await page.state.exportHistory()
      assert.equal(page.exportCalls.length, admin ? 1 : 0)
      assert.equal(page.downloads.length, admin ? 1 : 0)
    } finally {
      page.mounted.unmount()
    }
  }
})

test('does not export during generation or when the app has no messages', async () => {
  const page = mountPage({ initPrompt: '' })
  try {
    await settle(() => !page.state.initializing)
    await page.state.exportHistory()
    assert.equal(page.exportCalls.length, 0)
    page.state.liveMessages = [{ id: 'local:1', role: 'user', content: '生成中' }]
    page.state.generating = true
    await page.state.exportHistory()
    assert.equal(page.exportCalls.length, 0)
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
