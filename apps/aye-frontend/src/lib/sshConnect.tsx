/**
 * 统一的 SSH 连接流程（任何页面/面板都可调用）：
 *   1. 组装凭据（显式传入 → 内存缓存 → 私钥免密）；
 *   2. 走 store.connect；
 *   3. need_trust（首连/指纹变更）时弹 antd 确认框，信任后自动重连。
 *
 * 成功后凭据由 store 缓存（仅内存），主机选择写入 localStorage；
 * 这样对话页右侧终端、文件面板都能原地连接，不必跳到「服务器」页。
 */
import { Modal, message } from 'antd'
import sshApi from '@/api/sshApi'
import { getCachedCredentials, useHostStore, type PendingTrust } from '@/store/useHostStore'
import type { Host } from '@/api/hostApi'

export type ConnectAttemptResult = 'connected' | 'need_password' | 'failed' | 'cancelled'

/** 弹主机指纹确认框；用户确认 → 录入指纹并重连。 */
function confirmTrustAndRetry(
  host: Host,
  credentials: { password?: string; privateKey?: string; passphrase?: string },
  trust: PendingTrust,
): Promise<ConnectAttemptResult> {
  return new Promise((resolve) => {
    const isChanged = trust.reason === 'CHANGED'
    Modal.confirm({
      title: isChanged ? '主机密钥指纹已变化' : '确认主机密钥指纹',
      content: (
        <div style={{ fontSize: 13, lineHeight: 1.7 }}>
          <p>
            服务器 <b>{trust.address}:{trust.port}</b> 的主机密钥指纹：
          </p>
          <code style={{ wordBreak: 'break-all', userSelect: 'all' }}>SHA256:{trust.fingerprint}</code>
          {isChanged ? (
            <p style={{ color: 'var(--dsw-alias-state-warn-primary)', marginTop: 8 }}>
              该指纹与此前记录不一致，可能是服务器密钥已更换，也可能是中间人攻击。请确认来源可信后再信任。
            </p>
          ) : (
            <p style={{ color: 'var(--dsw-alias-label-tertiary)', marginTop: 8 }}>
              首次连接该服务器，信任该指纹即完成录入（后续指纹一致才放行）。
            </p>
          )}
        </div>
      ),
      okText: '信任并连接',
      cancelText: '取消',
      onOk: async () => {
        try {
          await sshApi.trust(trust.address, trust.port, trust.fingerprint)
        } catch (e: any) {
          message.error(`信任失败：${e?.message || e}`)
          resolve('failed')
          return
        }
        const r = await useHostStore.getState().connect(host.id!, credentials)
        resolve(r.status === 'connected' ? 'connected' : 'failed')
      },
      onCancel: () => resolve('cancelled'),
    })
  })
}

/**
 * 连接指定主机。
 * @param password 本次显式输入的密码（输入框回车场景）；不传则尝试缓存/私钥
 */
export async function connectHost(hostId: number, password?: string): Promise<ConnectAttemptResult> {
  const { hosts, connect } = useHostStore.getState()
  const host = hosts.find((h) => h.id === hostId)
  if (!host?.id) return 'failed'

  const cached = getCachedCredentials(hostId)
  const credentials =
    password !== undefined
      ? { password }
      : cached !== undefined
        ? cached
        : host.authType === 'PRIVATE_KEY'
          ? {}
          : undefined

  if (credentials === undefined) return 'need_password'

  const res = await connect(hostId, credentials)
  if (res.status === 'connected') {
    message.success(`已连接 ${host.name}`)
    return 'connected'
  }
  if (res.status === 'need_trust' && res.pendingTrust) {
    return confirmTrustAndRetry(host, credentials, res.pendingTrust)
  }
  const errMsg = useHostStore.getState().serverStates[hostId]?.error
  message.error(errMsg ? `连接失败：${errMsg}` : `连接 ${host.name} 失败，请检查凭据`)
  return 'failed'
}
