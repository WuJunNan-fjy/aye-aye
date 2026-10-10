import axios from 'axios'
import { API_BASE, installAuthInterceptor } from '@/api/request'

/**
 * Settings → General 页的 REST 数据层（直连 Spring Boot sidecar，端口 8080）。
 * GET/PUT/DELETE /api/settings —— 与 SettingsController + SettingsStore 契约对齐：
 * 标量存字符串、对象存 JSON，部分更新只写 patch 里非 null 的 key。
 */

export interface GeneralSettings {
  defaultExecutionMode: string
  terminalFontSize: number
  terminalFontFamily: string
  terminalScrollback: number
  terminalTheme: string
  language: string
  autoAcceptPermissions: boolean
  webSearch: { provider: string; apiKey: string }
  notifications: { agent: boolean; permissions: boolean; errors: boolean }
  keybinds: Record<string, string>
}

const client = axios.create({
  baseURL: API_BASE,
  timeout: 15000,
})
installAuthInterceptor(client)

/** 把 axios/后端错误整理成可展示的中文消息。 */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string } | undefined
    if (data?.message) return data.message
    if (err.code === 'ECONNABORTED') return '请求超时，请检查后端服务是否已启动'
    if (err.code === 'ERR_NETWORK') return '无法连接后端（:8080），请确认 Aye 后端已启动'
    return err.message
  }
  return err instanceof Error ? err.message : String(err)
}

/** 读取完整设置（后端会合并默认值）。 */
export async function getSettings(): Promise<GeneralSettings> {
  const res = await client.get('/settings')
  return res.data.settings as GeneralSettings
}

/** 部分更新（只写 patch 里的 key），返回更新后的完整设置。 */
export async function updateSettings(patch: Partial<GeneralSettings>): Promise<GeneralSettings> {
  const res = await client.put('/settings', patch)
  return res.data.settings as GeneralSettings
}
