import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { ArrowLeft, ArrowRight, Check, ChevronRight, Clock3, FolderOpen, Loader2, Plus, Rocket, Server, ShieldCheck, Sparkles } from 'lucide-react'
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
  const [projectRequirements, setProjectRequirements] = useState<Record<string, string>>({})
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
  const requirements = project ? projectRequirements[project.id] ?? '' : ''
  const setRequirements = (value: string) => {
    if (project) setProjectRequirements(previous => ({ ...previous, [project.id]: value }))
  }
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

  const blockedReason = !project ? '请先选择一个项目'
    : !host ? '添加并选择目标服务器后即可继续'
    : !connected ? '请先连接目标服务器'
    : !model ? '配置 AI 模型后即可开始部署'
    : mode === 'readonly' ? '当前为只读模式，请先调整部署权限'
    : error ? '请先重试加载配置' : ''
  const permissionLabel = mode === 'readonly' ? '只读模式' : mode === 'all_confirm' ? '全部操作确认' : '修改前确认'
  const activeRecords = (project ? records : sessions).filter(s => s.runningTurn != null || runningIds.includes(s.id)).length

  return (
    <div className="projects-page">
      <div className="projects-content">
        <header className="projects-heading">
          <div className="project-heading-copy">
            {project ? <button type="button" className="project-back" disabled={starting} onClick={() => setParams({})}><ArrowLeft size={14} />全部项目</button>
              : <span className="projects-eyebrow">工作空间</span>}
            <div className="project-heading-title">{project && <span className="project-heading-icon"><FolderOpen size={23} /></span>}<h1>{project?.title ?? '项目'}</h1></div>
            <p title={project?.canonical_path}>{project ? project.canonical_path : '从本地代码到服务器，在这里完成每一次部署。'}</p>
          </div>
          {!project && <button ref={addRef} type="button" className={`project-button ${projects.length ? '' : 'primary'}`} onClick={() => setPickerOpen(true)} disabled={starting}><Plus size={16} />添加项目</button>}
          {project && <span className="project-header-meta">{activeRecords ? `${activeRecords} 个任务运行中` : '项目工作台'}</span>}
        </header>

        {error && <div role="alert" className="project-error">{error}<button type="button" onClick={() => void refresh()} disabled={starting}>重新加载</button></div>}
        {loading ? <div role="status" className="project-loading"><Loader2 className="dsh-spin" size={20} />正在加载工作空间…</div> : !project ? <>
          {projects.length === 0 ? <section className="project-onboarding">
            <div className="project-onboarding-icon"><FolderOpen size={32} /></div>
            <h2>从你的第一个项目开始</h2>
            <p>选择本地文件夹，或从 Git 拉取项目。<br />Aye 会协助你检查、构建并部署到服务器。</p>
            <button type="button" className="project-button primary" onClick={() => setPickerOpen(true)}><Plus size={16} />添加第一个项目</button>
            <div className="project-onboarding-steps"><span><b>1</b>添加项目</span><ChevronRight size={14} /><span><b>2</b>连接服务器</span><ChevronRight size={14} /><span><b>3</b>开始部署</span></div>
          </section> : <section aria-label="我的项目">
            <div className="project-section-heading"><h2>我的项目 <span>{projects.length}</span></h2><span>选择项目，进入部署工作台</span></div>
            <div className="project-catalog">{projects.map(ws => {
              const history = sessions.filter(s => s.workspaceId === ws.id)
              const busy = history.some(s => s.runningTurn != null || runningIds.includes(s.id))
              return <button type="button" key={ws.id} className="project-card" onClick={() => choose(ws)}>
                <div className="project-card-top"><span className="project-card-icon"><FolderOpen size={21} /></span>{busy && <span className="project-status ready">运行中</span>}<ArrowRight size={17} className="project-card-arrow" /></div>
                <strong>{ws.title}</strong><small title={ws.canonical_path}>{ws.canonical_path}</small>
                <div className="project-card-footer"><span>{history.length ? `${history.length} 条任务记录` : '还没有部署记录'}</span><span>打开项目</span></div>
              </button>
            })}</div>
          </section>}
        </> : <>
          <section className="project-deploy-panel" aria-label="部署设置">
            <div className="project-panel-heading"><span className="project-panel-icon"><Rocket size={18} /></span><div><h2>部署到服务器</h2><p>确认目标，Aye 将按权限设置执行构建、上传与启动。</p></div></div>
            <div className="project-deploy-grid">
              <div className="project-target">
                <div className="project-field-heading"><label htmlFor="deploy-host">目标服务器</label><button type="button" className="project-text-button" onClick={() => settings('hosts')} disabled={starting}>{hosts.length ? '管理服务器' : '添加服务器'}<ChevronRight size={13} /></button></div>
                {hosts.length ? <div className="project-host-select"><Server size={18} /><select id="deploy-host" value={host?.id ?? ''} disabled={starting} onChange={e => useHostStore.getState().selectHost(Number(e.target.value))}><option value="" disabled>选择目标服务器</option>{hosts.map(h => <option key={h.id} value={h.id}>{h.name === h.address ? h.name : `${h.name} · ${h.address}`}</option>)}</select></div>
                  : <button type="button" className="project-host-empty" onClick={() => settings('hosts')}><Server size={21} /><span>还没有服务器<small>添加一台 Linux 服务器作为部署目标</small></span><Plus size={18} /></button>}
                {host && <div className="project-host-caption"><span>{host.username} · 端口 {host.port}</span>{connected && <span className="project-status ready"><Check size={12} />已连接</span>}</div>}
                {host && !connected && <HostConnectBar key={host.id} showPrivateKey compact />}
              </div>
              <div className="project-readiness" aria-label="部署准备">
                <div className="project-field-heading"><span>部署准备</span><span className="project-muted">自动检查</span></div>
                <button type="button" className="project-check-row" onClick={() => settings('models')} disabled={starting}><Sparkles size={17} /><span><strong>AI 模型</strong><small>{model?.modelId ?? '配置模型以启用部署'}</small></span><span className={`project-status ${model ? 'ready' : 'pending'}`}>{model ? <Check size={14} /> : '待配置'}</span><ChevronRight size={14} /></button>
                <button type="button" className="project-check-row" onClick={() => settings('general')} disabled={starting}><ShieldCheck size={17} /><span><strong>操作权限</strong><small>{permissionLabel}</small></span><span className={`project-status ${mode !== 'readonly' ? 'ready' : 'pending'}`}>{mode !== 'readonly' ? <Check size={14} /> : '待调整'}</span><ChevronRight size={14} /></button>
              </div>
            </div>
            <details className="project-notes"><summary>补充部署要求 <span>{requirements.trim() ? '已填写' : '可选 · 端口、目录或启动方式'}</span><Plus size={14} /></summary><div><label className="project-sr-only" htmlFor="deploy-notes">部署要求</label><textarea id="deploy-notes" value={requirements} onChange={e => setRequirements(e.target.value)} disabled={starting} placeholder="例如：使用 8080 端口，部署到测试环境" rows={3} /></div></details>
            <div className="project-deploy-footer"><div aria-live="polite"><strong>{blockedReason || '准备就绪，可以开始部署'}</strong><small>{blockedReason ? '完成以上准备后，部署按钮将自动启用。' : '每次部署都会创建独立任务，执行过程可随时查看。'}</small></div><button type="button" className="project-button primary" disabled={!!blockedReason || starting || loading} onClick={() => void start()}>{starting ? <Loader2 size={17} className="dsh-spin" /> : <Rocket size={17} />}{starting ? '正在创建…' : '开始部署'}</button></div>
          </section>
          <section className="project-history" aria-label="项目部署记录">
            <div className="project-section-heading"><h2>任务记录 <span>{records.length}</span></h2><span>{activeRecords ? `${activeRecords} 个正在运行` : '保留每一次执行过程'}</span></div>
            {historyError ? <p role="alert" className="project-error">{historyError}<button type="button" onClick={() => void loadSessions(true)}>重试</button></p>
              : records.length === 0 ? <div className="project-history-empty"><Clock3 size={23} /><div><strong>还没有部署记录</strong><p>第一次部署后，进度、审批和执行结果都会保存在这里。</p></div></div>
              : <div className="project-record-list">{records.map(record => {
                const busy = record.runningTurn != null || runningIds.includes(record.id)
                return <button type="button" key={record.id} className="project-record" onClick={() => navigate(`/chat/${record.id}`)}><span className="project-record-icon"><Clock3 size={17} /></span><span className="project-record-copy"><strong>{record.title || '部署任务'}</strong><small>{hosts.find(h => h.id === record.hostId)?.name ?? '原服务器不可用'} · {new Date(record.updatedAt).toLocaleString()}</small></span><span className={`project-status ${busy ? 'ready' : ''}`}>{busy ? '运行中' : '查看记录'}</span><ChevronRight size={16} /></button>
              })}</div>}
          </section>
        </>}
        <WorkspacePicker open={pickerOpen} anchorRef={addRef} onClose={() => setPickerOpen(false)} onSelect={choose} />
      </div>
    </div>
  )
}
