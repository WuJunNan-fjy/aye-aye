import { useEffect, useRef, useState } from 'react'
import { Check, ChevronDown } from 'lucide-react'

/**
 * 设置行的胶囊选择器 + 下拉菜单（1:1 复刻 dsh 的 .selector pill + Menu 原语，
 * 见 LanguageRow / PermissionRow）。通用设置页的语言、执行模式两行共用。
 * Translate：dsh Menu(portal) → 轻量浮层 + mousedown 外点关闭 + Escape。
 */

export interface PillOption {
  id: string
  label: string
  desc?: string
}

interface SettingsPillMenuProps {
  /** 当前选中值 */
  value: string
  options: readonly PillOption[]
  /** aria-label */
  label?: string
  /** 保存中锁定并显示“保存中…” */
  busy?: boolean
  disabled?: boolean
  onSelect: (id: string) => void
}

export default function SettingsPillMenu({ value, options, label, busy, disabled, onSelect }: SettingsPillMenuProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const activeLabel = options.find(o => o.id === value)?.label ?? value

  useEffect(() => {
    if (!open) return
    const closeOutside = (event: MouseEvent): void => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', closeOutside)
    return () => { document.removeEventListener('mousedown', closeOutside) }
  }, [open])

  return (
    <div
      ref={rootRef}
      className="gs-select-root"
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          setOpen(false)
          triggerRef.current?.focus()
        }
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        className="gs-selector"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={label}
        disabled={disabled || busy}
        onClick={() => { setOpen(value => !value) }}
      >
        <span className="gs-selectorValue">{busy ? '保存中…' : activeLabel}</span>
        <ChevronDown className={`gs-chevron${open ? ' gs-chevronOpen' : ''}`} />
      </button>

      {open ? (
        <div className="gs-menu" role="menu" aria-label={label}>
          {options.map(option => (
            <button
              key={option.id}
              type="button"
              role="menuitemradio"
              aria-checked={option.id === value}
              className="gs-menuOption"
              onClick={() => {
                setOpen(false)
                if (option.id === value) return
                onSelect(option.id)
              }}
            >
              <span className="gs-menuOptionText">
                <span className="gs-menuOptionLabel">{option.label}</span>
                {option.desc !== undefined ? <span className="gs-menuOptionDesc">{option.desc}</span> : null}
              </span>
              {option.id === value ? <Check className="gs-menuCheck" /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
