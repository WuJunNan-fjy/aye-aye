import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type FocusEvent } from 'react'
import { Check, ChevronDown, ChevronRight, TriangleAlert } from 'lucide-react'

/**
 * ModelSelect —— 1:1 复刻 dsh ui-model-selection ModelSelect.tsx 的 DOM/逻辑/CSS。
 * composer 的命名模型座：两级菜单（root → model / effort）。
 * Translate：useSyncExternalStore + ModelDirectory → props(groups/current/select)；
 * IconChevronDown/Right/Check/Warning → antd 图标；t(key) → 中文；
 * Toast → 轻量浮层（modelselect.css .toast）。
 */

export interface ModelReasoningEffort {
  id: string
  name: string
  description?: string
}

export interface ModelSelectGroupModel {
  id: string
  name: string
  description?: string
  /** 有 reasoning 的模型才显示「推理档位」钻取行 */
  reasoning?: { defaultEffort?: string; efforts: ModelReasoningEffort[] }
}

export interface ModelSelectGroup {
  id: string
  name: string
  models: ModelSelectGroupModel[]
}

export interface ModelSelection {
  provider: string
  model: string
  reasoningEffort?: string
}

interface ModelSelectProps {
  groups: ModelSelectGroup[]
  current: ModelSelection | null
  /** 模型选择进行中时锁定（loading 目录） */
  locked?: boolean
  /** 返回 true = 接受选择并关闭；false = 拒绝（触发 toast） */
  select: (selection: ModelSelection) => Promise<boolean>
  /** 每次展开时调用，用于刷新目录（可选，mock 可空） */
  load?: () => void
}

type Pane = 'root' | 'model' | 'effort'

/** 动态档位行；effort 为 undefined 表示跟随厂商默认。 */
interface EffortChoice {
  key: string
  effort: string | undefined
  label: string
  description?: string
}

type Status = 'idle' | 'loading' | 'ready' | 'selecting' | 'error'

export default function ModelSelect({ groups, current, locked, select, load }: ModelSelectProps) {
  const [open, setOpen] = useState(false)
  const [pane, setPane] = useState<Pane>('root')
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)
  // 菜单内错误条只为目录加载服务；选择被拒走 toast。
  const lastActionRef = useRef<'load' | 'select'>('load')
  const [toast, setToast] = useState<{ seq: number; text: string } | null>(null)
  const toastSeq = useRef(0)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([])
  const id = useId()

  const choices = useMemo(() => groups.flatMap(group =>
    group.models.map(model => ({
      group,
      model,
      selection: {
        provider: group.id,
        model: model.id,
        ...model.reasoning?.defaultEffort === undefined
          ? {}
          : { reasoningEffort: model.reasoning.defaultEffort },
      } satisfies ModelSelection,
    }))), [groups])
  const selectedIndex = current === null
    ? -1
    : choices.findIndex(c => c.selection.provider === current?.provider && c.selection.model === current.model)
  const currentChoice = choices[selectedIndex]
  const reasoning = currentChoice?.model.reasoning
  const effectiveEffort = current?.reasoningEffort ?? reasoning?.defaultEffort
  const effortLabel = reasoning === undefined
    ? undefined
    : effectiveEffort === undefined
      ? '跟随厂商默认'
      : reasoning.efforts.find(level => level.id === effectiveEffort)?.name ?? effectiveEffort
  const effortChoices = useMemo<readonly EffortChoice[]>(() => reasoning === undefined
    ? []
    : [
      ...reasoning.defaultEffort === undefined
        ? [{ key: 'provider-default', effort: undefined, label: '跟随厂商默认' }]
        : [],
      ...reasoning.efforts.map((effort: ModelReasoningEffort) => ({
        key: `effort:${effort.id}`,
        effort: effort.id,
        label: effort.name,
        ...effort.description === undefined ? {} : { description: effort.description },
      })),
    ], [reasoning])
  const busy = status === 'selecting'

  const reload = (): void => {
    lastActionRef.current = 'load'
    load?.()
  }

  // 每次展开时刷新目录（mock 空实现；接后端时在此拉取）。
  useEffect(() => {
    if (open) {
      setStatus('loading')
      setError(null)
      window.setTimeout(() => {
        setStatus('ready')
        reload()
      }, 0)
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!open) return
    const closeOutside = (event: MouseEvent): void => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', closeOutside)
    return () => { document.removeEventListener('mousedown', closeOutside) }
  }, [open])

  if (locked) return null

  const show = (): void => {
    setPane('root')
    setOpen(true)
  }

  const close = (restoreFocus = false): void => {
    setOpen(false)
    setPane('root')
    if (restoreFocus) queueMicrotask(() => { triggerRef.current?.focus() })
  }

  const moveFocus = (offset: number): void => {
    const items = itemRefs.current.filter(item => item !== null)
    if (items.length === 0) return
    const active = items.findIndex(item => item === document.activeElement)
    const next = (Math.max(active, 0) + offset + items.length) % items.length
    items[next]?.focus()
  }

  const onRootKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      // Escape 先退出一级钻取，再关闭。
      if (pane !== 'root') setPane('root')
      else close(true)
      return
    }
    if (!open) return
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      moveFocus(event.key === 'ArrowDown' ? 1 : -1)
    }
  }

  const onBlur = (event: FocusEvent<HTMLDivElement>): void => {
    if (event.relatedTarget instanceof Node && rootRef.current?.contains(event.relatedTarget)) return
    close()
  }

  const settleSelection = (accepted: boolean): void => {
    if (accepted) {
      if (rootRef.current !== null) close(true)
      return
    }
    toastSeq.current += 1
    setToast({ seq: toastSeq.current, text: `选择被拒绝：请先在设置页为该厂商配置 API Key` })
  }

  const choose = (selection: ModelSelection): void => {
    if (current?.provider === selection.provider && current.model === selection.model) {
      close(true)
      return
    }
    lastActionRef.current = 'select'
    setStatus('selecting')
    void select(selection)
      .then((accepted) => {
        setStatus('ready')
        settleSelection(accepted)
      })
  }

  const chooseEffort = (effort: string | undefined): void => {
    if (current === null) return
    if (effectiveEffort === effort) {
      close(true)
      return
    }
    const selection: ModelSelection = {
      provider: current.provider,
      model: current.model,
      ...effort === undefined ? {} : { reasoningEffort: effort },
    }
    lastActionRef.current = 'select'
    setStatus('selecting')
    void select(selection)
      .then((accepted) => {
        setStatus('ready')
        settleSelection(accepted)
      })
  }

  const modelLabel = currentChoice?.model.name ?? '选择模型'
  const triggerLabel = effortLabel === undefined ? modelLabel : `${modelLabel} · ${effortLabel}`
  itemRefs.current = []
  let itemIndex = 0
  const itemRef = () => {
    const at = itemIndex++
    return (node: HTMLButtonElement | null) => { itemRefs.current[at] = node }
  }

  return (
    <div ref={rootRef} className="ms-root" onKeyDown={onRootKeyDown} onBlur={onBlur}>
      <button
        ref={triggerRef}
        type="button"
        className="ms-trigger"
        aria-label={`选择模型：${modelLabel}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? `${id}-menu` : undefined}
        title={triggerLabel}
        onClick={() => { if (open) close(); else show() }}
      >
        <span className="ms-triggerLabel">{modelLabel}</span>
        {effortLabel !== undefined && <span className="ms-triggerEffort">{effortLabel}</span>}
        <ChevronDown className={`ms-chevron ${open ? 'ms-chevronOpen' : ''}`} style={{ fontSize: 12 }} />
      </button>

      {open && (
        <div id={`${id}-menu`} className="ms-menu" role="menu" aria-label="选择模型与推理档位">
          {pane === 'root' && (
            <>
              <button ref={itemRef()} type="button" role="menuitem" className="ms-cell" onClick={() => { setPane('model') }}>
                <span className="ms-cellLabel">模型</span>
                <span className="ms-cellValue">{modelLabel}</span>
                <ChevronRight className="ms-cellChevron" style={{ fontSize: 12 }} />
              </button>
              {reasoning !== undefined && (
                <button ref={itemRef()} type="button" role="menuitem" className="ms-cell" onClick={() => { setPane('effort') }}>
                  <span className="ms-cellLabel">推理档位</span>
                  <span className="ms-cellValue">{effortLabel}</span>
                  <ChevronRight className="ms-cellChevron" style={{ fontSize: 12 }} />
                </button>
              )}
            </>
          )}

          {pane === 'model' && (
            <>
              {status === 'loading' && <div className="ms-status">加载中…</div>}
              {error !== null && lastActionRef.current === 'load' && (
                <div className="ms-error">
                  <span>{`加载失败：${error}`}</span>
                  <button type="button" className="ms-retry" onClick={reload}>重试</button>
                </div>
              )}
              <div className="ms-groups">
                {groups.map(group => {
                  const headingId = `${id}-${group.id}`
                  return (
                    <section role="group" aria-labelledby={headingId} className="ms-group" key={group.id}>
                      <div className="ms-groupTitle" id={headingId}>{group.name}</div>
                      {group.models.map(model => {
                        const selected = current?.provider === group.id && current.model === model.id
                        return (
                          <button
                            ref={itemRef()}
                            type="button"
                            role="menuitemradio"
                            aria-checked={selected}
                            className={`ms-option ${selected ? 'ms-selected' : ''}`}
                            key={model.id}
                            title={model.name}
                            disabled={busy}
                            onClick={() => { choose({ provider: group.id, model: model.id }) }}
                          >
                            <span className="ms-optionCopy">
                              <span className="ms-modelName">{model.name}</span>
                              {model.description !== undefined && (
                                <span className="ms-description">{model.description}</span>
                              )}
                            </span>
                            <span className="ms-check">
                              {selected ? <Check style={{ fontSize: 13 }} /> : null}
                            </span>
                          </button>
                        )
                      })}
                    </section>
                  )
                })}
              </div>
              {status === 'ready' && choices.length === 0 && (
                <div className="ms-empty">暂无可用模型</div>
              )}
            </>
          )}

          {pane === 'effort' && (
            <>
              {error !== null && lastActionRef.current === 'load' && (
                <div className="ms-error">
                  <span>{`加载失败：${error}`}</span>
                  <button type="button" className="ms-retry" onClick={reload}>重试</button>
                </div>
              )}
              {effortChoices.length === 0
                ? <div className="ms-empty">该模型不支持调节档位</div>
                : effortChoices.map(level => (
                  <button
                    ref={itemRef()}
                    type="button"
                    role="menuitemradio"
                    aria-checked={effectiveEffort === level.effort}
                    className={`ms-option ${effectiveEffort === level.effort ? 'ms-selected' : ''}`}
                    key={level.key}
                    disabled={busy}
                    onClick={() => { chooseEffort(level.effort) }}
                  >
                    <span className="ms-optionCopy">
                      <span className="ms-modelName">{level.label}</span>
                      {level.description !== undefined && (
                        <span className="ms-description">{level.description}</span>
                      )}
                    </span>
                    <span className="ms-check">
                      {effectiveEffort === level.effort ? <Check style={{ fontSize: 13 }} /> : null}
                    </span>
                  </button>
                ))}
            </>
          )}
        </div>
      )}

      {toast !== null && (
        <div key={toast.seq} className="ms-toast" role="alert">
          <TriangleAlert style={{ fontSize: 13 }} />
          <span>{toast.text}</span>
        </div>
      )}
    </div>
  )
}
