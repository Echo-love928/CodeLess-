export const generationStatuses: Record<string, { label: string; color: string }> = {
  pending: { label: '待生成', color: 'default' },
  generating: { label: '生成中', color: 'processing' },
  completed: { label: '已完成', color: 'success' },
  stopped: { label: '已停止', color: 'warning' },
  failed: { label: '生成失败', color: 'error' },
  unknown: { label: '待确认', color: 'default' },
}

export const getGenerationStatus = (status?: string) =>
  generationStatuses[status ?? 'unknown'] ?? generationStatuses.unknown!

export const isAppOnline = (app?: API.AppVO) =>
  Boolean(app?.deployKey?.trim()) && (app?.deploymentStatus === 'online' || !app?.deploymentStatus)

export const getDeploymentLabel = (app?: API.AppVO) =>
  isAppOnline(app) ? '已上线' : app?.deployKey ? '已下线' : '未部署'
