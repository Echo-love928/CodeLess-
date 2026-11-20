import request from '@/request'

// Java Long 类型的统计值以字符串返回。
export type AppWithDownloadCount = API.AppVO & { downloadCount?: string | null }

const downloadFailed = '下载代码失败，请稍后重试'

export const getAppCodeArchive = async (appId: string) => {
  const { status, data, headers } = await request<Blob>(`/app/download/${encodeURIComponent(appId)}`, {
    method: 'GET',
    responseType: 'blob',
    validateStatus: () => true,
  }).catch(() => {
    throw new Error(downloadFailed)
  })

  if (status !== 200 || !data?.size || data.type.split(';', 1)[0] !== 'application/zip') {
    // 后端业务错误也会按 Blob 接收，需要读取 JSON 中的错误信息。
    const result: { message?: unknown } | null = data
      ? await data.text().then(JSON.parse).catch(() => null)
      : null
    const message = result?.message
    throw new Error(typeof message === 'string' && message ? message : downloadFailed)
  }

  const disposition = headers['content-disposition']
  const match =
    typeof disposition === 'string'
      ? /(?:^|;)\s*filename\s*=\s*(?:"([^"]*)"|([^;]*))/i.exec(disposition)
      : null
  const name = (match?.[1] ?? match?.[2] ?? '').replace(/[\\/:*?"<>|\p{Cc}]/gu, '_').trim()
  const fileName = name.length > 4 && /\.zip$/i.test(name) ? name : `${appId}.zip`
  return { blob: data, fileName }
}
