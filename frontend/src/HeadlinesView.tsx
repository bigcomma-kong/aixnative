import { useEffect, useState } from 'react'
import { api, type HeadlineGroup } from './api'

/** 매체 라벨 → 브랜드 색(출처 뱃지·좌측 악센트). 알 수 없는 매체는 중립색. */
const SOURCE_ACCENT: Record<string, string> = {
  SPI: '#2563eb',
  코어비트: '#0d9488',
  딜사이트: '#b45309',
}

function accentOf(source: string): string {
  return SOURCE_ACCENT[source] ?? '#64748b'
}

/** 상대 시각(방금/N분 전/N시간 전/N일 전), 그보다 오래면 월-일. */
function fmtRelative(s: string | null): string {
  if (!s) return ''
  const then = new Date(s).getTime()
  if (Number.isNaN(then)) return ''
  const diffMin = Math.floor((Date.now() - then) / 60000)
  if (diffMin < 1) return '방금'
  if (diffMin < 60) return `${diffMin}분 전`
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return `${diffHr}시간 전`
  const diffDay = Math.floor(diffHr / 24)
  if (diffDay < 7) return `${diffDay}일 전`
  return new Date(s).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' })
}

/**
 * 업계 헤드라인 보드 — 자산운용사가 주로 보는 CRE 매체(SPI·코어비트·딜사이트)의 최신 기사 제목을
 * 매체별로 묶어 보여준다. 제목+출처+시각만 노출하고, 클릭하면 원문(새 탭)으로 이동한다.
 */
export function HeadlinesView({ embedded }: { embedded?: boolean } = {}) {
  const [groups, setGroups] = useState<HeadlineGroup[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    api
      .headlines()
      .then((g) => live && setGroups(g))
      .catch((e: unknown) => live && setError(e instanceof Error ? e.message : '헤드라인을 불러오지 못했습니다.'))
    return () => {
      live = false
    }
  }, [])

  if (error) return <div className="hl-state hl-error">{error}</div>
  if (!groups) return <div className="hl-state">불러오는 중…</div>

  const empty = groups.every((g) => g.items.length === 0)
  if (groups.length === 0 || empty) {
    return (
      <div className="hl-wrap">
        {!embedded && <HeadlinesHeader />}
        <div className="hl-state">아직 수집된 헤드라인이 없습니다. 잠시 후 다시 확인해 주세요.</div>
      </div>
    )
  }

  return (
    <div className="hl-wrap">
      {!embedded && <HeadlinesHeader />}
      <div className="hl-grid">
        {groups.map((g) => (
          <section key={g.source} className="hl-col" style={{ ['--hl-accent' as string]: accentOf(g.source) }}>
            <header className="hl-col-head">
              <span className="hl-badge">{g.source}</span>
              <span className="hl-count">{g.items.length}</span>
            </header>
            <ul className="hl-list">
              {g.items.map((it, i) => (
                <li key={i} className="hl-item">
                  {it.url ? (
                    <a href={it.url} target="_blank" rel="noopener noreferrer" className="hl-link">
                      <span className="hl-title">{it.title}</span>
                      <time className="hl-time">{fmtRelative(it.publishedAt)}</time>
                    </a>
                  ) : (
                    <span className="hl-link hl-link--dead">
                      <span className="hl-title">{it.title}</span>
                      <time className="hl-time">{fmtRelative(it.publishedAt)}</time>
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
      <p className="hl-foot">
        제목·출처만 제공하며 본문은 각 매체 원문 링크로 이동합니다. 유료 매체는 로그인이 필요할 수 있습니다.
      </p>
    </div>
  )
}

function HeadlinesHeader() {
  return (
    <div className="page-head">
      <div>
        <span className="eyebrow">INDUSTRY HEADLINES</span>
        <h1>업계 헤드라인</h1>
        <p className="page-sub">상업용 부동산 매체의 최신 기사 제목을 한곳에서. 실제 매각·우선협상 등 거래 신호는 ‘시장’ 탭의 오늘의 딜에서 확인하세요.</p>
      </div>
    </div>
  )
}
