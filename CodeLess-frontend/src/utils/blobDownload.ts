export const downloadBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  try {
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
  } finally {
    link.remove()
    // 留出浏览器开始下载的时间，再释放临时 URL。
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}
