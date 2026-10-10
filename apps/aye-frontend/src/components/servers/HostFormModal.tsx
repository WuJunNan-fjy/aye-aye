import { useEffect, useState } from 'react'
import { Modal, message } from 'antd'
import { KeyRound, Lock } from 'lucide-react'
import type { Host } from '@/api/hostApi'
import { useHostStore } from '@/store/useHostStore'

interface HostFormState {
  name: string
  address: string
  port: string
  username: string
  authType: 'PASSWORD' | 'PRIVATE_KEY'
  privateKeyPath: string
}

const EMPTY_FORM: HostFormState = {
  name: '',
  address: '',
  port: '22',
  username: '',
  authType: 'PASSWORD',
  privateKeyPath: '',
}

/**
 * 主机新增/编辑弹窗（唯一的主机表单容器）。
 * 密码只用于连接、不落库；编辑时不回填密码。
 */
export default function HostFormModal(props: {
  open: boolean
  /** 传入主机=编辑；null=新增 */
  editing: Host | null
  onClose: () => void
  onSaved?: (host: Host) => void
}) {
  const { open, editing, onClose, onSaved } = props
  const addHost = useHostStore((s) => s.addHost)
  const updateHost = useHostStore((s) => s.updateHost)
  const [form, setForm] = useState<HostFormState>(EMPTY_FORM)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!open) return
    setForm(editing
      ? {
          name: editing.name,
          address: editing.address,
          port: String(editing.port),
          username: editing.username,
          authType: editing.authType,
          privateKeyPath: editing.privateKeyPath || '',
        }
      : EMPTY_FORM)
  }, [open, editing])

  const set = (patch: Partial<HostFormState>) => setForm((f) => ({ ...f, ...patch }))

  const save = async () => {
    if (!form.name.trim() || !form.address.trim() || !form.username.trim()) {
      message.warning('名称 / 地址 / 用户名必填')
      return
    }
    const payload = {
      name: form.name.trim(),
      address: form.address.trim(),
      port: parseInt(form.port, 10) || 22,
      username: form.username.trim(),
      authType: form.authType,
      privateKeyPath: form.authType === 'PRIVATE_KEY' ? form.privateKeyPath.trim() || undefined : undefined,
    }
    setSaving(true)
    try {
      const saved = editing?.id
        ? await updateHost(editing.id, { ...editing, ...payload })
        : await addHost(payload as Host)
      message.success(editing ? '已更新主机' : '已添加主机')
      onSaved?.(saved)
      onClose()
    } catch (e: any) {
      message.error(`保存失败：${e?.message || e}`)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title={editing ? '编辑主机' : '添加主机'}
      open={open}
      onCancel={onClose}
      onOk={save}
      confirmLoading={saving}
      okText={editing ? '保存修改' : '添加'}
      cancelText="取消"
      width={520}
      destroyOnClose
      maskClosable={false}
      className="host-form-modal"
    >
      <div className="host-form-grid">
        <label className="host-form-field">
          <span>名称</span>
          <input className="dsh-input" value={form.name} onChange={(e) => set({ name: e.target.value })} placeholder="生产服务器" autoFocus />
        </label>
        <label className="host-form-field host-form-field-addr">
          <span>地址</span>
          <input className="dsh-input" value={form.address} onChange={(e) => set({ address: e.target.value })} placeholder="192.168.1.10" />
        </label>
        <label className="host-form-field host-form-field-port">
          <span>端口</span>
          <input className="dsh-input" value={form.port} onChange={(e) => set({ port: e.target.value })} placeholder="22" />
        </label>
        <label className="host-form-field">
          <span>用户名</span>
          <input className="dsh-input" value={form.username} onChange={(e) => set({ username: e.target.value })} placeholder="root" />
        </label>
        <label className="host-form-field">
          <span>认证方式</span>
          <div className="host-auth-switch">
            <button
              type="button"
              className={`dsh-pill interactive ${form.authType === 'PASSWORD' ? 'active' : ''}`}
              onClick={() => set({ authType: 'PASSWORD' })}
            >
              <Lock /> 密码
            </button>
            <button
              type="button"
              className={`dsh-pill interactive ${form.authType === 'PRIVATE_KEY' ? 'active' : ''}`}
              onClick={() => set({ authType: 'PRIVATE_KEY' })}
            >
              <KeyRound /> 私钥
            </button>
          </div>
        </label>
        {form.authType === 'PRIVATE_KEY' && (
          <label className="host-form-field">
            <span>私钥路径</span>
            <input className="dsh-input" value={form.privateKeyPath} onChange={(e) => set({ privateKeyPath: e.target.value })} placeholder="/root/.ssh/id_rsa" />
          </label>
        )}
        {form.authType === 'PASSWORD' && (
          <div className="host-form-hint">密码不保存；连接时在主机切换器或终端面板中输入，仅缓存在内存中。</div>
        )}
      </div>
    </Modal>
  )
}
