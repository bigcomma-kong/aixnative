import { useEffect, useState } from 'react'
import {
  api, ApiError,
  type AnalysisType, type AnalyzeResponse, type Analysis, type GuidelineSummary, type ProForma, type ProFormaResponse,
  type RunResult, type RunSummary, type Scenario, type UnderwriteInput,
} from './api'
import { CashflowChart } from './Chart'
import { DealCompare } from './DealCompare'

interface UnderwriteViewProps {
  onCreditBalance: (balance: number) => void
  /** 크레딧 소진(402) 시 중앙 페이월 안내 노출. */
  onNeedCredits: () => void
}

interface FormState {
  dealName: string
  assetType: string
  location: string
  notes: string
  askingPriceEok: string
  noiEok: string
  ltvPct: string
  loanRatePct: string
  exitCapPct: string
  holdYears: string
  rentGrowthPct: string
}

const ASSET_TYPES = ['오피스', '물류', '호텔', '리테일'] as const

/** IM 분석 파이프라인 단계 (각 1 크레딧). */
const STAGES: { type: AnalysisType; label: string; hint: string }[] = [
  { type: 'SCREENING', label: '1차 스크리닝', hint: '지표·Flag·Go/No-Go' },
  { type: 'MARKET_STUDY', label: '시장조사', hint: '권역·가정 검증' },
  { type: 'UNDERWRITING', label: '언더라이팅', hint: 'IRR·DSCR 결론' },
  { type: 'IC_MEMO', label: '투심 메모', hint: 'IC 상정용 종합' },
]

const STAGE_LABEL: Record<string, string> = Object.fromEntries(STAGES.map((s) => [s.type, s.label]))

// 필수 입력은 빈 값으로 시작(데모 숫자 자동 채움 금지 → 의도치 않은 분석/크레딧 소진 방지).
// 보유기간·임대성장률은 표준 가정이라 기본값 유지(선택).
const INITIAL: FormState = {
  dealName: '',
  assetType: '오피스',
  location: '',
  notes: '',
  askingPriceEok: '',
  noiEok: '',
  ltvPct: '',
  loanRatePct: '',
  exitCapPct: '',
  holdYears: '5',
  rentGrowthPct: '3.0',
}

interface Results {
  runId?: number
  analysisType?: string
  proForma: ProForma
  scenarios: Scenario[]
  guidelineChecks?: GuidelineSummary
  disclaimer: string
  analysis?: Analysis | null
  analysisRaw?: string | null
  provider?: string
}

export function UnderwriteView({ onCreditBalance, onNeedCredits }: UnderwriteViewProps) {
  const [form, setForm] = useState<FormState>(INITIAL)
  const [results, setResults] = useState<Results | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'none' | 'proforma' | AnalysisType>('none')
  const [reportBusy, setReportBusy] = useState(false)
  const [historyVersion, setHistoryVersion] = useState(0)

  function set<K extends keyof FormState>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  function buildInput(): UnderwriteInput {
    return {
      dealName: form.dealName || undefined,
      assetType: form.assetType || undefined,
      location: form.location || undefined,
      notes: form.notes || undefined,
      askingPriceEok: Number(form.askingPriceEok),
      noiEok: Number(form.noiEok),
      ltvPct: Number(form.ltvPct),
      loanRatePct: Number(form.loanRatePct),
      exitCapPct: Number(form.exitCapPct),
      // 선택값 — 비어 있으면 표준 가정 적용.
      holdYears: Number(form.holdYears || '5'),
      rentGrowthPct: Number(form.rentGrowthPct || '3'),
    }
  }

  /**
   * 필수 입력 검증. requireName=true(AI 분석)면 딜 이름도 필수.
   * 통과하지 못하면 안내 문자열 반환 → 호출부가 실행을 막아 빈 값/크레딧 낭비를 방지.
   */
  function validate(requireName: boolean): string | null {
    if (requireName && !form.dealName.trim()) return '딜 이름을 입력하세요. (분석 이력 구분에 필요합니다)'
    if (!(Number(form.askingPriceEok) > 0)) return '매입가(억원)를 입력하세요.'
    if (!(Number(form.noiEok) > 0)) return 'NOI(억원)를 입력하세요.'
    const ltv = Number(form.ltvPct)
    if (form.ltvPct.trim() === '' || ltv < 0 || ltv > 100) return 'LTV(%)는 0~100 사이로 입력하세요.'
    if (!(Number(form.loanRatePct) >= 0) || form.loanRatePct.trim() === '') return '대출금리(%)를 입력하세요.'
    if (!(Number(form.exitCapPct) > 0)) return 'Exit Cap(%)를 입력하세요.'
    return null
  }

  async function runProforma() {
    const invalid = validate(false)
    if (invalid) { setError(invalid); return }
    setError(null)
    setBusy('proforma')
    try {
      const res: ProFormaResponse = await api.proforma(buildInput())
      setResults({ proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer })
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '계산 중 오류가 발생했습니다.')
    } finally {
      setBusy('none')
    }
  }

  async function runStage(type: AnalysisType) {
    const invalid = validate(true)
    if (invalid) { setError(invalid); return }
    setError(null)
    setBusy(type)
    try {
      const res: AnalyzeResponse = await api.analyzeStage(type, buildInput())
      setResults({
        runId: res.runId, analysisType: res.analysisType,
        proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer,
        analysis: res.analysis, analysisRaw: res.analysisRaw, provider: res.provider,
      })
      onCreditBalance(res.creditBalance)
      setHistoryVersion((v) => v + 1)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) {
        onNeedCredits()
      } else if (err instanceof ApiError && err.status === 503) {
        // 서버가 실제 사유(인증 토큰 미설정 / Claude API 4xx·5xx·rate limit 등)를 담아 보낸다.
        setError(err.message || 'AI 분석 서비스를 사용할 수 없습니다. ProForma 계산은 가능합니다.')
      } else {
        setError(err instanceof ApiError ? err.message : '분석 중 오류가 발생했습니다.')
      }
    } finally {
      setBusy('none')
    }
  }

  /** 현재 결과가 속한 딜의 분석 단계를 합본한 HTML 보고서를 새 창으로 연다. */
  async function openReport() {
    if (!results?.runId) return
    setReportBusy(true)
    try {
      const html = await api.reportHtml(results.runId)
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
      window.open(url, '_blank', 'noopener')
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '보고서를 불러오지 못했습니다.')
    } finally {
      setReportBusy(false)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">딜 언더라이팅</span>
          <h1>매물 한 건, 1분 심사.</h1>
        </div>
      </div>

      <div className="layout">
        <form className="card input-panel" onSubmit={(e) => { e.preventDefault(); runProforma() }}>
          <div className="form-head">
            <span className="section-title" style={{ margin: 0 }}>딜 입력 <span className="req-legend"><span className="req">*</span> 필수</span></span>
            <button type="button" className="btn-link" onClick={() => { setForm(INITIAL); setResults(null); setError(null) }}>
              초기화
            </button>
          </div>

          <div className="form-grid">
            <div className="full">
              <label htmlFor="dealName">딜 이름 <span className="req">*</span></label>
              <input id="dealName" value={form.dealName} onChange={(e) => set('dealName', e.target.value)} placeholder="예: 강남 오피스 (AI 분석에 필수)" />
            </div>

            <div className="full">
              <label>자산유형</label>
              <div className="seg" role="group" aria-label="자산유형">
                {ASSET_TYPES.map((t) => (
                  <button key={t} type="button" aria-pressed={form.assetType === t} onClick={() => set('assetType', t)}>
                    {t}
                  </button>
                ))}
              </div>
            </div>

            <div className="full">
              <label htmlFor="location">위치 / 권역 (선택)</label>
              <input id="location" value={form.location} onChange={(e) => set('location', e.target.value)} placeholder="예: 서울 GBD, 판교" />
            </div>

            <NumField id="askingPriceEok" label="매입가 (억원)" required placeholder="예: 1000" value={form.askingPriceEok} onChange={(v) => set('askingPriceEok', v)} />
            <NumField id="noiEok" label="NOI (억원)" required placeholder="예: 55" value={form.noiEok} onChange={(v) => set('noiEok', v)} />
            <NumField id="ltvPct" label="LTV (%)" required placeholder="예: 50" value={form.ltvPct} onChange={(v) => set('ltvPct', v)} />
            <NumField id="loanRatePct" label="대출금리 (%)" required placeholder="예: 3.5" value={form.loanRatePct} onChange={(v) => set('loanRatePct', v)} />
            <NumField id="exitCapPct" label="Exit Cap (%)" required placeholder="예: 5.0" value={form.exitCapPct} onChange={(v) => set('exitCapPct', v)} />
            <NumField id="holdYears" label="보유기간 (년)" placeholder="기본 5" value={form.holdYears} onChange={(v) => set('holdYears', v)} />
            <NumField id="rentGrowthPct" label="임대성장률 (%)" placeholder="기본 3" value={form.rentGrowthPct} onChange={(v) => set('rentGrowthPct', v)} />

            <div className="full">
              <label htmlFor="notes">메모 (선택) · IM 요약·임대 현황·특이사항</label>
              <textarea id="notes" rows={3} value={form.notes} onChange={(e) => set('notes', e.target.value)}
                placeholder="예: 핵심 임차인 2026년 만기, 최근 리모델링 완료 등" />
            </div>
          </div>

          <div className="actions">
            <div className="stage-grid">
              {STAGES.map((s) => (
                <button key={s.type} type="button" className="stage-btn"
                  onClick={() => runStage(s.type)} disabled={busy !== 'none'}>
                  <span className="stage-label">{busy === s.type ? '분석 중…' : s.label}</span>
                  <span className="stage-hint">{s.hint} · 1크레딧</span>
                </button>
              ))}
            </div>
            <button type="submit" className="btn-ghost" disabled={busy !== 'none'}>
              {busy === 'proforma' ? '계산 중…' : 'ProForma만 계산 (무료)'}
            </button>
            <p className="hint">각 단계는 AI 1회 호출 = 1 크레딧. 같은 딜 이름으로 단계를 쌓으면 보고서에 합본됩니다.</p>
          </div>
          {error && <p className="error">{error}</p>}
        </form>

        <div className="card">
          {results ? (
            <ResultPanel results={results} onReport={openReport} reportBusy={reportBusy} />
          ) : (
            <div className="result-empty">
              <div className="empty-state">
                <div className="empty-ico" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M3 3v18h18" />
                    <path d="M7 14l3.5-3.5L14 14l4-5" />
                  </svg>
                </div>
                <h3>분석 결과가 여기에 표시됩니다</h3>
                <p>좌측에 딜 정보를 입력하고 <b>ProForma 계산</b>(무료) 또는 <b>AI 분석 단계</b>를 실행하세요.</p>
                <div className="empty-preview" aria-hidden="true">
                  <div className="empty-prev-metrics">
                    {['IRR', 'EQUITY MULTIPLE', 'DSCR'].map((k) => (
                      <div className="epm" key={k}><span className="epm-k">{k}</span><span className="epm-bar" /></div>
                    ))}
                  </div>
                  <div className="empty-prev-chart">
                    {[40, 58, 52, 70, 84].map((h, i) => <i key={i} style={{ height: `${h}%` }} />)}
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      <HistoryPanel version={historyVersion} onOpen={(r, runId) => setResults({ ...r, runId })} />

      {busy !== 'none' && (
        <div className="analyze-overlay" role="alertdialog" aria-busy="true" aria-live="assertive" aria-label="분석 진행 중">
          <div className="analyze-modal">
            <div className="analyze-spinner" aria-hidden="true" />
            <strong className="analyze-modal-title">
              {busy === 'proforma' ? 'ProForma 계산 중…' : `${STAGE_LABEL[busy] ?? 'AI'} 분석 중…`}
            </strong>
            <p className="analyze-modal-sub">
              {busy === 'proforma'
                ? '순수 계산이라 곧 끝납니다.'
                : 'AI가 딜을 분석하고 있습니다 · 보통 30~60초 걸립니다. 창을 닫지 마세요.'}
            </p>
          </div>
        </div>
      )}
    </>
  )
}

function HistoryPanel({ version, onOpen }: { version: number; onOpen: (r: RunResult, runId: number) => void }) {
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [compareOpen, setCompareOpen] = useState(false)

  useEffect(() => {
    let active = true
    api.runs()
      .then((list) => { if (active) setRuns(list) })
      .catch((err: unknown) => { if (active) setError(err instanceof ApiError ? err.message : '이력 조회 실패') })
    return () => { active = false }
  }, [version])

  async function open(id: number) {
    try {
      const detail = await api.run(id)
      if (detail.result) onOpen(detail.result, detail.id)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '이력 상세 조회 실패')
    }
  }

  return (
    <div className="card">
      <div className="hist-head">
        <div className="section-title" style={{ margin: 0 }}>분석 이력</div>
        <button className="btn-ghost btn-xs" disabled={runs.length < 2} onClick={() => setCompareOpen(true)}>
          딜 비교
        </button>
      </div>
      {error && <p className="error">{error}</p>}
      {compareOpen && <DealCompare onClose={() => setCompareOpen(false)} />}
      {runs.length === 0 ? (
        <p className="hist-empty">아직 AI 분석 이력이 없습니다. 분석을 실행하면 여기에 저장됩니다.</p>
      ) : (
        <table>
          <thead><tr><th>딜</th><th>유형</th><th>상태</th><th>일시</th><th></th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td>{r.dealName ?? '(이름없음)'}</td>
                <td>{STAGE_LABEL[r.tool] ?? r.tool}</td>
                <td>{r.status}</td>
                <td className="num">{r.createdAt ? new Date(r.createdAt).toLocaleString('ko-KR') : '-'}</td>
                <td><button className="btn-link" onClick={() => open(r.id)}>열기</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

interface NumFieldProps {
  id: string; label: string; value: string; onChange: (v: string) => void
  required?: boolean; placeholder?: string
}
function NumField({ id, label, value, onChange, required, placeholder }: NumFieldProps) {
  return (
    <div>
      <label htmlFor={id}>
        {label}{required ? <span className="req"> *</span> : <span className="opt"> (선택)</span>}
      </label>
      <input id={id} type="number" step="any" value={value} placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}

function minDscr(p: ProForma): number | null {
  const vals = p.proForma.map((r) => r.dscr).filter((d) => Number.isFinite(d) && d > 0)
  return vals.length ? Math.min(...vals) : null
}

/** 무차입 딜은 이자=0 → DSCR 무한대. 표에는 "-" 로 표기한다. */
function fmtDscr(d: number): string {
  return Number.isFinite(d) ? `${d}` : '-'
}

function ResultPanel({ results, onReport, reportBusy }: { results: Results; onReport: () => void; reportBusy: boolean }) {
  const p = results.proForma
  const md = minDscr(p)
  const stageLabel = results.analysisType ? (STAGE_LABEL[results.analysisType] ?? results.analysisType) : null
  return (
    <div className="ai-block">
      {(stageLabel || results.runId) && (
        <div className="result-head">
          {stageLabel && <span className="stage-pill">{stageLabel}</span>}
          {results.runId && (
            <button type="button" className="btn-ghost btn-report" onClick={onReport} disabled={reportBusy}>
              {reportBusy ? '보고서 여는 중…' : '투자 보고서 보기'}
            </button>
          )}
        </div>
      )}
      {results.analysis && <Verdict analysis={results.analysis} />}

      <section>
        <div className="metrics">
          <div className="metric hero">
            <span className="k">Levered IRR</span>
            <span className="v">{p.leveredIrrPct}%</span>
          </div>
          <Metric k="Equity Multiple" v={`${p.equityMultiple}x`} />
          <Metric k="Going-in Cap" v={`${p.goingInCapPct}%`} />
          <Metric k="Yield on Cost" v={`${p.yieldOnCostPct}%`} />
          <Metric k="최소 DSCR" v={md != null ? `${md.toFixed(2)}x` : '-'} />
          <Metric k="Equity" v={`${p.equityEok}억`} />
        </div>
      </section>

      {results.guidelineChecks && <GuidelineFit summary={results.guidelineChecks} />}

      <section className="chart-card">
        <div className="section-title">연차별 현금흐름 · DSCR</div>
        <div className="chart-legend">
          <span><i className="swatch-cf" />Levered Cash Flow (억원)</span>
          <span><i className="swatch-dscr" />DSCR</span>
        </div>
        <CashflowChart rows={p.proForma} />
      </section>

      <section>
        <div className="section-title">연차별 운영</div>
        <table>
          <thead><tr><th>연차</th><th>NOI</th><th>이자</th><th>Levered CF</th><th>DSCR</th><th>CoC%</th></tr></thead>
          <tbody>
            {p.proForma.map((r) => (
              <tr key={r.year}>
                <td>Y{r.year}</td>
                <td>{r.noi}</td>
                <td>{r.interest}</td>
                <td>{r.leveredCf}</td>
                <td className={Number.isFinite(r.dscr) && r.dscr < 1.2 ? 'warn' : undefined}>{fmtDscr(r.dscr)}</td>
                <td>{r.cocPct}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section>
        <div className="section-title">시나리오</div>
        <table>
          <thead><tr><th>케이스</th><th>임대성장</th><th>Exit Cap</th><th>IRR</th><th>EM</th><th>최소 DSCR</th></tr></thead>
          <tbody>
            {results.scenarios.map((s) => (
              <tr key={s.name}>
                <td>{s.name}</td>
                <td>{s.rentGrowthPct}%</td>
                <td>{s.exitCapPct}%</td>
                <td>{s.leveredIrrPct}%</td>
                <td>{s.equityMultiple}x</td>
                <td className={Number.isFinite(s.minDscr) && s.minDscr < 1.2 ? 'warn' : undefined}>{fmtDscr(s.minDscr)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {results.analysis ? (
        <StageAnalysis type={results.analysisType} analysis={results.analysis} provider={results.provider} />
      ) : results.analysisRaw ? (
        <section>
          <div className="section-title">AI 분석</div>
          <p className="narrative">{results.analysisRaw}</p>
        </section>
      ) : null}

      <p className="disclaimer">{results.disclaimer}</p>
    </div>
  )
}

/** 단계별 AI 결과 인라인 렌더. 상세 합본은 '투자 보고서 보기' 참조. */
function StageAnalysis({ type, analysis, provider }: { type?: string; analysis: Analysis; provider?: string }) {
  const a = analysis as unknown as Record<string, unknown>
  const str = (k: string): string | null => (typeof a[k] === 'string' && a[k] ? (a[k] as string) : null)
  const list = (k: string): unknown[] => (Array.isArray(a[k]) ? (a[k] as unknown[]) : [])

  if (type === 'MARKET_STUDY') {
    return (
      <section className="ai-block">
        <div className="section-title">시장조사</div>
        {(str('region') || str('house_view')) && (
          <p><b>{str('region') ?? '권역'}</b> · House View {str('house_view') ?? '-'}</p>
        )}
        {str('fundamentals') && <p className="narrative">{str('fundamentals')}</p>}
        {str('conclusion') && <p className="guideline">{str('conclusion')}</p>}
      </section>
    )
  }

  if (type === 'SCREENING') {
    const green = list('green_flags')
    const red = list('red_flags')
    return (
      <section className="ai-block">
        <div className="section-title">1차 스크리닝</div>
        {str('verdict') && (
          <Verdict analysis={{ recommendation: str('verdict') ?? undefined, recommendation_reason: str('verdict_reason') ?? undefined }} />
        )}
        {str('thesis') && <p className="narrative">{str('thesis')}</p>}
        {green.length > 0 && (
          <div>
            <div className="section-title">Green Flags</div>
            <div className="chips">{green.map((g, i) => <span key={i} className="chip">{String(g)}</span>)}</div>
          </div>
        )}
        {red.length > 0 && (
          <div>
            <div className="section-title">Red Flags</div>
            {red.map((r, i) => {
              const o = r as Record<string, unknown>
              return <div key={i} className="risk"><span className="r-name">{String(o.flag ?? '')}</span><span className="r-impact">{String(o.impact ?? '')}</span></div>
            })}
          </div>
        )}
      </section>
    )
  }

  if (type === 'IC_MEMO') {
    const highlights = list('highlights')
    return (
      <section className="ai-block">
        <div className="section-title">투심 메모</div>
        {str('thesis') && <p className="narrative">{str('thesis')}</p>}
        {highlights.length > 0 && (
          <div>
            <div className="section-title">투자 하이라이트</div>
            <ul>{highlights.map((h, i) => <li key={i}>{String(h)}</li>)}</ul>
          </div>
        )}
        {str('lp_alignment') && <p className="guideline">{str('lp_alignment')}</p>}
      </section>
    )
  }

  // UNDERWRITING (기본/레거시)
  return <AiNarrative analysis={analysis} provider={provider} />
}

function Metric({ k, v }: { k: string; v: string }) {
  return <div className="metric"><span className="k">{k}</span><span className="v">{v}</span></div>
}

/** 가이드라인 적합성 — 코드가 임계값과 대조한 결정론적 판정(PASS/WARN/FAIL). AI 판단 아님. */
function GuidelineFit({ summary }: { summary: GuidelineSummary }) {
  const tone: Record<string, string> = { PASS: 'go', WARN: 'cond', FAIL: 'no' }
  return (
    <section className="ai-block guideline-fit">
      <div className="section-title">
        가이드라인 적합성 <span className="gl-sub">코드 판정 · 임계값 대조</span>
      </div>
      <div className="gl-summary">
        <span className="gl-tag go">PASS {summary.pass}</span>
        <span className="gl-tag cond">WARN {summary.warn}</span>
        <span className="gl-tag no">FAIL {summary.fail}</span>
      </div>
      <table className="gl-table">
        <tbody>
          {summary.checks.map((c) => (
            <tr key={c.metric}>
              <td className="gl-metric">{c.metric}</td>
              <td className="num">{c.actual}</td>
              <td className="gl-th">{c.threshold}</td>
              <td><span className={`gl-badge ${tone[c.status] ?? 'cond'}`}>{c.status}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

interface VerdictStyle { cls: 'go' | 'cond' | 'no'; mark: string; label: string }
function verdictStyle(rec?: string): VerdictStyle {
  if (rec === 'GO') return { cls: 'go', mark: 'GO', label: '투자 적격' }
  if (rec === 'NO_GO') return { cls: 'no', mark: 'NO', label: '투자 부적격' }
  return { cls: 'cond', mark: '!', label: '조건부 검토' }
}

function Verdict({ analysis }: { analysis: Analysis }) {
  if (!analysis.recommendation) return null
  const s = verdictStyle(analysis.recommendation)
  return (
    <div className={`verdict ${s.cls}`}>
      <div className="v-mark">{s.mark}</div>
      <div>
        <div className="v-label">{s.label}</div>
        {analysis.recommendation_reason && <div className="v-reason">{analysis.recommendation_reason}</div>}
      </div>
    </div>
  )
}

function AiNarrative({ analysis, provider }: { analysis: Analysis; provider?: string }) {
  return (
    <section className="ai-block">
      <div className="section-title">AI 언더라이팅 {provider ? `· ${provider}` : ''}</div>
      {analysis.summary && <p className="narrative">{analysis.summary}</p>}
      {analysis.guideline_check && <p className="guideline">{analysis.guideline_check}</p>}
      {analysis.key_drivers && analysis.key_drivers.length > 0 && (
        <div>
          <div className="section-title">주요 동인</div>
          <div className="chips">{analysis.key_drivers.map((d, i) => <span key={i} className="chip">{d}</span>)}</div>
        </div>
      )}
      {analysis.key_risks && analysis.key_risks.length > 0 && (
        <div>
          <div className="section-title">리스크</div>
          {analysis.key_risks.map((r, i) => (
            <div key={i} className="risk"><span className="r-name">{r.risk}</span><span className="r-impact">{r.impact}</span></div>
          ))}
        </div>
      )}
    </section>
  )
}
