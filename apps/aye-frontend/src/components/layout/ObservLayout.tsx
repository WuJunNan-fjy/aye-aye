import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'

/**
 * 诊断页共享返回路径，切换日志和轨迹后仍能回到原任务。
 */
export default function ObservLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const from = typeof location.state?.from === 'string' && location.state.from.startsWith('/chat/')
    ? location.state.from : '/projects'
  return (
    <div className="obs-body">
      <nav className="diagnostics-nav" aria-label="诊断记录">
        <button type="button" className="project-text-button" onClick={() => navigate(from)}>{from === '/projects' ? '返回项目' : '返回任务'}</button>
        <NavLink to="/observability/traces" state={{ from }}>任务轨迹</NavLink>
        <NavLink to="/observability/logs" state={{ from }}>操作日志</NavLink>
      </nav>
      <Outlet />
    </div>
  )
}
