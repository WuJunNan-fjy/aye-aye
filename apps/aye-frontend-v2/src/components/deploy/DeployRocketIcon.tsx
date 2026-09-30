import { memo } from 'react'

/**
 * 部署火箭（lucide rocket@1.46 ISC，path 从 node_modules 实读核对）。
 * 与静态 <Rocket> 同形同笔画，仅把四条原笔画拆成运动部件：
 *   g.rc-all  = 整机（推力震动的整体后坐↔前冲）
 *   g.rc-body = 机身三笔（右尾翼 / 机身轮廓 / 左尾翼）
 *   path.rc-flame = 左下火焰（根部锚定在右上角，随推力快闪）
 * 动效定义在 styles/fx-icons.css，由 .inp-deploy / .dp-lead 的状态类驱动。
 */
interface Props {
  size?: number
  strokeWidth?: number
  className?: string
}

export const DeployRocketIcon = memo(function DeployRocketIcon({
  size = 14,
  strokeWidth = 2,
  className,
}: Props) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <g className="rc-all">
        <g className="rc-body">
          <path d="M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5" />
          <path d="M9 12a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.4 22.4 0 0 1-4 2z" />
          <path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 .05 5 .05" />
        </g>
        <path
          className="rc-flame"
          d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09"
        />
      </g>
    </svg>
  )
})
