<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  CopyOutlined,
  HistoryOutlined,
  RollbackOutlined,
  SwapOutlined,
} from '@ant-design/icons-vue'
import {
  getAppVersion,
  listAppVersions,
  restoreAppVersion,
  type AppVersion,
  type VersionDetail,
} from '@/api/appVersionController'
import { compareLines } from '@/utils/lineDiff'

const props = defineProps<{
  open: boolean
  appId: string
  currentVersion?: number
  deployedVersion?: number
  canRestore: boolean
  generating: boolean
}>()
const emit = defineEmits<{ 'update:open': [value: boolean]; restored: []; loaded: [] }>()
const modalOpen = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value),
})
const versions = ref<AppVersion[]>([])
const loading = ref(false)
const comparing = ref(false)
const restoring = ref(false)
const error = ref('')
const comparisonError = ref('')
const showChangesOnly = ref(true)
const rowLimit = ref(800)
const from = ref<number>()
const to = ref<number>()
const file = ref('index.html')
const before = ref<VersionDetail>()
const after = ref<VersionDetail>()
const cache = new Map<string, VersionDetail>()
let listSequence = 0
let compareSequence = 0
const effectiveCurrent = computed(() => props.currentVersion || versions.value[0]?.version || 0)
const options = computed(() =>
  versions.value.map((item) => ({
    value: item.version,
    label: `V${item.version}${item.version === effectiveCurrent.value ? ' · 当前版本' : ''}`,
  })),
)
const filenames = computed(() => [
  ...new Set([...Object.keys(before.value?.files ?? {}), ...Object.keys(after.value?.files ?? {})]),
])
const comparison = computed(() =>
  compareLines(before.value?.files[file.value] ?? '', after.value?.files[file.value] ?? ''),
)
const visibleRows = computed(() => {
  const rows = comparison.value.rows
  if (!showChangesOnly.value || !rows.some((row) => row.changed)) return rows
  return rows.filter(
    (row, index) =>
      row.changed || rows.slice(Math.max(0, index - 3), index + 4).some((nearby) => nearby.changed),
  )
})
const formatTime = (value: string) =>
  new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(value),
  )

const loadVersions = async () => {
  const sequence = ++listSequence
  loading.value = true
  error.value = ''
  try {
    const res = await listAppVersions(props.appId)
    if (sequence !== listSequence || !props.open) return
    if (res.data.code !== 0) throw new Error(res.data.message || '获取版本历史失败')
    versions.value = res.data.data ?? []
    const previousFrom = from.value
    const previousTo = to.value
    const latest = versions.value[0]?.version
    if (!versions.value.some((item) => item.version === to.value))
      to.value = effectiveCurrent.value || latest
    if (!versions.value.some((item) => item.version === from.value))
      from.value = versions.value.find((item) => item.version !== to.value)?.version ?? latest
    emit('loaded')
    if (previousFrom === from.value && previousTo === to.value) void loadComparison()
  } catch (cause) {
    if (sequence === listSequence)
      error.value = cause instanceof Error ? cause.message : '获取版本历史失败'
  } finally {
    if (sequence === listSequence) loading.value = false
  }
}

const readVersion = async (version: number) => {
  const appId = props.appId
  const key = `${appId}:${version}`
  const cached = cache.get(key)
  if (cached) return cached
  const res = await getAppVersion(appId, version)
  if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '读取版本代码失败')
  if (cache.size >= 6) cache.delete(cache.keys().next().value!)
  cache.set(key, res.data.data)
  return res.data.data
}

const loadComparison = async () => {
  const sequence = ++compareSequence
  if (!props.open || from.value === undefined || to.value === undefined) return
  comparing.value = true
  comparisonError.value = ''
  try {
    const result = await Promise.all([readVersion(from.value), readVersion(to.value)])
    if (sequence !== compareSequence || !props.open) return
    before.value = result[0]
    after.value = result[1]
    if (!filenames.value.includes(file.value)) file.value = filenames.value[0] ?? 'index.html'
  } catch (cause) {
    if (sequence === compareSequence)
      comparisonError.value = cause instanceof Error ? cause.message : '对比加载失败'
  } finally {
    if (sequence === compareSequence) comparing.value = false
  }
}

const selectVersion = (version: number) => {
  to.value = version
  if (from.value === version)
    from.value = versions.value.find((item) => item.version !== version)?.version ?? version
}

const swapVersions = () => {
  const previous = from.value
  from.value = to.value
  to.value = previous
}

const copy = async (side: 'before' | 'after') => {
  try {
    await navigator.clipboard.writeText(
      (side === 'before' ? before.value : after.value)?.files[file.value] ?? '',
    )
    message.success('代码已复制')
  } catch {
    message.error('复制失败，请手动选择代码复制')
  }
}

const restore = () => {
  const version = to.value
  if (
    !version ||
    version === effectiveCurrent.value ||
    !props.canRestore ||
    props.generating ||
    restoring.value
  )
    return
  Modal.confirm({
    centered: true,
    title: `回退到 V${version}？`,
    content:
      '当前代码和预览将切换到此版本，所有历史版本都会保留。线上应用需点击“更新部署”后才会更新。',
    okText: '确认回退',
    cancelText: '取消',
    async onOk() {
      restoring.value = true
      try {
        const res = await restoreAppVersion(props.appId, version)
        if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '回退失败')
        message.success(`已回退到 V${version}`)
        emit('restored')
        await loadVersions()
      } catch (cause) {
        message.error(cause instanceof Error ? cause.message : '回退失败，请重试')
        throw cause
      } finally {
        restoring.value = false
      }
    },
  })
}

watch(
  () => [props.open, props.appId] as const,
  ([open], previous) => {
    if (previous?.[1] !== props.appId) {
      cache.clear()
      from.value = undefined
      to.value = undefined
      versions.value = []
    }
    if (open) void loadVersions()
    else {
      listSequence++
      compareSequence++
    }
  },
  { immediate: true },
)
watch(
  () => props.currentVersion,
  () => {
    if (props.open) void loadVersions()
  },
)
watch([from, to], () => void loadComparison())
watch([from, to, file, showChangesOnly], () => {
  rowLimit.value = 800
})
</script>

<template>
  <a-modal
    v-model:open="modalOpen"
    title="版本历史与对比"
    width="min(1380px, 96vw)"
    :footer="null"
    :style="{ top: '24px' }"
    :mask-closable="!restoring"
    :closable="!restoring"
  >
    <div class="version-summary">
      <span><HistoryOutlined /> 每次成功生成都会保存一个版本</span
      ><span
        >当前 V{{ effectiveCurrent
        }}<template v-if="deployedVersion"> · 线上 V{{ deployedVersion }}</template></span
      >
    </div>
    <a-alert v-if="error" type="error" :message="error" show-icon
      ><template #action
        ><a-button size="small" @click="loadVersions">重试</a-button></template
      ></a-alert
    >
    <a-spin :spinning="loading">
      <div v-if="!loading && !error && !versions.length" class="version-empty">
        <a-empty description="还没有版本记录，成功生成应用后会自动保存" />
      </div>
      <div v-else-if="versions.length" class="version-workspace">
        <aside class="version-history" aria-label="历史版本">
          <div class="history-heading">
            全部版本 <span>{{ versions.length }}</span>
          </div>
          <div class="history-items">
            <button
              v-for="item in versions"
              :key="item.version"
              type="button"
              :class="['version-item', { selected: to === item.version }]"
              :aria-pressed="to === item.version"
              @click="selectVersion(item.version)"
            >
              <div class="version-item__heading">
                <strong>V{{ item.version }}</strong
                ><a-tag v-if="item.version === effectiveCurrent" color="blue">当前</a-tag
                ><span v-else>{{ item.source === 'imported' ? '历史导入' : 'AI 生成' }}</span>
              </div>
              <time>{{ formatTime(item.createTime) }}</time>
              <p>{{ item.prompt || '初始应用代码' }}</p>
              <small v-if="item.parentVersion">基于 V{{ item.parentVersion }} 生成</small>
            </button>
          </div>
        </aside>
        <section class="version-compare" aria-label="版本代码对比">
          <div class="compare-controls">
            <div class="compare-selectors">
              <label
                >旧版本<a-select
                  v-model:value="from"
                  :options="options"
                  aria-label="旧版本" /></label
              ><a-button aria-label="交换比较版本" @click="swapVersions"><SwapOutlined /></a-button
              ><label
                >新版本<a-select v-model:value="to" :options="options" aria-label="新版本"
              /></label>
            </div>
            <a-button
              v-if="canRestore"
              :loading="restoring"
              :disabled="generating || to === effectiveCurrent || !to"
              @click="restore"
              ><RollbackOutlined />回退到 V{{ to }}</a-button
            >
          </div>
          <p v-if="generating" class="compare-hint" role="status">
            应用正在生成，可查看历史；停止或完成生成后可以回退。
          </p>
          <a-tabs v-model:active-key="file" size="small" class="file-tabs"
            ><a-tab-pane v-for="name in filenames" :key="name" :tab="name"
          /></a-tabs>
          <a-checkbox v-model:checked="showChangesOnly" class="compare-hint"
            >只显示差异及上下文</a-checkbox
          >
          <a-alert v-if="comparisonError" type="error" :message="comparisonError" show-icon
            ><template #action
              ><a-button size="small" @click="loadComparison">重试</a-button></template
            ></a-alert
          >
          <a-spin :spinning="comparing" class="compare-spin">
            <template v-if="!comparisonError && before && after">
              <div class="diff-headings">
                <div>
                  <span class="removed-count">− {{ comparison.removals }} 行删除</span
                  ><span>V{{ from }}</span
                  ><a-button type="text" size="small" @click="copy('before')"
                    ><CopyOutlined />复制</a-button
                  >
                </div>
                <div>
                  <span class="added-count">+ {{ comparison.additions }} 行新增</span
                  ><span>V{{ to }}</span
                  ><a-button type="text" size="small" @click="copy('after')"
                    ><CopyOutlined />复制</a-button
                  >
                </div>
              </div>
              <p v-if="comparison.grouped" class="compare-hint">变更内容较大，已按整段对齐展示。</p>
              <p v-else-if="!comparison.additions && !comparison.removals" class="compare-hint">
                {{ from === to ? '当前选择的是同一版本。' : '此文件在两个版本中没有变化。' }}
              </p>
              <div class="diff-scroll" tabindex="0" aria-label="代码差异，左侧旧版本，右侧新版本">
                <div
                  v-for="(row, index) in visibleRows.slice(0, rowLimit)"
                  :key="index"
                  class="diff-row"
                >
                  <div :class="['diff-cell', { removed: row.changed && row.left, gap: !row.left }]">
                    <span class="diff-sign" aria-hidden="true">{{
                      row.changed && row.left ? '−' : ''
                    }}</span
                    ><span class="line-number">{{ row.left?.number }}</span
                    ><code>{{ row.left?.text || '\u00a0' }}</code>
                  </div>
                  <div :class="['diff-cell', { added: row.changed && row.right, gap: !row.right }]">
                    <span class="diff-sign" aria-hidden="true">{{
                      row.changed && row.right ? '+' : ''
                    }}</span
                    ><span class="line-number">{{ row.right?.number }}</span
                    ><code>{{ row.right?.text || '\u00a0' }}</code>
                  </div>
                </div>
                <a-button v-if="visibleRows.length > rowLimit" block @click="rowLimit += 800"
                  >显示更多代码（还有 {{ visibleRows.length - rowLimit }} 行）</a-button
                >
                <a-empty v-if="!comparison.rows.length" description="文件内容为空" />
              </div>
            </template>
          </a-spin>
        </section>
      </div>
    </a-spin>
  </a-modal>
</template>

<style scoped>
.version-summary {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 0 18px;
  color: #68758b;
  font-size: 13px;
}
.version-summary .anticon {
  margin-right: 6px;
}
.version-workspace {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  min-height: 460px;
  height: 72vh;
  max-height: 850px;
  border: 1px solid #e4e9f1;
  border-radius: 12px;
  overflow: hidden;
}
.version-history {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid #e4e9f1;
  background: #f8fafc;
}
.history-heading {
  display: flex;
  justify-content: space-between;
  padding: 17px 16px;
  color: #435169;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid #e4e9f1;
}
.history-heading span {
  color: #8490a2;
}
.history-items {
  overflow: auto;
  padding: 10px;
}
.version-item {
  display: block;
  width: 100%;
  margin-bottom: 8px;
  padding: 13px 12px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  text-align: left;
  color: #34425a;
  cursor: pointer;
}
.version-item:hover {
  background: #edf3fb;
}
.version-item.selected {
  border-color: #a9c7f2;
  background: #eaf2ff;
}
.version-item:focus-visible {
  outline: 2px solid #2468df;
  outline-offset: 2px;
}
.version-item__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.version-item__heading span,
.version-item time,
.version-item small {
  font-size: 12px;
  color: #748198;
}
.version-item .ant-tag {
  margin: 0;
  font-size: 11px;
}
.version-item p {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 9px 0 5px;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.version-compare {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  padding: 16px;
  background: #fff;
}
.compare-controls,
.compare-selectors {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}
.compare-controls {
  justify-content: space-between;
}
.compare-selectors label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12px;
  color: #748198;
}
.compare-selectors .ant-select {
  width: 165px;
}
.file-tabs {
  margin-top: 10px;
  flex: none;
}
.file-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 12px;
}
.compare-hint {
  margin: 6px 0;
  color: #738097;
  font-size: 12px;
}
.compare-spin {
  flex: 1;
  min-height: 0;
}
.compare-spin :deep(.ant-spin-container) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.diff-headings {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border: 1px solid #e4e9f1;
  border-radius: 8px 8px 0 0;
  background: #fafbfd;
}
.diff-headings > div {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  font-size: 12px;
}
.diff-headings > div + div {
  border-left: 1px solid #e4e9f1;
}
.diff-headings .ant-btn {
  margin-left: auto;
}
.removed-count {
  color: #b3332a;
}
.added-count {
  color: #197549;
}
.diff-scroll {
  min-height: 0;
  overflow: auto;
  flex: 1;
  border: 1px solid #e4e9f1;
  border-top: 0;
  border-radius: 0 0 8px 8px;
  padding: 8px 0;
}
.diff-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-width: 580px;
}
.diff-cell {
  display: grid;
  grid-template-columns: 18px 35px minmax(0, 1fr);
  padding: 2px 8px 2px 2px;
  line-height: 21px;
  min-width: 0;
}
.diff-cell + .diff-cell {
  border-left: 1px solid #e9edf3;
}
.diff-sign {
  text-align: center;
  color: inherit;
  user-select: none;
}
.line-number {
  padding-right: 9px;
  text-align: right;
  color: #8994a5;
  font-size: 11px;
  user-select: none;
}
.diff-cell code {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: #2c394d;
  font-size: 12px;
  font-family: 'Cascadia Code', Consolas, monospace;
  tab-size: 2;
}
.diff-cell.removed {
  background: #fff0ef;
  color: #b3332a;
}
.diff-cell.added {
  background: #eaf8f0;
  color: #197549;
}
.diff-cell.gap {
  background: #f3f5f7;
}
.version-empty {
  padding: 90px 0;
}
@media (max-width: 850px) {
  .version-workspace {
    grid-template-columns: 1fr;
    height: 78vh;
  }
  .version-history {
    max-height: 175px;
    border-right: 0;
    border-bottom: 1px solid #e4e9f1;
  }
  .history-heading {
    padding: 8px 12px;
  }
  .history-items {
    display: flex;
    gap: 8px;
    overflow: auto;
  }
  .version-item {
    min-width: 200px;
    width: 200px;
    margin: 0;
    padding: 8px;
  }
  .version-item p {
    -webkit-line-clamp: 1;
    margin: 4px 0;
  }
  .version-compare {
    padding: 10px;
  }
  .compare-selectors .ant-select {
    width: 130px;
  }
}
</style>
