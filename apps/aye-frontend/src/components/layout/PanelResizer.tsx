import { useRef, type MouseEvent as ReactMouseEvent } from 'react'

interface PanelResizerProps {
  /** vertical = 垂直分隔条（左右分栏拖宽）；horizontal = 水平分隔条 */
  direction?: 'vertical' | 'horizontal'
  onResize?: (delta: number) => void
}

/** 分栏拖拽条：鼠标拖动时连续回调增量位移（对齐 v1 workbench PanelResizer）。 */
export default function PanelResizer({ direction = 'vertical', onResize }: PanelResizerProps) {
  const startX = useRef(0)
  const startY = useRef(0)

  const startResize = (e: ReactMouseEvent) => {
    e.preventDefault()
    startX.current = e.clientX
    startY.current = e.clientY

    const onMouseMove = (ev: globalThis.MouseEvent) => {
      const dx = ev.clientX - startX.current
      const dy = ev.clientY - startY.current
      startX.current = ev.clientX
      startY.current = ev.clientY
      onResize?.(direction === 'vertical' ? dx : dy)
    }

    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }

    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    document.body.style.cursor = direction === 'vertical' ? 'col-resize' : 'row-resize'
    document.body.style.userSelect = 'none'
  }

  return <div className={`panel-resizer ${direction}`} onMouseDown={startResize} />
}
