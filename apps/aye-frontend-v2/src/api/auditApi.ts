/**
 * 工具名 → 中文标签 / 语义色元数据。
 *
 * 观测页（Logs / Trace）统一走 observabilityApi（事件日志投影
 * /api/observability/*）；旧 /api/audit 查询投影已于精简批 8 下线，
 * 本文件仅保留展示层共用的工具字典（含已下线工具名，用于渲染历史数据）。
 */

/**
 * v2 工具 → 中文名 + 语义色（图标在页面层按此着色）。
 * 色彩只表达操作风险：墨色=执行 / 灰=只读类 / 琥珀=写改动 / 红=删除；
 * 不再用蓝青紫分类彩虹色，与全局黑白单色体系一致。
 * 取值为 CSS token 字符串（仅用于 inline color，不做 hex alpha 拼接）。
 */
const C_READ = 'var(--dsw-alias-label-tertiary)'
// 写/删色同时用于图标与文字标签，取各色系 -label 文字档（浅底对比度足够、深底沉稳）
const C_WRITE = 'var(--dsw-alias-state-warn-label)'
const C_DELETE = 'var(--dsw-alias-state-error-primary)'
const C_EXEC = 'var(--dsw-alias-label-primary)'

export const TOOL_META: Record<string, { label: string; color: string }> = {
  run_ssh: { label: '执行命令', color: C_EXEC },
  sftp_list: { label: '列出目录', color: C_READ },
  sftp_read: { label: '读取文件', color: C_READ },
  sftp_write: { label: '写入文件', color: C_WRITE },
  sftp_upload: { label: '上传文件', color: C_WRITE },
  sftp_remove: { label: '删除文件', color: C_DELETE },
  sftp_mkdir: { label: '创建目录', color: C_WRITE },
  sftp_edit: { label: '编辑文件', color: C_WRITE },
  sftp_rename: { label: '重命名', color: C_WRITE },
  remote_grep: { label: '远程搜索', color: C_READ },
  web_search: { label: '联网搜索', color: C_READ },
  web_fetch: { label: '抓取网页', color: C_READ },
  todo_write: { label: '待办进度', color: C_READ },
  tool_search: { label: '加载工具', color: C_READ },
  execute_command: { label: '执行命令', color: C_EXEC },
}

export function getToolLabel(toolName: string): string {
  return TOOL_META[toolName]?.label ?? toolName
}

export function getToolColor(toolName: string): string {
  return TOOL_META[toolName]?.color ?? C_READ
}
