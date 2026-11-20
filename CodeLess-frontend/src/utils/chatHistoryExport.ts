export const chatHistoryExportFileName = (appName: string | undefined, appId: string): string => {
  const safeName = (appName || '未命名应用').replace(/[\\/:*?"<>|\p{Cc}]/gu, '_').trim()
  const name = Array.from(safeName || '未命名应用')
    .slice(0, 60)
    .join('')
  return `${name}-对话记录-${appId}.md`
}

/** 错误响应同样会被 Axios 包装成 Blob，先识别错误，避免下载成伪 Markdown。 */
export const assertMarkdownExport = async (blob: Blob): Promise<void> => {
  if (blob.type.toLowerCase().startsWith('text/markdown')) return
  let errorMessage = '导出失败，请稍后重试'
  try {
    const result = JSON.parse(await blob.text()) as { message?: unknown }
    if (typeof result.message === 'string' && result.message) errorMessage = result.message
  } catch {
    // 非 Markdown、非 JSON 的网关响应也作为导出失败处理。
  }
  throw new Error(errorMessage)
}

export { downloadBlob as downloadMarkdown } from './blobDownload.ts'
