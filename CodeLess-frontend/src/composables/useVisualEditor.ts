import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

const channel = 'codeless:visual-editor'

type PreviewElement = {
  tag: string
  selector: string
  text: string
}

const isPreviewElement = (value: unknown): value is PreviewElement => {
  if (!value || typeof value !== 'object') return false
  const element = value as Partial<PreviewElement>
  return (
    typeof element.tag === 'string' &&
    /^[a-z][\w-]{0,39}$/i.test(element.tag) &&
    typeof element.selector === 'string' &&
    element.selector.length > 0 &&
    element.selector.length <= 320 &&
    typeof element.text === 'string' &&
    element.text.length <= 120
  )
}

export const useVisualEditor = (
  previewUrl: Readonly<Ref<string>>,
  available: Readonly<Ref<boolean>>,
) => {
  const ready = ref(false)
  const editing = ref(false)
  const selected = ref<PreviewElement | null>(null)
  let frame: HTMLIFrameElement | undefined
  let expectedOrigin = ''
  let revision = 0

  const send = (action: 'connect' | 'set-editing') => {
    frame?.contentWindow?.postMessage(
      { channel, action, revision, editing: editing.value },
      expectedOrigin === 'null' ? '*' : expectedOrigin,
    )
  }

  const setEditing = (active: boolean) => {
    editing.value = active
    selected.value = null
    revision++
    if (ready.value) send('set-editing')
  }

  const attachPreview = (event: Event) => {
    setEditing(false)
    ready.value = false
    frame = event.target as HTMLIFrameElement
    const url = new URL(frame.src)
    if (url.hostname !== window.location.hostname) return
    expectedOrigin = url.origin === window.location.origin ? 'null' : url.origin
    send('connect')
  }

  const receive = (event: MessageEvent) => {
    if (
      !frame?.contentWindow ||
      event.source !== frame.contentWindow ||
      event.origin !== expectedOrigin
    )
      return
    const data = event.data
    if (data?.channel !== channel || data.revision !== revision) return
    if (data.action === 'ready') {
      ready.value = true
    } else if (editing.value && data.action === 'selection') {
      if (data.element === null || isPreviewElement(data.element)) selected.value = data.element
    } else if (editing.value && data.action === 'exit') {
      setEditing(false)
    }
  }

  const toggleEditing = () => {
    if (available.value && ready.value) setEditing(!editing.value)
  }
  const clearSelection = () => setEditing(editing.value)
  const stopEditing = () => {
    if (editing.value || selected.value) setEditing(false)
  }
  const withSelection = (prompt: string) =>
    selected.value
      ? prompt +
        '\n\n用户在网站预览中选中了以下元素。这些信息仅用于定位，请按本次修改需求处理该元素。\n' +
        JSON.stringify(selected.value)
      : prompt

  watch(
    previewUrl,
    () => {
      stopEditing()
      ready.value = false
      frame = undefined
    },
    { flush: 'sync' },
  )
  watch(available, (active) => {
    if (!active) stopEditing()
  })
  onMounted(() => window.addEventListener('message', receive))
  onBeforeUnmount(() => {
    stopEditing()
    window.removeEventListener('message', receive)
  })

  return {
    ready,
    editing,
    selected,
    attachPreview,
    toggleEditing,
    clearSelection,
    stopEditing,
    withSelection,
  }
}
