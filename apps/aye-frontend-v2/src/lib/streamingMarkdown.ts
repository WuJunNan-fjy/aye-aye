/**
 * 流式 Markdown 块级冻结切分器（方案 D1，无 React 依赖的纯函数）。
 *
 * 依据 CommonMark 块级解析是行式的：追加文本只可能重塑解析前沿（最后一个顶层块），
 * 因此把除最后 UNSTABLE_TAIL_BLOCKS 个块之外的前缀视为「已冻结」，调用方每帧只
 * 重新解析 tail 源文；每个源文区域总计只解析 O(1) 次，整条回复解析成本由 O(n²)
 * 降到接近 O(n)。
 *
 * 本实现不依赖 mdast parser position，用词法（围栏配对 + 空行边界）独立完成，
 * 刻意保守：任何不确定情形（块数不足、未闭合围栏）一律少冻结或不冻结。
 */

/** tail 固定覆盖的顶层块数；2 是安全边际（懒续行列表等边界误判的缓冲）。 */
export const UNSTABLE_TAIL_BLOCKS = 2

/** 一个已冻结源文段：key=段首字符偏移（跨帧稳定，React key 同值）。 */
export interface FrozenChunk {
  readonly key: number
  readonly source: string
}

/** 一个流式文档的冻结状态，由调用方存于 useRef，跨帧更新。 */
export interface FreezeState {
  prevText: string
  cutOffset: number
  chunks: FrozenChunk[]
  /** 非 append 输入（中断/重试/替换）时 +1，调用方据此让 tail 重挂载。 */
  generation: number
}

export function createFreezeState(): FreezeState {
  return { prevText: '', cutOffset: 0, chunks: [], generation: 0 }
}

/** 围栏开行：0-3 空格缩进 + 至少 3 个连续 ` 或 ~。 */
const FENCE_OPEN_RE = /^ {0,3}(`{3,}|~{3,})/
/** 围栏闭合行：同 marker 行（只允许空白尾随，CommonMark 闭合围栏不带 info）。 */
const FENCE_CLOSE_RE = /^ {0,3}(`{3,}|~{3,})\s*$/
/** 非围栏状态下的空白行 = 顶层块分隔。 */
const BLANK_LINE_RE = /^\s*$/

interface BlockSpan {
  /** 块首行起点（UTF-16 偏移）。 */
  start: number
  /** 块末行行尾（UTF-16 偏移，不含该行后的 \n）。 */
  end: number
}

/**
 * 计算 text 中可冻结前缀的字符偏移：tail 必须覆盖最后 2 个顶层块。
 * 规则（保守优先，宁可不冻结也不误切）：
 * 1. 按 \n 切行并记录每行字符区间；围栏内不识别空行边界与嵌套围栏；
 *    闭合需同字符且 tick 数 ≥ 开合计数。
 * 2. 非围栏内，空白行为块分隔；连续非空行 = 一个顶层块（表格、setext、
 *    ATX 标题、引用、列表均天然是连续行，切分绝不会进入这些结构内部）。
 * 3. 开围栏未闭合时，开围栏行到文末整体是一个「不完整块」（必然属于 tail）。
 * 4. 块总数 ≤ 2 → cut=0；否则 cut = 倒数第 3 个块的行尾，块间空行留在 tail。
 */
export function computeFrozenCut(text: string): number {
  const blocks: BlockSpan[] = []
  let inFence: { marker: string; ticks: number } | null = null
  let blockStart = -1
  let blockEnd = -1
  let offset = 0
  const lines = text.split('\n')

  for (const line of lines) {
    const lineStart = offset
    const lineEnd = offset + line.length

    if (inFence) {
      const m = line.match(FENCE_CLOSE_RE)
      if (m && m[1][0] === inFence.marker && m[1].length >= inFence.ticks) {
        // 围栏闭合：开围栏行到闭合行整体构成一个完整块。
        blocks.push({ start: blockStart, end: lineEnd })
        blockStart = -1
        blockEnd = -1
        inFence = null
      } else {
        blockEnd = lineEnd
      }
    } else {
      const m = line.match(FENCE_OPEN_RE)
      if (m) {
        inFence = { marker: m[1][0], ticks: m[1].length }
        blockStart = lineStart
        blockEnd = lineEnd
      } else if (BLANK_LINE_RE.test(line)) {
        if (blockStart !== -1) {
          blocks.push({ start: blockStart, end: blockEnd })
          blockStart = -1
          blockEnd = -1
        }
      } else {
        if (blockStart === -1) blockStart = lineStart
        blockEnd = lineEnd
      }
    }

    offset = lineEnd + 1
  }

  // 文末收尾：未闭合围栏块（开围栏行到文末）或最后一个普通块。
  if (blockStart !== -1) {
    blocks.push({ start: blockStart, end: blockEnd })
  }

  if (blocks.length <= UNSTABLE_TAIL_BLOCKS) return 0
  return blocks[blocks.length - 1 - UNSTABLE_TAIL_BLOCKS].end
}

/**
 * 喂入新一帧的全文，推进冻结状态（幂等：同 text 直接返回）。
 * 非 append（!text.startsWith(prevText)）→ generation+1、cut=0、chunks=[]。
 * cut 推进时把 [oldCut, newCut) 切成一个 FrozenChunk 入列（实践中每帧至多新增 1 段）。
 */
export function advanceFreeze(state: FreezeState, text: string): void {
  if (text === state.prevText) return

  if (!text.startsWith(state.prevText)) {
    state.generation++
    state.cutOffset = 0
    state.chunks = []
  }

  const newCut = computeFrozenCut(text)
  if (newCut > state.cutOffset) {
    state.chunks.push({
      key: state.cutOffset,
      source: text.slice(state.cutOffset, newCut),
    })
    state.cutOffset = newCut
  }

  state.prevText = text
}
