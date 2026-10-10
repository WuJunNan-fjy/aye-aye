import { useState } from 'react'
import { ChevronRight, Trash2 } from 'lucide-react'
import type { ModelDraft } from '@/api/modelApi'
import { discoverModels, errorMessage } from '@/api/modelApi'

/**
 * 模型目录编辑器 —— 1:1 复刻 dsh ModelListEditor.tsx 的 DOM/CSS。
 * Translate：dsh 的 api.llm.discoverModels 探测 → Aye POST
 * /api/models/discover（拉取 OpenAI 兼容 GET {baseUrl}/models，免费不耗 token）；
 * t(key) → 中文。
 */

interface ModelListEditorProps {
  models: ModelDraft[]
  /** 所属厂商 id（探测时作为 provider 参数） */
  providerId: string
  /** 当前编辑中的 Base URL（探测时使用） */
  baseUrl: string
  /** 用户层是否拥有整个数组（决定是否显示"重置"） */
  overridden?: boolean
  onChange: (models: ModelDraft[]) => void
  onReset?: () => void
  disabled?: boolean
}

type CapacityField = 'contextWindow' | 'maxTokens'

/** 空容量字段的占位提示（K/M 后缀）。 */
const CAPACITY_HINT: Record<CapacityField, string> = { contextWindow: '256K', maxTokens: '32K' }

/** 把存储的计数拼成 K/M 后缀文本。 */
export function formatCapacity(value: number): string {
  if (value >= 1_000_000) {
    const m = value / 1_000_000
    return `${Number.isInteger(m) ? m : m.toFixed(1)}M`
  }
  if (value >= 1_000) {
    const k = value / 1_000
    return `${Number.isInteger(k) ? k : k.toFixed(1)}K`
  }
  return String(value)
}

/** 解析 K/M 后缀文本；空串或不可读返回 undefined。 */
export function parseCapacity(text: string): number | undefined {
  const t = text.trim().toUpperCase()
  if (t === '') return undefined
  const m = /^(\d+(?:\.\d+)?)([KM])?$/.exec(t)
  if (!m) return undefined
  const n = Number(m[1])
  if (m[2] === 'K') return Math.round(n * 1000)
  if (m[2] === 'M') return Math.round(n * 1000000)
  return n
}

function textOf(model: ModelDraft, key: keyof ModelDraft): string {
  const value = model[key]
  return typeof value === 'string' ? value : ''
}

function numberOf(model: ModelDraft, key: keyof ModelDraft): number | undefined {
  const value = model[key]
  return typeof value === 'number' ? value : undefined
}

export default function ModelListEditor({ models, providerId, baseUrl, onChange, onReset, overridden, disabled }: ModelListEditorProps) {
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set())
  // 容量以文本编辑，因此按键缓冲存在这里，而不是从解析后的计数反推。
  const [editing, setEditing] = useState<ReadonlyMap<string, string>>(new Map())
  const [failure, setFailure] = useState<string | undefined>(undefined)
  const [busy, setBusy] = useState(false)

  const bufferKey = (index: number, field: CapacityField) => `${String(index)}:${field}`

  const editCapacity = (index: number, field: CapacityField, text: string): void => {
    setEditing(current => new Map(current).set(bufferKey(index, field), text))
    patch(index, { [field]: parseCapacity(text) })
  }

  const capacityText = (model: ModelDraft, index: number, field: CapacityField): string =>
    editing.get(bufferKey(index, field)) ?? (numberOf(model, field) === undefined ? '' : formatCapacity(numberOf(model, field)!))

  /** 删除一行并同步下移行号缓冲。 */
  const reindexOnRemove = (current: ReadonlyMap<string, string>, index: number): Map<string, string> => {
    const next = new Map<string, string>()
    for (const [key, value] of current) {
      const at = Number(key.slice(0, key.indexOf(':')))
      if (at === index) continue
      next.set(at > index ? key.replace(/^\d+/, String(at - 1)) : key, value)
    }
    return next
  }

  const toggleExpanded = (index: number): void => {
    setExpanded(current => {
      const next = new Set(current)
      if (!next.delete(index)) next.add(index)
      return next
    })
  }

  const patch = (index: number, next: Record<string, string | number | boolean | undefined>): void => {
    onChange(models.map((model, at) => {
      if (at !== index) return model
      const cleared = new Set(
        Object.entries(next).filter(([, value]) => value === undefined || value === '').map(([key]) => key),
      )
      return Object.fromEntries(
        Object.entries({ ...model, ...next }).filter(([key]) => !cleared.has(key)),
      ) as unknown as ModelDraft
    }))
  }

  /** 探测：POST /api/models/discover，把返回的 id 并入目录（保留已有高级字段）。 */
  const fetchModels = async (): Promise<void> => {
    setBusy(true)
    setFailure(undefined)
    try {
      const result = await discoverModels({ provider: providerId, baseUrl })
      if (!result.success) {
        setFailure(result.message ?? '探测失败')
        return
      }
      const existing = new Set(models.map(m => m.id))
      const fresh = result.models ?? []
      const added = fresh.filter(id => !existing.has(id)).map(id => ({ id }))
      if (added.length === 0) {
        setFailure('端点返回的模型都已在目录中')
        return
      }
      onChange([...models, ...added])
      setFailure(`${added.length} 个模型已从端点拉取并加入目录`)
    } catch (err) {
      setFailure(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="modelCatalog" aria-label="模型">
      <div className="modelListHead">
        <div className="modelCatalogHeading">
          <span className="modelCatalogTitle">模型</span>
          {overridden === undefined ? null : (
            <span className="modelCatalogMeta">{overridden ? '已自定义（覆盖内置目录）' : '继承内置目录'}</span>
          )}
        </div>
        {overridden === true && onReset !== undefined ? (
          <button type="button" className="linkButton" disabled={disabled} onClick={onReset}>
            重置
          </button>
        ) : null}
        <button
          type="button"
          className="linkButton"
          disabled={disabled || busy}
          title="从端点拉取该 provider 的模型清单"
          onClick={fetchModels}
        >
          {busy ? '拉取中…' : '拉取模型'}
        </button>
      </div>
      {models.length === 0 ? <p className="modelEmpty">还没有模型，点下方「添加模型」或「拉取模型」。</p> : null}
      {models.map((model, index) => (
        <div key={index} className="modelEntry">
          <div className="modelRow">
            <input
              className="input"
              type="text"
              value={textOf(model, 'id')}
              placeholder="模型 ID（如 deepseek-chat）"
              aria-label={`模型 ID ${index + 1}`}
              disabled={disabled}
              onChange={(event) => { patch(index, { id: event.target.value }) }}
            />
            <input
              className="input"
              type="text"
              value={textOf(model, 'name')}
              placeholder="显示名"
              aria-label={`显示名 ${index + 1}`}
              disabled={disabled}
              onChange={(event) => { patch(index, { name: event.target.value === '' ? undefined : event.target.value }) }}
            />
            <button
              type="button"
              className="iconButton"
              aria-label={`高级 ${index + 1}`}
              aria-expanded={expanded.has(index)}
              title="容量 / 思考模式"
              onClick={() => { toggleExpanded(index) }}
            >
              <ChevronRight
                size={14}
                aria-hidden
                style={{
                  transform: expanded.has(index) ? 'rotate(90deg)' : undefined,
                  transition: 'transform var(--dsw-motion-panel) var(--dsw-ease-standard)',
                }}
              />
            </button>
            <button
              type="button"
              className="iconButton iconButtonDanger"
              aria-label={`移除模型 ${index + 1}`}
              title="移除"
              disabled={disabled}
              onClick={() => {
                onChange(models.filter((_model, at) => at !== index))
                setExpanded(current => {
                  const next = new Set<number>()
                  for (const at of current) {
                    if (at < index) next.add(at)
                    else if (at > index) next.add(at - 1)
                  }
                  return next
                })
                setEditing(current => reindexOnRemove(current, index))
              }}
            >
              <Trash2 size={14} />
            </button>
          </div>
          {expanded.has(index) ? (
            <div className="modelAdvanced">
              <label className="modelField">
                <span className="modelFieldLabel">上下文窗口</span>
                <input
                  className="input"
                  type="text"
                  inputMode="numeric"
                  value={capacityText(model, index, 'contextWindow')}
                  placeholder={CAPACITY_HINT.contextWindow}
                  aria-label={`上下文窗口 ${index + 1}`}
                  disabled={disabled}
                  onChange={(event) => { editCapacity(index, 'contextWindow', event.target.value) }}
                />
              </label>
              <label className="modelField">
                <span className="modelFieldLabel">最大输出 tokens</span>
                <input
                  className="input"
                  type="text"
                  inputMode="numeric"
                  value={capacityText(model, index, 'maxTokens')}
                  placeholder={CAPACITY_HINT.maxTokens}
                  aria-label={`最大输出 tokens ${index + 1}`}
                  disabled={disabled}
                  onChange={(event) => { editCapacity(index, 'maxTokens', event.target.value) }}
                />
              </label>
              <label className="modelField">
                <span className="modelFieldLabel">支持思考模式</span>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, height: 32 }}>
                  <input
                    type="checkbox"
                    checked={model.thinking === true}
                    disabled={disabled}
                    onChange={(event) => { patch(index, { thinking: event.target.checked }) }}
                  />
                  <span className="modelFieldLabel" style={{ fontWeight: 400 }}>🔬 推理增强</span>
                </span>
              </label>
            </div>
          ) : null}
        </div>
      ))}
      <button
        type="button"
        className="addModelButton"
        disabled={disabled}
        onClick={() => { onChange([...models, { id: '' }]) }}
      >
        ＋ 添加模型
      </button>
      {failure !== undefined ? <p className="error">{failure}</p> : null}
    </section>
  )
}
