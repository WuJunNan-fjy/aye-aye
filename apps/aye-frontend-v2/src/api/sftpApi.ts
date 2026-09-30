import request, { buildDownloadUrl, authHeaders } from '@/api/request'

export interface SftpFile {
  name: string
  path: string
  type: 'directory' | 'file'
  size: number
  modifiedTime: number
}

/** 触发浏览器下载（后端 GET /sftp/download 返回文件字节流） */
async function downloadWithFetch(sessionId: string, path: string) {
  const url = buildDownloadUrl(sessionId, path)
  const res = await fetch(url, { headers: await authHeaders() })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `下载失败：HTTP ${res.status}`)
  }
  const blob = await res.blob()
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = path.substring(path.lastIndexOf('/') + 1)
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(objectUrl)
}

export default {
  list: (sessionId: string, path: string) =>
    request.get(`/sftp/list/${sessionId}?path=${encodeURIComponent(path)}`) as Promise<SftpFile[]>,

  readText: (sessionId: string, path: string, maxBytes = 65536) =>
    request.get(`/sftp/read/${sessionId}?path=${encodeURIComponent(path)}&maxBytes=${maxBytes}`) as Promise<{ content: string }>,

  writeText: (sessionId: string, path: string, content: string) =>
    request.post(`/sftp/write/${sessionId}?path=${encodeURIComponent(path)}`, { content }) as Promise<{ status: string }>,

  mkdir: (sessionId: string, path: string) =>
    request.post(`/sftp/mkdir/${sessionId}?path=${encodeURIComponent(path)}`, {}) as Promise<{ status: string }>,

  rename: (sessionId: string, oldPath: string, newPath: string) =>
    request.post(`/sftp/rename/${sessionId}?oldPath=${encodeURIComponent(oldPath)}&newPath=${encodeURIComponent(newPath)}`, {}) as Promise<{ status: string }>,

  download: (sessionId: string, path: string) => downloadWithFetch(sessionId, path),

  remove: (sessionId: string, path: string, directory = false) =>
    request.delete(`/sftp/remove/${sessionId}?path=${encodeURIComponent(path)}&directory=${directory}`) as Promise<{ status: string }>,

  upload: (sessionId: string, path: string, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post(`/sftp/upload/${sessionId}?path=${encodeURIComponent(path)}`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 0, // 大文件上传不限制超时
    }) as Promise<{ status: string }>
  }
}
