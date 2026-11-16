import request from '@/request'

export function listPublicApps(body: API.AppQueryRequest) {
  return request<API.BaseResponsePageAppVO>('/app/public/list/page/vo', {
    method: 'POST',
    data: body,
  })
}
export function applyFeatured(appId: string, reason: string) {
  return request<API.BaseResponseBoolean>('/app/feature/apply', {
    method: 'POST',
    data: { appId, reason },
  })
}
export function reviewFeatured(appId: string, approved: boolean, reviewMessage: string) {
  return request<API.BaseResponseBoolean>('/app/feature/review', {
    method: 'POST',
    data: { appId, approved, reviewMessage },
  })
}
