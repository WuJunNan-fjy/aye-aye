import { Modal, message } from 'antd'
import { useUploadStore } from '@/store/useUploadStore'

const DEFAULT_EXCLUDES = [
  { label: 'node_modules', glob: '**/node_modules/**', on: true },
  { label: '.git', glob: '**/.git/**', on: true },
  { label: '.idea / .vscode', glob: '**/{.idea,.vscode}/**', on: true },
  { label: 'dist / build 产物', glob: '**/{dist,build}/**', on: false },
  { label: '日志文件', glob: '**/*.log', on: false },
]

export async function archiveAndEnqueue(
  sessionId: string,
  remoteDir: string,
  presetDir?: string,
): Promise<void> {
  if (!window.electronAPI) {
    message.warning('工作区打包上传仅支持桌面端，请使用 Aye 桌面版')
    return
  }
  const eapi = window.electronAPI
  const dir = presetDir ?? (await eapi.selectWorkspace())
  if (!dir) return

  // 用命令式弹窗收集 format + excludes（紧凑、无多余状态）
  let format: 'zip' | 'tar.gz' = 'zip'
  const checks = DEFAULT_EXCLUDES.map((e) => ({ ...e }))
  let extra = ''

  const Choice = (
    <div style={{ fontSize: 12 }}>
      <div style={{ marginBottom: 8 }}>
        <span style={{ marginRight: 12 }}>
          <input type="radio" name="fmt" defaultChecked onClick={() => { format = 'zip' }} /> zip
        </span>
        <span>
          <input type="radio" name="fmt" onClick={() => { format = 'tar.gz' }} /> tar.gz
        </span>
      </div>
      <div style={{ color: '#444', marginBottom: 4 }}>排除项：</div>
      {checks.map((c) => (
        <label key={c.glob} style={{ display: 'block', lineHeight: '22px' }}>
          <input type="checkbox" defaultChecked={c.on} onChange={(e) => { c.on = e.target.checked }} />
          {' '}{c.label}
        </label>
      ))}
      <input
        type="text"
        placeholder="额外排除 glob，逗号分隔（如 **/tmp/**）"
        style={{
          width: '100%', marginTop: 6, fontSize: 12, padding: '4px 6px',
          border: '1px solid #ddd', borderRadius: 4,
        }}
        onChange={(e) => { extra = e.target.value }}
      />
      <div style={{ color: '#999', marginTop: 6 }}>工作区：{dir}</div>
    </div>
  )

  const ok = await new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: '打包上传工作区',
      width: 420,
      content: Choice,
      okText: '开始打包并上传',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
  if (!ok) return

  const excludesGlobs = [
    ...checks.filter((c) => c.on).map((c) => c.glob),
    ...extra.split(',').map((s) => s.trim()).filter(Boolean),
  ]

  message.info('正在本地打包…')
  const arc = await eapi.archiveWorkspace(dir, format, excludesGlobs)
  const base = dir.split(/[\\/]/).filter(Boolean).pop() ?? 'workspace'
  const name = `${base}.${format === 'zip' ? 'zip' : 'tar.gz'}`
  useUploadStore.getState().enqueue({
    kind: 'archive',
    sessionId,
    remoteDir,
    localPath: arc.path,
    name,
    size: arc.size,
  })
}
