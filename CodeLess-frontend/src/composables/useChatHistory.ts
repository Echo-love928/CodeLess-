import { ref } from 'vue'

const PAGE_SIZE = 10

/** 游标始终来自服务端最早记录，保留原始时间字符串以免丢失精度。 */
export const useChatHistory = (fetchPage: (cursor?: string) => Promise<API.PageChatHistory>) => {
  const records = ref<API.ChatHistory[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const hasMore = ref(false)
  const cursor = ref<string>()

  const load = async () => {
    if (loading.value || (loaded.value && !hasMore.value)) return
    loading.value = true
    try {
      const page = await fetchPage(cursor.value)
      const sorted = [...(page.records ?? [])].sort((a, b) =>
        (a.createTime ?? '').localeCompare(b.createTime ?? ''),
      )
      const ids = new Set(records.value.map((record) => record.id).filter(Boolean))
      const previousCursor = cursor.value
      const nextCursor = sorted[0]?.createTime
      records.value = [
        ...sorted.filter((record) => !record.id || !ids.has(record.id)),
        ...records.value,
      ]
      cursor.value = nextCursor ?? previousCursor
      hasMore.value = Boolean(
        nextCursor &&
          nextCursor !== previousCursor &&
          sorted.length === PAGE_SIZE &&
          (page.totalRow === undefined || page.totalRow > sorted.length),
      )
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  return { records, loading, loaded, hasMore, load }
}
