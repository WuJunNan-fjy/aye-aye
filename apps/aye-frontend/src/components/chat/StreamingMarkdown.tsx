import { memo, useMemo, useRef } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  advanceFreeze,
  createFreezeState,
  type FreezeState,
} from '@/lib/streamingMarkdown'

const REMARK_PLUGINS = [remarkGfm]

interface StreamingMarkdownProps {
  text: string
  /** true=流式中（冻结 chunks + tail）；false=定稿（整篇一次解析，自愈全部偏差）。 */
  streaming: boolean
  /** MessageItem 的 markdownComponents（chipify/mention/pre/code 定制），必须引用稳定。 */
  components: Components
}

/**
 * 已冻结段：source 一旦切出就不再变化，components 引用稳定时 memo 永远命中，
 * 每个段在全生命周期只解析一次。不外套任何 DOM（ReactMarkdown 自身不产生包装元素）。
 */
const FrozenMarkdown = memo(function FrozenMarkdown({
  source,
  components,
}: {
  source: string
  components: Components
}) {
  return (
    <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={components}>
      {source}
    </ReactMarkdown>
  )
})

/**
 * 流式 Markdown：流式期间把已稳定的顶层块冻结（每块只解析一次），每帧只重新
 * 解析尾部约 2 个块；定稿时整篇全量解析一次，结果与单 ReactMarkdown 完全一致。
 * streaming true→false 的干净切换由调用方用 key（"stream"/"settled"）保证重挂载。
 */
export function StreamingMarkdown({ text, streaming, components }: StreamingMarkdownProps) {
  const freezeRef = useRef<FreezeState | undefined>(undefined)
  if (freezeRef.current === undefined) freezeRef.current = createFreezeState()

  // render 阶段推进冻结状态：advanceFreeze 幂等（同 text 直接返回），可安全重复执行。
  useMemo(() => {
    advanceFreeze(freezeRef.current!, text)
  }, [text])

  if (!streaming) {
    return (
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={components}>
        {text}
      </ReactMarkdown>
    )
  }

  const state = freezeRef.current
  return (
    <>
      {state.chunks.map((c) => (
        <FrozenMarkdown key={c.key} source={c.source} components={components} />
      ))}
      {/* tail key 含 generation/cutOffset：非 append 或边界推进时强制 tail 干净重挂载。 */}
      <ReactMarkdown
        key={`tail:${state.generation}:${state.cutOffset}`}
        remarkPlugins={REMARK_PLUGINS}
        components={components}
      >
        {text.slice(state.cutOffset)}
      </ReactMarkdown>
    </>
  )
}
