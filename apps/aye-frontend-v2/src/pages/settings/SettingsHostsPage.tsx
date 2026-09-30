import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, Pencil, Trash2, Plug, Unplug, Monitor } from 'lucide-react'
import { Empty, Popconfirm, message } from 'antd'
import type { Host } from '@/api/hostApi'
import { useHostStore } from '@/store/useHostStore'
import HostFormModal from '@/components/servers/HostFormModal'

/**
 * 设置 → 主机：主机列表的唯一 CRUD 入口。
 * 连接/切换在全局主机切换器（⌘K）与对话面板完成；这里只做配置管理。
 */
export default function SettingsHostsPage() {
  const navigate = useNavigate()
  const hosts = useHostStore((s) => s.hosts)
  const serverStates = useHostStore((s) => s.serverStates)
  const loadHosts = useHostStore((s) => s.loadHosts)
  const removeHost = useHostStore((s) => s.removeHost)
  const disconnect = useHostStore((s) => s.disconnect)
  const selectHost = useHostStore((s) => s.selectHost)

  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Host | null>(null)

  useEffect(() => {
    loadHosts().catch(() => message.error('加载主机列表失败'))
  }, [loadHosts])

  const handleRemove = async (host: Host) => {
    if (host.id == null) return
    try {
      await removeHost(host.id)
      message.success(`已删除 ${host.name}`)
    } catch (e: any) {
      message.error(`删除失败：${e?.message || e}`)
    }
  }

  const openConnect = (host: Host) => {
    if (host.id == null) return
    selectHost(host.id)
    navigate('/chat')
  }

  return (
    <div className="settings-pane">
      <div className="settings-pane-head">
        <div>
          <h2 className="settings-pane-title">主机</h2>
          <p className="settings-pane-desc">管理 SSH 主机配置；按 ⌘K 可随时快速连接或切换主机。</p>
        </div>
        <button className="dsh-btn dsh-btn-primary" onClick={() => { setEditing(null); setFormOpen(true) }}>
          <Plus /> 添加主机
        </button>
      </div>

      {hosts.length === 0 ? (
        <div className="settings-empty">
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <div>
                <div className="settings-empty-title">还没有主机</div>
                <div className="settings-empty-hint">添加一台服务器后，即可在对话中直接操作终端与文件</div>
              </div>
            }
          >
            <button className="dsh-btn dsh-btn-primary" onClick={() => { setEditing(null); setFormOpen(true) }}>
              <Plus /> 添加第一台主机
            </button>
          </Empty>
        </div>
      ) : (
        <div className="host-table">
          <div className="host-table-head">
            <span className="host-col-name">名称 / 地址</span>
            <span className="host-col-auth">认证</span>
            <span className="host-col-status">状态</span>
            <span className="host-col-actions" />
          </div>
          {hosts.map((host) => {
            const status = host.id != null ? serverStates[host.id]?.status ?? 'disconnected' : 'disconnected'
            return (
              <div key={host.id} className="host-row">
                <div className="host-col-name host-name-cell">
                  <span className="host-row-icon"><Monitor /></span>
                  <div className="host-name-main">
                    <span className="host-name">{host.name}</span>
                    <span className="host-addr">{host.username}@{host.address}:{host.port}</span>
                  </div>
                </div>
                <div className="host-col-auth">{host.authType === 'PRIVATE_KEY' ? '私钥' : '密码'}</div>
                <div className="host-col-status">
                  <span className={`host-status-pill ${status}`}>
                    <span className={`fb-dot ${status}`} />
                    {status === 'connected' ? '已连接' : status === 'connecting' ? '连接中' : status === 'error' ? '异常' : '未连接'}
                  </span>
                </div>
                <div className="host-col-actions">
                  {status === 'connected' ? (
                    <Popconfirm title={`断开 ${host.name} 的连接？`} okText="断开" cancelText="取消" onConfirm={() => host.id != null && disconnect(host.id)}>
                      <button type="button" className="dsh-btn dsh-btn-sm dsh-btn-ghost host-action-danger">
                        <Unplug /> 断开
                      </button>
                    </Popconfirm>
                  ) : (
                    <button type="button" className="dsh-btn dsh-btn-sm dsh-btn-ghost" onClick={() => openConnect(host)}>
                      <Plug /> 连接
                    </button>
                  )}
                  <button type="button" className="dsh-icon-btn" title="编辑" onClick={() => { setEditing(host); setFormOpen(true) }}>
                    <Pencil />
                  </button>
                  <Popconfirm
                    title={`删除主机 ${host.name}？`}
                    description="删除后不可恢复。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => handleRemove(host)}
                  >
                    <button type="button" className="dsh-icon-btn danger" title="删除">
                      <Trash2 />
                    </button>
                  </Popconfirm>
                </div>
              </div>
            )
          })}
        </div>
      )}

      <HostFormModal open={formOpen} editing={editing} onClose={() => { setFormOpen(false); setEditing(null) }} />
    </div>
  )
}
