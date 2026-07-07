import { useEffect, useState } from 'react'
import {
  api, ApiError, track,
  type AnalysisType, type AnalyzeResponse, type Analysis, type DealStage, type DuplicateCheck, type GuidelineSummary, type MarketFact, type ProForma, type ProFormaResponse,
  type RunResult, type RunSummary, type Scenario, type UnderwriteInput,
} from './api'
import { CashflowChart } from './Chart'
import { DealCompare } from './DealCompare'
import { StageAnalysis, Verdict } from './StageAnalysis'
import { canonicalTool } from './ResultModal'
import { downloadUnderwritingXls } from './reportExport'

interface UnderwriteViewProps {
  onCreditBalance: (balance: number) => void
  /** 크레딧 소진(402) 시 중앙 페이월 안내 노출. */
  onNeedCredits: () => void
  /** 분석유형 id → 크레딧 단가(서버 단일 소스). 미로딩 시 숫자 생략. */
  toolCosts?: Record<string, number>
  /** 현재 크레딧 잔액 - AI 단계 실행 전 사전 확인용(모자라면 시작조차 안 하고 안내). */
  creditBalance?: number
  /** 내 딜 대시보드 '이어서 분석' - 이 딜 id(PK)를 자동 로드(폼 프리필 + 무료 ProForma + 단계 탭). */
  openDealId?: number
  /** 로드 완료 후 부모가 신호를 비우도록. */
  onDealOpened?: () => void
}

/** "N크레딧" 라벨 - 단가 미로딩 시 숫자 생략("크레딧"). */
/** 중복 분석 재실행 확인 - 동일 입력으로 최근 같은 단계를 했을 때 과금 전 사용자 확인. */
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

/** IM 분석 파이프라인 단계 (분석별 차등 크레딧 - 단가는 서버 ToolPricing). */
const STAGES: { type: AnalysisType; label: string; hint: string }[] = [
  { type: 'SCREENING', label: '스크리닝', hint: '핵심 지표·리스크 플래그로 초기 Go/No-Go 판단' },
  { type: 'MARKET_STUDY', label: '시장조사', hint: '권역·수요·입지 가정의 시장 타당성 검증' },
  { type: 'UNDERWRITING', label: '언더라이팅', hint: 'IRR·DSCR 등 수익성 결론과 투자 논리 정리' },
  { type: 'IC_MEMO', label: '투심 메모', hint: '앞 단계를 종합한 투자심의(IC) 상정 문서' },
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

/** 신규 사용자 활성화용 예시 딜 - 누르면 폼을 채우고 무료 ProForma 를 즉시 실행(크레딧·AI 비용 0). */
interface Sample { key: string; label: string; emoji: string; form: Partial<FormState> }
const SAMPLES: Sample[] = [
  { key: 'office', label: '강남 오피스', emoji: '🏢', form: {
    dealName: '예시 · 강남 GBD 오피스', assetType: '오피스', location: '서울 강남구 GBD',
    askingPriceEok: '1800', noiEok: '81', ltvPct: '55', loanRatePct: '4.3', exitCapPct: '4.75', holdYears: '5', rentGrowthPct: '3' } },
  { key: 'logistics', label: '수도권 물류센터', emoji: '🚚', form: {
    dealName: '예시 · 이천 물류센터', assetType: '물류', location: '경기 이천',
    askingPriceEok: '900', noiEok: '50', ltvPct: '60', loanRatePct: '4.6', exitCapPct: '5.75', holdYears: '5', rentGrowthPct: '2.5' } },
  { key: 'hotel', label: '서울 호텔', emoji: '🏨', form: {
    dealName: '예시 · 명동 비즈니스 호텔', assetType: '호텔', location: '서울 중구 명동',
    askingPriceEok: '1200', noiEok: '78', ltvPct: '60', loanRatePct: '4.8', exitCapPct: '6.75', holdYears: '5', rentGrowthPct: '3' } },
]

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
  /** 분석에 주입된 실측 시장데이터(출처·기준일) - 스크리닝·시장조사. */
  marketFacts?: MarketFact[] | null
  /** 이 결과를 만든 입력값(폼 또는 저장된 이력) - 결과와 함께 표시해 "무엇을 입력했는지" 보이게. */
  inputs?: UnderwriteInput | null
}

/** 입력 요약 카드용 - (키, 라벨, 접미사). 값이 빈 항목은 표시하지 않음. */
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

/** 저장된 언더라이팅 입력 → 폼 상태 복원. loadDeal(이어서 분석)·이력 불러오기 공용. */
function formFromInput(req: UnderwriteInput, fallbackDealName: string): FormState {
  return {
    dealName: req.dealName ?? fallbackDealName,
    assetType: req.assetType ?? '오피스',
    location: req.location ?? '',
    notes: req.notes ?? '',
    askingPriceEok: String(req.askingPriceEok ?? ''),
    noiEok: String(req.noiEok ?? ''),
    ltvPct: String(req.ltvPct ?? ''),
    loanRatePct: String(req.loanRatePct ?? ''),
    exitCapPct: String(req.exitCapPct ?? ''),
    holdYears: String(req.holdYears ?? '5'),
    rentGrowthPct: String(req.rentGrowthPct ?? '3'),
  }
}

/** FormState → 계산 입력. buildInput/loadSample 공용(폼 상태 비동기 문제 회피). */
function inputOf(f: FormState): UnderwriteInput {
  return {
    dealName: f.dealName || undefined,
    assetType: f.assetType || undefined,
    location: f.location || undefined,
    notes: f.notes || undefined,
    askingPriceEok: Number(f.askingPriceEok),
    noiEok: Number(f.noiEok),
    ltvPct: Number(f.ltvPct),
    loanRatePct: Number(f.loanRatePct),
    exitCapPct: Number(f.exitCapPct),
    holdYears: Number(f.holdYears || '5'),
    rentGrowthPct: Number(f.rentGrowthPct || '3'),
  }
}

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
    marketFacts: r?.marketFacts ?? null,
    inputs: s.request,
  }
}

export function UnderwriteView({ onCreditBalance, onNeedCredits, toolCosts, creditBalance, openDealId, onDealOpened }: UnderwriteViewProps) {
  const [form, setForm] = useState<FormState>(INITIAL)
  // 현재 편집 중인 딜의 식별자(PK). null=아직 저장 안 된 새 딜. 첫 분석 후 응답 dealId 로 채워져 이후 분석이 같은 딜로 묶인다.
  const [currentDealId, setCurrentDealId] = useState<number | null>(null)
  const [results, setResults] = useState<Results | null>(null)
  // 현재 딜의 완료된 단계 모음(합본 탭) - 같은 딜명으로 스크리닝·시장조사 등을 따로 했어도 한 화면에서 전환.
  const [stageMap, setStageMap] = useState<Partial<Record<AnalysisType, DealStage>>>({})
  // 활성 탭 - 'FINANCE'(공통 재무 모델) 또는 분석 단계. 재무 데이터와 단계별 AI 분석을 분리해 한 번에 하나만 표시.
  const [activeTab, setActiveTab] = useState<'FINANCE' | AnalysisType>('FINANCE')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'none' | 'proforma' | AnalysisType>('none')
  const [reportBusy, setReportBusy] = useState(false)
  const [historyVersion, setHistoryVersion] = useState(0)
  // 예시 딜로 결과를 본 상태인지(배너 표시용). 초기화·실제 입력 시 해제.
  const [isSample, setIsSample] = useState(false)

  /** 예시 딜 불러오기 - 폼 채우고 무료 ProForma 즉시 실행(크레딧 0). 신규 활성화용. */
  async function loadSample(s: Sample) {
    const f: FormState = { ...INITIAL, ...s.form }
    setForm(f)
    setCurrentDealId(null) // 예시는 저장 안 된 새 딜
    setIsSample(true)
    setError(null)
    setStageMap({})
    setActiveTab('FINANCE')
    const input: UnderwriteInput = {
      dealName: f.dealName, assetType: f.assetType, location: f.location || undefined,
      askingPriceEok: Number(f.askingPriceEok), noiEok: Number(f.noiEok),
      ltvPct: Number(f.ltvPct), loanRatePct: Number(f.loanRatePct), exitCapPct: Number(f.exitCapPct),
      holdYears: Number(f.holdYears || '5'), rentGrowthPct: Number(f.rentGrowthPct || '3'),
    }
    setBusy('proforma')
    try {
      const res = await api.proforma(input)
      setResults({ proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer, inputs: input })
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '계산 중 오류가 발생했습니다.')
    } finally {
      setBusy('none')
    }
  }

  /** '이어서 분석' - 딜 id(PK)의 저장된 단계에서 입력을 복원해 폼 프리필 + 무료 ProForma + 단계 탭 로드(무과금). */
  async function loadDeal(dealId: number) {
    setError(null); setIsSample(false); setActiveTab('FINANCE')
    setCurrentDealId(dealId)
    setBusy('proforma')
    try {
      const ds = await api.dealStages(dealId)
      const map: Partial<Record<AnalysisType, DealStage>> = {}
      for (const s of ds.stages) map[s.analysisType] = s
      setStageMap(map)
      const req = ds.stages.find((s) => s.request && s.request.askingPriceEok != null)?.request
      if (req) {
        const f = formFromInput(req, ds.dealName ?? '')
        setForm(f)
        const input: UnderwriteInput = {
          dealName: f.dealName, assetType: f.assetType, location: f.location || undefined,
          askingPriceEok: Number(f.askingPriceEok), noiEok: Number(f.noiEok),
          ltvPct: Number(f.ltvPct), loanRatePct: Number(f.loanRatePct), exitCapPct: Number(f.exitCapPct),
          holdYears: Number(f.holdYears || '5'), rentGrowthPct: Number(f.rentGrowthPct || '3'),
        }
        const res = await api.proforma(input)
        setResults({ proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer, inputs: input })
      } else if (ds.dealName) {
        setForm((cur) => ({ ...cur, dealName: ds.dealName ?? cur.dealName }))
      }
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '딜을 불러오지 못했습니다.')
    } finally {
      setBusy('none')
    }
  }

  // 내 딜 대시보드 '이어서 분석' 진입 - openDealId 신호가 오면 그 딜을 로드하고 신호를 비운다.
  useEffect(() => {
    if (openDealId == null) return
    void loadDeal(openDealId)
    onDealOpened?.()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openDealId])

  /** 딜 id(PK)로 완료된 단계들을 모아 탭에 채운다(무과금). 실패해도 단일 결과 표시엔 지장 없음. */
  async function loadDealStages(dealId?: number | null) {
    if (dealId == null) { setStageMap({}); return }
    try {
      const ds = await api.dealStages(dealId)
      const map: Partial<Record<AnalysisType, DealStage>> = {}
      for (const s of ds.stages) map[s.analysisType] = s
      setStageMap(map)
    } catch { /* 합본 탭은 보조 - 무시 */ }
  }

  function set<K extends keyof FormState>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }))
    if (isSample) setIsSample(false) // 사용자가 직접 수정하면 더 이상 '예시'가 아님
  }

  function buildInput(): UnderwriteInput {
    // currentDealId 가 있으면 그 딜에 이어붙이고, 없으면 서버가 새 딜(self-anchor)로 만든다.
    return { ...inputOf(form), dealId: currentDealId ?? undefined }
  }

  /**
   * '새 딜로 시작' - 현재 입력값은 그대로 두고 딜 연결만 끊는다(currentDealId=null).
   * 다음 분석이 새 dealId 로 갈라져, 이름 변경만으론 안 되던 '다른 딜로 분리'를 명시적으로 수행.
   */
  function startNewDeal() {
    setCurrentDealId(null)
    setStageMap({})
    setActiveTab('FINANCE')
    setError(null)
  }

  /**
   * 필수 입력 검증. requireName=true(AI 분석)면 딜 이름도 필수.
   * 통과하지 못하면 안내 문자열 반환 → 호출부가 실행을 막아 빈 값/크레딧 낭비를 방지.
   */
  function validate(requireName: boolean): { id: string; msg: string } | null {
    if (requireName && !form.dealName.trim()) return { id: 'dealName', msg: '딜 이름을 입력하세요. (분석 이력 구분에 필요합니다)' }
    if (!(Number(form.askingPriceEok) > 0)) return { id: 'askingPriceEok', msg: '매입가(억원)를 입력하세요.' }
    if (!(Number(form.noiEok) > 0)) return { id: 'noiEok', msg: 'NOI(억원)를 입력하세요.' }
    const ltv = Number(form.ltvPct)
    if (form.ltvPct.trim() === '' || ltv < 0 || ltv > 100) return { id: 'ltvPct', msg: 'LTV(%)는 0~100 사이로 입력하세요.' }
    if (!(Number(form.loanRatePct) >= 0) || form.loanRatePct.trim() === '') return { id: 'loanRatePct', msg: '대출금리(%)를 입력하세요.' }
    if (!(Number(form.exitCapPct) > 0)) return { id: 'exitCapPct', msg: 'Exit Cap(%)를 입력하세요.' }
    return null
  }

  /** 미입력 필드로 스크롤 + 포커스 - 배너 대신 직접 안내. */
  function focusField(id: string) {
    const el = document.getElementById(id)
    if (!el) return
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    el.focus({ preventScroll: true })
  }

  async function runProforma() {
    const invalid = validate(false)
    if (invalid) { setError(invalid.msg); focusField(invalid.id); return }
    track('free_calc', { path: 'underwrite' })
    setError(null)
    setBusy('proforma')
    try {
      const input = buildInput()
      const res: ProFormaResponse = await api.proforma(input)
      setResults({ proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer, inputs: input })
      setActiveTab('FINANCE')
      void loadDealStages(currentDealId)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '계산 중 오류가 발생했습니다.')
    } finally {
      setBusy('none')
    }
  }

  async function runStage(type: AnalysisType) {
    const invalid = validate(true)
    if (invalid) { setError(invalid.msg); focusField(invalid.id); return }
    // 시작 전 크레딧 사전 확인 - 모자라면 분석을 시작조차 하지 않고 안내(모달만 떴다 사라지는 문제 방지).
    const cost = toolCosts?.[type]
    if (cost != null && creditBalance != null && creditBalance < cost) {
      onNeedCredits()
      return
    }
    track('analysis_start', { path: 'underwrite', meta: type })
    setError(null)
    const input = buildInput()
    // 중복 분석 가드 - 동일 입력으로 최근 같은 단계를 이미 했으면 과금 전 확인(가드 실패는 분석을 막지 않음).
    try {
      const dup = await api.checkDuplicate(type, input)
      if (dup.duplicate && !confirmRerun(type, dup)) return
    } catch { /* 가드는 보조 안내일 뿐 - 실패해도 분석 진행 */ }
    setBusy(type)
    try {
      const res: AnalyzeResponse = await api.analyzeStage(type, input)
      // 첫 분석이면 서버가 새 딜 id 를 발급 → 이후 분석이 같은 딜로 묶이도록 저장.
      setCurrentDealId(res.dealId)
      setResults({
        runId: res.runId, analysisType: res.analysisType,
        proForma: res.proForma, scenarios: res.scenarios, guidelineChecks: res.guidelineChecks, disclaimer: res.disclaimer,
        analysis: res.analysis, analysisRaw: res.analysisRaw, provider: res.provider, marketFacts: res.marketFacts, inputs: input,
      })
      setActiveTab(type)
      track('analysis_done', { path: 'underwrite', meta: type })
      onCreditBalance(res.creditBalance)
      setHistoryVersion((v) => v + 1)
      void loadDealStages(res.dealId)
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

  /** 현재 결과가 속한 딜의 언더라이팅 단계(파이프라인)를 합본한 HTML 보고서를 새 창으로 연다. */
  async function openReport() {
    if (!results?.runId) return
    setReportBusy(true)
    try {
      const html = await api.reportHtml(results.runId, 'pipeline')
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
          <span className="eyebrow">AI DEAL UNDERWRITING</span>
          <h1>매물 한 건, 1분 심사.</h1>
          <p className="page-sub">매입가·NOI·자본구조만 넣으면 IRR·DSCR·민감도까지 자동 계산하고, AI가 스크리닝 판정과 리스크를 짚어 드립니다.</p>
        </div>
      </div>

      <div className="layout" id="uw-layout">
        <form className="card input-panel" onSubmit={(e) => { e.preventDefault(); runProforma() }}>
          <div className="form-head">
            <span className="section-title" style={{ margin: 0 }}>딜 입력 <span className="req-legend"><span className="req">*</span> 필수</span></span>
            <button type="button" className="btn-link" onClick={() => { setForm(INITIAL); setCurrentDealId(null); setResults(null); setError(null); setStageMap({}); setActiveTab('FINANCE'); setIsSample(false) }}>
              초기화
            </button>
          </div>

          {!results && (
            <div className="sample-row">
              <span className="sample-label">처음이세요? <b>예시 딜로 1분 체험</b> - 무료</span>
              <div className="sample-chips">
                {SAMPLES.map((s) => (
                  <button type="button" key={s.key} className="sample-chip" onClick={() => void loadSample(s)} disabled={busy !== 'none'}>
                    <span aria-hidden="true">{s.emoji}</span> {s.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="form-grid">
            <div className="full">
              <div className="deal-name-head">
                <label htmlFor="dealName">딜 이름 <span className="req">*</span></label>
                {currentDealId != null && (
                  <>
                    <span className="deal-id-chip" title="이 딜의 고유 번호입니다. 이름을 바꿔도 같은 딜에 누적됩니다.">딜 #{currentDealId}</span>
                    <button
                      type="button"
                      className="btn-link deal-new-btn"
                      onClick={startNewDeal}
                      disabled={busy !== 'none'}
                      title="현재 입력값은 유지하고, 다음 분석부터 별개의 새 딜로 시작합니다."
                    >
                      + 새 딜로 시작
                    </button>
                  </>
                )}
              </div>
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
              <label htmlFor="location">위치 / 권역</label>
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
              <label htmlFor="notes">IM 요약·임대 현황·특이사항</label>
              <textarea id="notes" rows={2} value={form.notes} onChange={(e) => set('notes', e.target.value)}
                placeholder="예: 핵심 임차인 2026년 만기, 최근 리모델링 완료 등" />
            </div>
          </div>

          <div className="actions">
            <button type="submit" className="btn-ghost free-calc" disabled={busy !== 'none'}>
              {busy === 'proforma' ? '계산 중…' : 'ProForma 계산 (무료)'}
              <span className="free-calc-sub">IRR·DSCR 등 지표 - 크레딧 차감 없음</span>
            </button>

            <p className="stage-caption">무료 계산으로 지표를 먼저 본 뒤 <b>AI 분석</b>을 실행하세요<br/> 같은 딜 이름이면 1~4단계 데이터가 한 보고서에 모입니다. <br/>
              ( <b>각 단계 독립 실행</b> 가능 )</p>

            <div className="stage-grid" role="list" aria-label="분석 파이프라인 - 권장 순서, 각 단계 독립 실행 가능">
              {STAGES.map((s, i) => (
                <button key={s.type} type="button" className="stage-btn" role="listitem"
                  onClick={() => runStage(s.type)} disabled={busy !== 'none'}>
                  <span className="stage-num" aria-hidden="true">{String(i + 1).padStart(2, '0')}</span>
                  <span className="stage-text">
                    <span className="stage-label">{busy === s.type ? '분석 중…' : s.label}</span>
                    <span className="stage-hint">{s.hint}</span>
                  </span>
                  {toolCosts?.[s.type] != null && (
                    <span className="stage-coin" title={`${toolCosts[s.type]} 크레딧`} aria-label={`${toolCosts[s.type]} 크레딧`}>{toolCosts[s.type]}</span>
                  )}
                </button>
              ))}
            </div>
          </div>
          {error && <p className="error">{error}</p>}
        </form>

        <div className="card">
          {results ? (
            <>
              {isSample && (
                <div className="sample-banner">
                  <span>📋 <b>예시 딜</b> · 무료 ProForma 결과입니다. AI 심층 심사는 좌측 버튼으로</span>
                  <button type="button" className="btn-link" onClick={() => { setForm(INITIAL); setCurrentDealId(null); setResults(null); setIsSample(false); setStageMap({}); setActiveTab('FINANCE') }}>
                    내 딜 입력하기 →
                  </button>
                </div>
              )}
              <ResultPanel
                results={results}
                stageMap={stageMap}
                activeTab={activeTab}
                onSelectTab={(tab) => {
                  if (tab === 'FINANCE') { setActiveTab('FINANCE'); return }
                  const s = stageMap[tab]
                  if (s) { setResults(resultsFromStage(s)); setActiveTab(tab) }
                }}
                onReport={openReport}
                reportBusy={reportBusy}
              />
            </>
          ) : (
            <div className="rp">
              <div className="rp-top">
                <div className="empty-ico" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M3 3v18h18" />
                    <path d="M7 14l3.5-3.5L14 14l4-5" />
                  </svg>
                </div>
                <div>
                  <h3>분석 결과가 여기에 표시됩니다</h3>
                  <p>좌측에 딜 정보를 입력하고 <b>ProForma 계산</b>(무료) 또는 <b>AI 분석 단계</b>를 실행하세요.</p>
                </div>
              </div>
              <div className="rp-skeleton" aria-hidden="true">
                <div className="rp-metrics">
                  {['IRR', 'EQUITY MULTIPLE', 'DSCR'].map((k) => (
                    <div className="rp-card" key={k}><span>{k}</span><i /></div>
                  ))}
                </div>
                <div className="rp-verdict"><span /><i /></div>
                <div className="rp-lines">{[100, 86, 94, 72, 88].map((w, i) => <span key={i} style={{ width: `${w}%` }} />)}</div>
              </div>
            </div>
          )}
        </div>
      </div>

      <HistoryPanel version={historyVersion} onOpen={(r, runId, request, dealId) => {
        setResults({ ...r, runId, inputs: request })
        setCurrentDealId(dealId) // 이 이력이 속한 딜로 컨텍스트 전환 → 이후 분석이 같은 딜로 묶임.
        // 좌측 입력 폼도 저장된 값으로 복원 - 결과만 뜨고 폼이 비던 문제 수정(딜명·매입가 등).
        if (request && request.askingPriceEok != null) {
          setIsSample(false)
          setForm(formFromInput(request, request.dealName ?? ''))
        }
        const at = (r as unknown as { analysisType?: AnalysisType }).analysisType
        setActiveTab(at ?? 'FINANCE')
        void loadDealStages(dealId)
        // 불러온 데이터(폼·결과)로 부드럽게 이동 - 키보드 포커스는 뺏지 않음.
        requestAnimationFrame(() => {
          document.getElementById('uw-layout')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        })
      }} />

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

function HistoryPanel({ version, onOpen }: { version: number; onOpen: (r: RunResult, runId: number, request: UnderwriteInput | null, dealId: number | null) => void }) {
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [compareOpen, setCompareOpen] = useState(false)

  useEffect(() => {
    let active = true
    api.runs()
      // 언더라이팅 이력에는 파이프라인(스크리닝·시장조사·언더라이팅·투심)만 - 심화·시장 분석은 각 메뉴에서.
      .then((list) => { if (active) setRuns(list.filter((r) => STAGE_LABEL[canonicalTool(r.tool)])) })
      .catch((err: unknown) => { if (active) setError(err instanceof ApiError ? err.message : '이력 조회 실패') })
    return () => { active = false }
  }, [version])

  async function open(id: number) {
    try {
      const detail = await api.run(id)
      if (detail.result) onOpen(detail.result, detail.id, detail.request, detail.dealId)
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
          <thead><tr><th>딜 ID</th><th>딜</th><th>유형</th><th>상태</th><th>일시</th><th></th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td><span className="hist-deal-id" title="딜 고유 번호 - 이름이 달라도 같은 번호면 같은 딜입니다">#{r.dealId ?? '-'}</span></td>
                <td>{r.dealName ?? '(이름없음)'}</td>
                <td>{STAGE_LABEL[canonicalTool(r.tool)] ?? r.tool}</td>
                <td><span className={r.status === 'SUCCESS' ? 'st-ok' : 'st-fail'}>{r.status === 'SUCCESS' ? '성공' : r.status === 'FAILED' ? '실패' : r.status}</span></td>
                <td className="num">{r.createdAt ? new Date(r.createdAt).toLocaleString('ko-KR') : '-'}</td>
                <td><button className="btn-link" onClick={() => open(r.id)}>보기</button></td>
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
        {label}{required && <span className="req"> *</span>}
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

function ResultPanel({ results, stageMap, activeTab, onSelectTab, onReport, reportBusy }: {
  results: Results
  stageMap: Partial<Record<AnalysisType, DealStage>>
  activeTab: 'FINANCE' | AnalysisType
  onSelectTab: (tab: 'FINANCE' | AnalysisType) => void
  onReport: () => void
  reportBusy: boolean
}) {
  const p = results.proForma
  const md = minDscr(p)
  return (
    <div className="ai-block">
      <div className="result-head">
        <span className="result-head-actions">
          <button type="button" className="btn-ghost btn-report"
            onClick={() => downloadUnderwritingXls(p, results.scenarios, results.inputs, results.inputs?.dealName)}
            title="ProForma 모델을 Excel(.xls)로 저장">Excel 모델</button>
          {results.runId && (
            <button type="button" className="btn-ghost btn-report" onClick={onReport} disabled={reportBusy}>
              {reportBusy ? '보고서 여는 중…' : '투자 보고서 보기'}
            </button>
          )}
          {results.runId && <ShareButton runId={results.runId} />}
        </span>
      </div>
      <StageTabs stageMap={stageMap} active={activeTab} onSelect={onSelectTab} />
      {results.inputs && <InputSummary inputs={results.inputs} />}

      {activeTab === 'FINANCE' ? (
        <>
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
        </>
      ) : results.analysis ? (
        <>
          <Verdict analysis={results.analysis} />
          <StageAnalysis type={results.analysisType} analysis={results.analysis} provider={results.provider} />
          {results.marketFacts && results.marketFacts.length > 0 && <MarketFactsCard facts={results.marketFacts} />}
        </>
      ) : results.analysisRaw ? (
        <section>
          <div className="section-title">AI 분석</div>
          <p className="narrative">{results.analysisRaw}</p>
        </section>
      ) : (
        <p className="hist-empty">이 단계는 아직 분석 결과가 없습니다. 좌측에서 실행하세요.</p>
      )}

      <p className="disclaimer">{results.disclaimer}</p>
    </div>
  )
}

/**
 * 결과 탭 - 공통 「재무 모델」(ProForma 지표·차트·시나리오) + 단계별 AI 분석(스크리닝·시장조사·언더라이팅·투심).
 * 재무 탭은 항상, 단계 탭은 완료 시 ✓ 클릭 가능, 미실행은 비활성('미실행' - 좌측 버튼으로 실행).
 */
function StageTabs({ stageMap, active, onSelect }: {
  stageMap: Partial<Record<AnalysisType, DealStage>>
  active: 'FINANCE' | AnalysisType
  onSelect: (tab: 'FINANCE' | AnalysisType) => void
}) {
  return (
    <div className="stage-tabs" role="tablist" aria-label="결과 보기">
      <button
        type="button" role="tab" aria-selected={active === 'FINANCE'}
        className={`stage-tab${active === 'FINANCE' ? ' active' : ''}`}
        onClick={() => onSelect('FINANCE')} title="재무 모델 - ProForma 지표·차트·시나리오"
      >
        <span className="st-label">재무 모델</span>
      </button>
      {STAGES.map((s) => {
        const done = !!stageMap[s.type]
        const isActive = active === s.type
        return (
          <button
            key={s.type} type="button" role="tab" aria-selected={isActive}
            className={`stage-tab${isActive ? ' active' : ''}${done ? '' : ' undone'}`}
            disabled={!done} onClick={() => done && onSelect(s.type)}
            title={done ? s.label : `${s.label} - 미실행 (좌측에서 실행)`}
          >
            <span className="st-label">{s.label}</span>
            <span className="st-mark">{done ? '✓' : '미실행'}</span>
          </button>
        )
      })}
    </div>
  )
}

/** 읽기전용 공유 링크 - 토큰 발급 후 링크를 클립보드에 복사(로그인 없이 열람 가능). */
function ShareButton({ runId }: { runId: number }) {
  const [state, setState] = useState<'idle' | 'busy' | 'copied' | 'error'>('idle')
  async function share() {
    setState('busy')
    try {
      const { token } = await api.shareReport(runId)
      const url = `${window.location.origin}/api/public/report/${token}`
      try { await navigator.clipboard.writeText(url) } catch { window.prompt('공유 링크 (복사하세요)', url) }
      setState('copied')
      setTimeout(() => setState('idle'), 2500)
    } catch {
      setState('error')
      setTimeout(() => setState('idle'), 2500)
    }
  }
  return (
    <button type="button" className="btn-ghost btn-report" onClick={() => void share()} disabled={state === 'busy'}
      title="로그인 없이 열람 가능한 읽기전용 링크를 복사합니다">
      {state === 'busy' ? '발급 중…' : state === 'copied' ? '링크 복사됨 ✓' : state === 'error' ? '실패' : '공유 링크'}
    </button>
  )
}

/** 실측 시장데이터 카드 - 분석에 주입된 공공데이터(출처·기준일)를 노출. "실측 앵커링"을 가시화. */
function MarketFactsCard({ facts }: { facts: MarketFact[] }) {
  // 실거래가 등 '; ' 로 이어진 항목은 건별 줄로 분리(한 줄로 뭉치는 것 방지).
  const linesOf = (detail: string): string[] => detail.split(';').map((s) => s.trim()).filter(Boolean)
  return (
    <section className="calc-card mkt-facts">
      <div className="section-title">실측 시장데이터 <span className="calc-badge">확정 · 공공데이터</span></div>
      <ul className="mkt-list">
        {facts.map((f, i) => {
          const lines = linesOf(f.detail)
          return (
            <li key={i} className="mkt-item">
              <span className="mkt-src">{f.source}</span>
              {lines.length > 1 ? (
                <div className="mkt-comps">
                  {lines.map((l, k) => <span key={k} className="mkt-comp">{l}</span>)}
                </div>
              ) : (
                <span className="mkt-detail">{lines[0] ?? f.detail}</span>
              )}
            </li>
          )
        })}
      </ul>
      <p className="mkt-note">위 수치는 공공 API 실측값(출처·기준일 명시)으로, AI 추정이 아니라 분석의 근거로 직접 인용됩니다.<br />실측이 없는 항목은 본문에 "(추정)"·신뢰도로 표기됩니다.</p>
    </section>
  )
}

/** 입력 요약 - 이 결과를 만든 딜 입력값을 결과 상단에 표시(이력에서 다시 열 때 "무엇을 입력했는지" 확인). */
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

/** 지표 양호도 - 색·배지 판정(보수적 CRE 임계값). */
type MStatus = 'good' | 'warn' | 'neutral'
const irrStatus = (v: number): MStatus => (v >= 12 ? 'good' : v < 8 ? 'warn' : 'neutral')
const emStatus = (v: number): MStatus => (v >= 1.8 ? 'good' : v < 1.3 ? 'warn' : 'neutral')
const dscrStatus = (v: number): MStatus => (v >= 1.25 ? 'good' : v < 1.0 ? 'warn' : 'neutral')

/** 핵심 지표 - 큰 숫자 + 양호/주의 색·배지. */
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

/** 보조 지표 - 컴팩트 라벨 + 값(필요 시 색). */
function MiniMetric({ label, value, status }: { label: string; value: string; status?: MStatus }) {
  return (
    <div className="mini-metric">
      <span className="mm-label">{label}</span>
      <span className={`mm-value${status && status !== 'neutral' ? ` ${status}` : ''}`}>{value}</span>
    </div>
  )
}

/** 가이드라인 적합성 - 코드가 임계값과 대조한 결정론적 판정(PASS/WARN/FAIL). AI 판단 아님. */
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
