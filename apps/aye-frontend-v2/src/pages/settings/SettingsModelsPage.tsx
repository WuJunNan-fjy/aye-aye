import { useCallback, useEffect, useState } from 'react'
import type { ProviderProfile } from '@/api/modelApi'
import {
  addProviderModel,
  deleteProvider,
  errorMessage,
  listConfiguredProviders,
  listVendors,
  removeProviderModel,
  saveProvider,
  setProviderKey,
} from '@/api/modelApi'
import ProviderEditor from '@/components/settings/ProviderEditor'

/**
 * Settings → Models 页 —— 1:1 复刻 dsh ModelsSection.tsx 的 DOM/CSS 与添加入口。
 * Translate：
 *   - 列表只显示「已保存配置」的厂商（dsh provider 中心制；未配置的内置厂商不占行）；
 *   - 底部两个等宽虚线按钮：「添加提供方」（从内置 vendors 目录选一个未添加的，
 *     创建配置后自动展开编辑卡填 Key）、「添加自定义提供方」（手填名称/BaseURL/Key，custom 单例）；
 *   - 删除厂商后它回到「添加提供方」选项中，可重新添加（替代旧的重置内置厂商）。
 */

interface CustomDraft {
  displayName: string
  baseUrl: string
  apiKey: string
}

const emptyCustom: CustomDraft = { displayName: '', baseUrl: '', apiKey: '' }

type AddPanel = 'none' | 'builtin' | 'custom'

export default function SettingsModelsPage() {
  const [providers, setProviders] = useState<ProviderProfile[]>([])
  const [vendors, setVendors] = useState<{ id: string; name: string }[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | undefined>(undefined)
  const [panel, setPanel] = useState<AddPanel>('none')
  const [builtinId, setBuiltinId] = useState('')
  const [builtinBusy, setBuiltinBusy] = useState(false)
  const [custom, setCustom] = useState<CustomDraft>(emptyCustom)
  const [customError, setCustomError] = useState<string | undefined>(undefined)
  const [customBusy, setCustomBusy] = useState(false)
  /** 添加成功后自动展开编辑卡的厂商 id（ProviderEditor defaultEditing）。 */
  const [openId, setOpenId] = useState<string | null>(null)

  const reload = useCallback(async (): Promise<void> => {
    try {
      const [configured, vend] = await Promise.all([listConfiguredProviders(), listVendors()])
      setProviders(configured)
      setVendors(vend.map(v => ({ id: v.id, name: v.name })))
      setLoadError(undefined)
    } catch (err) {
      setLoadError(errorMessage(err))
    }
  }, [])

  useEffect(() => {
    setLoading(true)
    reload().finally(() => setLoading(false))
  }, [reload])

  const savedIds = new Set(providers.map(p => p.id))
  /** 可添加的内置厂商：vendors 目录中尚未保存配置的（custom 走单独入口）。 */
  const addable = vendors.filter(v => v.id !== 'custom' && !savedIds.has(v.id))
  const customSaved = savedIds.has('custom')

  /** ProviderEditor 保存：厂商配置 + key + 模型目录 diff，全部落库后刷新。 */
  const applyProvider = async (prev: ProviderProfile, next: ProviderProfile, keyValue: string): Promise<void> => {
    await saveProvider(next.id, {
      displayName: next.displayName,
      baseUrl: next.baseUrl,
      enabled: true,
    })
    if (keyValue.length > 0) await setProviderKey(next.id, keyValue)
    const prevIds = new Set(prev.models.map(m => m.id))
    for (const model of next.models) {
      if (model.id.trim().length === 0) continue
      await addProviderModel(next.id, model)
    }
    for (const modelId of prevIds) {
      if (!next.models.some(m => m.id === modelId)) {
        await removeProviderModel(next.id, modelId)
      }
    }
    setOpenId(null)
    await reload()
  }

  const deleteOne = async (id: string): Promise<void> => {
    await deleteProvider(id)
    setOpenId(prev => (prev === id ? null : prev))
    await reload()
  }

  /** 添加提供方：选中内置厂商即创建配置（默认 baseUrl），刷新后自动展开其编辑卡。 */
  const addBuiltin = async (id: string): Promise<void> => {
    if (id.length === 0) return
    setBuiltinBusy(true)
    setLoadError(undefined)
    try {
      await saveProvider(id, { enabled: true })
      setPanel('none')
      setBuiltinId('')
      await reload()
      setOpenId(id)
    } catch (err) {
      setLoadError(errorMessage(err))
    } finally {
      setBuiltinBusy(false)
    }
  }

  const saveCustom = async (): Promise<void> => {
    const name = custom.displayName.trim()
    const url = custom.baseUrl.trim()
    if (name.length === 0) { setCustomError('请填写厂商名称'); return }
    if (url.length === 0 || !/^https?:\/\//.test(url)) {
      setCustomError('自定义厂商必须填写以 http:// 或 https:// 开头的 Base URL')
      return
    }
    setCustomBusy(true)
    setCustomError(undefined)
    try {
      await saveProvider('custom', { displayName: name, baseUrl: url, enabled: true })
      if (custom.apiKey.trim().length > 0) await setProviderKey('custom', custom.apiKey.trim())
      await reload()
      setOpenId('custom')
      setCustom(emptyCustom)
      setPanel('none')
    } catch (err) {
      setCustomError(errorMessage(err))
    } finally {
      setCustomBusy(false)
    }
  }

  if (loading) {
    return (
      <div className="section">
        <h2 className="title">模型</h2>
        <p className="intro">配置模型厂商的 API Key、Base URL 与模型目录；绿色圆点表示该厂商已配置密钥。</p>
        <p className="error" style={{ opacity: 0.7 }}>加载中…</p>
      </div>
    )
  }

  return (
    <div className="section">
      <h2 className="title">模型</h2>
      <p className="intro">
        配置模型厂商的 API Key、Base URL 与模型目录；绿色圆点表示该厂商已配置密钥。
      </p>

      {loadError !== undefined ? <p className="error">{loadError}</p> : null}

      <ul className="rows">
        {providers.map(provider => (
          <ProviderEditor
            key={provider.id}
            provider={provider}
            defaultEditing={openId === provider.id}
            onApply={(next, keyValue) => applyProvider(provider, next, keyValue)}
            onDelete={() => deleteOne(provider.id)}
          />
        ))}
        {providers.length === 0 ? (
          <p className="intro" style={{ padding: '8px 2px' }}>
            尚未添加任何提供方，点击下方「添加提供方」选择内置厂商，或「添加自定义提供方」接入 OpenAI 兼容网关。
          </p>
        ) : null}
      </ul>

      <div className="addBlock">
        {panel === 'builtin' ? (
          <div className="addCard">
            <div className="editorHeader">
              <span className="editorTitle">添加提供方</span>
            </div>
            <div className="field">
              <span className="fieldLabel">选择提供方</span>
              <select
                className="input selectInput"
                value={builtinId}
                aria-label="选择提供方"
                disabled={builtinBusy}
                onChange={(event) => {
                  setBuiltinId(event.target.value)
                  void addBuiltin(event.target.value)
                }}
              >
                <option value="" disabled>{builtinBusy ? '添加中…' : '请选择要添加的提供方…'}</option>
                {addable.map(v => (
                  <option key={v.id} value={v.id}>{v.name}</option>
                ))}
              </select>
              <p className="advancedHint">选择后将创建该提供方配置，并自动展开卡片供你填写 API Key 与模型目录。</p>
            </div>
            <div className="editorActions">
              <button type="button" className="secondaryButton" disabled={builtinBusy} onClick={() => { setPanel('none'); setBuiltinId('') }}>
                取消
              </button>
            </div>
          </div>
        ) : panel === 'custom' ? (
          <div className="addCard">
            <div className="editorHeader">
              <span className="editorTitle">添加自定义提供方</span>
              <span className="editorRoute">custom</span>
            </div>

            <div className="field">
              <span className="fieldLabel">厂商名称（备注）</span>
              <input
                className="input"
                type="text"
                value={custom.displayName}
                placeholder="如：我的内网网关"
                aria-label="厂商名称"
                onChange={(event) => { setCustom(c => ({ ...c, displayName: event.target.value })) }}
              />
            </div>

            <div className="field">
              <span className="fieldLabel">Base URL（必填）</span>
              <input
                className="input"
                type="text"
                value={custom.baseUrl}
                placeholder="https://gateway.example.com/v1"
                aria-label="Base URL"
                onChange={(event) => { setCustom(c => ({ ...c, baseUrl: event.target.value })) }}
              />
              <p className="advancedHint">自定义提供方必须填写请求地址（http/https），协议为 OpenAI 兼容。</p>
            </div>

            <div className="field">
              <span className="fieldLabel">API Key（可选）</span>
              <input
                className="input"
                type="password"
                autoComplete="off"
                value={custom.apiKey}
                placeholder="添加时立即写入"
                aria-label="API Key"
                onChange={(event) => { setCustom(c => ({ ...c, apiKey: event.target.value })) }}
              />
            </div>

            {customError !== undefined ? <p className="error">{customError}</p> : null}

            <div className="editorActions">
              <button type="button" className="secondaryButton" disabled={customBusy} onClick={() => { setPanel('none'); setCustomError(undefined) }}>
                取消
              </button>
              <button type="button" className="primaryButton" disabled={customBusy} onClick={saveCustom}>
                {customBusy ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        ) : (
          <div className="addActions">
            <button
              type="button"
              className="addButton"
              disabled={addable.length === 0}
              title={addable.length === 0 ? '所有内置提供方均已添加' : undefined}
              onClick={() => { setCustomError(undefined); setPanel('builtin') }}
            >
              ＋ 添加提供方
            </button>
            <button
              type="button"
              className="addButton"
              disabled={customSaved}
              title={customSaved ? '自定义提供方已存在，请在上方列表中编辑' : undefined}
              onClick={() => { setCustomError(undefined); setCustom(emptyCustom); setPanel('custom') }}
            >
              ＋ 添加自定义提供方
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
