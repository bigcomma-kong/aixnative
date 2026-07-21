import { useState } from 'react'
import {
  fetchLocationReport, fetchPriceTrend, fetchPresale, fetchPresaleBrief, track, ApiError,
  type LocationReport, type MonthlyPrice, type PresaleNotice,
} from './api'
import { RentBarChart } from './RentBarChart'

interface LocationReportViewProps {
  onWantMore?: () => void
  embedded?: boolean
  /** 로그인 상태 - AI 분양 브리핑(크레딧) 버튼 노출 조건. */
  authed?: boolean
  onCreditBalance?: (balance: number) => void
  onNeedCredits?: () => void
}

/**
 * 무료 입지 리포트 뷰(Phase 1) - 주소/지역 입력 → 주변 시설·단지 스펙·최근 실거래 종합.
 * 비인증에서도 동작(top-of-funnel). 심화/딜분석은 로그인·크레딧 경로로 유도.
 */
export function LocationReportView({ onWantMore, embedded, authed, onCreditBalance, onNeedCredits }: LocationReportViewProps) {
  const [query, setQuery] = useState('')
  const [report, setReport] = useState<LocationReport | null>(null)
  const [trend, setTrend] = useState<MonthlyPrice[]>([])
  const [presale, setPresale] = useState<PresaleNotice[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(e: React.FormEvent) {
    e.preventDefault()
    const q = query.trim()
    if (!q || loading) return
    setLoading(true)
    setError(null)
    setTrend([])
    setPresale([])
    track('location_report', { meta: q.slice(0, 60) })
    try {
      const rep = await fetchLocationReport(q)
      setReport(rep)
      // 실거래 트렌드·분양 동향은 별도 지연 로딩(리포트 응답을 막지 않음). 실패해도 리포트는 그대로.
      const sigungu = rep.geo?.sigunguCode
      if (sigungu) fetchPriceTrend(sigungu, 12).then(setTrend).catch(() => setTrend([]))
      fetchPresale(undefined, 6).then(setPresale).catch(() => setPresale([]))
    } catch (err) {
      setError(err instanceof Error ? err.message : '리포트를 불러오지 못했습니다.')
      setReport(null)
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      {!embedded && (
        <div className="page-head">
          <div>
            <span className="eyebrow">FREE NEIGHBORHOOD REPORT</span>
            <h1>동네 리포트</h1>
            <p className="page-sub">주소만 넣으면 주변 교통·학교·편의시설과 단지 스펙, 최근 아파트 실거래를 한눈에. 무료.</p>
          </div>
        </div>
      )}

      <form className="locrep-search" onSubmit={run}>
        <input
          className="locrep-input"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="예) 서울 강남구 역삼동 / 도로명주소 / 아파트명"
          aria-label="주소 또는 지역"
        />
        <button type="submit" className="btn-primary" disabled={loading || !query.trim()}>
          {loading ? '분석 중…' : '입지 분석'}
        </button>
      </form>

      {error && <p className="locrep-error" role="alert">{error}</p>}

      {report && (
        <ReportBody
          report={report}
          trend={trend}
          presale={presale}
          onWantMore={onWantMore}
          authed={authed}
          onCreditBalance={onCreditBalance}
          onNeedCredits={onNeedCredits}
        />
      )}

      <style>{LOCREP_CSS}</style>
    </>
  )
}

function ReportBody({
  report, trend, presale, onWantMore, authed, onCreditBalance, onNeedCredits,
}: {
  report: LocationReport
  trend: MonthlyPrice[]
  presale: PresaleNotice[]
  onWantMore?: () => void
  authed?: boolean
  onCreditBalance?: (balance: number) => void
  onNeedCredits?: () => void
}) {
  const { geo, nearby, complexes, recentDeals, notes } = report
  const hasAny = nearby.length > 0 || complexes.length > 0 || recentDeals.length > 0
  const trendRows = trend.filter((t) => t.avgPricePerPyeong > 0)

  const [brief, setBrief] = useState<string | null>(null)
  const [briefing, setBriefing] = useState(false)
  const [briefErr, setBriefErr] = useState<string | null>(null)

  async function runBrief() {
    if (briefing) return
    setBriefing(true)
    setBriefErr(null)
    track('presale_brief')
    try {
      const res = await fetchPresaleBrief()
      setBrief(res.brief)
      onCreditBalance?.(res.creditBalance)
    } catch (err) {
      if (err instanceof ApiError && err.status === 402) {
        onNeedCredits?.()
        setBriefErr('크레딧이 부족합니다. 충전 후 다시 시도해 주세요.')
      } else {
        setBriefErr(err instanceof Error ? err.message : 'AI 브리핑에 실패했습니다.')
      }
    } finally {
      setBriefing(false)
    }
  }

  return (
    <div className="locrep-body">
      {report.macro && report.macro.baseRate != null && (
        <p className="locrep-macro muted">
          참고 · 기준금리 {report.macro.baseRate}%
          {report.macro.gov10y != null ? ` · 국고채 10년 ${report.macro.gov10y}%` : ''}
          {report.macro.asOf ? ` (${report.macro.asOf} 기준)` : ''}
        </p>
      )}
      {geo && (
        <p className="locrep-addr muted">
          {geo.roadAddress ?? geo.jibunAddress ?? report.query}
          {geo.jibunAddress && geo.roadAddress ? ` · ${geo.jibunAddress}` : ''}
        </p>
      )}

      {nearby.length > 0 && (
        <section className="locrep-section">
          <h2 className="locrep-h2">주변 시설 <span className="muted">(반경 1km)</span></h2>
          <div className="locrep-grid">
            {nearby.map((g) => (
              <div className="card locrep-poi" key={g.label}>
                <div className="locrep-poi-head">{g.label}</div>
                <ul className="locrep-poi-list">
                  {g.places.map((p, i) => (
                    <li key={`${g.label}-${i}`}>
                      <span className="locrep-poi-name">{p.name}</span>
                      {p.distanceM != null && <span className="chip locrep-dist">{formatMeters(p.distanceM)}</span>}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </section>
      )}

      {complexes.length > 0 && (
        <section className="locrep-section">
          <h2 className="locrep-h2">인근 단지</h2>
          <div className="locrep-grid">
            {complexes.map((c) => (
              <div className="card locrep-complex" key={c.kaptCode}>
                <div className="locrep-complex-name">{c.name}</div>
                <dl className="locrep-spec">
                  <Spec label="세대수" value={c.householdCount != null ? `${c.householdCount.toLocaleString()}세대` : null} />
                  <Spec label="동수" value={c.dongCount != null ? `${c.dongCount}개동` : null} />
                  <Spec label="사용승인" value={formatApprovalDate(c.approvalDate)} />
                  <Spec label="주차" value={c.parkingTotal != null ? `${c.parkingTotal.toLocaleString()}대` : null} />
                  <Spec label="지하철" value={c.subwayWalk} />
                  <Spec label="버스" value={c.busWalk} />
                  <Spec label="난방" value={c.heatingType} />
                </dl>
              </div>
            ))}
          </div>
        </section>
      )}

      {recentDeals.length > 0 && (
        <section className="locrep-section">
          <h2 className="locrep-h2">최근 아파트 실거래 <span className="muted">(최근 1년)</span></h2>
          <div className="locrep-table-wrap">
            <table className="locrep-table">
              <thead>
                <tr><th>거래월</th><th>단지</th><th>전용</th><th>층</th><th className="num">거래가</th></tr>
              </thead>
              <tbody>
                {recentDeals.map((d, i) => (
                  <tr key={i}>
                    <td>{d.dealYmd}</td>
                    <td>{d.aptName}{d.dong ? ` · ${d.dong}` : ''}</td>
                    <td>{formatArea(d.areaSqm)}</td>
                    <td>{d.floor}층</td>
                    <td className="num">{formatManwon(d.amountManwon)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {trendRows.length >= 2 && (
        <section className="locrep-section">
          <h2 className="locrep-h2">
            실거래 트렌드 <span className="muted">(시군구 · 최근 {trendRows.length}개월 · 평단가)</span>
          </h2>
          <RentBarChart
            data={trendRows.map((t) => ({ label: `${t.ym} · ${t.dealCount}건`, value: t.avgPricePerPyeong }))}
            unit="만원/평"
          />
        </section>
      )}

      {presale.length > 0 && (
        <section className="locrep-section">
          <h2 className="locrep-h2">분양 동향 <span className="muted">(전국 · 최근 청약공고)</span></h2>
          <div className="locrep-grid">
            {presale.map((p) => (
              <div className="card locrep-presale" key={`${p.houseName}-${p.noticeDate}`}>
                <div className="locrep-presale-top">
                  {p.region && <span className="chip">{p.region}</span>}
                  {p.totalSupply != null && <span className="muted">{p.totalSupply.toLocaleString()}세대</span>}
                </div>
                <div className="locrep-presale-name">{p.houseName}</div>
                {p.address && <div className="muted locrep-presale-addr">{p.address}</div>}
                <dl className="locrep-spec">
                  <Spec label="공고일" value={p.noticeDate} />
                  <Spec label="청약접수" value={p.receiptStart ? `${p.receiptStart}${p.receiptEnd ? ` ~ ${p.receiptEnd}` : ''}` : null} />
                  <Spec label="당첨발표" value={p.winnerDate} />
                </dl>
                {p.detailUrl && (
                  <a className="btn-link locrep-presale-link" href={p.detailUrl} target="_blank" rel="noopener noreferrer">
                    청약홈 공고 보기 →
                  </a>
                )}
              </div>
            ))}
          </div>

          <div className="locrep-brief">
            {authed ? (
              <>
                {!brief && (
                  <button type="button" className="btn-primary btn-xs" onClick={runBrief} disabled={briefing}>
                    {briefing ? 'AI 브리핑 생성 중…' : 'AI 분양 브리핑 (2크레딧)'}
                  </button>
                )}
                {briefErr && <p className="locrep-error" role="alert">{briefErr}</p>}
                {brief && (
                  <div className="card locrep-brief-box">
                    <div className="locrep-brief-title">AI 분양 브리핑</div>
                    <p className="locrep-brief-text">{brief}</p>
                  </div>
                )}
              </>
            ) : (
              <button type="button" className="btn-link" onClick={onWantMore}>
                로그인하고 AI 분양 브리핑 받기 →
              </button>
            )}
          </div>
        </section>
      )}

      {!hasAny && (
        <p className="muted">표시할 데이터가 아직 없습니다. 주소를 더 구체적으로 입력하거나 잠시 후 다시 시도해 주세요.</p>
      )}

      {notes.length > 0 && (
        <ul className="locrep-notes muted">
          {notes.map((n, i) => <li key={i}>{n}</li>)}
        </ul>
      )}

      {onWantMore && (
        <div className="locrep-cta">
          <button type="button" className="btn-primary" onClick={onWantMore}>
            상업용 딜 분석까지 - AI 언더라이팅 시작
          </button>
        </div>
      )}

      <style>{LOCREP_CSS}</style>
    </div>
  )
}

function Spec({ label, value }: { label: string; value: string | null }) {
  if (!value) return null
  return (
    <div className="locrep-spec-row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

/** 거리(m) → "120m" / "1.2km". */
function formatMeters(m: number): string {
  return m >= 1000 ? `${(m / 1000).toFixed(1)}km` : `${m}m`
}

/** "125000"(만원) → "12억 5,000". */
function formatManwon(raw: string): string {
  const n = Number((raw || '').replace(/[^0-9]/g, ''))
  if (!n) return raw || '-'
  const eok = Math.floor(n / 10000)
  const man = n % 10000
  if (eok > 0) return man > 0 ? `${eok}억 ${man.toLocaleString()}` : `${eok}억`
  return `${man.toLocaleString()}만`
}

/** 전용면적 ㎡ → "84.9㎡ (약 25평)". */
function formatArea(raw: string): string {
  const n = Number((raw || '').replace(/[^0-9.]/g, ''))
  if (!n) return raw || '-'
  const pyeong = Math.round(n / 3.3058)
  return `${n}㎡ · ${pyeong}평`
}

/** 사용승인일 yyyyMMdd/yyyy-MM-dd → "1998년". */
function formatApprovalDate(raw: string | null): string | null {
  if (!raw) return null
  const y = raw.replace(/[^0-9]/g, '').slice(0, 4)
  return y.length === 4 ? `${y}년` : raw
}

const LOCREP_CSS = `
.locrep-search { display: flex; gap: 0.6rem; margin: 0 0 1.2rem; flex-wrap: wrap; }
.locrep-input {
  flex: 1; min-width: 240px; padding: 0.8rem 1rem; font-size: 1rem;
  border: 1px solid var(--line, #d7dbe3); border-radius: 12px; background: var(--surface, #fff); color: inherit;
}
.locrep-input:focus { outline: 2px solid var(--accent, #2563eb); outline-offset: 1px; }
.locrep-error { color: #c2410c; margin: 0 0 1rem; }
.locrep-addr { margin: -0.4rem 0 1.4rem; }
.locrep-macro { margin: 0 0 0.5rem; font-size: 0.82rem; }
.locrep-section { margin: 0 0 1.8rem; }
.locrep-h2 { font-size: 1.05rem; font-weight: 700; margin: 0 0 0.8rem; }
.locrep-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 0.9rem; }
.locrep-poi { padding: 1rem 1.1rem; }
.locrep-poi-head { font-weight: 700; margin-bottom: 0.55rem; }
.locrep-poi-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.4rem; }
.locrep-poi-list li { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; font-size: 0.9rem; }
.locrep-poi-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.locrep-dist { flex: none; font-size: 0.72rem; }
.locrep-complex { padding: 1rem 1.1rem; }
.locrep-complex-name { font-weight: 700; margin-bottom: 0.6rem; }
.locrep-presale { padding: 1rem 1.1rem; display: flex; flex-direction: column; gap: 0.5rem; }
.locrep-presale-top { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.locrep-presale-name { font-weight: 700; line-height: 1.3; }
.locrep-presale-addr { font-size: 0.82rem; }
.locrep-presale-link { align-self: flex-start; margin-top: 0.2rem; font-size: 0.86rem; }
.locrep-brief { margin-top: 1.1rem; }
.locrep-brief-box { padding: 1.1rem 1.2rem; margin-top: 0.6rem; border-left: 3px solid var(--accent, #2d3aa8); }
.locrep-brief-title { font-weight: 700; font-size: 0.9rem; margin-bottom: 0.5rem; color: var(--accent, #2d3aa8); }
.locrep-brief-text { margin: 0; line-height: 1.65; white-space: pre-line; }
.locrep-spec { margin: 0; display: grid; gap: 0.35rem; }
.locrep-spec-row { display: flex; justify-content: space-between; gap: 0.6rem; font-size: 0.9rem; }
.locrep-spec-row dt { color: var(--ink-soft, #6b7280); margin: 0; }
.locrep-spec-row dd { margin: 0; font-weight: 600; font-variant-numeric: tabular-nums; }
.locrep-table-wrap { overflow-x: auto; }
.locrep-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
.locrep-table th, .locrep-table td { padding: 0.6rem 0.7rem; text-align: left; border-bottom: 1px solid var(--line, #e5e7eb); white-space: nowrap; }
.locrep-table th { font-weight: 600; color: var(--ink-soft, #6b7280); }
.locrep-table .num { text-align: right; font-variant-numeric: tabular-nums; }
.locrep-notes { margin: 1rem 0 0; padding-left: 1.1rem; font-size: 0.82rem; }
.locrep-cta { margin-top: 1.6rem; }
`
