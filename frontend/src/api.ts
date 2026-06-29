// 백엔드 REST 클라이언트. ApiResponse 엔벨로프({success,data,error})를 언랩하고
// JWT 토큰을 자동 첨부한다. 개발 시 /api 는 Vite 프록시로 Spring(8080)에 전달된다.

export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
}

export type UserRole = 'USER' | 'ADMIN'

export interface AuthResult {
  token: string
  email: string
  plan: string
  role: UserRole
  creditBalance: number
  emailVerified: boolean
}

export interface Me {
  userId: number
  tenantId: number
  email: string
  role: UserRole
  creditBalance: number
  emailVerified: boolean
}

export type CreditReason = 'SIGNUP_GRANT' | 'AI_ANALYSIS' | 'PURCHASE'

export interface CreditHistoryItem {
  id: number
  delta: number
  reason: CreditReason
  createdAt: string
}

export interface BillingHistory {
  plan: 'FREE' | 'PAID'
  creditBalance: number
  entries: CreditHistoryItem[]
}

export interface YearRow {
  year: number
  noi: number
  interest: number
  capex: number
  leveredCf: number
  dscr: number
  cocPct: number
}

export interface Sensitivity {
  exitCapPct: number
  saleValueEok: number
  leveredIrrPct: number
  em: number
}

export interface Scenario {
  name: string
  rentGrowthPct: number
  exitCapPct: number
  leveredIrrPct: number
  equityMultiple: number
  minDscr: number
  exitValueEok: number
}

export interface ProForma {
  totalInvestEok: number
  equityEok: number
  debtEok: number
  annualInterestEok: number
  proForma: YearRow[]
  exitCapPct: number
  exitNoiEok: number
  exitValueEok: number
  netSaleEok: number
  exitEquityEok: number
  leveredIrrPct: number
  equityMultiple: number
  unleveredIrrPct: number
  goingInCapPct: number
  yieldOnCostPct: number
  exitCapSensitivity: Sensitivity[]
}

/** 가이드라인 적합성 — 코드가 임계값과 대조한 결정론적 판정. */
export type CheckStatus = 'PASS' | 'WARN' | 'FAIL'
export interface GuidelineCheck {
  metric: string
  actual: string
  threshold: string
  status: CheckStatus
}
export interface GuidelineSummary {
  checks: GuidelineCheck[]
  pass: number
  warn: number
  fail: number
}

export interface ProFormaResponse {
  proForma: ProForma
  scenarios: Scenario[]
  guidelineChecks: GuidelineSummary
  disclaimer: string
}

export interface RiskItem {
  risk: string
  impact: string
}

export interface Analysis {
  summary?: string
  guideline_check?: string
  key_drivers?: string[]
  key_risks?: RiskItem[]
  recommendation?: string
  recommendation_reason?: string
}

export interface AnalyzeResponse {
  runId: number
  analysisType?: string
  proForma: ProForma
  scenarios: Scenario[]
  guidelineChecks: GuidelineSummary
  analysis?: Analysis | null
  analysisRaw?: string | null
  provider: string
  creditBalance: number
  disclaimer: string
}

/** IM 분석 단계. 백엔드 AnalysisType enum 과 일치. */
export type AnalysisType = 'SCREENING' | 'MARKET_STUDY' | 'UNDERWRITING' | 'IC_MEMO'

export interface RunSummary {
  id: number
  dealName: string | null
  tool: string
  status: string
  createdAt: string | null
}

/** 저장된 분석 결과 페이로드(= AnalyzeResponse 와 동일 구조). */
export interface RunResult {
  proForma: ProForma
  scenarios: Scenario[]
  guidelineChecks?: GuidelineSummary
  analysis?: Analysis | null
  analysisRaw?: string | null
  provider?: string
  disclaimer: string
}

export interface RunDetail {
  id: number
  dealName: string | null
  tool: string
  status: string
  createdAt: string | null
  request: UnderwriteInput | null
  result: RunResult | null
}

export interface UnderwriteInput {
  dealName?: string
  assetType?: string
  location?: string
  notes?: string
  askingPriceEok: number
  noiEok: number
  ltvPct: number
  loanRatePct: number
  exitCapPct: number
  holdYears?: number
  rentGrowthPct?: number
}

/** 문서/텍스트 기반 분석 단계(매입 추가분 + 신규 트랙). 백엔드 DocAnalysisType 과 일치. */
export type DocAnalysisType =
  | 'UNDERWRITING_GUIDE'
  | 'BUILDING_RESEARCH'
  | 'TAX_PRICE_DIAGNOSIS'
  | 'BOV'
  | 'AM_QUARTERLY'
  | 'HOLD_SELL_REFI'
  | 'DEV_FEASIBILITY'
  | 'MARKET_RESEARCH_DEEP'
  | 'COUNTERPARTY_DD'
  | 'PRICE_FORECAST'

/** 매각 BOV 3-Method 평가 입력(코드 확정 계산). 할인율·Exit Cap 미입력 시 서버가 자산유형 기본값으로 보정. */
export interface BovInput {
  noiEok: number
  marketCapPct: number
  discountRatePct?: number
  exitCapPct?: number
  holdYears?: number
  rentGrowthPct?: number
  salesCompValueEok?: number
}

/** 개발 타당성 수익성 입력. GDV = 분양수입(있으면) 또는 Stabilized Value(NOI/ExitCap). */
export interface DevFeasibilityInput {
  landCostEok: number
  constructionCostEok: number
  financingCostEok?: number
  otherCostEok?: number
  contingencyPct?: number
  stabilizedNoiEok?: number
  exitCapPct?: number
  salesRevenueEok?: number
}

/** 가격 예측 입력 — NOI(소득환원) 또는 연면적(거래사례) 중 하나는 필요. 시장Cap 미입력 시 자산유형 기본값. */
export interface PriceForecastInput {
  noiEok?: number
  marketCapPct?: number
  areaPyeong?: number
}

/** 딜 추출 결과 — 기사/딜 텍스트에서 뽑은 구조화 필드(분석 폼 프리필용). 모르는 값은 null. */
export interface DealExtract {
  dealName: string | null
  buildingName: string | null
  assetType: string | null
  location: string | null
  parcelAddress: string | null
  seller: string | null
  buyer: string | null
  preferredBidder: string | null
  dealPriceEok: number | null
  noiEok: number | null
  areaPyeong: number | null
  marketCapPct: number | null
  tenantSummary: string | null
  summary: string | null
  confidence: string | null
}

export interface DealExtractResponse {
  extract: DealExtract | null
  raw: string | null
  provider: string
}

/** 시장 인텔리전스 피드 카드. sourceText = '이 딜 분석하기' 진입용 원문. */
export interface MarketFeedItem {
  id: number
  title: string
  summary: string | null
  assetType: string | null
  location: string | null
  sourceText: string | null
  sourceUrl: string | null
  publishedAt: string | null
}

/** 관리자 피드 생성 입력. */
export interface MarketFeedInput {
  title: string
  summary?: string
  assetType?: string
  location?: string
  sourceText?: string
  sourceUrl?: string
  publishedAt?: string
}

export interface DocAnalyzeInput {
  dealName?: string
  assetType?: string
  location?: string
  documentText?: string
  bov?: BovInput
  dev?: DevFeasibilityInput
  forecast?: PriceForecastInput
  bizNo?: string
  counterpartyName?: string
  parcelAddress?: string
}

/** 코드 확정 BOV 평가 결과(결정론적). */
export interface BovCalc {
  directCapValueEok: number
  dcfValueEok: number
  salesCompValueEok: number
  bovValueEok: number
  lowEok: number
  highEok: number
  impliedCapPct: number
}

export interface DevSensitivity {
  label: string
  profitMarginPct: number
  developmentProfitEok: number
}

/** 코드 확정 개발 수익성 결과(결정론적). */
export interface DevCalc {
  baseCostEok: number
  contingencyEok: number
  totalProjectCostEok: number
  stabilizedValueEok: number
  grossDevelopmentValueEok: number
  developmentProfitEok: number
  profitMarginPct: number
  yieldOnCostPct: number
  marginVerdict: 'GO' | 'CONDITIONAL' | 'NO_GO'
  sensitivity: DevSensitivity[]
}

/** 거래상대방 실사 결과(결정론적 공공데이터 사실). */
export interface BizStatusInfo { available: boolean; status?: string; taxType?: string; closedDate?: string }
export interface SanctionItem { from: string; to: string; org: string; basis: string }
export interface SanctionResultInfo { available: boolean; count: number; items: SanctionItem[] }
export interface CorpInfoData { available: boolean; corpName?: string; repName?: string; estbDate?: string; industry?: string }
export interface PensionInfoData { available: boolean; workplaceName?: string; members?: string; monthlyNotice?: string; industry?: string }
export interface BizHealthCalc {
  bizNo?: string
  name?: string
  status: BizStatusInfo
  sanctions: SanctionResultInfo
  corp: CorpInfoData
  pension: PensionInfoData
}

/** 코드 산출 가격 예측 결과(결정론적 밸류에이션 밴드). */
export interface PriceMethod { name: string; valueEok: number }
export interface PriceForecastCalc {
  incomeValueEok: number | null
  compValueEok: number | null
  estimateEok: number
  buyLowEok: number
  buyHighEok: number
  sellLowEok: number
  sellHighEok: number
  impliedCapPct: number
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  methods: PriceMethod[]
}

export type DocCalc = BovCalc | DevCalc | BizHealthCalc | PriceForecastCalc

/** calc 가 BOV 결과인지 판별(렌더 분기용). */
export function isBovCalc(c: DocCalc): c is BovCalc {
  return (c as BovCalc).bovValueEok !== undefined
}

/** calc 가 거래상대방 실사 결과인지 판별. */
export function isBizHealthCalc(c: DocCalc): c is BizHealthCalc {
  return (c as BizHealthCalc).status !== undefined && (c as BizHealthCalc).sanctions !== undefined
}

/** calc 가 가격 예측 결과인지 판별. */
export function isPriceForecastCalc(c: DocCalc): c is PriceForecastCalc {
  return (c as PriceForecastCalc).estimateEok !== undefined && (c as PriceForecastCalc).buyLowEok !== undefined
}

export interface SectionTable {
  headers: string[]
  rows: string[][]
}

/** 신규 트랙 공통 섹션(택1: text | bullets | table). */
export interface DocSection {
  title?: string
  text?: string
  bullets?: string[]
  table?: SectionTable
}

export interface TaxGuide {
  kind?: string
  title?: string
  detail?: string
  basis?: string
}

/** 단계별 출력 합집합 — 단계에 따라 일부 필드만 채워진다. */
export interface DocAnalysis {
  headline?: string
  verdict?: string
  confidence?: string
  sections?: DocSection[]
  // BUILDING_RESEARCH
  im_markdown?: string
  // UNDERWRITING_GUIDE
  recommend?: Record<string, number>
  rationale?: string
  // TAX_PRICE_DIAGNOSIS
  priceVerdict?: string
  priceComment?: string
  guides?: TaxGuide[]
  disclaimer?: string
}

export interface DocAnalyzeResponse {
  runId: number
  analysisType: string
  analysis?: DocAnalysis | null
  analysisRaw?: string | null
  calc?: DocCalc | null
  provider: string
  creditBalance: number
  disclaimer: string
}

/**
 * API 오리진. 기본은 빈 문자열 = same-origin(상대경로 /api, dev 는 Vite 프록시).
 * API 가 별도 호스트면 VITE_API_BASE 로 지정(예: https://api.aixnative.com — 끝에 /api 붙이지 않음).
 */
const API_BASE: string = (import.meta.env.VITE_API_BASE as string | undefined)?.replace(/\/$/, '') ?? ''

const TOKEN_KEY = 'aixnative.token'

export const tokenStore = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string): void => localStorage.setItem(TOKEN_KEY, token),
  clear: (): void => localStorage.removeItem(TOKEN_KEY),
}

/** HTTP error that carries the response status (e.g. 402 paywall, 503 AI unavailable). */
export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = tokenStore.get()
  const res = await fetch(API_BASE + path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })

  let body: ApiResponse<T> | null = null
  try {
    body = (await res.json()) as ApiResponse<T>
  } catch {
    throw new ApiError(res.status, `서버 응답을 해석할 수 없습니다 (${res.status})`)
  }

  if (!res.ok || !body.success) {
    throw new ApiError(res.status, body?.error ?? `요청 실패 (${res.status})`)
  }
  return body.data as T
}

export const api = {
  signup: (email: string, password: string): Promise<AuthResult> =>
    request('/api/auth/signup', { method: 'POST', body: JSON.stringify({ email, password }) }),

  login: (email: string, password: string): Promise<AuthResult> =>
    request('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),

  me: (): Promise<Me> => request('/api/auth/me'),

  /** 미인증 사용자의 인증 메일 재발송. */
  resendVerification: (): Promise<{ sent: boolean }> =>
    request('/api/auth/resend-verification', { method: 'POST' }),

  proforma: (input: UnderwriteInput): Promise<ProFormaResponse> =>
    request('/api/underwriting/proforma', { method: 'POST', body: JSON.stringify(input) }),

  analyze: (input: UnderwriteInput): Promise<AnalyzeResponse> =>
    request('/api/underwriting/analyze', { method: 'POST', body: JSON.stringify(input) }),

  analyzeStage: (type: AnalysisType, input: UnderwriteInput): Promise<AnalyzeResponse> =>
    request(`/api/underwriting/analyze/${type}`, { method: 'POST', body: JSON.stringify(input) }),

  analyzeDoc: (type: DocAnalysisType, input: DocAnalyzeInput): Promise<DocAnalyzeResponse> =>
    request(`/api/underwriting/analyze-doc/${type}`, { method: 'POST', body: JSON.stringify(input) }),

  /** 무료 — 기사/딜 텍스트 → 구조화 추출(분석 폼 프리필). 크레딧 미차감. */
  extractDeal: (text: string): Promise<DealExtractResponse> =>
    request('/api/underwriting/extract-deal', { method: 'POST', body: JSON.stringify({ text }) }),

  /** 투자 보고서 HTML(원문). 인증 헤더가 필요하므로 fetch 로 받아 새 창에 띄운다. */
  reportHtml: async (runId: number): Promise<string> => {
    const token = tokenStore.get()
    const res = await fetch(`${API_BASE}/api/underwriting/report/${runId}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw new ApiError(res.status, `보고서를 불러오지 못했습니다 (${res.status})`)
    return res.text()
  },

  runs: (): Promise<RunSummary[]> => request('/api/underwriting/runs'),

  run: (id: number): Promise<RunDetail> => request(`/api/underwriting/runs/${id}`),

  history: (): Promise<BillingHistory> => request('/api/billing/history'),

  /** 시장 인텔리전스 피드 — 최신 카드(인증 사용자). */
  marketFeed: (limit = 30): Promise<MarketFeedItem[]> =>
    request(`/api/market-feed?limit=${limit}`),

  /** 관리자 — 피드 카드 추가. */
  marketFeedCreate: (input: MarketFeedInput): Promise<MarketFeedItem> =>
    request('/api/admin/market-feed', { method: 'POST', body: JSON.stringify(input) }),

  /** 관리자 — 피드 카드 삭제. */
  marketFeedDelete: (id: number): Promise<{ deleted: boolean }> =>
    request(`/api/admin/market-feed/${id}`, { method: 'DELETE' }),
}
