(() => {
  if (window.parent === window) return

  const channel = 'codeless:visual-editor'
  const hoverClass = 'codeless-editor-hover'
  const selectedClass = 'codeless-editor-selected'
  let parentOrigin
  let revision
  let editing = false
  let hovered = null
  let selected = null
  const style = document.createElement('style')
  style.textContent = '.codeless-editor-hover{outline:2px solid #5b9bff !important;' +
    'outline-offset:-2px !important;cursor:crosshair !important}' +
    '.codeless-editor-selected{outline:3px solid #1246ad !important;outline-offset:-3px !important}'

  const send = (action, details = {}) => {
    window.parent.postMessage({ channel, action, revision, ...details }, parentOrigin)
  }

  const setHovered = (element) => {
    if (hovered === element) return
    hovered?.classList.remove(hoverClass)
    hovered = element
    hovered?.classList.add(hoverClass)
  }

  const setSelected = (element) => {
    selected?.classList.remove(selectedClass)
    selected = element
    selected?.classList.add(selectedClass)
  }

  const observer = new MutationObserver(() => {
    if (selected && !selected.isConnected) {
      setSelected(null)
      send('selection', { element: null })
    }
  })

  const setEditing = (active) => {
    editing = active
    setHovered(null)
    setSelected(null)
    if (active) {
      if (!style.isConnected) document.head.append(style)
      observer.observe(document.body, { childList: true, subtree: true })
    } else {
      style.remove()
      observer.disconnect()
    }
  }

  const targetElement = (event) => {
    const element = event.composedPath().find((target) => target instanceof Element)
    return element && element !== document.documentElement && element !== document.body
      ? element
      : null
  }

  const selectorFor = (element) => {
    let selector = ''
    while (element && element !== document.documentElement) {
      if (element.id) {
        const idSelector = '#' + CSS.escape(element.id)
        if (idSelector.length <= 320 && document.querySelectorAll(idSelector).length === 1) {
          const path = selector ? idSelector + ' > ' + selector : idSelector
          if (path.length <= 320) return path
        }
      }
      let position = 1
      for (let sibling = element.previousElementSibling; sibling; sibling = sibling.previousElementSibling) {
        if (sibling.localName === element.localName) position++
      }
      const segment = CSS.escape(element.localName) + ':nth-of-type(' + position + ')'
      const path = selector ? segment + ' > ' + selector : segment
      if (path.length > 320) break
      selector = path
      element = element.parentElement
    }
    return selector
  }

  const select = (event) => {
    event.preventDefault()
    event.stopImmediatePropagation()
    const element = targetElement(event)
    if (!element) return
    const tag = element.localName.slice(0, 40)
    const selector = selectorFor(element)
    const text = (element.getAttribute('aria-label') || element.getAttribute('alt') || element.textContent || '')
      .replace(/\s+/g, ' ').trim().slice(0, 120)
    setSelected(element)
    send('selection', { element: { tag, selector, text } })
  }

  window.addEventListener('pointerover', (event) => {
    if (editing) setHovered(targetElement(event))
  }, true)
  window.addEventListener('pointerout', (event) => {
    if (editing && !event.relatedTarget) setHovered(null)
  }, true)
  window.addEventListener('click', (event) => {
    if (editing) select(event)
  }, true)
  for (const name of ['auxclick', 'dblclick', 'contextmenu']) {
    window.addEventListener(name, (event) => {
      if (!editing) return
      event.preventDefault()
      event.stopImmediatePropagation()
    }, true)
  }
  window.addEventListener('keydown', (event) => {
    if (!editing) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopImmediatePropagation()
      setEditing(false)
      send('exit')
    } else if (event.key === 'Enter' || event.key === ' ') {
      select(event)
    }
  }, true)

  window.addEventListener('message', (event) => {
    const data = event.data
    if (event.source !== window.parent || data?.channel !== channel || !Number.isSafeInteger(data.revision))
      return
    if (data.action === 'connect') {
      if (event.origin === 'null' || new URL(event.origin).hostname !== window.location.hostname) return
      parentOrigin = event.origin
      revision = data.revision
      setEditing(false)
      send('ready')
    } else if (event.origin === parentOrigin && data.action === 'set-editing' && data.revision > revision) {
      revision = data.revision
      setEditing(data.editing === true)
    }
  })
})()
