import { useCallback, useRef, type ReactNode } from 'react'

/**
 * 侧栏 hover 动效图标外壳（fx-icons.css 中的 .tm-nav-N / .pn-ico 等套件）。
 *
 * - pointerenter / click 时重播一次：先摘 .play 强制重排再挂回，
 *   保证鼠标连续划入、或点击后再次进入都能重新触发；
 * - .play 保持到 pointerleave 才摘除：需要"动画终态停留整段 hover"
 *   的套件（如 pn 箭头弹入后持续可见）依赖此行为；
 * - 动效样式全部封闭在 fx-icons.css 命名空间内，组件只负责播放时序；
 *   减弱动态效果由 CSS 媒体查询统一降级。
 *
 * @param cls       动效命名空间类（如 "tm-nav tm-nav-1"、"pn-ico pn-close"）
 * @param className 宿主尺寸/颜色类（如 sb-new-icon）
 */
export default function FxNavIcon(
  { cls, className, children }:
  { cls: string; className?: string; children: ReactNode },
) {
  const ref = useRef<HTMLSpanElement>(null)

  const replay = useCallback(() => {
    const el = ref.current
    if (!el) return
    el.classList.remove('play')
    void el.offsetWidth // 强制重排：连续两次进入也能重新启动画
    el.classList.add('play')
  }, [])

  const stop = useCallback(() => {
    ref.current?.classList.remove('play')
  }, [])

  return (
    <span
      ref={ref}
      className={`${cls}${className ? ` ${className}` : ''}`}
      onPointerEnter={replay}
      onClick={replay}
      onPointerLeave={stop}
      aria-hidden
    >
      {children}
    </span>
  )
}
