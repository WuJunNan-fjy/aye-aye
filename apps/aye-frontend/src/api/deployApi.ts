/**
 * 部署数据层：工作区 CRUD / 绑定。
 *
 * 对应后端 /api/workspaces。
 */

import request from '@/api/request'

export interface DefaultDeployModel { providerId: string; modelId: string }

export async function getDefaultDeployModel(): Promise<DefaultDeployModel | null> {
  const data = await request.get('/models/default') as { default: DefaultDeployModel | null }
  return data.default
}

export interface Workspace {
  id: number
  canonical_path: string
  title: string
  source: string // local / git
  git_url: string | null
  created_at: number
  last_used_at: number
}

export async function listWorkspaces(): Promise<Workspace[]> {
  const data = (await request.get('/workspaces')) as { workspaces: Workspace[] }
  return data.workspaces
}

/** 登记工作区（选本地目录后调用；git clone 完成后同样调用）。 */
export async function createWorkspace(body: {
  path: string
  title?: string
  source?: 'local' | 'git'
  gitUrl?: string
  /** 桌面端主进程颁发的路径来源凭证（登记本机目录时必填） */
  pathToken?: string
}): Promise<Workspace> {
  return (await request.post('/workspaces', body)) as Workspace
}

export async function cloneGitWorkspace(
  url: string,
  branch?: string,
): Promise<Workspace> {
  return (await request.post('/workspaces/clone-git', { url, branch })) as Workspace
}

export async function renameWorkspace(id: number, title: string): Promise<Workspace> {
  return (await request.post(`/workspaces/${id}/rename`, { title })) as Workspace
}

export async function removeWorkspace(id: number): Promise<{ ok: boolean }> {
  return (await request.delete(`/workspaces/${id}`)) as { ok: boolean }
}

/** 绑定工作区到会话（选中时立即调用）。 */
export async function bindWorkspace(sessionId: string, workspaceId: number): Promise<void> {
  await request.post('/workspaces/bind', { sessionId, workspaceId })
}

/** 解绑会话当前工作区。 */
export async function unbindWorkspace(sessionId: string): Promise<void> {
  await request.post('/workspaces/unbind', { sessionId })
}

/** 查会话当前绑定的工作区（刷新/重启后回填）；未绑定返回 null。 */
export async function getBoundWorkspace(sessionId: string): Promise<Workspace | null> {
  const data = (await request.get('/workspaces/bound', {
    params: { sessionId },
  })) as { workspace: Workspace | null }
  return data.workspace
}
