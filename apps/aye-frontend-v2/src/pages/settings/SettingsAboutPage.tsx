export default function SettingsAboutPage() {
  return (
    <div>
      <div className="settings-section-title">关于 Aye v2</div>

      <div style={{
        padding: 20,
        borderRadius: 12,
        border: '1px solid var(--dsw-alias-border-l2)',
        background: 'var(--dsw-alias-bg-layer-1)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
          <span style={{
            width: 44, height: 44, borderRadius: 10,
            background: 'var(--dsw-alias-brand-primary)',
            color: 'var(--dsw-alias-label-primary-foreground)', fontWeight: 700, fontSize: 20,
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <circle cx="12" cy="12.2" r="3.6" strokeWidth={1.6}/>
              <path d="M15.6 7.8 V16.6" strokeWidth={1.6} strokeLinecap="round"/>
              <circle className="aye-brand-pupil" cx="12" cy="12.2" r="1.2" fill="currentColor" stroke="none"/>
            </svg>
          </span>
          <div>
            <div style={{ fontSize: 18, fontWeight: 700 }}>Aye <span style={{ fontWeight: 400, color: 'var(--dsw-alias-label-secondary)' }}>v2 (复刻版)</span></div>
            <div style={{ fontSize: 12, color: 'var(--dsw-alias-label-tertiary)', marginTop: 2 }}>
              基于 deepseek-harness UI/交互体系的 React 18 · Vite · TypeScript 重写前端
            </div>
          </div>
        </div>

        <div className="settings-section-title" style={{ marginTop: 0 }}>技术栈</div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {[
            'Electron 35', 'React 18', 'TypeScript 5', 'React Router 6',
            'Zustand', 'Spring Boot 3.4', 'Java 17 + 自研 Agent Loop',
            'SQLite 单机存储', 'xterm.js', 'JSch (SSH/SFTP)',
          ].map(tag => (
            <span key={tag} className="dsh-pill">{tag}</span>
          ))}
        </div>

        <div className="settings-section-title">复刻说明</div>
        <div style={{ fontSize: 13, lineHeight: '22px', color: 'var(--dsw-alias-label-secondary)' }}>
          <div>• 原有 <code style={{ padding: '1px 5px', background: 'var(--dsw-alias-markdown-inline-code)', borderRadius: 4 }}>apps/aye-frontend</code> 保持不动，作为生产稳定版。</div>
          <div>• 本工程 <code style={{ padding: '1px 5px', background: 'var(--dsw-alias-markdown-inline-code)', borderRadius: 4 }}>apps/aye-frontend-v2</code> 从零搭建，样式与组件按 deepseek-harness 原源码 1:1 迁移，不依赖 Cordis。</div>
          <div>• UI 组件 token 体系完全保留 <code style={{ padding: '1px 5px', background: 'var(--dsw-alias-markdown-inline-code)', borderRadius: 4 }}>--dsw-alias-* / --dsw-static-*</code> 两套变量，支持浅/深双主题。</div>
        </div>
      </div>
    </div>
  )
}
