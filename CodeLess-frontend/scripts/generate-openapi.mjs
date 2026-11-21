import { mkdtemp, rmdir, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { generateService } from '@umijs/openapi'
import { createJiti } from 'jiti'
import { loadEnv } from 'vite'

const loadSchema = async (schemaUrl) => {
  let response
  try {
    response = await fetch(schemaUrl, { signal: AbortSignal.timeout(10_000) })
  } catch (error) {
    const reason = error.cause?.code || error.message
    throw new Error(`无法访问 ${schemaUrl}（${reason}）。请确认 Spring Boot 已启动且文档地址正确。`)
  }
  if (!response.ok) throw new Error(`${schemaUrl} 返回 HTTP ${response.status}，请检查文档地址。`)

  const schema = await response.json().catch(() => null)
  const isOpenApi3 = typeof schema?.openapi === 'string' && schema.openapi.startsWith('3.')
  if (
    !(isOpenApi3 || schema?.swagger === '2.0') ||
    typeof schema.info?.version !== 'string' ||
    !schema.paths ||
    typeof schema.paths !== 'object' ||
    Array.isArray(schema.paths)
  ) {
    throw new Error(`${schemaUrl} 未返回有效的 OpenAPI 文档。`)
  }
  return schema
}

const generate = async () => {
  const jiti = createJiti(import.meta.url)
  const config = await jiti.import('../openapi2ts.config.ts', { default: true })
  const env = loadEnv('development', process.cwd(), ['OPENAPI_SCHEMA_URL', 'VITE_API_BASE_URL'])
  const apiBase = env.VITE_API_BASE_URL?.trim().replace(/\/+$/, '')
  const schemaUrl =
    env.OPENAPI_SCHEMA_URL?.trim() ||
    (apiBase ? new URL(`${apiBase}/v3/api-docs`, config.schemaPath).href : config.schemaPath)
  const schema = await loadSchema(schemaUrl)

  // 将已校验的文档交给生成器，避免重复请求和生成器吞掉网络错误。
  const directory = await mkdtemp(join(tmpdir(), 'codeless-openapi-'))
  const schemaPath = join(directory, 'openapi.json')
  try {
    await writeFile(schemaPath, JSON.stringify(schema))
    await generateService({ ...config, schemaPath })
  } finally {
    await unlink(schemaPath).catch((error) => {
      if (error.code !== 'ENOENT') throw error
    })
    await rmdir(directory)
  }
}

try {
  await generate()
} catch (error) {
  console.error(`OpenAPI 生成失败：${error.message}`)
  process.exitCode = 1
}
