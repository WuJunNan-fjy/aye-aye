import { useEffect, useState } from 'react'

export type FolderChoice = 'reference' | 'upload'

/**
 * 窗口级记住的选择：模块变量，刷新页面/重启应用后重置（设计要求「本次窗口内记住」）。
 */
let remembered: FolderChoice | null = null
export const getFolderChoice = (): FolderChoice | null => remembered
export const resetFolderChoice = (): void => { remembered = null }

export interface FolderDropChoiceProps {
  folderName: string
  onChoose: (choice: FolderChoice, remember: boolean) => void
  onCancel: () => void
}

/** 黑白单色小卡：二选一 + 「本次窗口不再询问」。 */
export default function FolderDropChoice({ folderName, onChoose, onCancel }: FolderDropChoiceProps) {
  const [remember, setRemember] = useState(false)

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => { if (e.key === 'Escape') onCancel() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])

  const pick = (choice: FolderChoice): void => {
    if (remember) remembered = choice
    onChoose(choice, remember)
  }

  return (
    <div className="fchoice-backdrop" onMouseDown={onCancel}>
      <div className="fchoice-card" onMouseDown={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="fchoice-title">拖入了文件夹：{folderName}</div>
        <div className="fchoice-desc">请选择如何使用它</div>
        <div className="fchoice-actions">
          <button type="button" className="fchoice-btn" onClick={() => pick('reference')}>
            作为上下文引用
            <span className="fchoice-sub">AI 按需读取本机文件，不上传</span>
          </button>
          <button type="button" className="fchoice-btn" onClick={() => pick('upload')}>
            上传到服务器
            <span className="fchoice-sub">保留目录结构，进入文件面板当前目录</span>
          </button>
        </div>
        <label className="fchoice-remember">
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
          本次窗口不再询问（可在卡片中改回）
        </label>
      </div>
    </div>
  )
}
