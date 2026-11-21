import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const script = fileURLToPath(new URL('./generate-openapi.mjs', import.meta.url))
const schemaUrl = 'http://127.0.0.1:9001/api/v3/api-docs'

const runGenerator = (
  t,
  fetchBody,
  env = { OPENAPI_SCHEMA_URL: schemaUrl },
  expectedUrl = schemaUrl,
) => {
  const cwd = mkdtempSync(join(tmpdir(), 'codeless-openapi-test-'))
  t.after(() => rmSync(cwd, { recursive: true, force: true }))
  const setup = `globalThis.fetch = async (url) => {
    if (url !== ${JSON.stringify(expectedUrl)}) throw new Error('Unexpected schema URL: ' + url)
    ${fetchBody}
  }`
  const prelude = `data:text/javascript,${encodeURIComponent(setup)}`
  const result = spawnSync(process.execPath, ['--import', prelude, script], {
    cwd,
    env: { ...process.env, ...env },
    encoding: 'utf8',
    timeout: 15_000,
  })
  assert.ifError(result.error)
  return { cwd, ...result }
}

test('reports a refused OpenAPI connection and exits before generating files', (t) => {
  const result = runGenerator(
    t,
    "throw new TypeError('fetch failed', { cause: { code: 'ECONNREFUSED' } })",
  )
  assert.equal(result.status, 1)
  assert.match(result.stderr, /ECONNREFUSED/)
  assert.match(result.stderr, /请确认 Spring Boot 已启动/)
  assert.doesNotMatch(result.stderr, /reading 'components'/)
  assert.equal(existsSync(join(result.cwd, 'tmp', 'openapi')), false)
})

test('rejects JSON responses that are not an API document', (t) => {
  const result = runGenerator(t, 'return Response.json({ code: 40100 })')
  assert.equal(result.status, 1)
  assert.match(result.stderr, /未返回有效的 OpenAPI 文档/)
  assert.equal(existsSync(join(result.cwd, 'tmp', 'openapi')), false)
})

test('resolves a same-origin API path against the local backend for OpenAPI generation', (t) => {
  const result = runGenerator(
    t,
    'return new Response(null, { status: 404 })',
    { VITE_API_BASE_URL: '/api', OPENAPI_SCHEMA_URL: '' },
    'http://localhost:8123/api/v3/api-docs',
  )
  assert.equal(result.status, 1)
  assert.match(result.stderr, /http:\/\/localhost:8123\/api\/v3\/api-docs 返回 HTTP 404/)
})

test('generates into the isolated folder and keeps Snowflake IDs as strings', (t) => {
  const schema = {
    openapi: '3.0.1',
    info: { title: 'CodeLess', version: '1.0' },
    paths: {},
    components: {
      schemas: { AppVO: { type: 'object', properties: { id: { type: 'integer', format: 'int64' } } } },
    },
  }
  const result = runGenerator(t, `return Response.json(${JSON.stringify(schema)})`)
  assert.equal(result.status, 0, result.stderr)
  const types = readFileSync(join(result.cwd, 'tmp', 'openapi', 'api', 'typings.d.ts'), 'utf8')
  assert.match(types, /id\?: string/)
  assert.equal(existsSync(join(result.cwd, 'src', 'api')), false)
})
