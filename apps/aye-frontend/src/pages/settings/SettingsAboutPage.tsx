import { Rocket } from 'lucide-react'

export default function SettingsAboutPage() {
  return (
    <section className="about-page" aria-labelledby="about-title">
      <div className="settings-section-title" id="about-title">关于 Aye</div>
      <div className="about-product">
        <span className="about-icon"><Rocket size={26} aria-hidden="true" /></span>
        <h2>Aye</h2>
        <p className="about-tagline">让项目部署更简单。</p>
        <p>选择本地项目，连接 Linux 服务器，让 AI 协助完成构建、上传与启动。</p>
        <p>执行过程随时可查，重要操作由你确认。</p>
      </div>
    </section>
  )
}
