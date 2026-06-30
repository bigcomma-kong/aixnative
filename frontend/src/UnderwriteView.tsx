import { Fragment, useEffect, useState } from 'react'
import {
  api, ApiError,
  type AnalysisType, type AnalyzeResponse, type Analysis, type DealStage, type DuplicateCheck, type GuidelineSummary, type ProForma, type ProFormaResponse,
  type RunResult, type RunSummary, type Scenario, type UnderwriteInput,
} from './api'
import { CashflowChart } from './Chart'
import { DealCompare } from './DealCompare'
import { StageAnalysis, Verdict } from './StageAnalysis'

interface UnderwriteViewProps {
  onCreditBalance: (balance: number) => void
  /** 크레딧 소진(402) 시 중앙 페이월 안내 노출. */
  onNeedCredits: () => void
  /** 분석유형 id → 크레딧 단가(서버 단일 소스). 미로딩 시 숫자 생략. */
  toolCosts?: Record<string, number>
}

/** "N크레딧" 라벨 — 단가 미로딩 시 숫자 생략("크레딧"). */
function creditLabel(cost?: number): string {
  return cost != null ? `${cost}크레딧` : '크레딧'
}

/** 중복 분석 재실행 확인 — 동일 입력으로 최근 같은 단계를 했을 때 과금 전 사용자 확인. */
function confirmRerun(type: AnalysisType, dup: DuplicateCheck): boolean {
  const when = dup.lastRunAt
    ? new Date(dup.lastRunAt).toLocaleString('ko-KR', { hour: '2-digit', minute: '2-digit' })
    : `최근 ${dup.withinMinutes}분 내`
  const label = STAGE_LABEL[type] ?? type
  return window.confirm(
    `${when}에 동일 입력으로 '${label}' 분석을 이미 실행했습니다.\n` +
    '데이터 변경이 없다면 결과가 거의 같고 크레딧만 추가로 차감됩니다.\n\n그래도 다시 분석할까요?',
  )
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

/** IM 분석 파이프라인 단계 (분석별 차등 크레딧 — 단가는 서버 ToolPricing). */
const STAGES: { type: AnalysisType; label: string; hint: string }[] = [
  { type: 'SCREENING', label: '스크리닝', hint: '지표·Flag·Go/No-Go' },
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
  /** 이 결과를 만든 입력값(폼 또는 저장된 이력) — 결과와 함께 표시해 "무엇을 입력했는지" 보이게. */
  inputs?: UnderwriteInput | null
}

/** 입력 요약 카드용 — (키, 라벨, 접미사). 값이 빈 항목은 표시하지 않음. */
const INPUT_FIELDS: { key: keyof UnderwriteInput; label: string; suffix?: string }[] = [
  { key: 'assetType', label: '자산유형' },
  { key: 'location', label: '위치' },
  { key: 'askingPriceEok', label: '매입가', suffix: '억' },
  { key: 'noiEok', label: 'NOI', suffix: '억' },
  { key: 'ltvPct', label: 'LTV', suffix: '%' },
  { key: 'loanRatePct', label: '대출금리', suffix: '%' },
  { key: 'exitCapPct', label: 'Exit Cap', suffix: '%' },
  { key: 'holdYears', label: '보유', suffix: '년' },
  { key: 'rentGrowthPct', label: '임대성장', suffix: '%' },
]

/** 저장된 단계(DealStage) → 결과 패널 표시용 Results. 탭 전환 시 사용. */
function resultsFromStage(s: DealStage): Results {
  const r = s.result
  return {
    runId: s.runId,
    analysisType: s.analysisType,
    proForma: r?.proForma as ProForma,
    scenarios: r?.scenarios ?? [],
    guidelineChecks: r?.guidelineChecks,
    disclaimer: r?.disclaimer ?? '',
    analysis: r?.analysis ?? null,
    analysisRaw: r?.analysisRaw ?? null,
    provider: r?.provider,
    inputs: s.request,
  }
}

export function UnderwriteView({ onCreditBalance, onNeedCredits, toolCosts }: UnderwriteViewProps) {
  const [form, setForm] = useState<FormState>(INITIAL)
  const [results, setResults] = useState<Results | null>(null)
  // 현재 딜의 완료된 단계 모음(합본 탭) — 같은 딜명으로 스크리닝·시장조사 등을 따로 했어도 한 화면에서 전환.
  const [stageMap, setStageMap] = useState<Partial<Record<AnalysisType, DealStage>>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'none' | 'proforma' | AnalysisType>('none')
  const [reportBusy, setReportBusy] = useState(false)
  const [historyVersion, setHistoryVersion] = useState(0)

  /** 딜명으로 완료된 단계들을 모아 탭에 채운다(무과금). 실패해도 단일 결과 표시엔 지장 없음. */
  async function loadDealStages(dealName?: string | null) {
    if (!dealName) { setStageMap({}); return }
    try {
      const ds = await api.dealStages(dealName)
      const map: Partial<Record<AnalysisType, DealStage>> = {}
      for (const s of ds.stages) map[s.analysisType] = s
      setStageMap(map)
    } catch { /* 합본 탭은 보조 — 무시 */ }
  }

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
      const input = buildInput()
      const res: ProFormaResponse = await api.proforma(input)
      setResults({ proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer, inputs: input })
      void loadDealStages(input.dealName)
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
    const input = buildInput()
    // 중복 분석 가드 — 동일 입력으로 최근 같은 단계를 이미 했으면 과금 전 확인(가드 실패는 분석을 막지 않음).
    try {
      const dup = await api.checkDuplicate(type, input)
      if (dup.duplicate && !confirmRerun(type, dup)) return
    } catch { /* 가드는 보조 안내일 뿐 — 실패해도 분석 진행 */ }
    setBusy(type)
    try {
      const res: AnalyzeResponse = await api.analyzeStage(type, input)
      setResults({
        runId: res.runId, analysisType: res.analysisType,
        proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer,
        analysis: res.analysis, analysisRaw: res.analysisRaw, provider: res.provider, inputs: input,
      })
      onCreditBalance(res.creditBalance)
      setHistoryVersion((v) => v + 1)
      void loadDealStages(input.dealName)
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
            <button type="button" className="btn-link" onClick={() => { setForm(INITIAL); setResults(null); setError(null); setStageMap({}) }}>
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
            <p className="stage-caption">권장 순서 · <b>각 단계는 독립 실행</b> 가능 — 1번부터 안 해도, 원하는 단계만 바로 실행하세요.</p>
            <div className="stage-grid" role="list" aria-label="분석 파이프라인 — 권장 순서, 각 단계 독립 실행 가능">
              {STAGES.map((s, i) => (
                <Fragment key={s.type}>
                  {i > 0 && <span className="stage-arrow" aria-hidden="true">↓</span>}
                  <button type="button" className="stage-btn" role="listitem"
                    onClick={() => runStage(s.type)} disabled={busy !== 'none'}>
                    <span className="stage-num" aria-hidden="true">{i + 1}</span>
                    <span className="stage-text">
                      <span className="stage-label">{busy === s.type ? '분석 중…' : s.label}</span>
                      <span className="stage-hint">{s.hint} · {creditLabel(toolCosts?.[s.type])}</span>
                    </span>
                  </button>
                </Fragment>
              ))}
            </div>
            <button type="submit" className="btn-ghost" disabled={busy !== 'none'}>
              {busy === 'proforma' ? '계산 중…' : 'ProForma만 계산 (무료)'}
            </button>
            <p className="hint">단계마다 AI 1회 호출 = 분석별 1~5크레딧. 같은 딜 이름으로 단계를 쌓으면 보고서에 합본됩니다.</p>
          </div>
          {error && <p className="error">{error}</p>}
        </form>

        <div className="card">
          {results ? (
            <ResultPanel
              results={results}
              stageMap={stageMap}
              onSelectStage={(type) => { const s = stageMap[type]; if (s) setResults(resultsFromStage(s)) }}
              onReport={openReport}
              reportBusy={reportBusy}
            />
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

      <HistoryPanel version={historyVersion} onOpen={(r, runId, request) => { setResults({ ...r, runId, inputs: request }); void loadDealStages(request?.dealName) }} />

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

function HistoryPanel({ version, onOpen }: { version: number; onOpen: (r: RunResult, runId: number, request: UnderwriteInput | null) => void }) {
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
      if (detail.result) onOpen(detail.result, detail.id, detail.request)
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

function ResultPanel({ results, stageMap, onSelectStage, onReport, reportBusy }: {
  results: Results
  stageMap: Partial<Record<AnalysisType, DealStage>>
  onSelectStage: (type: AnalysisType) => void
  onReport: () => void
  reportBusy: boolean
}) {
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
      <StageTabs stageMap={stageMap} active={results.analysisType} onSelect={onSelectStage} />
      {results.inputs && <InputSummary inputs={results.inputs} />}
      {results.analysis && <Verdict analysis={results.analysis} />}

      <section>
        <div className="metric-heroes">
          <HeroMetric label="레버리지 IRR" value={`${p.leveredIrrPct}%`} status={irrStatus(p.leveredIrrPct)} />
          <HeroMetric label="투자 배수" value={`${p.equityMultiple}x`} status={emStatus(p.equityMultiple)} />
        </div>
        <div className="metric-mini-row">
          <MiniMetric label="매입 수익률" value={`${p.goingInCapPct}%`} />
          <MiniMetric label="원가 수익률" value={`${p.yieldOnCostPct}%`} />
          <MiniMetric label="최소 DSCR" value={md != null ? `${md.toFixed(2)}x` : '–'} status={md != null ? dscrStatus(md) : undefined} />
          <MiniMetric label="투입 자기자본" value={`${p.equityEok}억`} />
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

/**
 * 단계 탭 — 한 딜의 완료된 단계(스크리닝·시장조사·언더라이팅·투심)를 한 화면에서 전환.
 * 완료된 단계만 클릭 가능(✓), 미실행 단계는 비활성('미실행' — 좌측 버튼으로 실행). 완료가 0개면 숨김.
 */
function StageTabs({ stageMap, active, onSelect }: {
  stageMap: Partial<Record<AnalysisType, DealStage>>
  active?: string
  onSelect: (type: AnalysisType) => void
}) {
  const doneCount = STAGES.filter((s) => stageMap[s.type]).length
  if (doneCount === 0) return null
  return (
    <div className="stage-tabs" role="tablist" aria-label="분석 단계">
      {STAGES.map((s) => {
        const done = !!stageMap[s.type]
        const isActive = active === s.type
        return (
          <button
            key={s.type} type="button" role="tab" aria-selected={isActive}
            className={`stage-tab${isActive ? ' active' : ''}${done ? '' : ' undone'}`}
            disabled={!done} onClick={() => done && onSelect(s.type)}
            title={done ? s.label : `${s.label} — 미실행 (좌측에서 실행)`}
          >
            <span className="st-label">{s.label}</span>
            <span className="st-mark">{done ? '✓' : '미실행'}</span>
          </button>
        )
      })}
    </div>
  )
}

/** 입력 요약 — 이 결과를 만든 딜 입력값을 결과 상단에 표시(이력에서 다시 열 때 "무엇을 입력했는지" 확인). */
function InputSummary({ inputs }: { inputs: UnderwriteInput }) {
  const rows = INPUT_FIELDS
    .map((f) => ({ label: f.label, value: inputs[f.key], suffix: f.suffix }))
    .filter((r) => r.value != null && r.value !== '' && !(typeof r.value === 'number' && !Number.isFinite(r.value)))
  if (rows.length === 0) return null
  return (
    <section className="input-recap">
      <div className="section-title">입력 요약{inputs.dealName ? ` · ${inputs.dealName}` : ''}</div>
      <div className="recap-grid">
        {rows.map((r) => (
          <div key={r.label} className="recap-cell">
            <span className="rc-k">{r.label}</span>
            <span className="rc-v num">{String(r.value)}{r.suffix ?? ''}</span>
          </div>
        ))}
      </div>
      {inputs.notes && <p className="recap-notes">메모 · {inputs.notes}</p>}
    </section>
  )
}

/** 지표 양호도 — 색·배지 판정(보수적 CRE 임계값). */
type MStatus = 'good' | 'warn' | 'neutral'
const irrStatus = (v: number): MStatus => (v >= 12 ? 'good' : v < 8 ? 'warn' : 'neutral')
const emStatus = (v: number): MStatus => (v >= 1.8 ? 'good' : v < 1.3 ? 'warn' : 'neutral')
const dscrStatus = (v: number): MStatus => (v >= 1.25 ? 'good' : v < 1.0 ? 'warn' : 'neutral')

/** 핵심 지표 — 큰 숫자 + 양호/주의 색·배지. */
function HeroMetric({ label, value, status }: { label: string; value: string; status: MStatus }) {
  const badge = status === 'good' ? '양호' : status === 'warn' ? '주의' : null
  return (
    <div className={`hero-metric ${status}`}>
      <span className="hm-label">{label}</span>
      <div className="hm-row">
        <span className="hm-value">{value}</span>
        {badge && <span className={`hm-badge ${status}`}>{status === 'good' ? '▲' : '▼'} {badge}</span>}
      </div>
    </div>
  )
}

/** 보조 지표 — 컴팩트 라벨 + 값(필요 시 색). */
function MiniMetric({ label, value, status }: { label: string; value: string; status?: MStatus }) {
  return (
    <div className="mini-metric">
      <span className="mm-label">{label}</span>
      <span className={`mm-value${status && status !== 'neutral' ? ` ${status}` : ''}`}>{value}</span>
    </div>
  )
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
