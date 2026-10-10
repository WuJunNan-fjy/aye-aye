/**
 * 应用主题切换助手（对应 dsh ui-theme 的 themeStore 客户端侧能力）。
 * 视觉契约见 theme.css 头部注释：
 *   浅色 = html.light（body 无 data 属性）；深色 = html.dark 且 body[data-ds-dark-theme]。
 * 偏好持久化在 localStorage；"跟随系统" 监听 prefers-color-scheme 实时切换。
 */

export type ThemePreference = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'aye.theme'
const media = window.matchMedia('(prefers-color-scheme: dark)')

function isDark(pref: ThemePreference): boolean {
  return pref === 'dark' || (pref === 'system' && media.matches)
}

function sync(pref: ThemePreference): void {
  const dark = isDark(pref)
  document.documentElement.classList.toggle('dark', dark)
  document.documentElement.classList.toggle('light', !dark)
  document.body.toggleAttribute('data-ds-dark-theme', dark)
}

export function getStoredTheme(): ThemePreference {
  const value = localStorage.getItem(STORAGE_KEY)
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
}

let watching = false

/** 应用主题（含跟随系统时的实时切换），幂等；应用启动时调用一次。 */
export function applyTheme(pref: ThemePreference): void {
  sync(pref)
  if (!watching) {
    watching = true
    media.addEventListener?.('change', () => sync(getStoredTheme()))
  }
}

/** 持久化并立即应用。 */
export function setTheme(pref: ThemePreference): void {
  localStorage.setItem(STORAGE_KEY, pref)
  sync(pref)
}
