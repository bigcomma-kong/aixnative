import { useEffect, useState } from 'react'
import { FollowUpPanel } from './FollowUpPanel'
import { api, ApiError, track, type DartCorp, type TenantCreditResponse, type TenantSimYear } from './api'

interface TenantCreditPanelProps {
  onCreditBalance: (balance: number) => void
  onNeedCredits: () => void
  toolCosts?: Record<string, number>
}

const GRADE_TONE: Record<string, string> = { A: 'go', B: 'go', C: 'cond', D: 'no' }
const VERDICT_TONE: Record<string, string> = { 적격: 'go', 조건부: 'cond', 부적격: 'no' }
const RISK_TONE: Record<string, string> = { 안전: 'go', 양호: 'go', 주의: 'cond', 경고: 'cond', 위험: 'no' }

/** 원 → "1,234억" / "5,600만" (표 표시용 축약 - 원 단위 원본은 툴팁). */
function eok(won?: number | null): string {
  if (won == null) return '-'
  const abs = Math.abs(won)
  if (abs >= 1e8) return `${(won / 1e8).toLocaleString(undefined, { maximumFractionDigits: 1 })}억`
  if (abs >= 1e4) return `${Math.round(won / 1e4).toLocaleString()}만`
  return won.toLocaleString()
}

function pct(v?: number | null): string {
  return v == null ? '-' : `${v.toLocaleString(undefined, { maximumFractionDigits: 1 })}%`
}

/**
 * 임차인 신용·지급능력 - DART 사업보고서 5개년 재무로 코드가 등급·지급능력·10년 임대료 커버리지를 계산하고,
 * AI 는 그 결과를 임대차 조건(보증금·기간·보증) 제안으로 서술한다.
 */
export function TenantCreditPanel({ onCreditBalance, onNeedCredits, toolCosts }: TenantCreditPanelProps) {
  const [query, setQuery] = useState('')
  const [picked, setPicked] = useState<DartCorp | null>(null)
  const [suggestions, setSuggestions] = useState<DartCorp[]>([])
  const [rent, setRent] = useState('')
  const [growth, setGrowth] = useState('3')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<TenantCreditResponse | null>(null)

  // 회사 자동완성 - 입력이 멈추면 검색(무료). 선택한 뒤에는 다시 찾지 않는다.
  useEffect(() => {
    if (picked || query.trim().length < 2) { setSuggestions([]); return }
    // 늦게 도착한 이전 검색 응답이 선택 후 목록을 다시 여는 것을 막는다.
    let alive = true
    const t = window.setTimeout(() => {
      api.tenantCreditSearch(query.trim())
        .then((list) => { if (alive) setSuggestions(list) })
        .catch(() => { if (alive) setSuggestions([]) })
    }, 300)
    return () => { alive = false; window.clearTimeout(t) }
  }, [query, picked])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null); setResult(null)
    try {
      const monthly = Number(rent.replace(/[^0-9]/g, ''))
      const g = Number(growth)
      const res = await api.tenantCreditAnalyze({
        company: picked?.corpCode ?? query.trim(),
        monthlyRentWon: monthly > 0 ? monthly : undefined,
        rentGrowthPct: Number.isFinite(g) ? g : undefined,
      })
      setResult(res)
      onCreditBalance(res.creditBalance)
      track('analysis_done', { path: 'advanced', meta: 'TENANT_CREDIT' })
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      setError(err instanceof ApiError ? err.message : '임차인 분석에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="tc-section" aria-labelledby="tc-title">
      <div className="page-head">
        <div>
          <span className="eyebrow">TENANT CREDIT</span>
          <h2 id="tc-title">임차인, 임대료 낼 수 있는 회사인가요?</h2>
          <p className="page-sub">금융감독원 DART 사업보고서 5개년 재무로 신용등급·지급능력·10년 임대료 커버리지를 코드가 계산하고, AI 가 보증금·계약기간·보증 조건을 제안합니다.</p>
        </div>
      </div>

      <form className="card" onSubmit={submit}>
        <div className="form-grid">
          <div className="full tc-search">
            <label htmlFor="tcCompany">임차 회사 <span className="req">*</span></label>
            <input id="tcCompany" value={query} autoComplete="off"
              onChange={(e) => { setQuery(e.target.value); setPicked(null) }}
              placeholder="회사명 또는 종목코드 (예: 쿠팡, 035420)" />
            {suggestions.length > 0 && (
              <ul className="tc-suggest" role="listbox">
                {suggestions.map((c) => (
                  <li key={c.corpCode}>
                    <button type="button" onClick={() => { setPicked(c); setQuery(c.corpName); setSuggestions([]) }}>
                      <b>{c.corpName}</b>
                      <span className="muted">{c.stockCode ? `상장 ${c.stockCode}` : '비상장'}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <span className="field-hint">상장사·외감 대상 법인처럼 DART 에 사업보고서를 내는 회사만 조회됩니다.</span>
          </div>
          <div>
            <label htmlFor="tcRent">월 임대료 <span className="opt">(선택, 원)</span></label>
            <input id="tcRent" inputMode="numeric" value={rent} onChange={(e) => setRent(e.target.value)} placeholder="예: 45000000" />
          </div>
          <div>
            <label htmlFor="tcGrowth">연 임대료 인상률(%)</label>
            <input id="tcGrowth" inputMode="decimal" value={growth} onChange={(e) => setGrowth(e.target.value)} />
          </div>
        </div>
        <div className="actions">
          <button type="submit" className="btn-primary" disabled={busy || query.trim().length < 2}>
            {busy ? 'DART 조회·분석 중…' : `임차인 신용 분석 · ${toolCosts?.TENANT_CREDIT != null ? `${toolCosts.TENANT_CREDIT}크레딧` : '크레딧'}`}
          </button>
          <p className="hint">월 임대료를 넣으면 영업이익 대비 임대료 커버리지를 낙관·기준·비관 3개 시나리오로 10년간 계산합니다. 재무를 찾지 못하면 크레딧은 차감되지 않습니다.</p>
        </div>
        {error && <p className="error" role="alert">{error}</p>}
      </form>

      {result && <TenantCreditResult res={result} />}
      {result && (
        <FollowUpPanel key={result.runId} runId={result.runId} toolCosts={toolCosts}
          onCreditBalance={onCreditBalance} onNeedCredits={onNeedCredits} />
      )}
    </section>
  )
}

function TenantCreditResult({ res }: { res: TenantCreditResponse }) {
  const c = res.calc
  const a = res.analysis
  const r = c.ratios
  const sim = c.simulation
  return (
    <div className="card tc-result">
      <div className="tc-head">
        <div>
          <h3>{c.company?.corpName ?? c.corp.corpName}</h3>
          <p className="muted">
            {[c.company?.corpClassLabel, c.company?.ceoName && `대표 ${c.company.ceoName}`, c.source].filter(Boolean).join(' · ')}
          </p>
        </div>
        <div className="tc-grade">
          <span className={`tc-grade-letter tone-${GRADE_TONE[c.rating.grade] ?? 'cond'}`}>{c.rating.grade}</span>
          <span className="tc-grade-score">{c.rating.score}점</span>
        </div>
      </div>

      {a?.verdict && (
        <div className="risk-head">
          <span className={`sev-badge ${VERDICT_TONE[a.verdict] ?? 'cond'}`}>임차인 {a.verdict}</span>
          <span className="sev-badge cond">지급능력 {c.paymentCapacity.rating}</span>
        </div>
      )}
      {a?.summary && <p className="tc-summary">{a.summary}</p>}

      <div className="tc-scores">
        {([['안정성', c.rating.stabilityScore], ['수익성', c.rating.profitabilityScore], ['성장성', c.rating.growthScore]] as const).map(([k, v]) => (
          <div key={k} className="tc-score">
            <span>{k}</span>
            <div className="tc-bar" aria-hidden="true"><i style={{ transform: `scaleX(${Math.min(100, v) / 100})` }} /></div>
            <b>{v}</b>
          </div>
        ))}
      </div>

      <h4>재무 비율 <span className="rule-tag">코드 계산 · {c.latestYear}년 {c.fsDiv}</span></h4>
      <dl className="kv-grid tc-kv">
        <dt>부채비율</dt><dd>{r.capitalImpaired ? '자본잠식' : pct(r.debtRatio)}</dd>
        <dt>유동비율</dt><dd>{pct(r.currentRatio)}</dd>
        <dt>영업이익률</dt><dd>{pct(r.operatingMargin)}</dd>
        <dt>ROE</dt><dd>{pct(r.roe)}</dd>
        <dt>매출 성장률</dt><dd>{pct(r.revenueGrowth)}</dd>
        <dt>월 영업현금흐름</dt><dd>{eok(c.paymentCapacity.monthlyOperatingCashFlowWon)}</dd>
      </dl>

      {c.history.length > 0 && (
        <div className="table-scroll">
          <table className="doc-table">
            <thead><tr><th>연도</th><th>매출</th><th>영업이익</th><th>순이익</th><th>영업현금흐름</th><th>자본총계</th></tr></thead>
            <tbody>
              {c.history.map((h) => (
                <tr key={h.year}>
                  <td>{h.year}</td><td>{eok(h.revenue)}</td><td>{eok(h.operatingIncome)}</td>
                  <td>{eok(h.netIncome)}</td><td>{eok(h.operatingCashFlow)}</td><td>{eok(h.totalEquity)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {a?.trend && <p className="field-hint">{a.trend}</p>}

      {sim && <SimulationBlock sim={sim} />}

      {c.codeRisks.length > 0 && (
        <section className="scr-section doc-block">
          <h4>재무 리스크 <span className="rule-tag">규칙</span></h4>
          <ul className="bullet-list">{c.codeRisks.map((x) => <li key={x}>{x}</li>)}</ul>
        </section>
      )}

      {a && (
        <div className="tc-ai-grid">
          {a.strengths && a.strengths.length > 0 && <TcList title="강점" items={a.strengths} />}
          {a.concerns && a.concerns.length > 0 && <TcList title="우려" items={a.concerns} />}
          {a.leaseTerms && (
            <section className="scr-section doc-block">
              <h4>권장 임대 조건</h4>
              <dl className="kv-grid">
                {a.leaseTerms.depositMonths && (<><dt>보증금</dt><dd>{a.leaseTerms.depositMonths}</dd></>)}
                {a.leaseTerms.termYears && (<><dt>계약 기간</dt><dd>{a.leaseTerms.termYears}</dd></>)}
              </dl>
              {a.leaseTerms.guarantees && a.leaseTerms.guarantees.length > 0 && (
                <ul className="bullet-list">{a.leaseTerms.guarantees.map((x) => <li key={x}>{x}</li>)}</ul>
              )}
            </section>
          )}
          {a.monitoring && a.monitoring.length > 0 && <TcList title="계약 후 모니터링" items={a.monitoring} />}
        </div>
      )}
      <p className="disclaimer">{res.disclaimer}</p>
    </div>
  )
}

function SimulationBlock({ sim }: { sim: NonNullable<TenantCreditResponse['calc']['simulation']> }) {
  const rows: [string, TenantSimYear[], number | null | undefined, number][] = [
    ['낙관', sim.optimistic, sim.breakEven.optimistic, sim.rates.optimisticPct],
    ['기준', sim.base, sim.breakEven.base, sim.rates.basePct],
    ['비관', sim.pessimistic, sim.breakEven.pessimistic, sim.rates.pessimisticPct],
  ]
  const years = [1, 3, 5, 10].filter((y) => y <= sim.base.length)
  return (
    <section className="scr-section doc-block">
      <h4>10년 임대료 커버리지 <span className="rule-tag">코드 계산</span></h4>
      <p className="field-hint">
        영업이익 ÷ 연 임대료. 현재 {sim.currentCoverage}배 · 월 {eok(sim.monthlyRentWon)}원 · 연 {sim.rentGrowthPct}% 인상. {sim.rates.rationale}
      </p>
      <div className="table-scroll">
        <table className="doc-table">
          <thead>
            <tr><th>시나리오</th><th>영업이익 성장</th>{years.map((y) => <th key={y}>{y}년차</th>)}<th>임대료 역전</th></tr>
          </thead>
          <tbody>
            {rows.map(([label, list, be, g]) => (
              <tr key={label}>
                <td>{label}</td>
                <td>{g > 0 ? '+' : ''}{g}%</td>
                {years.map((y) => {
                  const row = list[y - 1]
                  return <td key={y}><span className={`sev-badge ${RISK_TONE[row.risk] ?? 'cond'}`}>{row.coverage}배</span></td>
                })}
                <td>{be != null ? `${be}년차` : '없음'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>{sim.assessment}</p>
    </section>
  )
}

function TcList({ title, items }: { title: string; items: string[] }) {
  return (
    <section className="scr-section doc-block">
      <h4>{title}</h4>
      <ul className="bullet-list">{items.map((x) => <li key={x}>{x}</li>)}</ul>
    </section>
  )
}
