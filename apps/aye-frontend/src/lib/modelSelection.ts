import type { ModelSelection } from '@/components/chat/ModelSelect'

/**
 * 对话页「当前模型选择」的 localStorage 持久化。
 * - 仅存 provider/model/reasoningEffort 三个字段；
 * - 读不到或损坏时返回 null，由调用方按"首次默认值 / 目录回退"处理；
 * - reasoningEffort 为 undefined 表示"跟随厂商默认"（不写入）。
 *
 * 约定：reasoningEffort 仅在显式设置时写入；undefined 字段在 JSON 中自然省略，
 * 再次加载时保持 undefined 语义。
 */

const STORAGE_KEY = 'aye.modelSelection'

interface StoredSelection {
  provider: string
  model: string
  reasoningEffort?: string
}

export function getStoredModelSelection(): ModelSelection | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === null) return null
    const value = JSON.parse(raw) as unknown
    if (!isObject(value) || typeof (value as Record<string, unknown>).provider !== 'string' || typeof (value as Record<string, unknown>).model !== 'string') {
      return null
    }
    const stored = value as StoredSelection
    const out: ModelSelection = { provider: stored.provider, model: stored.model }
    if (typeof stored.reasoningEffort === 'string') out.reasoningEffort = stored.reasoningEffort
    return out
  } catch {
    // JSON 损坏或 localStorage 不可用：静默丢弃，回退默认。
    return null
  }
}

/** 写入选中模型到 localStorage。reasoningEffort 为 undefined 时省略该键。 */
export function setStoredModelSelection(selection: ModelSelection): void {
  try {
    const payload: StoredSelection = { provider: selection.provider, model: selection.model }
    if (selection.reasoningEffort !== undefined) payload.reasoningEffort = selection.reasoningEffort
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  } catch {
    // 隐身模式/配额上限等场景：非致命，默默忽略即可。
  }
}

// eslint-disable-next-line @typescript-eslint/ban-types
function isObject(value: unknown): value is object {
  return typeof value === 'object' && value !== null
}
