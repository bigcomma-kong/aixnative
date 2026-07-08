import { useEffect, useRef, useState } from 'react'
import { TrustBadge } from './TrustBadge'
import { Markdown, DataTable } from './Markdown'
import {
  api, ApiError, track, isBovCalc, isBizHealthCalc, isPriceForecastCalc,
  type AnalysisFlag, type BizHealthCalc, type BovInput, type DealSummary, type DevFeasibilityInput, type DocAnalysisType, type DocAnalyzeInput,
  type DocAnalyzeResponse, type DocCalc, type DocSection, type GuideProForma, type MarketFact, type PriceForecastCalc, type PriceForecastInput,
  type RunSummary, type TaxGuide, type UnderwriteInput,
} from './api'

interface DocAnalysisViewProps {
  onCreditBalance: (balance: number) => void
  /** 크레딧 소진(402) 시 중앙 페이월 안내 노출. */
  onNeedCredits: () => void
  /** 분석유형 id → 크레딧 단가(서버 단일 소스). 미로딩 시 숫자 생략. */
  toolCosts?: Record<string, number>
  /** 현재 크레딧 잔액 - 실행 전 사전 확인용(모자라면 분석을 시작조차 안 하고 안내). */
  creditBalance?: number
  /** 내 딜 '심화 이어서' → 이 딜(PK)을 컨텍스트로 로드. 어떤 딜이든 가능(파이프라인·심화 무관). */
  openDealId?: number
  /** openDealId 처리 완료 통지(상위가 값을 비워 재진입 방지). */
  onDealOpened?: () => void
}

/** "N크레딧" 라벨 - 단가 미로딩 시 숫자 생략. */
function creditLabel(cost?: number): string {
  return cost != null ? `${cost}크레딧` : '크레딧'
}

/**
 * 언더라이팅 입력을 심화 분석 서술칸에 넣을 한 줄 요약으로 변환.
 * 재무 수치는 심화에 대응 칸이 없어 documentText 로 흡수한다(정형→서술).
 */
function summarizeUnderwrite(u: UnderwriteInput): string {
  const p: string[] = []
  if (u.askingPriceEok != null) p.push(`매입가 ${u.askingPriceEok}억`)
  if (u.noiEok != null) p.push(`NOI ${u.noiEok}억`)
  if (u.exitCapPct != null) p.push(`Exit Cap ${u.exitCapPct}%`)
  if (u.ltvPct != null) p.push(`LTV ${u.ltvPct}%`)
  if (u.loanRatePct != null) p.push(`대출금리 ${u.loanRatePct}%`)
  if (u.holdYears != null) p.push(`보유 ${u.holdYears}년`)
  if (u.rentGrowthPct != null) p.push(`임대성장 ${u.rentGrowthPct}%/년`)
  const line = `[언더라이팅 딜 요약] ${p.join(', ')}.`
  return u.notes?.trim() ? `${line}\n비고: ${u.notes.trim()}` : line
}

interface DocTypeMeta {
  type: DocAnalysisType
  label: string
  hint: string
  placeholder: string
  /** 이 분석에 꼭 필요한 입력(평이한 말). */
  needs: string
  /** 무엇이 나오는지(산출물). */
  gives: string
}

const ASSET_TYPES = ['오피스', '물류', '호텔', '리테일'] as const

/** 신규/추가 분석 단계 - 자유 텍스트 입력 기반(각 1 크레딧). */
const DOC_TYPES: DocTypeMeta[] = [
  { type: 'UNDERWRITING_GUIDE', label: '언더라이팅 입력가이드', hint: '권장 가정 선제안',
    needs: '자산 개요 텍스트 (아는 값 있으면 함께)', gives: '권장 매입가·Cap·LTV 등 입력 가정',
    placeholder: '예: 강남 GBD 중형 오피스 매입 검토, 연면적 약 2,000평. 알고 있는 값(매입가·NOI·Cap)이 있으면 함께 적어주세요.' },
  { type: 'BUILDING_RESEARCH', label: '건물 검색(예비 IM)', hint: '공개 벤치마크 예비 IM',
    needs: '건물·위치 텍스트', gives: '공개 벤치마크 기반 예비 IM',
    placeholder: '예: 서울 영등포구 여의도동 소재 오피스, 연면적 약 3,000평, 2015년 준공, 호가 미상.' },
  { type: 'TAX_PRICE_DIAGNOSIS', label: '세무·가격 진단', hint: '절세 포인트·가격 적정성',
    needs: '취득가·양도가·보유기간 텍스트 (필지주소 넣으면 공시지가 자동)', gives: '절세 포인트·가격 적정성',
    placeholder: '예: 취득가 2,000억, 양도가 2,400억, 보유 5년, 법인 보유. 취득세·중개보수·법무비·자본적지출 등 실제 지출 내역.' },
  { type: 'BOV', label: '매각 BOV', hint: '평가·가격범위·매각방식',
    needs: '안정화 NOI* · 시장 Cap* (아래 입력)', gives: '3법 평가·가격범위·매각방식',
    placeholder: '예: GBD 코어오피스, 현 NOI 95억, 시장 Cap 4.75%, 잔여대출 1,100억(조기상환 페널티 2%), 매각 우선순위=확실성.' },
  { type: 'AM_QUARTERLY', label: '분기 자산보고', hint: '운영 실적·KPI·Variance',
    needs: '분기 운영 실적 텍스트 (NOI·점유율 등)', gives: '실적·KPI·Variance 분석',
    placeholder: '예: 3분기 GPR 30억/EGI 28억/OpEx 9억/NOI 19억(예산 대비 +3%), Occupancy 94%, WALT 3.2년, Top1 임차 28%.' },
  { type: 'HOLD_SELL_REFI', label: '보유·매각·리파이', hint: '4-시나리오 결정',
    needs: '보유 현황 텍스트 (NAV·대출·NOI 등)', gives: '보유/매각/리파이 4-시나리오 결정',
    placeholder: '예: 보유 3년차, 현 NAV 2,200억, 잔여대출 1,100억(금리 4.5%), 현 NOI 95억, 취득가 2,000억.' },
  { type: 'DEV_FEASIBILITY', label: '개발 타당성', hint: '사업비·마진·인허가·PF',
    needs: '토지비* · 공사비* + (분양수입 또는 안정화 NOI+Exit Cap)', gives: '사업비·마진·인허가·PF 타당성',
    placeholder: '예: 역삼동 토지 500평, 용적률 800%, 오피스 개발, 토지매입가 800억, 예상 연면적 4,000평.' },
  { type: 'MARKET_RESEARCH_DEEP', label: '심화 시장리서치', hint: '권역·매크로·하우스뷰',
    needs: '권역·시장 텍스트 (위치 넣으면 실측 주입)', gives: '권역·매크로·하우스뷰',
    placeholder: '예: 서울 GBD 오피스 시장. 향후 3년 공급, 금리 환경, 최근 거래 사례 기준 House View 요청.' },
  { type: 'COUNTERPARTY_DD', label: '거래상대방 실사', hint: '사업자상태·제재·규모',
    needs: '사업자등록번호 10자리* (또는 상호)', gives: '사업자상태·제재·기업규모',
    placeholder: '정성 정보(선택): 거래 맥락·우려사항 등. 핵심 사실은 사업자번호로 공공데이터가 확정합니다.' },
  { type: 'PRICE_FORECAST', label: '매입·매각 가격 예측', hint: '소득환원+거래사례 밴드',
    needs: 'NOI 또는 연면적 중 하나* (위치/필지 넣으면 실측 자동)', gives: '매입·매각 가격 밴드',
    placeholder: '정성 정보(선택): 입찰 맥락·매도 우선순위 등. 핵심 수치는 아래 입력 + 실거래/공시지가로 확정합니다.' },
]

/** 타입 → 메타 빠른 조회. */
const DOC_BY_TYPE: Record<string, DocTypeMeta> = Object.fromEntries(DOC_TYPES.map((d) => [d.type, d]))

/** 딜 라이프사이클(목적)별 그룹 - 10종을 평면 나열 대신 4묶음으로 보여 선택 부담을 줄인다. */
const DOC_GROUPS: { title: string; types: DocAnalysisType[] }[] = [
  { title: '매입 검토', types: ['UNDERWRITING_GUIDE', 'BUILDING_RESEARCH', 'PRICE_FORECAST', 'TAX_PRICE_DIAGNOSIS'] },
  { title: '매각·보유 결정', types: ['BOV', 'HOLD_SELL_REFI'] },
  { title: '운용·개발', types: ['AM_QUARTERLY', 'DEV_FEASIBILITY'] },
  { title: '리서치·실사', types: ['MARKET_RESEARCH_DEEP', 'COUNTERPARTY_DD'] },
]

const DOC_LABEL: Record<string, string> = Object.fromEntries(
  DOC_TYPES.map((d) => [d.type, d.label]).concat(
    // 백엔드 tool 식별자 ↔ 라벨 (이력 표시용)
    [['UNDERWRITING_GUIDE', '언더라이팅 입력가이드'], ['BUILDING_RESEARCH', '건물 검색(예비 IM)'],
     ['TAX_PRICE_DIAGNOSIS', '세무·가격 진단'], ['BOV_NARRATIVE', '매각 BOV'], ['AM_QUARTERLY', '분기 자산보고'],
     ['HOLD_SELL_REFI', '보유·매각·리파이'], ['DEV_FEASIBILITY', '개발 타당성'], ['MARKET_RESEARCH_DEEP', '심화 시장리서치'],
     ['COUNTERPARTY_DD', '거래상대방 실사'], ['PRICE_FORECAST', '매입·매각 가격 예측']] as [string, string][],
  ),
)

const RECOMMEND_LABELS: Record<string, string> = {
  askingPriceEok: '매입가(억)', noiEok: 'NOI(억)', goingInCapPct: 'Going-in Cap(%)', ltvPct: 'LTV(%)',
  loanRatePct: '대출금리(%)', holdYears: '보유(년)', exitCapPct: 'Exit Cap(%)', rentGrowthPct: '임대성장(%)',
}

/** 코드 계산 입력 필드 정의(BOV·개발타당성). req=필수, def=기본 placeholder, hint=보조 설명. */
interface CalcField { k: string; label: string; req?: boolean; def?: string; hint?: string }

const BOV_FIELDS: CalcField[] = [
  { k: 'noiEok', label: '안정화 NOI (억)', req: true },
  { k: 'marketCapPct', label: '시장 Cap (%)', req: true },
  { k: 'discountRatePct', label: 'DCF 할인율 (%)', hint: '미입력 시 자산유형 기본값' },
  { k: 'exitCapPct', label: 'Exit Cap (%)', hint: '미입력 시 자산유형 기본값' },
  { k: 'holdYears', label: '보유기간 (년)', def: '5' },
  { k: 'rentGrowthPct', label: '임대성장률 (%)', def: '3' },
  { k: 'salesCompValueEok', label: '비교거래 추정가 (억)', hint: '선택 - 입력 시 3법 가중' },
]

const DEV_FIELDS: CalcField[] = [
  { k: 'landCostEok', label: '토지비 (억)', req: true },
  { k: 'constructionCostEok', label: '공사비 (억)', req: true },
  { k: 'financingCostEok', label: '금융비용 (억)', hint: 'PF 이자 등' },
  { k: 'otherCostEok', label: '기타비용 (억)', hint: '설계·인허가·마케팅' },
  { k: 'contingencyPct', label: '우발비율 (%)', def: '5' },
  { k: 'stabilizedNoiEok', label: '안정화 NOI (억)', hint: '임대형' },
  { k: 'exitCapPct', label: 'Exit Cap (%)', hint: '임대형' },
  { k: 'salesRevenueEok', label: '분양수입 (억)', hint: '분양형' },
]

const FORECAST_FIELDS: CalcField[] = [
  { k: 'noiEok', label: '안정화 NOI (억)', hint: '소득환원용 - 알면 입력' },
  { k: 'areaPyeong', label: '연면적 (평)', hint: '거래사례용 - 알면 입력' },
  { k: 'marketCapPct', label: '시장 Cap (%)', hint: '미입력 시 자산유형 기본값' },
]

function isCalcType(t: DocAnalysisType): boolean {
  return t === 'BOV' || t === 'DEV_FEASIBILITY'
}

/**
 * 언더라이팅 입력 → 현재 심화 분석의 계산 입력칸 프리필(대응되는 필드만).
 * 내가 언더라이팅에서 입력한 NOI·Exit Cap 등을 재입력하지 않도록 가져온다. 계산 타입이 아니면 빈 맵.
 */
function calcPrefillFromUnderwrite(t: DocAnalysisType, u: UnderwriteInput): Record<string, string> {
  const keys = new Set(
    (t === 'BOV' ? BOV_FIELDS : t === 'DEV_FEASIBILITY' ? DEV_FIELDS : t === 'PRICE_FORECAST' ? FORECAST_FIELDS : [])
      .map((f) => f.k),
  )
  const out: Record<string, string> = {}
  const put = (k: string, v: number | undefined) => { if (v != null && keys.has(k)) out[k] = String(v) }
  put('noiEok', u.noiEok)
  put('stabilizedNoiEok', u.noiEok)
  put('exitCapPct', u.exitCapPct)
  put('holdYears', u.holdYears)
  put('rentGrowthPct', u.rentGrowthPct)
  return out
}

/**
 * 저장된 요청(bov/dev/forecast)의 숫자 필드를 계산 입력칸(calcValues) 문자열 맵으로 역매핑.
 * 지난 분석을 다시 열 때 그때 넣은 입력값을 폼에 복원하기 위함. 필드 키는 입력칸 키와 동일.
 */
function calcValuesFromRequest(req: DocAnalyzeInput): Record<string, string> {
  const src = req.bov ?? req.dev ?? req.forecast
  if (!src) return {}
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(src)) {
    if (typeof v === 'number' && Number.isFinite(v)) out[k] = String(v)
  }
  return out
}

/**
 * 지정 요소의 상단으로 부드럽게 스크롤 — 결과 등 큰 콘텐츠가 렌더된 뒤 측정하도록 두 프레임 뒤에 실행.
 * (single rAF 은 커밋 직후라 레이아웃 확정 전이라 위치가 중간에서 어긋날 수 있음.)
 */
function scrollToTopOf(id: string) {
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }),
  )
}

/** 문자열 입력 → 숫자(빈값은 undefined). */
function toNum(v: string | undefined): number | undefined {
  if (v == null || v.trim() === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

const POSITIVE = ['GO', 'STRONG_BUY', 'BULLISH', '상향', '적정', 'REFINANCE', '양호']
const NEGATIVE = ['NO_GO', 'NO-GO', 'PASS', 'BEARISH', '하향', '과도', 'SELL', '위험']

function verdictTone(verdict?: string): 'go' | 'no' | 'cond' {
  if (!verdict) return 'cond'
  const v = verdict.toUpperCase()
  if (POSITIVE.some((p) => v.includes(p))) return 'go'
  if (NEGATIVE.some((n) => v.includes(n))) return 'no'
  return 'cond'
}

/** 심각도 → 색상. HIGH·높음=빨강, LOW·낮음=초록, 그 외(MEDIUM·중간)=주황. */
function sevTone(s?: string): 'go' | 'no' | 'cond' {
  if (!s) return 'cond'
  const t = s.trim().toUpperCase()
  if (t.startsWith('H') || t.includes('높')) return 'no'
  if (t.startsWith('L') || t.includes('낮')) return 'go'
  return 'cond'
}

/** 심각도 뱃지(HIGH/MEDIUM/LOW). 공통. */
function SevBadge({ v }: { v?: string }) {
  if (!v) return null
  return <span className={`sev-badge ${sevTone(v)}`}>{v}</span>
}

/** 주요 플래그 - sections 계약 트랙의 리스크·결격·체크 사유를 심각도 뱃지와 함께. */
function FlagList({ flags }: { flags: AnalysisFlag[] }) {
  return (
    <section className="scr-section">
      <div className="section-title">주요 플래그</div>
      {flags.map((f, i) => (
        <div key={i} className="risk">
          <span className="r-name">{f.label}</span>
          <span className="r-impact"><SevBadge v={f.severity} /></span>
        </div>
      ))}
    </section>
  )
}

export function DocAnalysisView({ onCreditBalance, onNeedCredits, toolCosts, creditBalance, openDealId, onDealOpened }: DocAnalysisViewProps) {
  const [type, setType] = useState<DocAnalysisType>('DEV_FEASIBILITY')
  const [dealName, setDealName] = useState('')
  const [assetType, setAssetType] = useState<string>('오피스')
  const [location, setLocation] = useState('')
  const [documentText, setDocumentText] = useState('')
  const [calcValues, setCalcValues] = useState<Record<string, string>>({})
  const [bizNo, setBizNo] = useState('')
  const [counterpartyName, setCounterpartyName] = useState('')
  const [parcelAddress, setParcelAddress] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<DocAnalyzeResponse | null>(null)
  const [reportBusy, setReportBusy] = useState(false)
  // 현재 편집 중인 딜 식별자(PK). null=새 딜. 첫 분석 후 응답 dealId 로 채워져 이후 분석이 같은 딜로 묶인다.
  const [currentDealId, setCurrentDealId] = useState<number | null>(null)
  // 현재 딜의 '커밋된' 이름(딜 단위 라벨). 이름칸을 바꿔 blur 하면 이 값과 비교해 딜 전체 이름 변경을 묻는다.
  const [loadedDealName, setLoadedDealName] = useState('')
  // 이름 변경 확인 모달 - blur 시 바꾼 이름을 담아 열고, 확인/취소로 일괄 반영 또는 되돌림(window.confirm 대체).
  const [renamePending, setRenamePending] = useState<string | null>(null)
  const [historyVersion, setHistoryVersion] = useState(0)
  // 언더라이팅 딜 가져오기 - 내 딜(파이프라인 입력 보유분)에서 딜명·자산·위치·재무요약을 프리필.
  const [importDeals, setImportDeals] = useState<DealSummary[]>([])
  const [importBusy, setImportBusy] = useState(false)
  // 스크롤 타깃 - 결과(가격 예측 등) 영역으로 이동.
  const forecastRef = useRef<HTMLDivElement>(null)

  // 이어서 분석 가능한(언더라이팅 입력 보유) 내 딜만 가져오기 후보로.
  useEffect(() => {
    api.myDeals()
      .then((ds) => setImportDeals(ds.filter((d) => d.canContinue && !d.isMarketReport)))
      .catch(() => setImportDeals([]))
  }, [])

  /**
   * 딜(PK)을 심화 폼의 컨텍스트로 로드 — 딜명·자산·위치를 즉시 채우고, 파이프라인 입력이 있으면
   * 재무 수치를 서술칸 요약 + 계산 입력칸으로 프리필. 어떤 딜이든(파이프라인·심화 무관) 동작한다.
   * dropdown '가져오기'와 내 딜 '심화 이어서'가 공유.
   */
  async function loadDealContext(deal: DealSummary) {
    setImportBusy(true)
    setError(null)
    setResult(null) // 직전 다른 분석 결과가 남아 '가져온 딜'을 덮는 것처럼 보이지 않도록 정리.
    // 딜명·자산·위치는 이미 가진 DealSummary 로 '즉시' 반영 — dealStages 호출 실패와 무관하게 항상 채운다.
    setCurrentDealId(deal.dealId) // 심화분석을 이 딜(PK)에 이어붙인다.
    setDealName(deal.dealName)
    setLoadedDealName(deal.dealName) // 딜의 커밋된 이름(라벨) 기준값.
    if (deal.assetType && (ASSET_TYPES as readonly string[]).includes(deal.assetType)) setAssetType(deal.assetType)
    if (deal.location) setLocation(deal.location)
    try {
      const ds = await api.dealStages(deal.dealId)
      // 파이프라인 단계 중 재무 입력(askingPriceEok)이 있는 request = UnderwriteInput.
      const u = ds.stages.find((s) => s.request && s.request.askingPriceEok != null)?.request as UnderwriteInput | undefined
      // 언더라이팅 입력이 더 구체적이면 자산·위치를 덮어쓴다.
      const at = u?.assetType ?? deal.assetType ?? undefined
      if (at && (ASSET_TYPES as readonly string[]).includes(at)) setAssetType(at)
      const loc = u?.location ?? deal.location ?? ''
      if (loc) setLocation(loc)
      if (u) {
        const summary = summarizeUnderwrite(u)
        // 이전에 끼워넣은 요약 블록([언더라이팅 딜 요약]…)을 먼저 제거 후 새로 프리필 → 재-가져오기 시 중복 누적 방지.
        setDocumentText((prev) => {
          const cleaned = prev.replace(/\[언더라이팅 딜 요약\][\s\S]*?(?:\n\n|$)/, '').trimStart()
          return cleaned.trim() ? `${summary}\n\n${cleaned}` : summary
        })
        // 계산 입력칸(NOI·Exit Cap 등)도 대응되는 값으로 채움 - 텍스트 요약만이 아니라 실제 입력값을 가져온다.
        const prefill = calcPrefillFromUnderwrite(type, u)
        if (Object.keys(prefill).length > 0) setCalcValues((s) => ({ ...s, ...prefill }))
      }
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '딜 정보를 불러오지 못했습니다.')
    } finally {
      setImportBusy(false)
      // 채워진 입력 폼으로 스크롤 — 가져온 딜의 딜명·데이터가 input 에 들어온 걸 바로 보이도록.
      scrollToTopOf('doc-input')
    }
  }

  /** dropdown '언더라이팅 딜에서 가져오기' - 후보(canContinue) 목록에서 선택. */
  function importFromDeal(dealId: number) {
    const deal = importDeals.find((d) => d.dealId === dealId)
    if (deal) void loadDealContext(deal)
  }

  /**
   * 내 딜 '심화 이어서'로 진입.
   * 이 딜의 '최신 심화분석'을 열어 그때 넣은 입력(토지비·NOI 등)과 결과를 그대로 복원한다(openPastRun).
   * 심화분석이 하나도 없는 딜(파이프라인만)이면 언더라이팅 입력을 프리필해 새 심화 분석을 시작하게 한다.
   */
  useEffect(() => {
    if (openDealId == null) return
    let active = true
    ;(async () => {
      try {
        const runs = await api.runs() // 최신순
        if (!active) return
        const docTools = new Set(Object.keys(DOC_LABEL))
        const latestDoc = runs.find((r) => r.status === 'SUCCESS' && docTools.has(r.tool) && r.dealId === openDealId)
        if (latestDoc) {
          await openPastRun(latestDoc.id) // 입력 + 결과 모두 복원
        } else {
          const ds = await api.myDeals()
          if (!active) return
          const deal = ds.find((d) => d.dealId === openDealId)
          if (deal) await loadDealContext(deal)
          else setError('딜 정보를 찾지 못했습니다.')
        }
      } catch {
        if (active) setError('딜 정보를 불러오지 못했습니다.')
      } finally {
        if (active) onDealOpened?.()
      }
    })()
    return () => { active = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openDealId])

  const meta = DOC_TYPES.find((d) => d.type === type) ?? DOC_TYPES[0]
  const calcMode = isCalcType(type)
  const ddMode = type === 'COUNTERPARTY_DD'
  const forecastMode = type === 'PRICE_FORECAST'
  const parcelMode = type === 'TAX_PRICE_DIAGNOSIS' || type === 'DEV_FEASIBILITY' || type === 'PRICE_FORECAST' // 공시지가·용도지역 선택 입력

  function selectType(next: DocAnalysisType) {
    setType(next)
    setError(null)
    // 결과·입력값은 유지 — 같은 딜에서 분석유형만 바꾸는 것이므로 비우면 초기화된 것처럼 느껴진다.
    // 딜명·위치·계산 입력·직전 결과는 그대로 두고, 완전 초기화는 상단 '초기화' 버튼으로만.
  }

  /** BOV/DEV 입력을 검증하고 요청 payload(bov/dev)를 구성. 실패 시 에러 문자열 반환. */
  function buildCalcInput(): { bov?: BovInput; dev?: DevFeasibilityInput } | string {
    const v = (k: string) => toNum(calcValues[k])
    if (type === 'BOV') {
      if (!v('noiEok') || !v('marketCapPct')) return '안정화 NOI 와 시장 Cap(%)은 필수입니다.'
      const bov: BovInput = {
        noiEok: v('noiEok')!, marketCapPct: v('marketCapPct')!,
        discountRatePct: v('discountRatePct'), exitCapPct: v('exitCapPct'),
        holdYears: v('holdYears') ?? 5, rentGrowthPct: v('rentGrowthPct') ?? 3,
        salesCompValueEok: v('salesCompValueEok') ?? 0,
      }
      return { bov }
    }
    // DEV_FEASIBILITY
    if (!v('landCostEok') || !v('constructionCostEok')) return '토지비와 공사비는 필수입니다.'
    const hasGdv = (v('salesRevenueEok') ?? 0) > 0 || ((v('stabilizedNoiEok') ?? 0) > 0 && (v('exitCapPct') ?? 0) > 0)
    if (!hasGdv) return '자산가치 산정 불가: 분양수입 또는 (안정화 NOI + Exit Cap)을 입력하세요.'
    const dev: DevFeasibilityInput = {
      landCostEok: v('landCostEok')!, constructionCostEok: v('constructionCostEok')!,
      financingCostEok: v('financingCostEok') ?? 0, otherCostEok: v('otherCostEok') ?? 0,
      contingencyPct: v('contingencyPct') ?? 5, stabilizedNoiEok: v('stabilizedNoiEok') ?? 0,
      exitCapPct: v('exitCapPct') ?? 0, salesRevenueEok: v('salesRevenueEok') ?? 0,
    }
    return { dev }
  }

  /** 미입력 필드로 스크롤 + 포커스 - 배너 대신 직접 안내. */
  function focusField(id: string) {
    const el = document.getElementById(id)
    if (!el) return
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    el.focus({ preventScroll: true })
  }

  /** 계산 모드(BOV/DEV)에서 첫 미입력 필수 필드의 DOM id. */
  function firstCalcMissingId(): string | null {
    const has = (k: string) => toNum(calcValues[k]) != null
    if (type === 'BOV') return !has('noiEok') ? 'calc-noiEok' : !has('marketCapPct') ? 'calc-marketCapPct' : null
    return !has('landCostEok') ? 'calc-landCostEok' : !has('constructionCostEok') ? 'calc-constructionCostEok' : null
  }

  async function run() {
    // 시작 전 크레딧 사전 확인 - 잔액을 알고 있고 단가보다 모자라면 분석을 시작조차 하지 않고 안내(모달만 떴다 사라지는 문제 방지).
    const cost = toolCosts?.[type]
    if (cost != null && creditBalance != null && creditBalance < cost) {
      onNeedCredits()
      return
    }
    const input: DocAnalyzeInput = {
      dealId: currentDealId ?? undefined, // 있으면 그 딜에 이어붙이고, 없으면 새 딜(self-anchor).
      dealName: dealName || undefined,
      assetType: assetType || undefined,
      location: location || undefined,
      documentText: documentText.trim() || undefined,
    }
    if (parcelMode && parcelAddress.trim()) input.parcelAddress = parcelAddress.trim()
    if (ddMode) {
      const digits = bizNo.replace(/[^0-9]/g, '')
      if (digits.length !== 10 && !counterpartyName.trim()) {
        setError('사업자등록번호(10자리) 또는 상호를 입력하세요.')
        focusField('ddBizNo')
        return
      }
      if (digits.length === 10) input.bizNo = digits
      if (counterpartyName.trim()) input.counterpartyName = counterpartyName.trim()
    } else if (calcMode) {
      const built = buildCalcInput()
      if (typeof built === 'string') {
        setError(built)
        const id = firstCalcMissingId()
        if (id) focusField(id)
        return
      }
      Object.assign(input, built)
    } else if (forecastMode) {
      const v = (k: string) => toNum(calcValues[k])
      if (!v('noiEok') && !v('areaPyeong')) {
        setError('NOI(소득환원) 또는 연면적(거래사례) 중 하나는 입력하세요.')
        focusField('fc-noiEok')
        return
      }
      const forecast: PriceForecastInput = {
        noiEok: v('noiEok'), marketCapPct: v('marketCapPct'), areaPyeong: v('areaPyeong'),
      }
      input.forecast = forecast
    } else if (!documentText.trim()) {
      setError('분석 대상 정보를 입력하세요.')
      focusField('docText')
      return
    }
    track('analysis_start', { path: 'advanced', meta: type })
    setError(null)
    setBusy(true)
    try {
      const res = await api.analyzeDoc(type, input)
      setResult(res)
      setCurrentDealId(res.dealId) // 첫 분석이면 새 딜 id 발급 → 이후 분석이 같은 딜로 묶임.
      setLoadedDealName(dealName.trim()) // 이 분석에 쓴 이름이 딜의 커밋된 라벨.
      track('analysis_done', { path: 'advanced', meta: type })
      onCreditBalance(res.creditBalance)
      setHistoryVersion((v) => v + 1)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) {
        onNeedCredits()
      } else if (err instanceof ApiError && err.status === 503) {
        setError(err.message || 'AI 분석 서비스를 사용할 수 없습니다.')
      } else {
        setError(err instanceof ApiError ? err.message : '분석 중 오류가 발생했습니다.')
      }
    } finally {
      setBusy(false)
    }
  }

  /** 이력에서 지난 심화 분석을 다시 열어 우측 결과 패널에 표시(재과금 없음). 저장된 결과 = DocAnalyzeResponse 형태. */
  async function openPastRun(id: number) {
    setError(null)
    try {
      const detail = await api.run(id)
      const raw = detail.result as unknown as DocAnalyzeResponse | null
      if (!raw) { setError('결과를 불러올 수 없습니다.'); return }
      // 같은 딜의 다른 분석을 여는 경우엔 딜 이름을 건드리지 않는다(딜 라벨은 하나 — 옛 스냅샷으로 되돌아가면 혼란).
      // 다른 딜로 전환할 때만 그 딜의 이름으로 반영.
      const sameDeal = detail.dealId != null && detail.dealId === currentDealId
      if (detail.dealId != null) setCurrentDealId(detail.dealId) // 이 이력이 속한 딜로 컨텍스트 전환.
      // 분석 유형·입력 폼도 그때 저장된 요청으로 복원 — '보기'만 해도 넣었던 데이터가 좌측에 그대로 보이도록.
      const t = (raw.analysisType ?? detail.tool) as DocAnalysisType
      if (t) setType(t)
      const req = detail.request as unknown as DocAnalyzeInput | null
      if (req) {
        if (!sameDeal) {
          const nm = req.dealName ?? detail.dealName ?? ''
          setDealName(nm)
          setLoadedDealName(nm)
        }
        if (req.assetType && (ASSET_TYPES as readonly string[]).includes(req.assetType)) setAssetType(req.assetType)
        setLocation(req.location ?? '')
        setDocumentText(req.documentText ?? '')
        setParcelAddress(req.parcelAddress ?? '')
        setBizNo(req.bizNo ?? '')
        setCounterpartyName(req.counterpartyName ?? '')
        setCalcValues(calcValuesFromRequest(req))
      }
      setResult({
        ...raw,
        runId: raw.runId ?? detail.id,
        analysisType: raw.analysisType ?? detail.tool,
        provider: raw.provider ?? '',
        creditBalance: raw.creditBalance ?? 0,
        disclaimer: raw.disclaimer ?? '',
      })
      // 결과 패널 상단으로 스크롤 — '보기'는 결과 확인이 목적이므로 doc-result 최상단(단계탭+결과)이 보이게.
      // 큰 결과가 렌더된 뒤 측정하도록 두 프레임 뒤 실행(중간에서 멈추는 문제 방지). 입력은 이미 좌측/상단에 복원됨.
      scrollToTopOf('doc-result')
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '이력 상세 조회에 실패했습니다.')
    }
  }

  /**
   * 이 딜의 심화분석 전부를 합본한 HTML 보고서를 새 창으로 연다(언더라이팅 '보고서 보기'와 동일 UX).
   * PDF·인쇄는 보고서 상단 툴바에서 처리 → 결과별 개별 PDF/Word 버튼을 대체한다.
   */
  async function openReport() {
    if (!result?.runId) return
    setReportBusy(true)
    try {
      const html = await api.reportHtml(result.runId, 'advanced')
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
      window.open(url, '_blank', 'noopener')
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '보고서를 불러오지 못했습니다.')
    } finally {
      setReportBusy(false)
    }
  }

  /**
   * 딜 이름칸 blur 시 - 기존 딜(currentDealId)의 이름을 바꿨으면 "이 딜의 모든 분석 이름을 바꿀까요?" 확인 후 일괄 변경.
   * 이름은 run별 스냅샷이 아니라 딜 단위 라벨이어야 하므로(안 그러면 옛 분석을 열 때 이름이 되돌아감) 전 분석에 반영한다.
   * 취소하면 원래 이름으로 되돌린다.
   */
  function commitDealNameChange() {
    const next = dealName.trim()
    if (currentDealId == null) return // 새 딜: 아직 저장 전이라 라벨일 뿐, 첫 분석 때 확정.
    if (next === loadedDealName) return
    if (!next) { setDealName(loadedDealName); return } // 빈 이름 금지 - 되돌림.
    setRenamePending(next) // 확인 모달 오픈(확인 시 doRename, 취소 시 되돌림).
  }

  /** 이름 변경 모달 '확인' - 딜(PK) 전체 분석 이름 일괄 반영. */
  async function doRename() {
    const next = renamePending
    if (next == null || currentDealId == null) { setRenamePending(null); return }
    setRenamePending(null)
    try {
      await api.renameDeal(currentDealId, next)
      setLoadedDealName(next)
      setHistoryVersion((v) => v + 1) // 단계탭·이력의 이름 라벨 갱신.
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '딜 이름 변경에 실패했습니다.')
      setDealName(loadedDealName)
    }
  }

  /** 이름 변경 모달 '취소' - 원래 이름으로 되돌림. */
  function cancelRename() {
    setRenamePending(null)
    setDealName(loadedDealName)
  }

  /** 새 딜로 시작 - 딜 컨텍스트·입력·결과를 비운다(다음 분석은 별도 딜로 self-anchor). */
  function startNewDeal() {
    setCurrentDealId(null)
    setResult(null)
    setError(null)
    setDealName('')
    setLoadedDealName('')
    setLocation('')
    setDocumentText('')
    setCalcValues({})
    setParcelAddress('')
    setBizNo('')
    setCounterpartyName('')
  }

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">AI DEEP ANALYSIS</span>
          <h1>매입·매각·운용·개발, 한 곳에서.</h1>
          <p className="page-sub">BOV·개발 타당성·포워드 시나리오까지, 딜의 전 단계를 하나의 흐름으로 분석합니다.</p>
        </div>
      </div>

      <ol className="use-steps" aria-label="사용 방법">
        <li><span className="us-n">1</span><span className="us-t"><b>분석 선택</b> 10종 중 목적에 맞는 분석을 고릅니다</span></li>
        <li><span className="us-n">2</span><span className="us-t"><b>입력</b> <span className="req">*</span> 표시는 필수, 나머지는 선택입니다</span></li>
        <li><span className="us-n">3</span><span className="us-t"><b>실행</b> 분석별 1~5크레딧 - 결과는 우측·이력에 저장됩니다</span></li>
      </ol>
      <div className="layout">
        <form id="doc-input" className="card input-panel" onSubmit={(e) => { e.preventDefault(); run() }}>
          <div className="form-head">
            <span className="section-title" style={{ margin: 0 }}>분석 선택 <span className="req-legend"><span className="req">*</span> 필수</span></span>
            <button type="button" className="btn-link" onClick={startNewDeal} title="딜 컨텍스트·입력·결과를 비우고 새 딜로 시작">
              초기화
            </button>
          </div>

          <div className="doc-type-groups">
            {DOC_GROUPS.map((g) => (
              <div className="doc-type-group" key={g.title}>
                <span className="dtg-title">{g.title}</span>
                <div className="doc-type-grid">
                  {g.types.map((t) => {
                    const d = DOC_BY_TYPE[t]
                    if (!d) return null
                    return (
                      <button key={d.type} type="button" className="doc-type-btn" aria-pressed={type === d.type}
                        onClick={() => selectType(d.type)}>
                        <span className="dt-label">{d.label}</span>
                        <span className="dt-hint">{d.hint}</span>
                        {toolCosts?.[d.type] != null && (
                          <span className="dt-coin" title={`${toolCosts[d.type]} 크레딧`} aria-label={`${toolCosts[d.type]} 크레딧`}>{toolCosts[d.type]}</span>
                        )}
                      </button>
                    )
                  })}
                </div>
              </div>
            ))}
          </div>

          <div className="doc-guide" role="note">
            <div className="dg-row"><span className="dg-k">필요 입력</span><span className="dg-v">{meta.needs}</span></div>
            <div className="dg-row"><span className="dg-k">산출</span><span className="dg-v">{meta.gives}</span></div>
          </div>

          <div className="form-grid">
            {importDeals.length > 0 && (
              <div className="full">
                <label htmlFor="importDeal">언더라이팅 분석 데이터 가져오기</label>
                <select
                  id="importDeal"
                  className="import-deal-select"
                  value=""
                  disabled={importBusy}
                  onChange={(e) => { if (e.target.value) void importFromDeal(Number(e.target.value)) }}
                >
                  <option value="">{importBusy ? '불러오는 중…' : '선택'}</option>
                  {importDeals.map((d) => (
                    <option key={d.dealId} value={d.dealId}>
                      {d.dealName}{d.completedStages.length > 0 ? ` · ${d.completedStages.join('·')}` : ''}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="full">
              <label htmlFor="docDeal">딜/자산 이름
                {currentDealId != null && <span className="doc-deal-tag" title="이 딜에 이어서 분석 중">이어서 분석 중</span>}
              </label>
              <input id="docDeal" value={dealName} onChange={(e) => setDealName(e.target.value)}
                onBlur={() => void commitDealNameChange()} placeholder="예: 강남 오피스" />
              {currentDealId != null && (
                <span className="field-hint">이름을 바꾸면 이 딜의 모든 분석에 함께 반영됩니다.</span>
              )}
            </div>
            {!ddMode && (
              <div className="full">
                <label htmlFor="docLoc">위치 / 권역 <span className="opt">(실측 검증)</span></label>
                <input id="docLoc" value={location} onChange={(e) => setLocation(e.target.value)} placeholder="예: 서울 강남구, 판교" />
              </div>
            )}
            <div className="full">
              <label>자산유형</label>
              <div className="seg" role="group" aria-label="자산유형">
                {ASSET_TYPES.map((t) => (
                  <button key={t} type="button" aria-pressed={assetType === t} onClick={() => setAssetType(t)}>{t}</button>
                ))}
              </div>
            </div>
            {parcelMode && (
              <div className="full">
                <label htmlFor="parcelAddr">필지 주소 <span className="opt">(공시지가·용도지역 조회)</span></label>
                <input id="parcelAddr" value={parcelAddress} onChange={(e) => setParcelAddress(e.target.value)}
                  placeholder="번지까지: 예 서울 강남구 역삼동 736-1" />
              </div>
            )}
            {ddMode && (
              <>
                <div className="full">
                  <label htmlFor="ddBizNo">사업자등록번호 <span className="req">*</span></label>
                  <input id="ddBizNo" value={bizNo} onChange={(e) => setBizNo(e.target.value)}
                    placeholder="10자리 (예: 124-81-00998)" inputMode="numeric" />
                </div>
                <div className="full">
                  <label htmlFor="ddName">상호 <span className="opt">(기업정보·규모 조회)</span></label>
                  <input id="ddName" value={counterpartyName} onChange={(e) => setCounterpartyName(e.target.value)}
                    placeholder="예: 삼성전자주식회사" />
                </div>
              </>
            )}
            {calcMode && (
              <div className="full">
                <label>{type === 'BOV' ? '3-Method 평가 입력 (코드 확정)' : '사업비·수입 입력 (코드 확정)'}</label>
                <div className="calc-grid">
                  {(type === 'BOV' ? BOV_FIELDS : DEV_FIELDS).map((f) => (
                    <div key={f.k} className="calc-field">
                      <label htmlFor={`calc-${f.k}`}>{f.label}{f.req && <span className="req"> *</span>}</label>
                      <input id={`calc-${f.k}`} type="number" inputMode="decimal" step="any"
                        value={calcValues[f.k] ?? ''} placeholder={f.def ?? ''}
                        onChange={(e) => setCalcValues((s) => ({ ...s, [f.k]: e.target.value }))} />
                      {f.hint && <span className="calc-hint">{f.hint}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}
            {forecastMode && (
              <div className="full" ref={forecastRef}>
                <label>가격 예측 입력 <span className="req">* NOI·연면적 중 하나 필수</span></label>
                <div className="calc-grid">
                  {FORECAST_FIELDS.map((f) => (
                    <div key={f.k} className="calc-field">
                      <label htmlFor={`fc-${f.k}`}>{f.label}</label>
                      <input id={`fc-${f.k}`} type="number" inputMode="decimal" step="any"
                        value={calcValues[f.k] ?? ''} placeholder={f.def ?? ''}
                        onChange={(e) => setCalcValues((s) => ({ ...s, [f.k]: e.target.value }))} />
                      {f.hint && <span className="calc-hint">{f.hint}</span>}
                    </div>
                  ))}
                </div>
                <span className="calc-hint">위치를 넣으면 상업 실거래 평당가가, 필지주소를 넣으면 공시지가가 자동 주입됩니다.</span>
              </div>
            )}
            <div className="full">
              <label htmlFor="docText">{calcMode || ddMode || forecastMode
                ? <>추가 컨텍스트</>
                : <>분석 대상 정보 <span className="req">*</span></>}</label>
              <textarea id="docText" rows={calcMode || ddMode || forecastMode ? 5 : 6} value={documentText} onChange={(e) => setDocumentText(e.target.value)}
                placeholder={calcMode || ddMode || forecastMode ? '정성 정보(포지셔닝·매도자 우선순위·인허가 상황 등)를 자유롭게 덧붙이면 서술 품질이 올라갑니다. 핵심 사실은 위 입력으로 확정합니다.' : meta.placeholder} />
            </div>
          </div>

          <div className="actions">
            <button type="submit" className="btn-primary" disabled={busy}>
              {busy ? '분석 중…' : `${meta.label} 분석 · ${creditLabel(toolCosts?.[type])}`}
            </button>
            <p className="hint">
              정성 정보를 자유롭게 덧붙이면 서술 품질이 올라갑니다.
              <br />
              같은 딜 이름으로 쌓으면 이력에서 함께 보입니다.
            </p>
          </div>
          {(calcMode || ddMode || forecastMode) && <TrustBadge />}
          {error && <p className="error">{error}</p>}
        </form>

        <div className="card" id="doc-result">
          <DocStageStrip dealId={currentDealId} version={historyVersion} activeRunId={result?.runId} onOpen={openPastRun} />
          {result ? (
            <DocResult res={result} label={DOC_LABEL[result.analysisType] ?? result.analysisType}
              onReport={openReport} reportBusy={reportBusy} />
          ) : (
            <ResultPreview meta={meta} />
          )}
        </div>
      </div>

      <DocHistoryPanel version={historyVersion} onOpen={openPastRun} />

      {busy && (
        <div className="analyze-overlay" role="alertdialog" aria-busy="true" aria-live="assertive" aria-label="분석 진행 중">
          <div className="analyze-modal">
            <div className="analyze-spinner" aria-hidden="true" />
            <strong className="analyze-modal-title">{meta.label} 분석 중…</strong>
            <p className="analyze-modal-sub">AI가 딜을 분석하고 있습니다 · 보통 30~60초 걸립니다. 창을 닫지 마세요.</p>
          </div>
        </div>
      )}

      {renamePending != null && (
        <div className="analyze-overlay" role="dialog" aria-modal="true" aria-label="딜 이름 변경" onClick={cancelRename}>
          <div className="confirm-modal" onClick={(e) => e.stopPropagation()}>
            <strong className="cm-title">딜 이름을 바꿀까요?</strong>
            <p className="cm-body">이 딜의 이름을 <strong>{renamePending}</strong>(으)로 변경합니다.<br />이 딜의 모든 분석에 함께 반영됩니다.</p>
            <div className="cm-actions">
              <button type="button" className="cm-cancel" onClick={cancelRename}>취소</button>
              <button type="button" className="btn-primary" onClick={() => void doRename()}>이름 변경</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

/**
 * 이 딜의 완료된 심화분석을 탭처럼 나열 - 언더라이팅 단계 탭처럼 "한 딜로 여러 분석"을 이어서 보고 전환.
 * 딜 id(PK)와 일치하는 성공 런을 유형별 최신 1건씩 pill 로. 클릭 시 재열람(무과금).
 */
function DocStageStrip({ dealId, version, activeRunId, onOpen }: {
  dealId: number | null; version: number; activeRunId?: number; onOpen: (id: number) => void
}) {
  const [runs, setRuns] = useState<RunSummary[]>([])
  useEffect(() => {
    if (dealId == null) { setRuns([]); return }
    let active = true
    const docTools = new Set(Object.keys(DOC_LABEL))
    api.runs()
      .then((list) => {
        if (!active) return
        const seen = new Set<string>()
        // api.runs() 는 최신순 → 유형별 첫 등장이 최신. 같은 딜(PK)·성공·심화툴만.
        const mine = list
          .filter((r) => r.status === 'SUCCESS' && docTools.has(r.tool) && r.dealId === dealId)
          .filter((r) => (seen.has(r.tool) ? false : (seen.add(r.tool), true)))
        setRuns(mine)
      })
      .catch(() => { if (active) setRuns([]) })
    return () => { active = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dealId, version])

  if (runs.length === 0) return null
  return (
    <div className="doc-stage-strip" role="tablist" aria-label="이 딜의 심화분석">
      <span className="dss-label">이 딜의 심화분석</span>
      <div className="dss-pills">
        {runs.map((r) => (
          <button key={r.id} type="button" role="tab" aria-selected={r.id === activeRunId}
            className={`dss-pill${r.id === activeRunId ? ' active' : ''}`} onClick={() => onOpen(r.id)}>
            {DOC_LABEL[r.tool] ?? r.tool}
          </button>
        ))}
      </div>
    </div>
  )
}

/** 결과 전 우측 패널 - 선택한 분석에 맞춘 '결과 미리보기'(고스트)로 허전함 제거 + 무엇이 나오는지 안내. */
function ResultPreview({ meta }: { meta: DocTypeMeta }) {
  return (
    <div className="rp">
      <div className="rp-top">
        <div className="empty-ico" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <path d="M14 2v6h6M9 13h6M9 17h4" />
          </svg>
        </div>
        <div>
          <h3>{meta.label} · 결과 미리보기</h3>
          <p>{meta.gives} - 왼쪽에 입력하고 실행하면 이 자리에 채워집니다.</p>
        </div>
      </div>
      <div className="rp-skeleton" aria-hidden="true">
        <div className="rp-metrics">
          {['가치평가', '가격범위', '판정'].map((k) => (
            <div className="rp-card" key={k}><span>{k}</span><i /></div>
          ))}
        </div>
        <div className="rp-verdict"><span /><i /></div>
        <div className="rp-lines">{[100, 86, 94, 72, 88].map((w, i) => <span key={i} style={{ width: `${w}%` }} />)}</div>
      </div>
    </div>
  )
}

function DocResult({ res, label, onReport, reportBusy }: {
  res: DocAnalyzeResponse; label: string; onReport: () => void; reportBusy: boolean
}) {
  const a = res.analysis
  return (
    <div className="ai-block">
      <div className="result-head">
        <span className="stage-pill">{label}</span>
        <span className="result-head-actions">
          <button type="button" className="btn-ghost btn-report" onClick={onReport} disabled={reportBusy}
            title="이 딜의 심화분석 전체를 합본 보고서로 보기 (PDF 저장은 보고서에서)">
            {reportBusy ? '보고서 여는 중…' : '보고서 보기'}
          </button>
        </span>
      </div>

      {res.calc && <CalcCard calc={res.calc} />}

      {res.marketFacts && res.marketFacts.length > 0 && <MarketFactsCard facts={res.marketFacts} />}

      {a ? (
        <>
          {a.headline && <p className="narrative"><b>{a.headline}</b></p>}
          {a.verdict && (
            <div className={`verdict ${verdictTone(a.verdict)}`}>
              <div className="v-mark">{verdictTone(a.verdict) === 'go' ? '✓' : verdictTone(a.verdict) === 'no' ? '×' : '!'}</div>
              <div>
                <div className="v-label">{a.verdict}{a.confidence ? ` · 신뢰도 ${a.confidence}` : ''}</div>
                {a.priceComment && <div className="v-reason">{a.priceComment}</div>}
              </div>
            </div>
          )}

          {a.flags && a.flags.length > 0 && <FlagList flags={a.flags} />}
          {a.recommend && <RecommendGrid recommend={a.recommend} rationale={a.rationale} />}
          {res.guideProForma && <GuideProFormaCard pf={res.guideProForma} />}
          {a.guides && a.guides.length > 0 && <TaxGuides guides={a.guides} priceVerdict={a.priceVerdict} />}
          {a.im_markdown && <Markdown md={a.im_markdown} />}
          {a.sections && a.sections.length > 0 && <Sections sections={a.sections} />}
        </>
      ) : res.analysisRaw ? (
        <section><div className="section-title">AI 분석</div><Markdown md={res.analysisRaw} /></section>
      ) : null}

      <p className="disclaimer">{a?.disclaimer ?? res.disclaimer}</p>
    </div>
  )
}

/** 실측 시장데이터 카드 - 분석에 주입된 공공데이터(실거래·공시지가·임대시장·매크로)를 출처와 함께 노출.
 *  "이 분석은 실측에 앵커링됐다"를 가시화 = AI 환각과 구분되는 확정 사실. */
const cleanDash = (s: string): string => s.replace(/—/g, '-')

/** 실거래가처럼 '; ' 로 이어진 항목을 줄 단위로 분리. 단일 항목이면 길이 1. */
function detailLines(detail: string): string[] {
  return cleanDash(detail).split(';').map((s) => s.trim()).filter(Boolean)
}

function MarketFactsCard({ facts }: { facts: MarketFact[] }) {
  return (
    <section className="calc-card mkt-facts">
      <div className="section-title">
        실측 시장데이터 <span className="calc-badge">확정 · 공공데이터</span>
      </div>
      <ul className="mkt-list">
        {facts.map((f, i) => {
          const lines = detailLines(f.detail)
          return (
            <li key={i} className="mkt-item">
              <span className="mkt-src">{cleanDash(f.source)}</span>
              {lines.length > 1 ? (
                <div className="mkt-comps">
                  {lines.map((l, k) => <span key={k} className="mkt-comp">{l}</span>)}
                </div>
              ) : (
                <span className="mkt-detail">{lines[0] ?? cleanDash(f.detail)}</span>
              )}
            </li>
          )
        })}
      </ul>
      <p className="mkt-note">위 수치는 공공 API 실측값으로, AI 추정이 아니라 분석의 근거로 직접 인용됩니다.</p>
    </section>
  )
}

/** 코드 확정 수치 카드 - BOV·개발수익성·거래상대방 실사(결정론적 공공데이터). AI 서술 위에 표기. */

function CalcCard({ calc }: { calc: DocCalc }) {
  if (isBizHealthCalc(calc)) return <BizHealthCard calc={calc} />
  if (isPriceForecastCalc(calc)) return <PriceForecastCard calc={calc} />
  if (isBovCalc(calc)) {
    const metrics: [string, string][] = [
      ['Direct Cap', `${calc.directCapValueEok} 억`],
      ['DCF', `${calc.dcfValueEok} 억`],
      ...(calc.salesCompValueEok > 0 ? [['Sales Comp', `${calc.salesCompValueEok} 억`] as [string, string]] : []),
      ['Blended BOV', `${calc.bovValueEok} 억`],
      ['가격 범위', `${calc.lowEok} ~ ${calc.highEok} 억`],
      ['Implied Cap', `${calc.impliedCapPct} %`],
    ]
    return (
      <section className="calc-card">
        <div className="section-title">코드 확정 평가 <span className="calc-badge">결정론적</span></div>
        <div className="metrics">
          {metrics.map(([k, v]) => (
            <div key={k} className={`metric${k === 'Blended BOV' ? ' hl' : ''}`}><span className="k">{k}</span><span className="v">{v}</span></div>
          ))}
        </div>
      </section>
    )
  }
  const metrics: [string, string, boolean?][] = [
    ['총사업비', `${calc.totalProjectCostEok} 억`],
    ...(calc.stabilizedValueEok > 0 ? [['Stabilized Value', `${calc.stabilizedValueEok} 억`] as [string, string]] : []),
    ['자산가치(GDV)', `${calc.grossDevelopmentValueEok} 억`],
    ['개발이익', `${calc.developmentProfitEok} 억`],
    ['Dev Margin', `${calc.profitMarginPct} %`, true],
    ...(calc.yieldOnCostPct > 0 ? [['Yield-on-Cost', `${calc.yieldOnCostPct} %`] as [string, string]] : []),
  ]
  const devTone: Record<string, string> = { GO: 'go', CONDITIONAL: 'cond', NO_GO: 'no' }
  const devLabel: Record<string, string> = { GO: 'GO', CONDITIONAL: '조건부', NO_GO: 'NO-GO' }
  return (
    <section className="calc-card">
      <div className="section-title">
        코드 확정 수익성 <span className="calc-badge">결정론적</span>
        <span className={`gl-badge ${devTone[calc.marginVerdict] ?? 'cond'}`} style={{ marginLeft: '0.5rem' }}>
          {devLabel[calc.marginVerdict] ?? calc.marginVerdict}
        </span>
      </div>
      <div className="metrics">
        {metrics.map(([k, v, hl]) => (
          <div key={k} className={`metric${hl ? ' hl' : ''}`}><span className="k">{k}</span><span className="v">{v}</span></div>
        ))}
      </div>
      {calc.sensitivity.length > 0 && (
        <table>
          <thead><tr><th>민감도</th><th>Margin(%)</th><th>개발이익(억)</th></tr></thead>
          <tbody>
            {calc.sensitivity.map((s) => (
              <tr key={s.label}><td>{s.label}</td><td className="num">{s.profitMarginPct}</td><td className="num">{s.developmentProfitEok}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

/** 매입·매각 가격 예측 - 코드 확정 밸류에이션 밴드 카드. */
function PriceForecastCard({ calc }: { calc: PriceForecastCalc }) {
  const confTone: Record<string, string> = { HIGH: 'go', MEDIUM: 'cond', LOW: 'no' }
  const fmt = (n: number) => n.toLocaleString('ko-KR')
  return (
    <section className="calc-card">
      <div className="section-title">
        코드 확정 가격 예측 <span className="calc-badge">결정론적</span>
        <span className={`gl-badge ${confTone[calc.confidence] ?? 'cond'}`} style={{ marginLeft: '0.5rem' }}>
          신뢰도 {calc.confidence}
        </span>
      </div>
      <div className="metrics">
        <div className="metric hl"><span className="k">적정 매입가</span><span className="v">{fmt(calc.buyLowEok)} ~ {fmt(calc.buyHighEok)} 억</span></div>
        <div className="metric hl"><span className="k">예상 매각가</span><span className="v">{fmt(calc.sellLowEok)} ~ {fmt(calc.sellHighEok)} 억</span></div>
        <div className="metric"><span className="k">추정가</span><span className="v">{fmt(calc.estimateEok)} 억</span></div>
        <div className="metric"><span className="k">Implied Cap</span><span className="v">{calc.impliedCapPct} %</span></div>
        {calc.incomeValueEok != null && <div className="metric"><span className="k">소득환원</span><span className="v">{fmt(calc.incomeValueEok)} 억</span></div>}
        {calc.compValueEok != null && <div className="metric"><span className="k">거래사례</span><span className="v">{fmt(calc.compValueEok)} 억</span></div>}
      </div>
    </section>
  )
}

/** 거래상대방 실사 - 공공데이터 확정 사실 카드(상태·제재·기업정보·규모). */
function BizHealthCard({ calc }: { calc: BizHealthCalc }) {
  const closed = calc.status.available && (calc.status.status?.includes('폐업') || calc.status.status?.includes('휴업'))
  const sanctioned = calc.sanctions.available && calc.sanctions.count > 0
  return (
    <section className="calc-card">
      <div className="section-title">공공데이터 확정 사실 <span className="calc-badge">실측</span></div>
      <div className="metrics">
        <div className={`metric${closed ? ' hl' : ''}`}>
          <span className="k">사업자상태</span>
          <span className="v" style={{ fontSize: '1rem' }}>{calc.status.available ? (calc.status.status || '-') : '확인 필요'}</span>
        </div>
        <div className={`metric${sanctioned ? ' hl' : ''}`}>
          <span className="k">부정당제재</span>
          <span className="v" style={{ fontSize: '1rem' }}>{calc.sanctions.available ? (calc.sanctions.count === 0 ? '없음' : `${calc.sanctions.count}건`) : '확인 필요'}</span>
        </div>
        {calc.corp.available && (
          <div className="metric"><span className="k">기업정보</span>
            <span className="v" style={{ fontSize: '0.95rem' }}>{[calc.corp.repName && `대표 ${calc.corp.repName}`, calc.corp.estbDate && `설립 ${calc.corp.estbDate}`].filter(Boolean).join(' · ') || calc.corp.corpName || '-'}</span>
          </div>
        )}
        {calc.pension.available && calc.pension.members && (
          <div className="metric"><span className="k">규모(국민연금)</span><span className="v" style={{ fontSize: '1rem' }}>가입 {calc.pension.members}명</span></div>
        )}
      </div>
      {sanctioned && (
        <table>
          <thead><tr><th>제재기간</th><th>기관</th><th>근거</th></tr></thead>
          <tbody>
            {calc.sanctions.items.slice(0, 5).map((s, i) => (
              <tr key={i}><td>{s.from}~{s.to}</td><td>{s.org}</td><td>{s.basis}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

function RecommendGrid({ recommend, rationale }: { recommend: Record<string, number>; rationale?: string }) {
  const keys = Object.keys(RECOMMEND_LABELS).filter((k) => recommend[k] != null)
  return (
    <section>
      <div className="section-title">권장 입력 가정</div>
      <div className="metrics">
        {keys.map((k) => (
          <div key={k} className="metric"><span className="k">{RECOMMEND_LABELS[k]}</span><span className="v">{recommend[k]}</span></div>
        ))}
      </div>
      {rationale && <div className="guideline"><Markdown md={rationale} /></div>}
    </section>
  )
}

/**
 * 입력가이드 예상 지표 - 권장 가정으로 결정론 ProForma 를 돌린 결과(IRR·EM·DSCR·시나리오·Exit Cap 민감도).
 * 수치는 AI 아닌 코드 계산이라 신뢰 가능. "권장 가정 넣으면 뭐가 나오나"를 즉시 보여줘 가이드의 가치를 높인다.
 */
function GuideProFormaCard({ pf }: { pf: GuideProForma }) {
  const pct = (n: number) => `${n.toFixed(1)}%`
  const mult = (n: number) => `${n.toFixed(2)}x`
  const eok = (n: number) => `${Math.round(n).toLocaleString()}억`
  return (
    <section className="guide-pf">
      <div className="section-title">권장 가정 기준 예상 지표 <span className="gpf-tag">코드 계산 · AI 아님</span></div>
      <div className="metrics gpf-metrics">
        <div className="metric hi"><span className="k">Levered IRR</span><span className="v">{pct(pf.leveredIrrPct)}</span></div>
        <div className="metric hi"><span className="k">Equity Multiple</span><span className="v">{mult(pf.equityMultiple)}</span></div>
        <div className="metric"><span className="k">최소 DSCR</span><span className="v">{pf.minDscr != null ? pf.minDscr.toFixed(2) : '-'}</span></div>
        <div className="metric"><span className="k">Going-in Cap</span><span className="v">{pct(pf.goingInCapPct)}</span></div>
      </div>
      <div className="gpf-sub">
        <span>총투자비 {eok(pf.totalInvestEok)}</span>
        <span>Equity {eok(pf.equityEok)}</span>
        <span>대출 {eok(pf.debtEok)}</span>
        <span>Exit 가치 {eok(pf.exitValueEok)}</span>
      </div>
      <div className="md-table-wrap">
        <table className="md-table">
          <thead><tr><th>시나리오</th><th className="md-num">Levered IRR</th><th className="md-num">Equity Multiple</th><th className="md-num">최소 DSCR</th></tr></thead>
          <tbody>
            {pf.scenarios.map((s) => (
              <tr key={s.name}><td>{s.name}</td><td className="md-num">{pct(s.leveredIrrPct)}</td><td className="md-num">{mult(s.equityMultiple)}</td><td className="md-num">{s.minDscr.toFixed(2)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="md-table-wrap">
        <table className="md-table">
          <thead><tr><th>Exit Cap 민감도</th><th className="md-num">Levered IRR</th><th className="md-num">Equity Multiple</th></tr></thead>
          <tbody>
            {pf.sensitivity.map((s) => (
              <tr key={s.exitCapPct}><td>{pct(s.exitCapPct)}</td><td className="md-num">{pct(s.leveredIrrPct)}</td><td className="md-num">{mult(s.em)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="guideline">위 권장 가정을 넣었을 때의 결정론 계산 결과입니다(AI 아님). 가정을 조정해 정밀 심사하려면 언더라이팅에서 실행하세요.</p>
    </section>
  )
}

function TaxGuides({ guides, priceVerdict }: { guides: TaxGuide[]; priceVerdict?: string }) {
  return (
    <section>
      <div className="section-title">
        진단 {priceVerdict && <span className={`sev-badge ${verdictTone(priceVerdict)}`}>가격 {priceVerdict}</span>}
      </div>
      {guides.map((g, i) => (
        <div key={i} className="risk" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4 }}>
          <span className="r-name flag-line">
            {g.kind && <span className={`sev-badge ${g.kind === '절세' ? 'go' : g.kind === '주의' ? 'no' : 'cond'}`}>{g.kind}</span>}
            <span>{g.title}</span>
            <SevBadge v={g.impact} />
          </span>
          {g.detail && <span className="muted">{g.detail}</span>}
          {g.basis && <span className="guideline" style={{ margin: 0 }}>{g.basis}</span>}
        </div>
      ))}
    </section>
  )
}

function Sections({ sections }: { sections: DocSection[] }) {
  return (
    <>
      {sections.map((s, i) => (
        <section key={i}>
          {s.title && <div className="section-title">{s.title}</div>}
          {s.text && <p className="narrative">{s.text}</p>}
          {s.bullets && s.bullets.length > 0 && <ul>{s.bullets.map((b, j) => <li key={j}>{b}</li>)}</ul>}
          {s.table && s.table.headers && <DataTable headers={s.table.headers} rows={s.table.rows} />}
        </section>
      ))}
    </>
  )
}

function DocHistoryPanel({ version, onOpen }: { version: number; onOpen: (id: number) => void }) {
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const docTools = new Set(Object.keys(DOC_LABEL))

  useEffect(() => {
    let active = true
    api.runs()
      .then((list) => { if (active) setRuns(list.filter((r) => docTools.has(r.tool))) })
      .catch((err: unknown) => { if (active) setError(err instanceof ApiError ? err.message : '이력 조회 실패') })
    return () => { active = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [version])

  return (
    <div className="card">
      <div className="section-title">심화 분석 이력</div>
      {error && <p className="error">{error}</p>}
      {runs.length === 0 ? (
        <p className="hist-empty">아직 심화 분석 이력이 없습니다.</p>
      ) : (
        <table>
          <thead><tr><th>딜 ID</th><th>딜</th><th>유형</th><th>상태</th><th>일시</th><th></th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td><span className="hist-deal-id" title="딜 고유 번호 - 이름이 달라도 같은 번호면 같은 딜입니다">#{r.dealId ?? '-'}</span></td>
                <td>{r.dealName ?? '(이름없음)'}</td>
                <td>{DOC_LABEL[r.tool] ?? r.tool}</td>
                <td><span className={r.status === 'SUCCESS' ? 'st-ok' : 'st-fail'}>{r.status === 'SUCCESS' ? '성공' : r.status === 'FAILED' ? '실패' : r.status}</span></td>
                <td className="num">{r.createdAt ? new Date(r.createdAt).toLocaleString('ko-KR') : '-'}</td>
                <td>{r.status === 'SUCCESS' && <button type="button" className="btn-link" onClick={() => onOpen(r.id)}>보기</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
