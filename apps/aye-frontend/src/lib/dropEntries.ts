import type { DragEvent } from 'react'

/** drop 条目：dir 保留原始 entry（File 无法反查 entry），file 的 entry 为 null。 */
export interface DropEntry {
  kind: 'file' | 'dir'
  file: File
  entry: FileSystemEntry | null
}

/** 从 drop 事件读取条目（用 webkitGetAsEntry 区分文件与目录）。 */
export async function readDroppedEntries(
  e: DragEvent<HTMLElement>,
): Promise<DropEntry[]> {
  const items = Array.from(e.dataTransfer.items ?? [])
  const out: DropEntry[] = []
  for (const item of items) {
    if (item.kind !== 'file') continue
    const entry = item.webkitGetAsEntry?.()
    if (!entry) {
      const f = item.getAsFile()
      if (f) out.push({ kind: 'file', file: f, entry: null })
      continue
    }
    const f = await new Promise<File | null>((resolve) =>
      // 规范上 file/dir 两类 entry 均有 file()；DOM lib 仅在 FileSystemFileEntry 声明
      (entry as FileSystemFileEntry).file(
        (file) => resolve(file), () => resolve(null)))
    if (f) {
      out.push({
        kind: entry.isDirectory ? 'dir' : 'file',
        file: f,
        entry,
      })
    }
  }
  return out
}

/**
 * 递归收集目录内文件，并把相对路径写入 webkitRelativePath
 * （上传 worker 依赖该字段批量建目录；OS 拖入的 File 本身无此字段）。
 */
export async function collectDirectoryFiles(
  dirEntry: FileSystemDirectoryEntry,
  topName: string,
): Promise<File[]> {
  const out: File[] = []

  const walk = async (entry: FileSystemDirectoryEntry, relPrefix: string): Promise<void> => {
    const reader = entry.createReader()
    // readEntries 规范上每批最多 100 条，需循环到返回空
    let batch: FileSystemEntry[] = []
    for (;;) {
      const items = await new Promise<FileSystemEntry[]>((resolve, reject) =>
        reader.readEntries(resolve, reject))
      if (items.length === 0) break
      batch = batch.concat(items)
    }
    for (const ent of batch) {
      if (ent.isDirectory) {
        await walk(ent as FileSystemDirectoryEntry, `${relPrefix}${ent.name}/`)
      } else if (ent.isFile) {
        const f = await new Promise<File>((resolve, reject) =>
          (ent as FileSystemFileEntry).file(resolve, reject))
        Object.defineProperty(f, 'webkitRelativePath', {
          value: `${topName}/${relPrefix}${ent.name}`,
          configurable: true,
        })
        out.push(f)
      }
    }
  }

  await walk(dirEntry, '')
  return out
}
