import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { notification } from 'antd'
import { ArrowUpRight, CircleHelp, ShieldQuestion } from 'lucide-react'
import {
  ComposerApprovalCard,
  ComposerQuestionCard,
  type ConfirmationCardData,
  type AskCardData,
} from '@/components/chat/InteractionCards'
import { useSessionsStore } from '@/store/useSessionsStore'
import {
  attachSessionRuntime,
  detachSessionRuntime,
  usePendingInteractions,
  useRuntimeViews,
} from '@/store/useSessionRuntime'
import type { LiveTurnView } from '@/lib/liveTurnProjector'
import { REASON } from '@/api/sessionEventsApi'

/** 非对话区轮询会话列表的间隔：发现外部启动的后台 turn（runningTurn 投影）。 */
const BACKGROUND_POLL_MS = 20_000

/**
 * 全局交互宿主（应用层单例，挂 AppLayout）：
 *
 * 方案 C 的「人在别处、Agent 在等」兜底——当用户停在观测/设置/其他会话页时，
 * 后台会话弹出工具审批 / 主动提问，右下角浮层可直接决议，不必先找回会话；
 * 用户正在查看该会话时不渲染（避免与 composer 接管卡双份）。
 *
 * 同时承担 D5 后台轻提示：非当前会话的 turn 以 error / interrupted 闭合时
 * 通知一次（completed / aborted 不打扰）；点击通知跳回该会话。
 */
export default function GlobalInteractionHost() {
  const navigate = useNavigate()
  const location = useLocation()
  const pending = usePendingInteractions()
  const views = useRuntimeViews()
  const sessions = useSessionsStore((s) => s.sessions)
  const loadSessions = useSessionsStore((s) => s.load)

  /** 当前路由正在查看的会话 id（/chat/:sessionId）；其余会话一律算后台。 */
  const currentSid = (() => {
    const m = /^\/chat\/([^/?]+)/.exec(location.pathname)
    return m ? decodeURIComponent(m[1]) : null
  })()

  const titleOf = (sid: string): string =>
    sessions.find((s) => s.id === sid)?.title?.trim() || '未命名会话'

  // ---- 后台结束通知：views 边沿监听 + 「会话:轮次」去重 ----
  const prevViewsRef = useRef<Map<string, { turnNo: number; phase: string }>>(new Map())
  const notifiedRef = useRef<Set<string>>(new Set())
  const currentSidRef = useRef<string | null>(currentSid)
  currentSidRef.current = currentSid
  const titleOfRef = useRef(titleOf)
  titleOfRef.current = titleOf

  useEffect(() => {
    for (const [sid, view] of Object.entries(views)) {
      if (!view) continue
      const prev = prevViewsRef.current.get(sid)
      prevViewsRef.current.set(sid, { turnNo: view.turnNo, phase: view.phase })
      if (!prev || prev.phase !== 'running' || view.phase !== 'ended') continue
      if (prev.turnNo !== view.turnNo) continue
      // 任何后台轮次闭合都刷新列表（runningTurn 消失 → 保活集合收缩、侧栏运行点消失）
      void loadSessions(true)
      if (view.reason !== REASON.ERROR && view.reason !== REASON.INTERRUPTED) continue
      // 边沿发生时用户正在该会话页：页内已有 endNote，不重复通知。
      if (sid === currentSidRef.current) continue
      const dedupeKey = `${sid}:${view.turnNo}`
      if (notifiedRef.current.has(dedupeKey)) continue
      notifiedRef.current.add(dedupeKey)
      notifyTurnEnded(sid, view, () => navigate(`/chat/${sid}`), titleOfRef.current(sid))
    }
  }, [views, navigate, loadSessions])

  // ---- 后台会话保活：非对话页也要订阅 running/待交互会话的 events（AC-5） ----
  // 否则 ConversationPage 卸载（refcount=0）后 30s 连接销毁，浮层与通知都失去数据源。
  const keptRef = useRef<Set<string>>(new Set())
  useEffect(() => {
    void loadSessions(true)
    const timer = setInterval(() => void loadSessions(), BACKGROUND_POLL_MS)
    return () => clearInterval(timer)
  }, [loadSessions, currentSid])

  useEffect(() => {
    // 保活集合 = 列表 runningTurn 投影（外部启动/冷恢复）∪ 本地 running/待交互视图
    const next = new Set<string>()
    for (const s of sessions) {
      if (s.runningTurn != null && s.id !== currentSid) next.add(s.id)
    }
    for (const [sid, v] of Object.entries(views)) {
      if (sid === currentSid || !v) continue
      if (v.phase === 'running' || v.activeInteraction) next.add(sid)
    }
    const prev = keptRef.current
    for (const sid of next) {
      if (!prev.has(sid)) attachSessionRuntime(sid)
    }
    for (const sid of prev) {
      if (!next.has(sid)) detachSessionRuntime(sid)
    }
    keptRef.current = next
  }, [sessions, views, currentSid])

  // 组件卸载（理论上 AppLayout 常驻）兜底释放保活引用
  useEffect(() => () => {
    keptRef.current.forEach((sid) => detachSessionRuntime(sid))
    keptRef.current = new Set()
  }, [])

  const backgroundPending = pending.filter((e) => e.sessionId !== currentSid)

  if (backgroundPending.length === 0) return null

  return (
    <div className="gih-layer" role="region" aria-label="后台会话待处理操作">
      {backgroundPending.map(({ sessionId, interaction }) => {
        const isConfirm = interaction.kind === 'confirm'
        const Icon = isConfirm ? ShieldQuestion : CircleHelp
        return (
          <div className="gih-card" key={`${sessionId}-${interaction.interactionId}`}>
            <div className="gih-head">
              <span className={`gih-head-dot ${isConfirm ? 'warn' : 'ask'}`}>
                <Icon size={14} aria-hidden />
              </span>
              <div className="gih-head-copy">
                <span className="gih-head-title" title={titleOf(sessionId)}>
                  {titleOf(sessionId)}
                </span>
                <span className="gih-head-sub">
                  {isConfirm ? '后台会话请求操作确认' : '后台会话向你提问'}
                </span>
              </div>
              <button
                type="button"
                className="gih-jump"
                title="回到该会话"
                onClick={() => navigate(`/chat/${sessionId}`)}
              >
                <ArrowUpRight size={15} aria-hidden />
              </button>
            </div>
            <div className="gih-body">
              {isConfirm
                ? (
                  <ComposerApprovalCard
                    data={interaction.data as ConfirmationCardData}
                    onSettled={() => { /* 决议 API 由卡片调用；浮层随 pending 身份更新自动消失 */ }}
                  />
                )
                : (
                  <ComposerQuestionCard
                    data={interaction.data as AskCardData}
                    onSettled={() => { /* 同上 */ }}
                  />
                )}
            </div>
          </div>
        )
      })}
    </div>
  )
}

/** error→错误通知；interrupted→警告通知；点击通知跳回会话。 */
function notifyTurnEnded(
  sid: string,
  view: LiveTurnView,
  goToSession: () => void,
  title: string,
): void {
  const isError = view.reason === REASON.ERROR
  const description = isError
    ? (view.errorMessage || '本轮执行过程中发生错误，点击查看现场')
    : (view.endNote?.text || '连接中断，本轮任务未完成（未完成的操作已标记中断）')
  notification.open({
    placement: 'bottomRight',
    duration: 8,
    type: isError ? 'error' : 'warning',
    message: isError ? `会话「${title}」执行出错` : `会话「${title}」连接中断`,
    description,
    onClick: goToSession,
    key: `turn-ended-${sid}-${view.turnNo}`,
  })
}
