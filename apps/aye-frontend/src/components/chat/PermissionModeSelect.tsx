import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Check, ChevronDown, ShieldCheck, ShieldQuestion, ShieldAlert } from 'lucide-react'
import type { ExecutionMode } from '@/api/agentApi'

/**
 * PermissionModeSelect —— 输入条上的权限模式「盾牌 chip + 向上菜单」（图标盾牌→当前模式名→chevron），
 * 菜单三行：盾牌 glyph + 名称 + 当前档对勾。
 * 三档（readonly / write_confirm / all_confirm）为 只读 / 写确认 / 全确认，
 * 严格度递进，盾牌族图标同系：ShieldCheck → ShieldQuestion → ShieldAlert。
 */

interface ModeMeta {
  value: ExecutionMode
  label: string
  desc: string
  glyph: ReactNode
}

const PERMISSION_MODES: ModeMeta[] = [
  { value: 'readonly', label: '只读', desc: '禁止写操作，仅允许读取/查询类工具', glyph: <ShieldCheck size={15} /> },
  { value: 'write_confirm', label: '写操作确认', desc: '写操作需确认，读取自动执行（默认）', glyph: <ShieldQuestion size={15} /> },
  { value: 'all_confirm', label: '全部确认', desc: '所有工具调用都需手动确认', glyph: <ShieldAlert size={15} /> },
]

interface Props {
  value: ExecutionMode
  onChange: (mode: ExecutionMode) => void
  disabled?: boolean
  disabledReason?: string
}

export default function PermissionModeSelect({ value, onChange, disabled, disabledReason }: Props) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const current = PERMISSION_MODES.find((m) => m.value === value) ?? PERMISSION_MODES[0]

  // 外部点击 / Esc 关单（菜单向上弹，与同排 ModelSelect 一致）。
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div className="pm-root" ref={rootRef}>
      {open && (
        <div className="pm-menu" role="listbox" aria-label="执行模式">
          {PERMISSION_MODES.map((m) => (
            <button
              key={m.value}
              type="button"
              role="option"
              aria-selected={m.value === value}
              className={`pm-item ${m.value === value ? 'selected' : ''}`}
              title={m.desc}
              onClick={() => { onChange(m.value); setOpen(false) }}
            >
              <span className="pm-item-icon" aria-hidden>{m.glyph}</span>
              <span className="pm-item-label">{m.label}</span>
              <span className="pm-check" aria-hidden>{m.value === value && <Check />}</span>
            </button>
          ))}
        </div>
      )}
      <button
        type="button"
        className="pm-trigger"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        title={disabled && disabledReason ? disabledReason : `执行模式：${current.desc}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="pm-trigger-icon" aria-hidden>{current.glyph}</span>
        <span className="pm-trigger-label">{current.label}</span>
        <span className={`pm-chevron ${open ? 'pm-chevronOpen' : ''}`} aria-hidden>
          <ChevronDown />
        </span>
      </button>
    </div>
  )
}
