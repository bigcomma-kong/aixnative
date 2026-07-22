import { useEffect, useState } from 'react'
import { fetchMyBriefs, type SavedBrief } from './api'

/** 마이페이지 - 저장된 AI 동네 브리핑 목록. 클릭하면 본문 펼침. */
export function MyBriefsView() {
  const [briefs, setBriefs] = useState<SavedBrief[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)

  useEffect(() => {
    let alive = true
    fetchMyBriefs()
      .then((b) => alive && setBriefs(b))
      .catch((e) => alive && setError(e instanceof Error ? e.message : '불러오지 못했습니다.'))
    return () => {
      alive = false
    }
  }, [])

  if (error) return <p className="locrep-error">{error}</p>
  if (briefs === null) return <p className="muted">불러오는 중…</p>
  if (briefs.length === 0) {
    return <p className="muted">저장된 브리핑이 없습니다. 동네 리포트에서 &lsquo;AI 동네 브리핑&rsquo;을 받아보세요.</p>
  }

  return (
    <div className="mybriefs">
      {briefs.map((b) => {
        const isOpen = open === b.id
        return (
          <div className="card mybrief" key={b.id}>
            <button
              type="button"
              className="mybrief-head"
              aria-expanded={isOpen}
              onClick={() => setOpen(isOpen ? null : b.id)}
            >
              <span className="mybrief-q">
                {b.region && <span className="chip">{b.region}</span>} {b.query}
              </span>
              <span className="muted mybrief-date">{formatDate(b.createdAt)}</span>
            </button>
            {isOpen && <p className="mybrief-text">{b.brief}</p>}
          </div>
        )
      })}
      <style>{CSS}</style>
    </div>
  )
}

function formatDate(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('ko-KR')
}

const CSS = `
.mybriefs { display: grid; gap: 0.7rem; }
.mybrief { padding: 0; overflow: hidden; }
.mybrief-head {
  width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 0.8rem;
  padding: 0.95rem 1.1rem; background: none; border: none; cursor: pointer; text-align: left; color: inherit;
}
.mybrief-head:hover { background: rgba(0,0,0,0.02); }
.mybrief-q { font-weight: 600; }
.mybrief-date { flex: none; font-size: 0.82rem; }
.mybrief-text { margin: 0; padding: 0 1.1rem 1.1rem; line-height: 1.65; white-space: pre-line; }
`
