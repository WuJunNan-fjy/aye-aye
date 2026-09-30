import { useEffect, useState } from 'react'
import type { ModelDraft, ProviderProfile } from '@/api/modelApi'
import ModelListEditor from '@/components/settings/ModelListEditor'

/**
 * 单个 provider 的编辑卡 —— 1:1 复刻 dsh ProviderEditor.tsx 的 DOM/CSS。
 * Translate：dsh 的 namespace/settingsPath/pathOps/api.settings.mutate →
 * Aye 的 onApply 异步持久化（由父页落 REST）；api.credentials → configured 布尔；
 * t(key) → 中文；custom 的 displayName 只对 custom 渲染。
 */

interface ProviderEditorProps {
  provider: ProviderProfile
  /** 保存：父页负责落库（厂商配置 + key + 模型目录），失败时抛出错误展示 */
  onApply: (next: ProviderProfile, apiKeyDraft: string) => Promise<void>
  onDelete: () => Promise<void>
  /** 挂载时即展开编辑卡（"添加提供方/自定义提供方"创建后自动进入填写） */
  defaultEditing?: boolean
}

/** 一行 API key 的浏览器端校验：合法字符为可打印 ASCII（不含空格）。 */
const LEGAL_API_KEY = /^[\x21-\x7E]+$/
const ENV_LINE = /^[A-Z][A-Z0-9_]*=[^=]/

function apiKeyFailure(draft: string): string | undefined {
  if (draft.length === 0) return undefined
  const value = draft.trim()
  if (value.length === 0) return 'API Key 不能全为空白'
  if (ENV_LINE.test(value) || (value[0] === '"' || value[0] === "'" || value[0] === '`') && value.length > 1 && value.endsWith(value[0])) {
    return '看起来像环境变量或带引号，请直接粘贴 Key 本体'
  }
  if (!LEGAL_API_KEY.test(value)) return '包含非法字符（仅可打印 ASCII）'
  return undefined
}

export default function ProviderEditor({ provider, onApply, onDelete, defaultEditing = false }: ProviderEditorProps) {
  const [editing, setEditing] = useState(defaultEditing)
  const [keyDraft, setKeyDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | undefined>(undefined)
  const [draft, setDraft] = useState<Pick<ProviderProfile, 'displayName' | 'baseUrl' | 'models'>>(() => ({
    displayName: provider.displayName,
    baseUrl: provider.baseUrl,
    models: provider.models,
  }))

  // 打开编辑时重置 draft（外部数据变化也跟随）。
  useEffect(() => {
    if (editing) {
      setDraft({ displayName: provider.displayName, baseUrl: provider.baseUrl, models: provider.models })
      setFailure(undefined)
    }
  }, [editing, provider])

  const keyFailure = apiKeyFailure(keyDraft)
  const keyValue = keyDraft.trim()
  const disabled = busy
  const isCustom = provider.custom === true

  const apply = async (): Promise<void> => {
    setBusy(true)
    setFailure(undefined)
    try {
      await onApply(
        {
          ...provider,
          displayName: draft.displayName,
          baseUrl: draft.baseUrl.trim().length > 0 ? draft.baseUrl.trim() : provider.baseUrl,
          models: draft.models,
          configured: provider.configured || keyValue.length > 0,
        },
        keyValue,
      )
      setKeyDraft('')
      setEditing(false)
    } catch (err) {
      setFailure(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const remove = async (): Promise<void> => {
    if (!window.confirm(`删除提供方「${provider.displayName}」？该配置（含 API Key 与模型目录）将被移除；内置提供方之后可从「添加提供方」重新添加。`)) return
    setBusy(true)
    try {
      await onDelete()
    } catch (err) {
      setFailure(err instanceof Error ? err.message : String(err))
      setBusy(false)
    }
  }

  const keyPlaceholder = provider.configured
    ? '已保存（write-only，留空则保持不变）'
    : '粘贴 API Key…'

  return (
    <li className="rowCard">
      <div className="rowHead">
        <div className="rowIdentity">
          <span
            className={`credentialDot ${provider.configured ? 'credentialDotConfigured' : 'credentialDotMissing'}`}
            title={provider.configured ? '已配置密钥' : '未配置密钥'}
          />
          <span className="rowName">{provider.displayName}</span>
          <span className="rowTag">{provider.route}</span>
        </div>
        <div className="rowActions">
          <button type="button" className="secondaryButton" onClick={() => setEditing(v => !v)}>
            {editing ? '收起' : '编辑'}
          </button>
          <button type="button" className="dangerButton" disabled={busy} onClick={remove}>
            删除
          </button>
        </div>
      </div>

      {editing ? (
        <div className="editor">
          <div className="editorHeader">
            <span className="editorTitle">{provider.displayName}</span>
            <span className="editorRoute">{provider.route}</span>
          </div>

          <div className="field">
            <span className="fieldLabel">API Key</span>
            <input
              className="input"
              type="password"
              autoComplete="off"
              value={keyDraft}
              placeholder={keyPlaceholder}
              aria-label="API Key"
              aria-invalid={keyFailure !== undefined}
              disabled={disabled}
              onChange={(event) => { setKeyDraft(event.target.value) }}
            />
            {keyFailure === undefined ? null : <p className="error">{keyFailure}</p>}
          </div>

          <details className="customized">
            <summary className="customizedSummary">自定义设置</summary>
            <div className="customizedBody">
              {isCustom ? (
                <div className="field">
                  <span className="fieldLabel">厂商名称（备注）</span>
                  <input
                    className="input"
                    type="text"
                    value={draft.displayName}
                    placeholder={provider.displayName}
                    aria-label="厂商名称"
                    disabled={disabled}
                    onChange={(event) => { setDraft(d => ({ ...d, displayName: event.target.value })) }}
                  />
                </div>
              ) : null}
              <div className="field">
                <span className="fieldLabel">Base URL</span>
                <input
                  className="input"
                  type="text"
                  value={draft.baseUrl}
                  placeholder={provider.baseUrl}
                  aria-label="Base URL"
                  disabled={disabled}
                  onChange={(event) => { setDraft(d => ({ ...d, baseUrl: event.target.value })) }}
                />
                {isCustom ? <p className="advancedHint">自定义厂商必须填写请求地址（http/https）。</p> : null}
              </div>
              <ModelListEditor
                models={draft.models}
                providerId={provider.id}
                baseUrl={draft.baseUrl.trim().length > 0 ? draft.baseUrl.trim() : provider.baseUrl}
                overridden={draft.models.length > 0}
                disabled={disabled}
                onChange={(models: ModelDraft[]) => setDraft(d => ({ ...d, models }))}
                onReset={() => setDraft(d => ({ ...d, models: [] }))}
              />
            </div>
          </details>

          {failure !== undefined ? <p className="error">{failure}</p> : null}

          <div className="editorActions">
            <button type="button" className="secondaryButton" disabled={busy} onClick={() => setEditing(false)}>
              取消
            </button>
            <button
              type="button"
              className="primaryButton"
              disabled={busy || (isCustom && draft.baseUrl.trim().length === 0)}
              onClick={apply}
            >
              {busy ? '保存中…' : '保存'}
            </button>
          </div>
        </div>
      ) : null}
    </li>
  )
}
