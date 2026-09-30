import React from 'react'

export interface FileIconInfo {
  icon: React.ReactNode
  color: string
  label: string
  svg: string
}

const toKebabCase = (str: string): string =>
  str.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)

const svg = (paths: React.ReactNode, color: string, size = 16): string => {
  const children = React.Children.toArray(paths)
  let inner = ''
  const serialize = (node: any): string => {
    if (typeof node === 'string') return node
    if (typeof node === 'number') return String(node)
    if (Array.isArray(node)) return node.map(serialize).join('')
    if (!node || !node.props) return ''
    const { type, props } = node
    // Fragment or Symbol type — just render children
    if (typeof type === 'symbol' || type === React.Fragment) {
      return props.children ? serialize(props.children) : ''
    }
    if (typeof type !== 'string') return ''
    const attrs = Object.entries(props || {})
      .filter(([k]) => k !== 'children')
      .map(([k, v]) => {
        const attrName = toKebabCase(k)
        if (v === true) return attrName
        if (v === false || v == null) return ''
        try { return `${attrName}="${String(v).replace(/"/g, '&quot;')}"` } catch { return '' }
      })
      .filter(Boolean)
      .join(' ')
    const childHtml = props.children ? serialize(props.children) : ''
    return `<${type}${attrs ? ' ' + attrs : ''}>${childHtml}</${type}>`
  }
  inner = serialize(children)
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${inner}</svg>`
}

const baseFile = (color: string, label: string, innerContent?: string): FileIconInfo => {
  const paths = (
    <>
      <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
      <polyline points="14 2 14 8 20 8" />
      {innerContent}
    </>
  )
  return {
    icon: React.cloneElement(svg(paths, color) as unknown as React.ReactElement, {}, null) || paths,
    color,
    label,
    svg: svg(paths, color),
  }
}

export function getFileIcon(fileName: string, isDir?: boolean): FileIconInfo {
  if (isDir) {
    const paths = <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" />
    return {
      icon: (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#d4a843" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          {paths}
        </svg>
      ),
      color: '#d4a843',
      label: '目录',
      svg: svg(paths, '#d4a843'),
    }
  }

  const ext = fileName.split('.').pop()?.toLowerCase() || ''
  const lowerName = fileName.toLowerCase()

  if (lowerName === '.gitignore' || lowerName === '.gitattributes' || lowerName.startsWith('.git')) {
    const paths = (
      <>
        <circle cx="12" cy="12" r="3" />
        <line x1="12" y1="2" x2="12" y2="6" /><line x1="12" y1="18" x2="12" y2="22" />
        <line x1="4.93" y1="4.93" x2="7.76" y2="7.76" /><line x1="16.24" y1="16.24" x2="19.07" y2="19.07" />
        <line x1="2" y1="12" x2="6" y2="12" /><line x1="18" y1="12" x2="22" y2="12" />
        <line x1="4.93" y1="19.07" x2="7.76" y2="16.24" /><line x1="16.24" y1="7.76" x2="19.07" y2="4.93" />
      </>
    )
    return { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#f05033" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">{paths}</svg>, color: '#f05033', label: 'Git', svg: svg(paths, '#f05033') }
  }

  if (lowerName === 'readme.md') {
    const paths = (
      <>
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <path d="M9 13l2 2 4-4" />
      </>
    )
    return { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2da44e" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">{paths}</svg>, color: '#2da44e', label: 'README', svg: svg(paths, '#2da44e') }
  }

  if (lowerName === 'package.json' || lowerName === 'package-lock.json' || lowerName === 'pnpm-lock.yaml' || lowerName === 'yarn.lock' || lowerName === 'bun.lockb') {
    const paths = (
      <>
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <path d="M8 15h8M8 11h8" />
      </>
    )
    return { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#cb3837" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">{paths}</svg>, color: '#cb3837', label: 'npm', svg: svg(paths, '#cb3837') }
  }

  if (lowerName === 'dockerfile' || lowerName === 'docker-compose.yml' || lowerName === 'docker-compose.yaml' || lowerName.endsWith('.dockerfile')) {
    const paths = (
      <>
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <rect x="8" y="13" width="3" height="3" /><rect x="12" y="13" width="3" height="3" />
        <rect x="8" y="17" width="3" height="2" /><rect x="12" y="17" width="3" height="2" />
      </>
    )
    return { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2496ed" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">{paths}</svg>, color: '#2496ed', label: 'Docker', svg: svg(paths, '#2496ed') }
  }

  if (lowerName === 'makefile' || lowerName === 'cmakelists.txt') {
    const paths = (
      <>
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <path d="M9 13h6M9 17h4" />
      </>
    )
    return { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#6a6a6a" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">{paths}</svg>, color: '#6a6a6a', label: 'Build', svg: svg(paths, '#6a6a6a') }
  }

  type IconDef = { color: string; label: string; children: React.ReactNode }
  const extMap: Record<string, IconDef> = {
    js: { color: '#f7df1e', label: 'JavaScript', children: <text x="12" y="17" textAnchor="middle" fill="#f7df1e" fontSize="7" fontWeight="bold" stroke="none">JS</text> },
    jsx: { color: '#61dafb', label: 'React JSX', children: <text x="12" y="17" textAnchor="middle" fill="#61dafb" fontSize="6" fontWeight="bold" stroke="none">JSX</text> },
    ts: { color: '#3178c6', label: 'TypeScript', children: <text x="12" y="17" textAnchor="middle" fill="#3178c6" fontSize="7" fontWeight="bold" stroke="none">TS</text> },
    tsx: { color: '#3178c6', label: 'React TSX', children: <text x="12" y="17" textAnchor="middle" fill="#3178c6" fontSize="6" fontWeight="bold" stroke="none">TSX</text> },
    vue: { color: '#42b883', label: 'Vue', children: <path d="M9 13l3 4 3-4" stroke="#42b883" /> },
    py: { color: '#3776ab', label: 'Python', children: <text x="12" y="17" textAnchor="middle" fill="#3776ab" fontSize="7" fontWeight="bold" stroke="none">Py</text> },
    java: { color: '#ed8b00', label: 'Java', children: <text x="12" y="17" textAnchor="middle" fill="#ed8b00" fontSize="7" fontWeight="bold" stroke="none">Jv</text> },
    go: { color: '#00add8', label: 'Go', children: <text x="12" y="17" textAnchor="middle" fill="#00add8" fontSize="7" fontWeight="bold" stroke="none">Go</text> },
    rs: { color: '#ce422b', label: 'Rust', children: <text x="12" y="17" textAnchor="middle" fill="#ce422b" fontSize="7" fontWeight="bold" stroke="none">Rs</text> },
    rb: { color: '#cc342d', label: 'Ruby', children: <text x="12" y="17" textAnchor="middle" fill="#cc342d" fontSize="7" fontWeight="bold" stroke="none">Rb</text> },
    php: { color: '#777bb4', label: 'PHP', children: <text x="12" y="17" textAnchor="middle" fill="#777bb4" fontSize="6" fontWeight="bold" stroke="none">PHP</text> },
    cpp: { color: '#00599c', label: 'C++', children: <text x="12" y="17" textAnchor="middle" fill="#00599c" fontSize="6" fontWeight="bold" stroke="none">C++</text> },
    c: { color: '#a8b9cc', label: 'C', children: <text x="12" y="17" textAnchor="middle" fill="#a8b9cc" fontSize="8" fontWeight="bold" stroke="none">C</text> },
    h: { color: '#a8b9cc', label: 'C Header', children: <text x="12" y="17" textAnchor="middle" fill="#a8b9cc" fontSize="8" fontWeight="bold" stroke="none">H</text> },
    hpp: { color: '#00599c', label: 'C++ Header', children: <text x="12" y="17" textAnchor="middle" fill="#00599c" fontSize="6" fontWeight="bold" stroke="none">H++</text> },
    cs: { color: '#512bd4', label: 'C#', children: <text x="12" y="17" textAnchor="middle" fill="#512bd4" fontSize="7" fontWeight="bold" stroke="none">C#</text> },
    swift: { color: '#f05138', label: 'Swift', children: <text x="12" y="17" textAnchor="middle" fill="#f05138" fontSize="6" fontWeight="bold" stroke="none">Swift</text> },
    kt: { color: '#7f52ff', label: 'Kotlin', children: <text x="12" y="17" textAnchor="middle" fill="#7f52ff" fontSize="6" fontWeight="bold" stroke="none">Kt</text> },
    sh: { color: '#4eaa25', label: 'Shell', children: <text x="12" y="17" textAnchor="middle" fill="#4eaa25" fontSize="6" fontWeight="bold" stroke="none">SH</text> },
    bash: { color: '#4eaa25', label: 'Bash', children: <text x="12" y="17" textAnchor="middle" fill="#4eaa25" fontSize="5" fontWeight="bold" stroke="none">Bash</text> },
    zsh: { color: '#4eaa25', label: 'Zsh', children: <text x="12" y="17" textAnchor="middle" fill="#4eaa25" fontSize="6" fontWeight="bold" stroke="none">ZSH</text> },
    bat: { color: '#4eaa25', label: 'Batch', children: <text x="12" y="17" textAnchor="middle" fill="#4eaa25" fontSize="6" fontWeight="bold" stroke="none">BAT</text> },
    ps1: { color: '#012456', label: 'PowerShell', children: <text x="12" y="17" textAnchor="middle" fill="#012456" fontSize="5" fontWeight="bold" stroke="none">PS</text> },
    md: { color: '#2da44e', label: 'Markdown', children: <text x="12" y="17" textAnchor="middle" fill="#2da44e" fontSize="6" fontWeight="bold" stroke="none">MD</text> },
    markdown: { color: '#2da44e', label: 'Markdown', children: <text x="12" y="17" textAnchor="middle" fill="#2da44e" fontSize="5" fontWeight="bold" stroke="none">MD</text> },
    txt: { color: '#6a6a6a', label: 'Text', children: <><line x1="8" y1="13" x2="16" y2="13" /><line x1="8" y1="16" x2="14" y2="16" /></> },
    log: { color: '#6a6a6a', label: 'Log', children: <><polyline points="8 15 10 13 12 15 14 12 16 14" /><line x1="8" y1="17" x2="16" y2="17" /></> },
    pdf: { color: '#de2b25', label: 'PDF', children: <text x="12" y="17" textAnchor="middle" fill="#de2b25" fontSize="7" fontWeight="bold" stroke="none">PDF</text> },
    doc: { color: '#2b579a', label: 'Word', children: <text x="12" y="17" textAnchor="middle" fill="#2b579a" fontSize="6" fontWeight="bold" stroke="none">DOC</text> },
    docx: { color: '#2b579a', label: 'Word', children: <text x="12" y="17" textAnchor="middle" fill="#2b579a" fontSize="6" fontWeight="bold" stroke="none">DOC</text> },
    xls: { color: '#217346', label: 'Excel', children: <text x="12" y="17" textAnchor="middle" fill="#217346" fontSize="6" fontWeight="bold" stroke="none">XLS</text> },
    xlsx: { color: '#217346', label: 'Excel', children: <text x="12" y="17" textAnchor="middle" fill="#217346" fontSize="6" fontWeight="bold" stroke="none">XLS</text> },
    ppt: { color: '#d24726', label: 'PowerPoint', children: <text x="12" y="17" textAnchor="middle" fill="#d24726" fontSize="6" fontWeight="bold" stroke="none">PPT</text> },
    pptx: { color: '#d24726', label: 'PowerPoint', children: <text x="12" y="17" textAnchor="middle" fill="#d24726" fontSize="6" fontWeight="bold" stroke="none">PPT</text> },
    png: { color: '#a060d0', label: 'PNG', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    jpg: { color: '#a060d0', label: 'JPEG', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    jpeg: { color: '#a060d0', label: 'JPEG', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    gif: { color: '#a060d0', label: 'GIF', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    webp: { color: '#a060d0', label: 'WebP', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    svg: { color: '#ffb13b', label: 'SVG', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    ico: { color: '#a060d0', label: 'Icon', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    bmp: { color: '#a060d0', label: 'BMP', children: <><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></> },
    json: { color: '#cbb000', label: 'JSON', children: <text x="12" y="17" textAnchor="middle" fill="#cbb000" fontSize="5" fontWeight="bold" stroke="none">JSON</text> },
    yaml: { color: '#cb171e', label: 'YAML', children: <text x="12" y="17" textAnchor="middle" fill="#cb171e" fontSize="6" fontWeight="bold" stroke="none">YML</text> },
    yml: { color: '#cb171e', label: 'YAML', children: <text x="12" y="17" textAnchor="middle" fill="#cb171e" fontSize="7" fontWeight="bold" stroke="none">Y</text> },
    xml: { color: '#0060ac', label: 'XML', children: <text x="12" y="17" textAnchor="middle" fill="#0060ac" fontSize="6" fontWeight="bold" stroke="none">XML</text> },
    toml: { color: '#9c4221', label: 'TOML', children: <text x="12" y="17" textAnchor="middle" fill="#9c4221" fontSize="6" fontWeight="bold" stroke="none">TOML</text> },
    ini: { color: '#6a6a6a', label: 'INI', children: <text x="12" y="17" textAnchor="middle" fill="#6a6a6a" fontSize="7" fontWeight="bold" stroke="none">INI</text> },
    conf: { color: '#6a6a6a', label: 'Config', children: <><circle cx="12" cy="14" r="3" /><path d="M19 12v4M5 12v4" /></> },
    cfg: { color: '#6a6a6a', label: 'Config', children: <><circle cx="12" cy="14" r="3" /><path d="M19 12v4M5 12v4" /></> },
    env: { color: '#eab308', label: 'Environment', children: <text x="12" y="17" textAnchor="middle" fill="#eab308" fontSize="6" fontWeight="bold" stroke="none">ENV</text> },
    sql: { color: '#00758f', label: 'SQL', children: <><ellipse cx="12" cy="13" rx="5" ry="3" /><path d="M7 13v3c0 1.66 2.24 3 5 3s5-1.34 5-3v-3" /></> },
    css: { color: '#264de4', label: 'CSS', children: <text x="12" y="17" textAnchor="middle" fill="#264de4" fontSize="7" fontWeight="bold" stroke="none">#</text> },
    scss: { color: '#cc6699', label: 'SCSS', children: <text x="12" y="17" textAnchor="middle" fill="#cc6699" fontSize="6" fontWeight="bold" stroke="none">SCSS</text> },
    less: { color: '#1d365d', label: 'Less', children: <text x="12" y="17" textAnchor="middle" fill="#1d365d" fontSize="6" fontWeight="bold" stroke="none">Less</text> },
    html: { color: '#e34c26', label: 'HTML', children: <text x="12" y="17" textAnchor="middle" fill="#e34c26" fontSize="5" fontWeight="bold" stroke="none">HTML</text> },
    htm: { color: '#e34c26', label: 'HTML', children: <text x="12" y="17" textAnchor="middle" fill="#e34c26" fontSize="5" fontWeight="bold" stroke="none">HTML</text> },
    zip: { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    tar: { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    gz: { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    tgz: { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    rar: { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    '7z': { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    bz2: { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    xz: { color: '#a16207', label: 'Archive', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    jar: { color: '#ed8b00', label: 'JAR', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    war: { color: '#ed8b00', label: 'WAR', children: <><rect x="10" y="11" width="4" height="3" /><line x1="12" y1="14" x2="12" y2="17" /></> },
    mp3: { color: '#10b981', label: 'Audio', children: <><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></> },
    wav: { color: '#10b981', label: 'Audio', children: <><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></> },
    flac: { color: '#10b981', label: 'Audio', children: <><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></> },
    aac: { color: '#10b981', label: 'Audio', children: <><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></> },
    ogg: { color: '#10b981', label: 'Audio', children: <><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></> },
    m4a: { color: '#10b981', label: 'Audio', children: <><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></> },
    mp4: { color: '#8b5cf6', label: 'Video', children: <><polygon points="23 7 16 12 23 17 23 7" /><rect x="1" y="5" width="15" height="14" rx="2" ry="2" /></> },
    avi: { color: '#8b5cf6', label: 'Video', children: <><polygon points="23 7 16 12 23 17 23 7" /><rect x="1" y="5" width="15" height="14" rx="2" ry="2" /></> },
    mov: { color: '#8b5cf6', label: 'Video', children: <><polygon points="23 7 16 12 23 17 23 7" /><rect x="1" y="5" width="15" height="14" rx="2" ry="2" /></> },
    mkv: { color: '#8b5cf6', label: 'Video', children: <><polygon points="23 7 16 12 23 17 23 7" /><rect x="1" y="5" width="15" height="14" rx="2" ry="2" /></> },
    wmv: { color: '#8b5cf6', label: 'Video', children: <><polygon points="23 7 16 12 23 17 23 7" /><rect x="1" y="5" width="15" height="14" rx="2" ry="2" /></> },
    flv: { color: '#8b5cf6', label: 'Video', children: <><polygon points="23 7 16 12 23 17 23 7" /><rect x="1" y="5" width="15" height="14" rx="2" ry="2" /></> },
    woff: { color: '#f29337', label: 'Font', children: <><path d="M4 7v10c0 2 1 3 3 3h10c2 0 3-1 3-3V7c0-2-1-3-3-3H7c-2 0-3 1-3 3z" /><path d="M9 9h6M9 13h6" /></> },
    woff2: { color: '#f29337', label: 'Font', children: <><path d="M4 7v10c0 2 1 3 3 3h10c2 0 3-1 3-3V7c0-2-1-3-3-3H7c-2 0-3 1-3 3z" /><path d="M9 9h6M9 13h6" /></> },
    ttf: { color: '#f29337', label: 'Font', children: <><path d="M4 7v10c0 2 1 3 3 3h10c2 0 3-1 3-3V7c0-2-1-3-3-3H7c-2 0-3 1-3 3z" /><path d="M9 9h6M9 13h6" /></> },
    eot: { color: '#f29337', label: 'Font', children: <><path d="M4 7v10c0 2 1 3 3 3h10c2 0 3-1 3-3V7c0-2-1-3-3-3H7c-2 0-3 1-3 3z" /><path d="M9 9h6M9 13h6" /></> },
    lock: { color: '#d4a843', label: 'Lock', children: <><rect x="3" y="11" width="18" height="11" rx="2" ry="2" /><path d="M7 11V7a5 5 0 0110 0v4" /></> },
  }

  const match = extMap[ext]
  if (match) {
    const filePaths = (
      <>
        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        {match.children}
      </>
    )
    return {
      icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={match.color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">{filePaths}</svg>,
      color: match.color,
      label: match.label,
      svg: svg(filePaths, match.color),
    }
  }

  const defaultPaths = (
    <>
      <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
      <polyline points="14 2 14 8 20 8" />
    </>
  )
  return {
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#8b949e" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        {defaultPaths}
      </svg>
    ),
    color: '#8b949e',
    label: 'File',
    svg: svg(defaultPaths, '#8b949e'),
  }
}
