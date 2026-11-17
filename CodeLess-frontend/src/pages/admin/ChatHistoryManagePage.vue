<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { EyeOutlined } from '@ant-design/icons-vue'
import { listAllChatHistoryByPageForAdmin } from '@/api/chatHistoryController'

const data = ref<API.ChatHistory[]>([])
const total = ref(0)
const loading = ref(false)
const detailOpen = ref(false)
const selectedRecord = ref<API.ChatHistory>()
const searchParams = reactive<API.ChatHistoryQueryRequest>({
  pageNum: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
})
const messageTypeOptions = [
  { label: '用户', value: 'user' },
  { label: 'AI', value: 'ai' },
]
const columns = [
  { title: 'ID', dataIndex: 'id', width: 200 },
  { title: '消息内容', dataIndex: 'message', width: 320, ellipsis: true },
  { title: '消息类型', dataIndex: 'messageType', width: 100 },
  { title: '应用 ID', dataIndex: 'appId', width: 200 },
  { title: '用户 ID', dataIndex: 'userId', width: 200 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 120 },
]
const tableWidth = columns.reduce((width, column) => width + column.width, 0)
let requestId = 0

const fetchData = async () => {
  const currentRequest = ++requestId
  loading.value = true
  try {
    const res = await listAllChatHistoryByPageForAdmin({ ...searchParams })
    if (currentRequest !== requestId) return
    if (res.data.code === 0 && res.data.data) {
      data.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
    } else message.error(`获取对话历史失败：${res.data.message || '未知错误'}`)
  } catch {
    if (currentRequest === requestId) message.error('获取对话历史失败，请检查后端服务')
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

const pagination = computed(() => ({
  current: searchParams.pageNum,
  pageSize: searchParams.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (value: number) => `共 ${value} 条`,
}))
const doSearch = () => {
  searchParams.pageNum = 1
  void fetchData()
}
const resetSearch = () => {
  Object.assign(searchParams, {
    pageNum: 1,
    pageSize: 10,
    id: undefined,
    appId: undefined,
    userId: undefined,
    message: undefined,
    messageType: undefined,
    sortField: 'createTime',
    sortOrder: 'descend',
  })
  void fetchData()
}
const tableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.pageNum = page.current ?? 1
  searchParams.pageSize = page.pageSize ?? 10
  void fetchData()
}
const showDetail = (record: API.ChatHistory) => {
  selectedRecord.value = record
  detailOpen.value = true
}
const formatTime = (value?: string) =>
  value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value),
      )
    : '-'
const messageTypeLabel = (value?: string) =>
  messageTypeOptions.find((option) => option.value === value)?.label ?? value ?? '-'

onMounted(fetchData)
</script>

<template>
  <main class="manage-page">
    <div class="page-title">
      <div>
        <h1>对话管理</h1>
        <p>查询平台中的对话历史，查看用户与 AI 的消息内容。</p>
      </div>
      <a-tag color="blue">{{ total }} 条消息</a-tag>
    </div>
    <a-card class="search-card" :bordered="false">
      <a-form layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="消息 ID">
          <a-input v-model:value="searchParams.id" placeholder="输入消息 ID" allow-clear />
        </a-form-item>
        <a-form-item label="应用 ID">
          <a-input v-model:value="searchParams.appId" placeholder="输入应用 ID" allow-clear />
        </a-form-item>
        <a-form-item label="用户 ID">
          <a-input v-model:value="searchParams.userId" placeholder="输入用户 ID" allow-clear />
        </a-form-item>
        <a-form-item label="消息内容">
          <a-input v-model:value="searchParams.message" placeholder="输入关键词" allow-clear />
        </a-form-item>
        <a-form-item label="消息类型">
          <a-select
            v-model:value="searchParams.messageType"
            :options="messageTypeOptions"
            placeholder="全部类型"
            allow-clear
            style="width: 120px"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit">搜索</a-button>
            <a-button @click="resetSearch">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>
    <a-table
      row-key="id"
      :columns="columns"
      :data-source="data"
      :loading="loading"
      :pagination="pagination"
      table-layout="fixed"
      :scroll="{ x: tableWidth }"
      @change="tableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'messageType'">
          <a-tag :color="record.messageType === 'ai' ? 'blue' : 'green'">
            {{ messageTypeLabel(record.messageType) }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'message'">
          {{ record.message || '（空消息）' }}
        </template>
        <template v-else-if="column.dataIndex === 'appId'">
          <RouterLink v-if="record.appId" :to="`/app/chat/${record.appId}`">
            {{ record.appId }}
          </RouterLink>
          <span v-else>-</span>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button size="small" @click="showDetail(record)"><EyeOutlined />详情</a-button>
        </template>
      </template>
    </a-table>
    <a-modal v-model:open="detailOpen" title="对话详情" :width="800" :footer="null">
      <a-descriptions v-if="selectedRecord" :column="1" bordered size="small">
        <a-descriptions-item label="消息 ID">{{ selectedRecord.id || '-' }}</a-descriptions-item>
        <a-descriptions-item label="应用 ID">{{ selectedRecord.appId || '-' }}</a-descriptions-item>
        <a-descriptions-item label="用户 ID">{{
          selectedRecord.userId || '-'
        }}</a-descriptions-item>
        <a-descriptions-item label="消息类型">
          {{ messageTypeLabel(selectedRecord.messageType) }}
        </a-descriptions-item>
        <a-descriptions-item label="创建时间">
          {{ formatTime(selectedRecord.createTime) }}
        </a-descriptions-item>
      </a-descriptions>
      <pre class="message-detail">{{ selectedRecord?.message || '（空消息）' }}</pre>
    </a-modal>
  </main>
</template>

<style scoped>
.manage-page {
  padding: 4px;
}
.page-title {
  display: flex;
  margin-bottom: 24px;
  align-items: center;
  justify-content: space-between;
}
.page-title h1 {
  margin: 4px 0;
  font-size: 30px;
}
.page-title p {
  margin: 0;
  color: #768398;
}
.search-card {
  margin-bottom: 18px;
  border-radius: 14px;
  box-shadow: 0 8px 28px #2441680d;
}
.search-card :deep(.ant-form) {
  row-gap: 14px;
}
.manage-page :deep(.ant-table-wrapper) {
  overflow: hidden;
  border-radius: 14px;
  box-shadow: 0 8px 28px #2441680d;
}
.message-detail {
  max-height: 55vh;
  margin: 18px 0 0;
  padding: 16px;
  overflow: auto;
  border: 1px solid #e8edf4;
  border-radius: 10px;
  background: #fbfcfe;
  color: #24324a;
  font: inherit;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .page-title {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
}
</style>
