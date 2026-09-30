import axios from 'axios'
import { API_BASE, installAuthInterceptor } from '@/api/request'

/**
 * 模型配置 REST 数据层（直连 Spring Boot sidecar）：
 * 厂商 Profile 走 /api/models/vendors，已保存配置走 /api/models/providers，
 * 前端合并出完整 ProviderProfile 列表。
 */

// ── 前端契约类型 ──

export interface ModelDraft {
  id: string
  name?: string
  contextWindow?: number
  maxTokens?: number
  /** 支持思考模式（v2 特色，dsh 无此维度，做在行内 chip） */
  thinking?: boolean
}

export interface PopularModel {
  id: string
  name?: string
  thinking?: boolean
}

export interface ProviderProfile {
  /** 路由 id（厂商 key） */
  id: string
  /** 卡片标题显示名 */
  displayName: string
  /** 行内 route 标签（显示在名字旁的小 tag） */
  route: string
  baseUrl: string
  /** wire 协议：openai / anthropic */
  api: 'openai' | 'anthropic'
  /** 是否已配置 API key（credentialDot 绿/红） */
  configured: boolean
  /** 推荐模型（一键添加） */
  popular: PopularModel[]
  /** 已添加的模型目录 */
  models: ModelDraft[]
  /** custom 专用：是否用户手填的自定义厂商 */
  custom?: boolean
}

// ── 后端 wire 类型（/api/models/vendors + /api/models/providers） ──

interface VendorInfo {
  id: string
  name: string
  icon: string
  iconUrl: string
  defaultBaseUrl: string
  sdkProvider: 'openai' | 'anthropic'
  docsUrl: string | null
  popularModels: { id: string; name: string; supportsThinking: boolean | null }[]
}

interface ServerModel {
  modelId: string
  name?: string
  contextWindow?: number
  maxTokens?: number
  thinking?: boolean
}

interface ServerProvider {
  id: string
  name: string
  icon: string
  iconUrl: string
  defaultBaseUrl: string
  sdkProvider: string
  docsUrl: string | null
  popularModels: { id: string; name: string; supportsThinking: boolean | null }[]
  baseUrl: string
  displayName: string
  enabled: boolean
  hasKey: boolean
  models: ServerModel[]
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

// ── 映射：厂商 Profile + 已保存配置 → ProviderProfile ──

function toProviderProfile(vendor: VendorInfo, saved?: ServerProvider): ProviderProfile {
  return {
    id: vendor.id,
    displayName: saved?.displayName || vendor.name,
    route: vendor.id,
    baseUrl: saved?.baseUrl || vendor.defaultBaseUrl,
    api: vendor.sdkProvider,
    configured: saved?.hasKey ?? false,
    popular: vendor.popularModels.map(m => ({
      id: m.id,
      name: m.name,
      thinking: m.supportsThinking ?? undefined,
    })),
    models: (saved?.models ?? []).map(m => ({
      id: m.modelId,
      name: m.name,
      contextWindow: m.contextWindow,
      maxTokens: m.maxTokens,
      thinking: m.thinking,
    })),
    custom: vendor.id === 'custom',
  }
}

// ── 接口函数 ──

/** 厂商 Profile（未保存的厂商也包含，configured=false、空模型目录）。 */
export async function listVendors(): Promise<VendorInfo[]> {
  const res = await client.get('/models/vendors')
  return res.data.vendors as VendorInfo[]
}

/** 已保存的厂商配置（key 已脱敏为 hasKey）。 */
export async function listSavedProviders(): Promise<ServerProvider[]> {
  const res = await client.get('/models/providers')
  return res.data.providers as ServerProvider[]
}

/** 合并 vendors + 已保存配置 → 页面渲染用的完整列表（含未配置的内置厂商）。 */
export async function listModels(): Promise<ProviderProfile[]> {
  const [vendors, saved] = await Promise.all([listVendors(), listSavedProviders()])
  const byId = new Map(saved.map(p => [p.id, p]))
  return vendors.map(v => toProviderProfile(v, byId.get(v.id)))
}

/**
 * 已保存配置的厂商列表（dsh provider 中心制：未配置的内置厂商不占行，
 * 通过"添加提供方"入口从 vendors 目录选出）。custom 单例保存后也在此。
 */
export async function listConfiguredProviders(): Promise<ProviderProfile[]> {
  const [vendors, saved] = await Promise.all([listVendors(), listSavedProviders()])
  const byId = new Map(vendors.map(v => [v.id, v]))
  return saved.flatMap(s => {
    const vendor = byId.get(s.id)
    return vendor === undefined ? [] : [toProviderProfile(vendor, s)]
  })
}

/** 新建/更新厂商配置（displayName/baseUrl/enabled 传 undefined 表示保持不变）。 */
export async function saveProvider(
  id: string,
  input: { displayName?: string; baseUrl?: string; enabled?: boolean },
): Promise<ServerProvider> {
  const res = await client.put(`/models/providers/${encodeURIComponent(id)}`, input)
  return res.data.provider as ServerProvider
}

/** 设置/更换 API Key（空串 = 清除）。 */
export async function setProviderKey(id: string, apiKey: string): Promise<void> {
  await client.put(`/models/providers/${encodeURIComponent(id)}/key`, { apiKey })
}

/** 删除厂商配置（连同 key 与模型目录）。 */
export async function deleteProvider(id: string): Promise<void> {
  await client.delete(`/models/providers/${encodeURIComponent(id)}`)
}

/** 向厂商目录添加/更新模型（upsert；高级字段传 undefined 表示清除）。 */
export async function addProviderModel(id: string, model: ModelDraft): Promise<void> {
  await client.post(`/models/providers/${encodeURIComponent(id)}/models`, {
    modelId: model.id,
    name: model.name,
    contextWindow: model.contextWindow ?? null,
    maxTokens: model.maxTokens ?? null,
    thinking: model.thinking ?? null,
  })
}

/** 从厂商目录删除模型。 */
export async function removeProviderModel(id: string, modelId: string): Promise<void> {
  await client.delete(`/models/providers/${encodeURIComponent(id)}/models/${encodeURIComponent(modelId)}`)
}

export interface DiscoverResult {
  success: boolean
  models?: string[]
  message?: string
}

/** 端点探测：GET {baseUrl}/models 拉取该端点可用的模型 id 列表（免费不耗 token）。 */
export async function discoverModels(input: { provider: string; baseUrl?: string; apiKey?: string }): Promise<DiscoverResult> {
  const res = await client.post('/models/discover', input)
  return res.data as DiscoverResult
}
