import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { FolderOpen, History, PanelLeftClose, PanelLeftOpen, Plus, Rocket, Settings, X } from 'lucide-react'
import { Modal, message } from 'antd'
import { listWorkspaces, type Workspace } from '@/api/deployApi'
import type { SessionInfo } from '@/api/sessionsApi'
import { useSessionsStore } from '@/store/useSessionsStore'
import { destroySessionRuntime, useRunningSessionIds } from '@/store/useSessionRuntime'
import { getLastSessionId, setLastSessionId } from '@/lib/prefs'
import '../../styles/projects.css'

/** 项目为一级导航，部署任务为项目下的记录；未关联项目的旧会话单独保留。 */
export default function AppSidebar() {
  const navigate = useNavigate()
  const location = useLocation()
  const { sessionId } = useParams()
  const [collapsed, setCollapsed] = useState(() => window.matchMedia('(max-width: 640px)').matches)
  const [projects, setProjects] = useState<Workspace[]>([])
  const [projectError, setProjectError] = useState('')
  const sessions = useSessionsStore(s => s.sessions)
  const error = useSessionsStore(s => s.error)
  const load = useSessionsStore(s => s.load)
  const remove = useSessionsStore(s => s.remove)
  const running = useRunningSessionIds()
  const refreshProjects = useCallback(() => {
    void listWorkspaces().then(items => { setProjects(items); setProjectError('') })
      .catch(() => setProjectError('项目加载失败'))
  }, [])

  useEffect(() => {
    refreshProjects()
    void load(true)
  }, [location.pathname, refreshProjects, load])

  useEffect(() => {
    const refresh = () => { refreshProjects(); void load(true) }
    const visible = () => { if (!document.hidden) refresh() }
    window.addEventListener('aye:projects-changed', refresh)
    document.addEventListener('visibilitychange', visible)
    return () => {
      window.removeEventListener('aye:projects-changed', refresh)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [refreshProjects, load])

  const open = (record: SessionInfo) => {
    setLastSessionId(record.id)
    navigate(`/chat/${record.id}`)
  }
  const deleteRecord = (record: SessionInfo) => {
    Modal.confirm({
      title: '删除这条任务记录？', content: '不会删除本地项目或服务器上的应用。', okText: '删除', cancelText: '取消',
      onOk: async () => {
        try {
          await remove(record.id)
          destroySessionRuntime(record.id)
          if (getLastSessionId() === record.id) setLastSessionId(null)
          if (sessionId === record.id) navigate('/projects')
        } catch (e) { message.error(e instanceof Error ? e.message : '删除失败') }
      },
    })
  }
  const recordRow = (record: SessionInfo) => {
    const busy = record.runningTurn != null || running.includes(record.id)
    return <div key={record.id} className={`project-nav-record${sessionId === record.id ? ' active' : ''}`}>
      <button type="button" onClick={() => open(record)} title={record.title}><span className={busy ? 'project-running-dot' : 'project-history-dot'} />{record.title || '部署任务'}</button>
      {!busy && <button type="button" className="project-delete" aria-label={`删除记录：${record.title}`} onClick={() => deleteRecord(record)}><X size={12} /></button>}
    </div>
  }
  const legacy = sessions.filter(s => s.workspaceId == null)

  return <aside className={`app-sidebar project-sidebar${collapsed ? ' is-collapsed' : ''}`} style={{ width: collapsed ? 64 : 244 }}>
    <div className="project-brand"><button type="button" title="项目首页" onClick={() => navigate('/projects')}><Rocket size={22} />{!collapsed && <strong>Aye</strong>}</button>
      {!collapsed && <button type="button" aria-label="收起侧栏" onClick={() => setCollapsed(true)}><PanelLeftClose size={17} /></button>}
    </div>
    {collapsed && <button className="project-nav-link" type="button" aria-label="展开侧栏" onClick={() => setCollapsed(false)}><PanelLeftOpen size={18} /></button>}
    <button className="project-nav-link project-nav-primary" type="button" title="新建部署" onClick={() => navigate('/projects')}><Plus size={18} />{!collapsed && '新建部署'}</button>
    {!collapsed && <div className="project-navigation">
      <div className="project-nav-label">项目与部署</div>
      {projectError && <button className="project-text-button" type="button" onClick={refreshProjects}>{projectError} · 重试</button>}
      {projects.map(project => <details key={project.id} className="project-nav-group" open>
        <summary><FolderOpen size={16} /><span>{project.title}</span></summary>
        <button className="project-nav-detail" type="button" onClick={() => navigate(`/projects?project=${project.id}`)}>项目概览</button>
        {sessions.filter(s => s.workspaceId === project.id).map(recordRow)}
      </details>)}
      {!projects.length && !projectError && <p className="project-muted">添加项目，开始第一次部署。</p>}
      {legacy.length > 0 && <details className="project-nav-group" open={legacy.some(s => s.id === sessionId) || undefined}>
        <summary><History size={16} /><span>历史记录</span></summary>{legacy.map(recordRow)}
      </details>}
      {error && <button className="project-text-button" type="button" onClick={() => void load(true)}>记录加载失败 · 重试</button>}
    </div>}
    <div className="project-sidebar-footer">
      <button type="button" className="project-nav-link" title="设置" onClick={() => navigate('/settings/general', { state: { from: location.pathname + location.search } })}><Settings size={18} />{!collapsed && '设置'}</button>
    </div>
  </aside>
}
