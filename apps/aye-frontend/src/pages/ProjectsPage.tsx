import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { ArrowRight, Check, FolderOpen, Loader2, Plus, Rocket, Server } from 'lucide-react'
import WorkspacePicker from '@/components/deploy/WorkspacePicker'
import { HostConnectBar } from '@/components/chat/HostConnectBar'
import { getDefaultDeployModel, listWorkspaces, type Workspace, type DefaultDeployModel } from '@/api/deployApi'
import { listSavedProviders } from '@/api/modelApi'
import { getSettings } from '@/api/settingsApi'
import { startTurn } from '@/api/agentV2Api'
import { useHostStore, selectCurrentHost, selectCurrentState } from '@/store/useHostStore'
import { useSessionsStore } from '@/store/useSessionsStore'
import { useRunningSessionIds } from '@/store/useSessionRuntime'
import { setLastSessionId } from '@/lib/prefs'
import { setStoredModelSelection } from '@/lib/modelSelection'
import '../styles/projects.css'

/** 项目首页只启动明确选定项目和服务器的新任务；继续任务始终使用已有记录。 */
export default function ProjectsPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const [projects, setProjects] = useState<Workspace[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [pickerOpen, setPickerOpen] = useState(false)
  const [starting, setStarting] = useState(false)
  const [model, setModel] = useState<DefaultDeployModel | null>(null)
  const [mode, setMode] = useState('readonly')
  const [requirements, setRequirements] = useState('')
  const addRef = useRef<HTMLButtonElement>(null)
  const startLock = useRef(false)
  const hosts = useHostStore(s => s.hosts)
  const host = useHostStore(selectCurrentHost)
  const connection = useHostStore(selectCurrentState)
  const sessions = useSessionsStore(s => s.sessions)
  const historyError = useSessionsStore(s => s.error)
  const loadSessions = useSessionsStore(s => s.load)
  const runningIds = useRunningSessionIds()
  const project = projects.find(p => p.id === Number(params.get('project'))) ?? null
  const records = project ? sessions.filter(s => s.workspaceId === project.id) : []
  const connected = connection?.status === 'connected'
  const returnTo = location.pathname + location.search
  const settings = (section: string) => navigate(`/settings/${section}`, { state: { from: returnTo } })

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [items, defaultModel, providers, saved] = await Promise.all([
        listWorkspaces(), getDefaultDeployModel(), listSavedProviders(), getSettings(),
        useHostStore.getState().loadHosts(),
      ])
      setProjects(items)
      const provider = providers.find(p => p.id === defaultModel?.providerId)
      setModel(provider?.enabled && provider.hasKey
        && provider.models.some(m => m.modelId === defaultModel?.modelId) ? defaultModel : null)
      setMode(saved.defaultExecutionMode)
      await useHostStore.getState().reconcile()
      await loadSessions(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试')
    } finally {
      setLoading(false)
    }
  }, [loadSessions])

  useEffect(() => { void refresh() }, [refresh])

  const choose = (ws: Workspace | null) => {
    if (!ws) return
    setProjects(prev => [ws, ...prev.filter(p => p.id !== ws.id)])
    setParams({ project: String(ws.id) })
    window.dispatchEvent(new Event('aye:projects-changed'))
  }

  const start = async () => {
    if (startLock.current || loading || error || !project || !host?.id || !model || !connected || mode === 'readonly') return
    startLock.current = true
    setStarting(true)
    setError('')
    try {
      const result = await startTurn({
        message: `部署项目「${project.title}」到服务器「${host.name}」。先检查项目与环境，说明方案；涉及修改时按权限规则请求确认，完成后验证并提供访问方式。${requirements.trim() ? `\n补充要求：${requirements.trim()}` : ''}`,
        workspaceId: project.id, hostId: host.id, deploy: true,
        modelRef: `${model.providerId}::${model.modelId}`,
        // 不覆盖服务端保存的权限偏好，尤其不能自动提升只读权限。
      })
      setLastSessionId(result.sessionId)
      setStoredModelSelection({ provider: model.providerId, model: model.modelId })
      void loadSessions(true)
      navigate(`/chat/${result.sessionId}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : '任务启动失败，请重试')
    } finally {
      startLock.current = false
      setStarting(false)
    }
  }

  return (
    <div className="projects-page">
      <header className="projects-heading">
        <div><span className="projects-eyebrow">AYE · 一键部署</span><h1>让项目上线</h1><p>选择项目和服务器，构建、上传与启动交给 Aye。</p></div>
        <button ref={addRef} type="button" className="project-button" onClick={() => setPickerOpen(true)} disabled={starting}>
          <Plus size={16} /> 添加 / 选择项目
        </button>
      </header>
      {error && <div role="alert" className="project-error">{error}<button type="button" onClick={() => void refresh()} disabled={starting}>重新加载</button></div>}
      {loading ? <p role="status" className="project-empty"><Loader2 className="dsh-spin" size={18} /> 正在加载项目与配置…</p> : (
        <div className="projects-grid">
          <section className="project-list" aria-label="我的项目">
            <h2>我的项目 <span>{projects.length}</span></h2>
            {projects.length === 0 ? <div className="project-empty"><FolderOpen size={28} /><p>还没有项目</p><p>添加本地文件夹，或从 Git 拉取。</p><button type="button" className="project-button" onClick={() => setPickerOpen(true)}>选择第一个项目</button></div>
              : projects.map(ws => <button type="button" key={ws.id} className={`project-tile${project?.id === ws.id ? ' selected' : ''}`} onClick={() => choose(ws)} disabled={starting} aria-pressed={project?.id === ws.id}>
                <FolderOpen size={20} /><span><strong>{ws.title}</strong><small title={ws.canonical_path}>{ws.canonical_path}</small></span>{project?.id === ws.id && <Check size={16} />}
              </button>)}
          </section>
          <section className="project-setup" aria-label="部署设置">
            <div className="project-section-title"><Rocket size={20} /><h2>{project ? `部署 ${project.title}` : '准备一次部署'}</h2></div>
            <p className="project-muted">{project ? '检查目标后开始。每次部署都有独立记录，可随时查看进度。' : '先在左侧选择项目，再指定目标服务器。'}</p>
            <label htmlFor="deploy-host">目标服务器</label>
            <div className="project-host-row">
              <Server size={18} />
              <select id="deploy-host" value={host?.id ?? ''} disabled={starting} onChange={e => useHostStore.getState().selectHost(Number(e.target.value))}>
                <option value="" disabled>请选择服务器</option>
                {hosts.map(h => <option key={h.id} value={h.id}>{h.name} · {h.address}</option>)}
              </select>
              <button type="button" className="project-text-button" onClick={() => settings('hosts')} disabled={starting}>{hosts.length ? '管理' : '添加服务器'}</button>
            </div>
            {host && <p className="project-muted">{host.username}@{host.address}:{host.port} · {connected ? '已连接' : '未连接'}</p>}
            {host && !connected && <HostConnectBar key={host.id} showPrivateKey />}
            <div className="project-model"><span>{model ? `模型：${model.modelId}` : '还没有可用的默认模型'}</span><button className="project-text-button" type="button" disabled={starting} onClick={() => settings('models')}>{model ? '配置模型' : '配置 API Key 与模型'}</button></div>
            <label htmlFor="deploy-notes">部署要求 <span className="project-muted">（可选）</span></label>
            <textarea id="deploy-notes" value={requirements} onChange={e => setRequirements(e.target.value)} disabled={starting} placeholder="例如：使用 8080 端口，部署到测试环境" rows={3} />
            <p className="project-muted">{mode === 'readonly' ? '当前为只读模式，请在高级权限设置中选择允许审批修改的模式。' : mode === 'all_confirm' ? '每次工具操作都需要你确认。' : '读取自动执行，构建、上传和修改先确认。'}</p>
            <button type="button" className="project-button primary" disabled={!project || !host || !model || !connected || starting || !!error || mode === 'readonly'} onClick={() => void start()}>
              {starting ? <Loader2 size={17} className="dsh-spin" /> : <Rocket size={17} />}{starting ? '正在创建部署…' : '开始部署'}
            </button>
            {mode === 'readonly' && <button type="button" className="project-text-button" onClick={() => settings('general')}>设置权限</button>}
          </section>
        </div>
      )}
      <section className="project-history" aria-label="项目部署记录">
        <h2>{project ? `${project.title} · 任务记录` : '部署记录'}</h2>
        {historyError ? <p role="alert" className="project-error">{historyError}<button type="button" onClick={() => void loadSessions(true)}>重试</button></p>
          : !project ? <p className="project-muted">选择项目后查看历史任务。</p>
          : records.length === 0 ? <p className="project-muted">这个项目还没有任务，开始部署后会记录在这里。</p>
          : records.map(record => <button type="button" key={record.id} className="project-record" onClick={() => navigate(`/chat/${record.id}`)}>
            <span><strong>{record.title || '部署任务'}</strong><small>{hosts.find(h => h.id === record.hostId)?.name ?? '原服务器不可用'} · {new Date(record.updatedAt).toLocaleString()}</small></span>
            <span>{record.runningTurn != null || runningIds.includes(record.id) ? '运行中' : '查看记录'}<ArrowRight size={16} /></span>
          </button>)}
      </section>
      <WorkspacePicker open={pickerOpen} anchorRef={addRef} onClose={() => setPickerOpen(false)} onSelect={choose} />
    </div>
  )
}
