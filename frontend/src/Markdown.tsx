import type { ReactNode } from 'react'

/** 표 셀이 순수 수치(선택적 쉼표·소수·%)인지 - 서술 표에서 수치 열만 우측정렬 판단용. */
function isNumericCell(s: string): boolean {
  const t = s.trim()
  return t !== '' && /^[-+]?[\d,]+(?:\.\d+)?\s*%?$/.test(t)
}

/** 인라인 마크다운(굵게 **…**) → ReactNode. 별표가 그대로 노출되지 않게 파싱. */
export function mdInline(text: string): ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*)/g)
  return parts.map((p, i) => {
    const m = /^\*\*([^*]+)\*\*$/.exec(p)
    return m ? <strong key={i}>{m[1]}</strong> : <span key={i}>{p}</span>
  })
}

/** AI 서술 표 - 텍스트 열은 좌측·줄바꿈, 수치 위주 열은 우측정렬. 좁은 화면은 가로 스크롤. */
export function DataTable({ headers, rows }: { headers: string[]; rows: string[][] }) {
  // 열 단위 수치 판정: 비어있지 않은 셀 중 수치가 텍스트 이상이면 우측정렬('미사용' 등 소수 텍스트 혼재 허용).
  const numCol = headers.map((_, c) => {
    let num = 0, txt = 0
    for (const r of rows) {
      const v = (r[c] ?? '').trim()
      if (v === '') continue
      if (isNumericCell(v)) num++; else txt++
    }
    return num > 0 && num >= txt
  })
  return (
    <div className="md-table-wrap">
      <table className="md-table">
        <thead><tr>{headers.map((h, i) => <th key={i} className={numCol[i] ? 'md-num' : undefined}>{h}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>{r.map((c, j) => <td key={j} className={numCol[j] ? 'md-num' : undefined}>{c}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * 최소 마크다운 렌더 - #/##/### 제목 / -·* 불릿 / | 표 | / **굵게** / 문단. (외부 의존성 없이)
 * 심화분석 화면(DocResult)과 '데이터 보기' 모달(ResultModal)이 공유해 렌더 동일성을 유지.
 */
export function Markdown({ md }: { md: string }) {
  const lines = md.split('\n')
  const nodes: ReactNode[] = []
  let bullets: string[] = []
  let tableRows: string[][] = []
  let key = 0

  const flushBullets = () => {
    if (bullets.length) { nodes.push(<ul className="md-list" key={key++}>{bullets.map((b, i) => <li key={i}>{mdInline(b)}</li>)}</ul>); bullets = [] }
  }
  const flushTable = () => {
    if (tableRows.length) {
      const [head, ...rest] = tableRows
      const body = rest.filter((r) => !r.every((c) => /^-+$/.test(c.trim()) || c.trim() === ''))
      nodes.push(<DataTable key={key++} headers={head} rows={body} />)
      tableRows = []
    }
  }

  for (const raw of lines) {
    const line = raw.trimEnd()
    if (line.startsWith('|') && line.includes('|')) {
      flushBullets()
      tableRows.push(line.replace(/^\||\|$/g, '').split('|').map((c) => c.trim()))
      continue
    }
    flushTable()
    // 헤딩: ###/##/# 모두 지원. h3 는 소제목(md-h3)로, 나머지는 섹션 타이틀.
    if (line.startsWith('### ')) { flushBullets(); nodes.push(<div className="md-h3" key={key++}>{mdInline(line.slice(4))}</div>) }
    else if (line.startsWith('## ')) { flushBullets(); nodes.push(<div className="section-title" key={key++}>{mdInline(line.slice(3))}</div>) }
    else if (line.startsWith('# ')) { flushBullets(); nodes.push(<div className="section-title" key={key++}>{mdInline(line.slice(2))}</div>) }
    else if (line.startsWith('- ') || line.startsWith('* ')) { bullets.push(line.slice(2)) }
    else if (line.trim() === '') { flushBullets() }
    else { flushBullets(); nodes.push(<p className="narrative" key={key++}>{mdInline(line)}</p>) }
  }
  flushBullets(); flushTable()
  return <section>{nodes}</section>
}
