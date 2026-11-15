import { onBeforeUnmount, watch } from 'vue'
import { getAppVoById } from '@/api/appController'

/** Refresh only applications that are actively generating, without flashing page loading states. */
export function useAppStatusPolling(records: () => API.AppVO[]) {
  let timer: number | undefined
  let pending = false
  let disposed = false
  watch(
    () => records().some((app) => app.generationStatus === 'generating'),
    (active) => {
      window.clearInterval(timer)
      timer = active
        ? window.setInterval(async () => {
            if (pending || disposed) return
            pending = true
            try {
              const ids = [
                ...new Set(
                  records()
                    .filter((app) => app.id && app.generationStatus === 'generating')
                    .map((app) => app.id!),
                ),
              ]
              await Promise.all(
                ids.map(async (id) => {
                  const res = await getAppVoById({ id })
                  if (disposed || res.data.code !== 0 || !res.data.data) return
                  for (const app of records()) if (app.id === id) Object.assign(app, res.data.data)
                }),
              )
            } catch {
              /* A transient status failure is retried on the next tick. */
            } finally {
              pending = false
            }
          }, 3000)
        : undefined
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    disposed = true
    window.clearInterval(timer)
  })
}
