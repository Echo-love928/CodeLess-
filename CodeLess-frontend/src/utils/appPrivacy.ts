export const VISIBILITY_OPTIONS = [
  { label: '私有 · 仅本人和管理员', value: 'private' },
  { label: '公开 · 所有人可见', value: 'public' },
]
export const FEATURE_STATUS_OPTIONS = [
  { label: '未申请', value: 'none' },
  { label: '待审核', value: 'pending' },
  { label: '已通过', value: 'approved' },
  { label: '已拒绝', value: 'rejected' },
]
export const featureLabel = (value?: string) =>
  FEATURE_STATUS_OPTIONS.find((item) => item.value === value)?.label ?? '未申请'
export const visibilityLabel = (value?: string) => (value === 'public' ? '公开' : '私有')
