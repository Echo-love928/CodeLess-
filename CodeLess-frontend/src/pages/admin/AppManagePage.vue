<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { DeleteOutlined, EditOutlined, StarOutlined } from '@ant-design/icons-vue'
import { deleteAppByAdmin, listAppVoByPageByAdmin, updateAppByAdmin } from '@/api/appController'
import { CODE_GEN_TYPE_OPTIONS, getCodeGenTypeLabel } from '@/constants/codeGenType'
import GenerationStatusTag from '@/components/GenerationStatusTag.vue'
import { getDeploymentLabel, isAppOnline } from '@/utils/appStatus'
import { useAppStatusPolling } from '@/composables/useAppStatusPolling'
import { reviewFeatured } from '@/api/appDiscoveryController'
import { FEATURE_STATUS_OPTIONS, featureLabel, visibilityLabel } from '@/utils/appPrivacy'

const router = useRouter()
const data = ref<API.AppVO[]>([])
useAppStatusPolling(() => data.value)
const total = ref(0)
const loading = ref(false)
const reviewOpen = ref(false)
const reviewing = ref(false)
const reviewApp = ref<API.AppVO>()
const reviewMessage = ref('')
const searchParams = reactive<API.AppQueryRequest>({ pageNum: 1, pageSize: 10 })
const columns = [
  { title: 'ID', dataIndex: 'id', width: 90 },
  { title: '应用', dataIndex: 'appName', width: 260 },
  { title: '用户名', key: 'userName', width: 130 },
  { title: '可见范围', dataIndex: 'visibility', width: 90 },
  { title: '精选申请', dataIndex: 'featureStatus', width: 100 },
  { title: '生成类型', dataIndex: 'codeGenType', width: 130 },
  { title: '生成状态', dataIndex: 'generationStatus', width: 100 },
  { title: '部署状态', key: 'deploymentStatus', width: 100 },
  { title: '优先级', dataIndex: 'priority', width: 120, className: 'priority-column' },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 320, className: 'action-column' },
]
const tableWidth = columns.reduce((width, column) => width + column.width, 0)

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listAppVoByPageByAdmin({ ...searchParams })
    if (res.data.code === 0 && res.data.data) {
      data.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
    } else message.error(`获取应用失败：${res.data.message || '未知错误'}`)
  } catch {
    message.error('获取应用失败，请检查后端服务')
  } finally {
    loading.value = false
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
    appName: undefined,
    codeGenType: undefined,
    userId: undefined,
    priority: undefined,
    visibility: undefined,
    featureStatus: undefined,
    searchText: undefined,
    tag: undefined,
  })
  void fetchData()
}
const tableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.pageNum = page.current ?? 1
  searchParams.pageSize = page.pageSize ?? 10
  void fetchData()
}

const removeApp = (record: API.AppVO) => {
  Modal.confirm({
    title: '删除应用',
    content: `确定删除“${record.appName || '未命名应用'}”吗？生成代码、封面、全部历史版本和部署文件将一并清除，应用会下线。此操作无法恢复。正在生成时请先停止生成。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      if (!record.id) return
      try {
        const res = await deleteAppByAdmin({ id: record.id })
        if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '删除失败')
        message.success('删除成功')
        await fetchData()
      } catch (error) {
        message.error(error instanceof Error ? error.message : '删除失败，请稍后重试')
        throw error
      }
    },
  })
}

const featureApp = (record: API.AppVO) => {
  Modal.confirm({
    centered: true,
    wrapClassName: 'feature-app-confirm',
    icon: null,
    title: '是否设为精选应用？',
    okText: '设为精选',
    cancelText: '取消',
    async onOk() {
      if (!record.id) return
      const res = await updateAppByAdmin({ id: record.id, priority: 99 })
      if (res.data.code === 0 && res.data.data) {
        message.success('已设为精选')
        await fetchData()
      } else throw new Error(res.data.message || '操作失败')
    },
  })
}

const formatTime = (value?: string) =>
  value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value),
      )
    : '-'
const openReview = (app: API.AppVO) => {
  reviewApp.value = app
  reviewMessage.value = ''
  reviewOpen.value = true
}
const submitReview = async (approved: boolean) => {
  if (!reviewApp.value?.id || reviewing.value) return
  reviewing.value = true
  try {
    const res = await reviewFeatured(reviewApp.value.id, approved, reviewMessage.value.trim())
    if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '审核失败')
    message.success(approved ? '已通过，应用将展示在精选区' : '已拒绝申请')
    reviewOpen.value = false
    await fetchData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '审核失败，请重试')
  } finally {
    reviewing.value = false
  }
}
onMounted(fetchData)
</script>

<template>
  <main class="manage-page">
    <div class="page-title">
      <div>
        <h1>应用管理</h1>
        <p>查看、编辑、精选或删除平台中的应用。</p>
      </div>
      <a-tag color="blue">{{ total }} 个应用</a-tag>
    </div>
    <a-card class="search-card" :bordered="false">
      <a-form layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="应用 ID"
          ><a-input v-model:value="searchParams.id" placeholder="输入 ID"
        /></a-form-item>
        <a-form-item label="应用名称"
          ><a-input v-model:value="searchParams.appName" placeholder="输入名称" allow-clear
        /></a-form-item>
        <a-form-item label="综合搜索"
          ><a-input
            v-model:value="searchParams.searchText"
            placeholder="名称、描述、标签"
            :maxlength="100"
            allow-clear
        /></a-form-item>
        <a-form-item label="标签"
          ><a-input v-model:value="searchParams.tag" placeholder="精确标签" allow-clear
        /></a-form-item>
        <a-form-item label="可见范围"
          ><a-select
            v-model:value="searchParams.visibility"
            :options="[
              { label: '公开', value: 'public' },
              { label: '私有', value: 'private' },
            ]"
            placeholder="全部"
            allow-clear
            style="width: 110px"
        /></a-form-item>
        <a-form-item label="精选申请"
          ><a-select
            v-model:value="searchParams.featureStatus"
            :options="FEATURE_STATUS_OPTIONS"
            placeholder="全部"
            allow-clear
            style="width: 120px"
        /></a-form-item>
        <a-form-item label="用户 ID"
          ><a-input v-model:value="searchParams.userId" placeholder="输入用户 ID"
        /></a-form-item>
        <a-form-item label="生成类型"
          ><a-select
            v-model:value="searchParams.codeGenType"
            :options="CODE_GEN_TYPE_OPTIONS"
            placeholder="全部类型"
            allow-clear
            style="width: 180px"
        /></a-form-item>
        <a-form-item
          ><a-space
            ><a-button type="primary" html-type="submit">搜索</a-button
            ><a-button @click="resetSearch">重置</a-button></a-space
          ></a-form-item
        >
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
        <template v-if="column.dataIndex === 'appName'"
          ><div class="app-cell">
            <a-avatar shape="square" :src="record.cover">{{
              record.appName?.slice(0, 1) || '应'
            }}</a-avatar>
            <div>
              <strong>{{ record.appName || '未命名应用' }}</strong
              ><small>{{ record.description || '暂无应用描述' }}</small>
            </div>
          </div></template
        >
        <template v-else-if="column.dataIndex === 'codeGenType'"
          ><a-tag>{{ getCodeGenTypeLabel(record.codeGenType) }}</a-tag></template
        >
        <template v-else-if="column.key === 'userName'">{{
          record.user?.userName?.trim() || record.user?.userAccount || '未知用户'
        }}</template>
        <template v-else-if="column.dataIndex === 'generationStatus'"
          ><GenerationStatusTag :status="record.generationStatus"
        /></template>
        <template v-else-if="column.dataIndex === 'visibility'"
          ><a-tag :color="record.visibility === 'public' ? 'cyan' : 'default'">{{
            visibilityLabel(record.visibility)
          }}</a-tag></template
        >
        <template v-else-if="column.dataIndex === 'featureStatus'"
          ><a-tag
            :color="
              record.featureStatus === 'pending'
                ? 'processing'
                : record.featureStatus === 'approved'
                  ? 'green'
                  : 'default'
            "
            >{{ featureLabel(record.featureStatus) }}</a-tag
          ></template
        >
        <template v-else-if="column.key === 'deploymentStatus'"
          ><a-tag :color="isAppOnline(record) ? 'green' : 'default'">{{
            getDeploymentLabel(record)
          }}</a-tag></template
        >
        <template v-else-if="column.dataIndex === 'priority'"
          ><a-tag :color="record.priority === 99 ? 'gold' : 'default'"
            >{{ record.priority ?? 0 }}<span v-if="record.priority === 99"> · 精选</span></a-tag
          ></template
        >
        <template v-else-if="column.dataIndex === 'createTime'">{{
          formatTime(record.createTime)
        }}</template>
        <template v-else-if="column.key === 'action'"
          ><div class="app-actions">
            <a-button
              v-if="record.featureStatus === 'pending'"
              size="small"
              type="primary"
              @click="openReview(record)"
              >审核</a-button
            ><a-button size="small" @click="router.push(`/admin/app/edit/${record.id}`)"
              ><EditOutlined />编辑</a-button
            ><a-button
              size="small"
              :disabled="record.priority === 99 || record.visibility !== 'public'"
              @click="featureApp(record)"
              ><StarOutlined />精选</a-button
            ><a-button size="small" danger @click="removeApp(record)"
              ><DeleteOutlined />删除</a-button
            >
          </div></template
        >
      </template>
    </a-table>
  </main>
  <a-modal
    v-model:open="reviewOpen"
    title="审核精选申请"
    :mask-closable="!reviewing"
    :closable="!reviewing"
    centered
  >
    <h3>{{ reviewApp?.appName }}</h3>
    <p>{{ reviewApp?.description || '暂无应用描述' }}</p>
    <p>
      <a-tag v-for="tag in reviewApp?.tags" :key="tag">{{ tag }}</a-tag>
    </p>
    <a-alert
      type="info"
      :message="reviewApp?.featureRequestMessage || '申请人未填写说明'"
      show-icon
    />
    <a-textarea
      v-model:value="reviewMessage"
      :maxlength="500"
      :rows="3"
      show-count
      placeholder="审核反馈（拒绝时建议说明需要改进的地方）"
      aria-label="审核反馈"
      style="margin-top: 18px"
    />
    <template #footer
      ><a-button :disabled="reviewing" @click="reviewOpen = false">取消</a-button
      ><a-button danger :loading="reviewing" @click="submitReview(false)">拒绝</a-button
      ><a-button type="primary" :loading="reviewing" @click="submitReview(true)"
        >通过并推荐</a-button
      ></template
    >
  </a-modal>
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
.page-title span {
  color: #168c83;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
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
.app-cell {
  display: flex;
  min-width: 220px;
  align-items: center;
  gap: 10px;
}
.manage-page :deep(.priority-column) {
  white-space: nowrap;
}
.manage-page :deep(.priority-column .ant-tag) {
  margin-inline-end: 0;
}
.manage-page :deep(.action-column) {
  border-left: 1px solid #f0f0f0;
}
.app-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.app-actions .ant-btn {
  flex: none;
}
.app-cell .ant-avatar {
  flex: none;
  background: #e7f4f2;
  color: #168c83;
}
.app-cell div {
  min-width: 0;
}
.app-cell strong,
.app-cell small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.app-cell small {
  max-width: 185px;
  margin-top: 3px;
  color: #919bad;
  font-size: 11px;
}
@media (max-width: 600px) {
  .page-title {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
}
</style>

<style>
.feature-app-confirm .ant-modal-confirm-title {
  display: block;
  width: 100%;
  padding: 22px 0 16px;
  text-align: center;
}
.feature-app-confirm .ant-modal-confirm-btns {
  display: flex;
  justify-content: center;
  float: none;
}
</style>
