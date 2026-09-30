/**
 * Agent ↔ 右侧面板联动：远程工具事件的纯函数工具集。
 */

/** 会改动远程文件系统的 sftp 工具及中文动作名（终端镜像判定用）。 */
export const MUTATION_TOOLS: Record<string, { label: string }> = {
  sftp_write: { label: '写入' },
  sftp_edit: { label: '编辑' },
  sftp_upload: { label: '上传' },
  sftp_rename: { label: '移动' },
  sftp_remove: { label: '删除' },
  sftp_mkdir: { label: '建目录' },
}

export function isMutationTool(toolName: string | undefined | null): boolean {
  return !!toolName && Object.prototype.hasOwnProperty.call(MUTATION_TOOLS, toolName)
}

type ToolInput = Record<string, unknown> | undefined | null

function str(input: ToolInput, key: string): string | null {
  const v = input?.[key]
  return typeof v === 'string' && v.length > 0 ? v : null
}

/** 工具输入里的主机标识 host_key（run_ssh / sftp_* 均携带）。 */
export function hostKeyOf(input: ToolInput): string | null {
  return str(input, 'host_key')
}

/**
 * 变更落盘的目标完整路径（泳道展示 + 文件树定位用）。
 * rename 取 new_path（移动后的新位置）；upload 拼 dir/filename；mkdir 取 path。
 */
export function targetPathOf(toolName: string, input: ToolInput): string | null {
  switch (toolName) {
    case 'sftp_upload': {
      const dir = str(input, 'dir')
      const filename = str(input, 'filename')
      if (!dir || !filename) return null
      return dir.replace(/\/+$/, '') + '/' + filename
    }
    case 'sftp_rename':
      return str(input, 'new_path')
    default:
      return str(input, 'path')
  }
}

/**
 * SshTool 结果格式：首行 `[exit=N]`，其后 stdout，stderr 非空时再以 `[stderr]` 分段。
 */
export function parseSshResult(raw: string): { exit: number | null; body: string } {
  const m = raw.match(/^\s*\[exit=(-?\d+)\]\s*\n?/)
  if (!m) return { exit: null, body: raw }
  return { exit: Number(m[1]), body: raw.slice(m[0].length).replace(/\n+$/, '') }
}

/** SSE tool_result 载荷统一转文本（字符串原样；错误对象取 error 字段）。 */
export function resultOutputText(output: unknown): string {
  if (output == null) return ''
  if (typeof output === 'string') return output
  if (typeof output === 'object') {
    const o = output as Record<string, unknown>
    if (typeof o.error === 'string') return o.error
    try {
      return JSON.stringify(o, null, 2)
    } catch {
      return String(output)
    }
  }
  return String(output)
}

/** 镜像到终端前限长，避免超大输出（如几十万行日志）刷爆 xterm 缓冲区。 */
export function clampForTerminal(text: string, max = 8000): string {
  if (text.length <= max) return text
  return text.slice(0, max) + `\n\x1b[33m…输出过长，已截断（共 ${text.length} 字符）\x1b[0m`
}
