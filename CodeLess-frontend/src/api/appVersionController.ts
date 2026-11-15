import request from '@/request'

export type AppVersion = {
  appId: string
  version: number
  parentVersion?: number
  prompt?: string
  source: 'generated' | 'imported'
  createTime: string
}

export type VersionDetail = { version: number; files: Record<string, string> }
type Result<T> = { code: number; data: T; message?: string }

export function listAppVersions(appId: string) {
  return request<Result<AppVersion[]>>('/app/version/list', { params: { appId } })
}

export function getAppVersion(appId: string, version: number) {
  return request<Result<VersionDetail>>('/app/version/detail', { params: { appId, version } })
}

export function restoreAppVersion(appId: string, version: number) {
  return request<Result<boolean>>('/app/version/restore', {
    method: 'POST',
    data: { appId, version },
  })
}
