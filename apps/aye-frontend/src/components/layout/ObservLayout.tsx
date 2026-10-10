import { Outlet } from 'react-router-dom'

/**
 * 观测中心骨架：仅内容容器。
 * 日志 / 追踪 / 备份 的二级导航已并入应用侧栏（观测区主体），
 * 子页不再自带页头与 Tab。
 */
export default function ObservLayout() {
  return (
    <div className="obs-body">
      <Outlet />
    </div>
  )
}
