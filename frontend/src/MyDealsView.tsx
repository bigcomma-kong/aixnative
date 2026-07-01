import { useEffect, useState } from 'react'
import { api, ApiError, type DealSummary } from './api'

const fmtDate = (s: string | null): string =>
  s ? new Date(s).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' }) : '-'

/**
 * 내 딜 대시보드 — 내가 분석한 딜을 한 곳에 모아 상태·최근 활동을 보여주고 보고서로 재진입(리텐션 허브).
 * 서버가 딜명으로 집계한 요약(`/api/underwriting/deals`)을 카드로 렌더.
 */
export function MyDealsView() {
  const [deals, setDeals] = useState<DealSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  useEffect(() => {
    api.myDeals()
      .then(setDeals)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '딜 목록 조회 실패'))
  }, [])

  async function openReport(anchorRunId: number) {
    setBusyId(anchorRunId); setError(null)
    try {
      const html = await api.reportHtml(anchorRunId)
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
      window.open(url, '_blank', 'noopener')
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '보고서를 불러오지 못했습니다.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">내 딜</span>
          <h1>내가 분석한 딜</h1>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      {deals === null ? (
        <p className="hint">불러오는 중…</p>
      ) : deals.length === 0 ? (
        <div className="card">
          <p className="hint">아직 분석한 딜이 없습니다. 언더라이팅·심화 분석에서 딜을 분석하면 여기에 모입니다.</p>
        </div>
      ) : (
        <div className="deals-grid">
          {deals.map((d) => (
            <div className="deal-card" key={d.dealName}>
              <div className="deal-top">
                {d.assetType && <span className="deal-type">{d.assetType}</span>}
                <span className="deal-date">최근 {fmtDate(d.lastActivityAt)}</span>
              </div>
              <h3 className="deal-name">{d.dealName}</h3>
              {d.location && <div className="deal-loc">📍 {d.location}</div>}
              <div className="deal-stages">
                {d.completedStages.map((s) => <span key={s} className="deal-stage-chip">{s}</span>)}
                {d.advancedCount > 0 && <span className="deal-stage-chip adv">심화 {d.advancedCount}종</span>}
                {d.completedStages.length === 0 && d.advancedCount === 0 && (
                  <span className="deal-stage-none">완료 단계 없음</span>
                )}
              </div>
              <div className="deal-foot">
                <span className="deal-runs">분석 {d.runCount}건</span>
                <button
                  type="button"
                  className="btn-primary btn-xs"
                  disabled={!d.hasReport || busyId === d.anchorRunId}
                  onClick={() => void openReport(d.anchorRunId)}
                >
                  {busyId === d.anchorRunId ? '여는 중…' : d.hasReport ? '보고서 보기' : '보고서 없음'}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </>
  )
}
