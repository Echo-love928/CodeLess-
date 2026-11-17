import request from '@/request'

/** 下载当前应用完整的 Markdown 对话历史。 */
export async function exportAppChatHistory(params: { appId: string }) {
  return request<Blob>(`/chatHistory/app/${params.appId}/export`, {
    method: 'GET',
    responseType: 'blob',
  })
}

/** 分页查询应用对话历史（创建时间游标）。 */
export async function listAppChatHistory(params: API.listAppChatHistoryParams) {
  const { appId, ...query } = params
  return request<API.BaseResponsePageChatHistory>(`/chatHistory/app/${appId}`, {
    method: 'GET',
    params: query,
  })
}

/** 管理员分页查询所有对话历史。 */
export async function listAllChatHistoryByPageForAdmin(body: API.ChatHistoryQueryRequest) {
  return request<API.BaseResponsePageChatHistory>('/chatHistory/admin/list/page/vo', {
    method: 'POST',
    data: body,
  })
}
