import { useCallback, useEffect, useState } from 'react'
import { Monitor, Moon, Sun } from 'lucide-react'
import type { GeneralSettings } from '@/api/settingsApi'
import { errorMessage, getSettings, updateSettings } from '@/api/settingsApi'
import SettingsPillMenu, { type PillOption } from '@/components/settings/SettingsPillMenu'
import { applyTheme, getStoredTheme, setTheme, type ThemePreference } from '@/lib/theme'

/**
 * Settings → General 页 —— 1:1 复刻 dsh GeneralSection 的三行：
 * 外观（ui-theme AppearanceRow）、语言（locale LanguageRow）、
 * 执行模式（ui-permission-presets PermissionRow）。
 * Translate：namespace/settings.mutate → Spring Boot REST /api/settings；
 * 外观偏好走客户端 localStorage（后端无 app 主题字段），语言/执行模式落库。
 */

const LANGUAGE_OPTIONS: PillOption[] = [
  { id: 'zh-CN', label: '简体中文' },
  { id: 'en-US', label: 'English' },
]

/* 与后端 /agent/mode 三档一致（旧前端 SettingsView 的文案）。 */
const MODE_OPTIONS: PillOption[] = [
  { id: 'readonly', label: '只读模式', desc: '仅查看文件和目录，不执行任何修改操作' },
  { id: 'write_confirm', label: '写操作确认模式', desc: '读操作直接执行，所有写操作都需要确认' },
  { id: 'all_confirm', label: '所有操作确认模式', desc: '所有工具调用都需要确认，最安全' },
]

const THEME_CUBES = [
  { id: 'light', label: '浅色', Icon: Sun },
  { id: 'dark', label: '深色', Icon: Moon },
  { id: 'system', label: '跟随系统', Icon: Monitor },
] as const

/* 联网搜索后端：AUTO 按当前模型自动选（DeepSeek→官方搜索，其他→Exa 免费）。 */
const WEBSEARCH_PROVIDERS: PillOption[] = [
  { id: 'AUTO', label: '自动', desc: 'DeepSeek 走官方搜索，其他模型走 Exa 免费搜索' },
  { id: 'DEEPSEEK', label: 'DeepSeek 原生', desc: '官方服务端搜索，复用模型 Key，无需配置' },
  { id: 'EXA', label: 'Exa 免费', desc: '零 Key 免费 MCP 搜索' },
  { id: 'TAVILY', label: 'Tavily', desc: '第三方搜索，需自行申请 API Key' },
]

export default function SettingsGeneralPage() {
  const [settings, setSettings] = useState<GeneralSettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | undefined>(undefined)
  const [theme, setThemePref] = useState<ThemePreference>(getStoredTheme)
  const [saving, setSaving] = useState<'language' | 'mode' | 'webSearch' | null>(null)
  const [saveError, setSaveError] = useState<string | undefined>(undefined)
  const [saved, setSaved] = useState<string | null>(null)
  const [webProvider, setWebProvider] = useState('AUTO')
  const [webKey, setWebKey] = useState('')

  const reload = useCallback(async (): Promise<void> => {
    try {
      setSettings(await getSettings())
      setLoadError(undefined)
    } catch (err) {
      setLoadError(errorMessage(err))
    }
  }, [])

  useEffect(() => {
    setLoading(true)
    reload().finally(() => setLoading(false))
  }, [reload])

  useEffect(() => {
    setWebProvider(settings?.webSearch?.provider ?? 'AUTO')
    setWebKey(settings?.webSearch?.apiKey ?? '')
  }, [settings])

  /** 语言/执行模式 → PUT /api/settings 部分更新，成功后用后端完整对象刷新。 */
  const commit = async (kind: 'language' | 'mode', label: string, patch: Partial<GeneralSettings>): Promise<void> => {
    setSaving(kind)
    setSaveError(undefined)
    try {
      const next = await updateSettings(patch)
      setSettings(next)
      setSaved(`${label}已保存`)
      window.setTimeout(() => setSaved(null), 2500)
    } catch (err) {
      setSaveError(errorMessage(err))
    } finally {
      setSaving(null)
    }
  }

  /** 联网搜索后端 → PUT /api/settings（webSearch.provider），点击即保存。 */
  const pickWebProvider = async (id: string): Promise<void> => {
    setWebProvider(id)
    setSaving('webSearch')
    setSaveError(undefined)
    try {
      const next = await updateSettings({ webSearch: { provider: id, apiKey: webKey.trim() } })
      setSettings(next)
      setSaved('联网搜索已切换')
      window.setTimeout(() => setSaved(null), 2500)
    } catch (err) {
      setSaveError(errorMessage(err))
      setWebProvider(settings?.webSearch?.provider ?? 'AUTO')
    } finally {
      setSaving(null)
    }
  }

  /** 联网搜索 API Key（仅 Tavily 需要）→ PUT /api/settings（webSearch 对象），成功后刷新。 */
  const saveWebSearch = async (): Promise<void> => {
    setSaving('webSearch')
    setSaveError(undefined)
    try {
      const next = await updateSettings({ webSearch: { provider: webProvider, apiKey: webKey.trim() } })
      setSettings(next)
      setSaved('联网搜索配置已保存')
      window.setTimeout(() => setSaved(null), 2500)
    } catch (err) {
      setSaveError(errorMessage(err))
    } finally {
      setSaving(null)
    }
  }

  if (loading) {
    return (
      <div className="gs-section">
        <h2 className="gs-title">通用设置</h2>
        <p className="gs-intro">外观（浅/深/跟随系统）、语言与新会话默认执行模式。</p>
        <p className="gs-status" style={{ opacity: 0.7 }}>加载中…</p>
      </div>
    )
  }

  return (
    <div className="gs-section">
      <h2 className="gs-title">通用设置</h2>
      <p className="gs-intro">外观（浅/深/跟随系统）、语言与新会话默认执行模式。</p>

      {loadError !== undefined
        ? <p className="gs-status" style={{ color: 'var(--dsw-alias-state-error-primary)' }}>{loadError}</p>
        : null}
      {saveError !== undefined
        ? <p className="gs-status" style={{ color: 'var(--dsw-alias-state-error-primary)' }}>{saveError}</p>
        : null}
      {saved !== null
        ? <p className="gs-status" style={{ color: 'var(--dsw-alias-state-success-primary)' }}>{saved}</p>
        : null}

      {/* 外观：三个主题方块 */}
      <div className="gs-appearance">
        <div className="gs-rowLabel">外观</div>
        <div className="gs-cubeRow">
          {THEME_CUBES.map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              className={`gs-cube${theme === id ? ' gs-cubeSelected' : ''}`}
              aria-pressed={theme === id}
              onClick={() => {
                setThemePref(id)
                setTheme(id)
              }}
            >
              <Icon className="gs-cubeIcon" />
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* 语言 */}
      <div className="gs-row">
        <div className="gs-rowText">
          <div className="gs-rowLabel">语言</div>
        </div>
        <SettingsPillMenu
          label="语言"
          value={settings?.language ?? 'zh-CN'}
          options={LANGUAGE_OPTIONS}
          busy={saving === 'language'}
          onSelect={(id) => { void commit('language', '语言', { language: id }) }}
        />
      </div>

      {/* 执行模式 */}
      <div className="gs-row">
        <div className="gs-rowText">
          <div className="gs-rowLabel">执行模式</div>
          <div className="gs-desc">新会话默认的工具调用确认策略，可在会话内临时切换。</div>
        </div>
        <SettingsPillMenu
          label="执行模式"
          value={settings?.defaultExecutionMode ?? 'write_confirm'}
          options={MODE_OPTIONS}
          busy={saving === 'mode'}
          onSelect={(id) => { void commit('mode', '执行模式', { defaultExecutionMode: id }) }}
        />
      </div>

      {/* 联网搜索 */}
      <div className="gs-row" style={{ alignItems: 'flex-start' }}>
        <div className="gs-rowText">
          <div className="gs-rowLabel">联网搜索</div>
          <div className="gs-desc">
            供 AI 的 web_search 工具使用。自动模式下 DeepSeek 走官方服务端搜索（复用模型 Key），
            其他模型走 Exa 免费搜索，开箱即用无需配置。
          </div>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
          <SettingsPillMenu
            label="搜索服务"
            value={webProvider}
            options={WEBSEARCH_PROVIDERS}
            busy={saving === 'webSearch'}
            onSelect={(id) => { void pickWebProvider(id) }}
          />
          {webProvider === 'TAVILY' && (
            <div className="gs-keyRow">
              <input
                className="gs-keyInput"
                type="password"
                value={webKey}
                placeholder="Tavily API Key（tvly-…）"
                spellCheck={false}
                autoComplete="off"
                onChange={(e) => setWebKey(e.target.value)}
              />
              <button
                className="gs-keyButton"
                type="button"
                disabled={saving === 'webSearch'}
                onClick={() => { void saveWebSearch() }}
              >
                {saving === 'webSearch' ? '保存中…' : '保存'}
              </button>
            </div>
          )}
        </div>
      </div>

    </div>
  )
}
