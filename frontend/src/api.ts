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

export type CreditReason = 'SIGNUP_GRANT' | 'AI_ANALYSIS' | 'PURCHASE' | 'ADMIN_ADJUST'

export type UserStatus = 'ACTIVE' | 'DISABLED'

export interface AdminUser {
  id: number
  email: string
  tenantId: number
  plan: 'FREE' | 'PAID'
  role: UserRole
  status: UserStatus
  emailVerified: boolean
  creditBalance: number
  createdAt: string | null
}

export interface AdminRun {
  id: number
  tenantId: number
  ownerUserId: number
  ownerEmail: string | null
  tool: string
  status: string
  dealName: string | null
  createdAt: string | null
}

export interface AdminRunDetail extends AdminRun {
  requestJson: string | null
  resultJson: string | null
}

/** 운영 대시보드 집계 지표. */
export interface AdminStats {
  users: { total: number; verified: number; admin: number; paid: number; newToday: number; new7d: number }
  runs: { total: number; success: number; today: number; last7d: number; byTool: Record<string, number> }
  credits: { granted: number; purchased: number; adminAdjust: number; spent: number }
  payments: { confirmedCount: number; totalKrw: number }
}

/** 관리자 크레딧 내역 1행 - 전 사용자 원장. */
export interface AdminCreditEntry {
  id: number
  tenantId: number
  userId: number
  ownerEmail: string | null
  delta: number
  reason: CreditReason
  ref: string | null
  createdAt: string | null
}

export interface NewsSubscriber {
  email: string
  active: boolean
  createdAt: string | null
}

export interface NewsletterSendLogEntry {
  email: string
  subject: string | null
  status: string
  sentAt: string | null
}

export interface CreditHistoryItem {
  id: number
  delta: number
  reason: CreditReason
  /** 변동 출처/경로(선택) - 충전 수단·금액, 관리자 조정 식별 등. */
  ref?: string | null
  createdAt: string
}

export interface BillingHistory {
  plan: 'FREE' | 'PAID'
  creditBalance: number
  entries: CreditHistoryItem[]
}

/** 분석별 크레딧 단가표 + 가입 무료 지급량. 키 = 분석유형 id(예: UNDERWRITING, BOV, MARKET_DEEP_REPORT). */
export interface Pricing {
  toolCosts: Record<string, number>
  freeSignupCredits: number
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

/** 가이드라인 적합성 - 코드가 임계값과 대조한 결정론적 판정. */
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

/** 스크리닝 핵심지표(9종). AI가 IM에서 추출. */
export interface ScreeningMetrics {
  asking_price_eok?: number | null
  price_per_pyeong_manwon?: number | null
  noi_eok?: number | null
  cap_rate_pct?: number | null
  occupancy_pct?: number | null
  walt_yr?: number | null
  top1_tenant_pct?: number | null
  loss_to_lease_pct?: number | null
  opex_ratio_pct?: number | null
}

/** 벤치마크 대조 1행 - 신호등(GREEN/YELLOW/RED). */
export interface BenchmarkEval {
  metric: string
  value?: string | number | null
  guideline?: string
  rating?: string
}

/** 스크리닝 Red Flag - 심각도 + 검증 필요사항. */
export interface RedFlag {
  code?: string
  flag: string
  impact?: string
  verify?: string
}

/** 시장조사 가정 검증 1행 - verdict G/Y/R. */
export interface AssumptionCheck {
  assumption: string
  market?: string
  verdict?: string
}

/** 시장조사 거래 사례 1행. */
export interface Comp {
  name?: string
  region?: string
  price_per_pyeong_manwon?: number | null
  cap_rate_pct?: number | null
}

/** 투심메모 리스크 매트릭스 1행. */
export interface RiskMatrixItem {
  risk: string
  likelihood?: string
  impact?: string
  mitigation?: string
}

/** 투심메모 Exec Summary 요약표. */
export interface ExecSummary {
  asset?: string
  price?: string
  strategy?: string
  expected_return?: string
  recommendation?: string
}

/**
 * 단계별 AI 결과. 단계마다 사용하는 필드가 다르므로 모두 옵셔널.
 * UNDERWRITING / SCREENING / MARKET_STUDY / IC_MEMO 필드를 한 타입에 합집합으로 정의한다.
 */
export interface Analysis {
  // UNDERWRITING
  summary?: string
  guideline_check?: string
  strengths?: string[]
  downside?: string
  key_drivers?: string[]
  key_risks?: RiskItem[]
  recommendation?: string
  recommendation_reason?: string
  confidence?: string | number
  // SCREENING
  asset?: Record<string, unknown>
  metrics?: ScreeningMetrics
  benchmark_eval?: BenchmarkEval[]
  key_points?: string[]
  red_flags?: RedFlag[]
  green_flags?: string[]
  verdict?: string
  verdict_reason?: string
  conditions?: string[]
  next_steps?: string[]
  thesis?: string
  investment_thesis?: string
  // MARKET_STUDY
  region?: string
  /** 신규 스키마는 불릿 배열, 구버전은 문자열. StageAnalysis 가 둘 다 렌더. */
  fundamentals?: string | string[]
  assumption_check?: AssumptionCheck[]
  comps?: Comp[]
  macro?: string
  house_view?: string
  house_view_reason?: string
  conclusion?: string
  // IC_MEMO
  exec_summary?: ExecSummary
  highlights?: string[]
  risk_matrix?: RiskMatrixItem[]
  lp_alignment?: string
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
  marketFacts?: MarketFact[] | null
  creditBalance: number
  disclaimer: string
}

/** IM 분석 단계. 백엔드 AnalysisType enum 과 일치. */
export type AnalysisType = 'SCREENING' | 'MARKET_STUDY' | 'UNDERWRITING' | 'IC_MEMO'

/** 한 딜의 완료된 단계 1건 - 합본 탭 화면용. result 는 저장된 결과(= RunResult). */
export interface DealStage {
  analysisType: AnalysisType
  runId: number
  request: UnderwriteInput | null
  result: RunResult | null
}

/** 한 딜에 대해 완료된 파이프라인 단계 모음. */
export interface DealStagesResponse {
  dealName: string | null
  stages: DealStage[]
}

/** 중복 분석 사전 확인 - 동일 입력으로 최근 같은 단계를 실행했는지. */
export interface DuplicateCheck {
  duplicate: boolean
  lastRunId?: number | null
  lastRunAt?: string | null
  withinMinutes: number
}

export interface RunSummary {
  id: number
  dealName: string | null
  tool: string
  status: string
  createdAt: string | null
}

/** 내 딜 대시보드 항목 - 딜명으로 집계한 요약. */
export interface DealSummary {
  dealName: string
  assetType: string | null
  location: string | null
  runCount: number
  completedStages: string[]
  advancedCount: number
  lastActivityAt: string | null
  anchorRunId: number
  hasReport: boolean
  /** 언더라이팅 입력 단계가 있어 '이어서 분석'이 가능한가. */
  canContinue: boolean
  /** 딜이 아니라 AI 심층 시장 분석 리포트인가('이어서 분석' 대신 '데이터 보기'). */
  isMarketReport: boolean
}

/** 저장된 분석 결과 페이로드(= AnalyzeResponse 와 동일 구조). */
export interface RunResult {
  proForma: ProForma
  scenarios: Scenario[]
  guidelineChecks?: GuidelineSummary
  analysis?: Analysis | null
  analysisRaw?: string | null
  provider?: string
  marketFacts?: MarketFact[] | null
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

/** 가격 예측 입력 - NOI(소득환원) 또는 연면적(거래사례) 중 하나는 필요. 시장Cap 미입력 시 자산유형 기본값. */
export interface PriceForecastInput {
  noiEok?: number
  marketCapPct?: number
  areaPyeong?: number
}

/** 딜 추출 결과 - 기사/딜 텍스트에서 뽑은 구조화 필드(분석 폼 프리필용). 모르는 값은 null. */
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
  /** 출처: 'ADMIN' | 'RSS:<매체>' | 'GOOGLE_NEWS'. */
  origin: string | null
}

/** 피드 페이지 - 더보기(아카이브) 페이지네이션. */
export interface MarketFeedPage {
  items: MarketFeedItem[]
  page: number
  hasMore: boolean
}

/** 관심 딜(찜) - 저장된 카드 스냅샷. */
export interface DealWatch {
  id: number
  feedItemId: number
  title: string
  summary: string | null
  assetType: string | null
  location: string | null
  sourceText: string | null
  sourceUrl: string | null
  createdAt: string | null
}

/** 마켓 브리핑(AI 다이제스트) - 뉴스레터 강점. */
export interface BriefingSection { topic: string | null; summary: string | null; impact: string | null }
export interface BriefingWatch { item: string | null; why: string | null }
export interface BriefingRisk { signal: string | null; severity: string | null; mitigation: string | null }
export interface MarketBriefing {
  id: number
  briefingDate: string | null
  headline: string | null
  outlook: string | null
  sections: BriefingSection[]
  watchlist: BriefingWatch[]
  risks: BriefingRisk[]
  articleCount: number | null
  provider: string | null
  generatedAt: string | null
}
export interface BriefingHistoryItem {
  id: number
  briefingDate: string | null
  headline: string | null
  articleCount: number | null
  generatedAt: string | null
}

/** AI 심층 시장 리포트(크레딧 소비, Claude). */
export interface DeepReportSection { title: string | null; body: string | null; bullets?: string[] | null }
export interface DeepReportPick {
  title: string | null
  why: string | null
  conviction: string | null
  risk: string | null
}
export interface DeepSector {
  name: string | null
  stance: string | null
  score: number | null
  note: string | null
}
export interface DeepScenario { name: string | null; narrative: string | null }
export interface DeepReportHistoryItem {
  id: number
  headline: string | null
  generatedAt: string | null
}
export interface MarketDeepReport {
  headline: string | null
  summary: string | null
  marketTempScore: number | null
  marketTempLabel: string | null
  sectors: DeepSector[]
  scenarios: DeepScenario[]
  sections: DeepReportSection[]
  picks: DeepReportPick[]
  contrarian: string | null
  provider: string
  creditBalance: number
  disclaimer: string
}

/** 자동 수집 실행 결과. */
export interface IngestReport {
  fetched: number
  afterFilter: number
  inserted: number
  skippedDuplicate: number
  briefingGenerated: boolean
  briefingProvider: string | null
  errors: string[]
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
  impact?: string
  title?: string
  detail?: string
  basis?: string
}

/** 분석 공통 플래그 - 심각도 표시(HIGH/MEDIUM/LOW). sections 계약 트랙 공용. */
export interface AnalysisFlag {
  label: string
  severity?: string
}

/** 단계별 출력 합집합 - 단계에 따라 일부 필드만 채워진다. */
export interface DocAnalysis {
  headline?: string
  verdict?: string
  confidence?: string
  /** 주요 리스크·결격·체크 사유 + 심각도(sections 계약 트랙 공통). */
  flags?: AnalysisFlag[]
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

/** 분석에 주입된 실측 시장데이터 한 건(공공 API 출처). */
export interface MarketFact {
  source: string
  detail: string
}

export interface DocAnalyzeResponse {
  runId: number
  analysisType: string
  analysis?: DocAnalysis | null
  analysisRaw?: string | null
  calc?: DocCalc | null
  /** 실측 시장데이터(없으면 빈 배열). "실측·확정" 카드로 노출. */
  marketFacts?: MarketFact[]
  provider: string
  creditBalance: number
  disclaimer: string
}

// ── 결제(크레딧 충전, 토스페이먼츠) ──────────────────────────────────────────
/** 판매 팩(가격표). */
export interface CreditPack {
  id: string
  credits: number
  amountKrw: number
  label: string
}
/** 결제 SDK 초기화용 - clientKey + 결제 활성 여부(secretKey 미설정 시 false). */
export interface PaymentConfig {
  clientKey: string
  configured: boolean
}
/** 주문 생성 결과 - 결제창에 넘길 서버 권위 값. */
export interface CreateOrderResponse {
  orderId: string
  orderName: string
  amountKrw: number
  customerKey: string
}
/** 승인 결과 - 충전 후 잔액. */
export interface ConfirmResponse {
  credits: number
  creditBalance: number
  orderName: string
}
export type PaymentStatus = 'PENDING' | 'CONFIRMED' | 'FAILED' | 'CANCELED'
/** 결제 이력 1건. */
export interface PaymentHistoryItem {
  orderId: string
  packLabel: string
  credits: number
  amountKrw: number
  status: PaymentStatus
  method: string | null
  approvedAt: string | null
  createdAt: string | null
}

/**
 * API 오리진. 기본은 빈 문자열 = same-origin(상대경로 /api, dev 는 Vite 프록시).
 * API 가 별도 호스트면 VITE_API_BASE 로 지정(예: https://api.aixnative.com - 끝에 /api 붙이지 않음).
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
  signup: (email: string, password: string, agreedTerms: boolean, marketingOptIn = false): Promise<AuthResult> =>
    request('/api/auth/signup', { method: 'POST', body: JSON.stringify({ email, password, agreedTerms, marketingOptIn }) }),

  login: (email: string, password: string): Promise<AuthResult> =>
    request('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),

  me: (): Promise<Me> => request('/api/auth/me'),

  /** 미인증 사용자의 인증 메일 재발송. */
  resendVerification: (): Promise<{ sent: boolean }> =>
    request('/api/auth/resend-verification', { method: 'POST' }),

  /** 비밀번호 찾기 - 가입 이메일로 재설정 링크 발송. 계정 존재 여부와 무관하게 동일 응답. */
  forgotPassword: (email: string): Promise<{ sent: boolean }> =>
    request('/api/auth/forgot-password', { method: 'POST', body: JSON.stringify({ email }) }),

  /** 비밀번호 재설정 - 메일 링크의 토큰 + 새 비밀번호. */
  resetPassword: (token: string, newPassword: string): Promise<{ reset: boolean }> =>
    request('/api/auth/reset-password', { method: 'POST', body: JSON.stringify({ token, newPassword }) }),

  proforma: (input: UnderwriteInput): Promise<ProFormaResponse> =>
    request('/api/underwriting/proforma', { method: 'POST', body: JSON.stringify(input) }),

  /** 무인증 - 공개 ProForma 계산기(리드마그넷). 크레딧·저장·로그인 없음. */
  publicProforma: (input: UnderwriteInput): Promise<ProFormaResponse> =>
    request('/api/public/proforma', { method: 'POST', body: JSON.stringify(input) }),

  /** 무인증 - 비회원 리드 캡처(이메일). source 로 유입 도구 구분. */
  captureLead: (email: string, source = 'FREE_PROFORMA', marketingOptIn = false): Promise<{ captured: boolean }> =>
    request('/api/public/lead', { method: 'POST', body: JSON.stringify({ email, source, marketingOptIn }) }),

  analyze: (input: UnderwriteInput): Promise<AnalyzeResponse> =>
    request('/api/underwriting/analyze', { method: 'POST', body: JSON.stringify(input) }),

  analyzeStage: (type: AnalysisType, input: UnderwriteInput): Promise<AnalyzeResponse> =>
    request(`/api/underwriting/analyze/${type}`, { method: 'POST', body: JSON.stringify(input) }),

  /** 과금 전 중복 확인(무료). 동일 입력 재실행이면 duplicate=true. */
  checkDuplicate: (type: AnalysisType, input: UnderwriteInput): Promise<DuplicateCheck> =>
    request(`/api/underwriting/analyze/${type}/check-duplicate`, { method: 'POST', body: JSON.stringify(input) }),

  analyzeDoc: (type: DocAnalysisType, input: DocAnalyzeInput): Promise<DocAnalyzeResponse> =>
    request(`/api/underwriting/analyze-doc/${type}`, { method: 'POST', body: JSON.stringify(input) }),

  /** 무료 - 기사/딜 텍스트 → 구조화 추출(분석 폼 프리필). 크레딧 미차감. */
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

  /** 내 딜 대시보드 - 딜명으로 집계(최근 활동순). */
  myDeals: (): Promise<DealSummary[]> => request('/api/underwriting/deals'),

  run: (id: number): Promise<RunDetail> => request(`/api/underwriting/runs/${id}`),

  /** 한 딜의 완료된 단계 모음(합본 탭). 무과금. */
  dealStages: (dealName: string): Promise<DealStagesResponse> =>
    request(`/api/underwriting/deal-stages?dealName=${encodeURIComponent(dealName)}`),

  /** 읽기전용 공유 링크 발급(멱등). 토큰 반환 → 프런트가 origin 붙여 링크 구성. */
  shareReport: (runId: number): Promise<{ token: string }> =>
    request(`/api/underwriting/report/${runId}/share`, { method: 'POST' }),

  history: (): Promise<BillingHistory> => request('/api/billing/history'),

  /** 분석별 크레딧 단가 + 가입 무료 지급량(버튼 라벨/안내의 단일 소스). */
  pricing: (): Promise<Pricing> => request('/api/billing/pricing'),

  /** 시장 인텔리전스 피드 - 카드 페이지(최신순). page 0-기반(과거 딜 더 보기). */
  marketFeed: (limit = 30, page = 0): Promise<MarketFeedPage> =>
    request(`/api/market-feed?limit=${limit}&page=${page}`),

  /** 관리자 - 피드 카드 추가. */
  marketFeedCreate: (input: MarketFeedInput): Promise<MarketFeedItem> =>
    request('/api/admin/market-feed', { method: 'POST', body: JSON.stringify(input) }),

  /** 관리자 - 피드 카드 삭제. */
  marketFeedDelete: (id: number): Promise<{ deleted: boolean }> =>
    request(`/api/admin/market-feed/${id}`, { method: 'DELETE' }),

  /** 최신 마켓 브리핑(AI 다이제스트). 아직 없으면 null. */
  marketBriefing: (): Promise<MarketBriefing | null> => request('/api/market-feed/briefing'),

  /** 무료 - 지난 브리핑 아카이브 목록(최신순). */
  marketBriefingHistory: (): Promise<BriefingHistoryItem[]> =>
    request('/api/market-feed/briefing/history'),

  /** 무료 - 저장된 브리핑 단건 다시 보기. */
  marketBriefingById: (id: number): Promise<MarketBriefing> =>
    request(`/api/market-feed/briefing/${id}`),

  /** 관심 딜(찜) - 내 목록. */
  watchList: (): Promise<DealWatch[]> => request('/api/market-feed/watch'),
  /** 관심 딜 카드 id 집합(⭐ 상태). */
  watchIds: (): Promise<number[]> => request('/api/market-feed/watch/ids'),
  /** 찜 추가. */
  watchAdd: (feedItemId: number): Promise<DealWatch> =>
    request('/api/market-feed/watch', { method: 'POST', body: JSON.stringify({ feedItemId }) }),
  /** 찜 해제. */
  watchRemove: (feedItemId: number): Promise<{ removed: boolean }> =>
    request(`/api/market-feed/watch/${feedItemId}`, { method: 'DELETE' }),

  /** 관리자 - 즉시 수집(딜 카드 + 무료 브리핑). 스케줄러와 동일 경로. */
  marketFeedIngest: (): Promise<IngestReport> =>
    request('/api/admin/market-feed/ingest', { method: 'POST' }),

  /** 과금 - AI 심층 시장 리포트(Claude, 1크레딧). */
  marketDeepReport: (focus?: string): Promise<MarketDeepReport> =>
    request('/api/market-feed/deep-report', { method: 'POST', body: JSON.stringify({ focus: focus ?? null }) }),

  /** 무료 - 내가 만든 지난 심층 리포트 목록. */
  marketDeepReportHistory: (): Promise<DeepReportHistoryItem[]> =>
    request('/api/market-feed/deep-report/history'),

  /** 무료 - 저장된 심층 리포트 단건 다시 보기. */
  marketDeepReportById: (id: number): Promise<MarketDeepReport> =>
    request(`/api/market-feed/deep-report/${id}`),

  /** 무료 - 마켓 브리핑 메일 구독 상태/구독/해지. */
  newsletterStatus: (): Promise<{ subscribed: boolean }> => request('/api/newsletter/status'),
  newsletterSubscribe: (): Promise<{ subscribed: boolean }> =>
    request('/api/newsletter/subscribe', { method: 'POST' }),
  newsletterUnsubscribe: (): Promise<{ subscribed: boolean }> =>
    request('/api/newsletter/subscribe', { method: 'DELETE' }),

  /** 관리자 - 전체 사용자 목록. */
  adminUsers: (): Promise<AdminUser[]> => request('/api/admin/users'),

  /** 관리자 - 사용자 권한 변경(USER/ADMIN). */
  adminSetRole: (id: number, role: UserRole): Promise<AdminUser> =>
    request(`/api/admin/users/${id}/role`, { method: 'POST', body: JSON.stringify({ role }) }),

  /** 관리자 - 크레딧 가감(+/-). */
  adminAdjustCredits: (id: number, delta: number): Promise<AdminUser> =>
    request(`/api/admin/users/${id}/credits`, { method: 'POST', body: JSON.stringify({ delta }) }),

  /** 관리자 - 계정 차단/해제(ACTIVE|DISABLED). */
  adminSetStatus: (id: number, status: UserStatus): Promise<AdminUser> =>
    request(`/api/admin/users/${id}/status`, { method: 'POST', body: JSON.stringify({ status }) }),

  /** 관리자 - 계정 영구 삭제(연관 데이터 정리). */
  adminDeleteUser: (id: number): Promise<{ deleted: boolean }> =>
    request(`/api/admin/users/${id}`, { method: 'DELETE' }),

  /** 관리자 - 운영 대시보드 집계 지표. */
  adminStats: (): Promise<AdminStats> => request('/api/admin/stats'),

  /** 관리자 - 전 사용자 크레딧 원장(최신순). 충전 경로·사유 포함. */
  adminCredits: (): Promise<AdminCreditEntry[]> => request('/api/admin/credits'),

  /** 관리자 - 전 테넌트 모든 분석 데이터. */
  adminRuns: (): Promise<AdminRun[]> => request('/api/admin/runs'),

  /** 관리자 - 분석 데이터 단건 상세(입력/결과 JSON). */
  adminRunDetail: (id: number): Promise<AdminRunDetail> => request(`/api/admin/runs/${id}`),

  /** 관리자 - 뉴스레터 구독자 목록(최신 가입순). */
  adminNewsletterSubscribers: (): Promise<NewsSubscriber[]> => request('/api/admin/newsletter/subscribers'),

  /** 관리자 - 뉴스레터 발송 로그(누구에게/언제/성공여부). */
  adminNewsletterSendLog: (limit = 100): Promise<NewsletterSendLogEntry[]> =>
    request(`/api/admin/newsletter/send-log?limit=${limit}`),

  /** 관리자 - 최신 브리핑 뉴스레터 HTML 미리보기(발송 없음). 인증 헤더 필요 → fetch 로 받아 새 창에. */
  adminNewsletterPreview: async (): Promise<string> => {
    const token = tokenStore.get()
    const res = await fetch(`${API_BASE}/api/admin/newsletter/preview`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw new ApiError(res.status, `미리보기를 불러오지 못했습니다 (${res.status})`)
    return res.text()
  },

  /** 관리자 - 지정 주소로 테스트 발송. */
  adminNewsletterTestSend: (email: string): Promise<{ sent: boolean }> =>
    request('/api/admin/newsletter/test-send', { method: 'POST', body: JSON.stringify({ email }) }),

  // ── 결제(크레딧 충전) ──
  /** 결제 SDK 초기화용 - clientKey + 활성 여부. */
  paymentConfig: (): Promise<PaymentConfig> => request('/api/payments/config'),

  /** 판매 팩(가격표). */
  creditPacks: (): Promise<CreditPack[]> => request('/api/payments/packs'),

  /** 주문 생성 - 팩 선택 → 결제창에 넘길 orderId/금액. */
  createOrder: (packId: string): Promise<CreateOrderResponse> =>
    request('/api/payments/order', { method: 'POST', body: JSON.stringify({ packId }) }),

  /** 결제 승인 - 토스 콜백(paymentKey/orderId/amount) 서버 검증 후 충전. */
  confirmPayment: (paymentKey: string, orderId: string, amount: number): Promise<ConfirmResponse> =>
    request('/api/payments/confirm', {
      method: 'POST',
      body: JSON.stringify({ paymentKey, orderId, amount }),
    }),

  /** 내 결제 이력. */
  paymentHistory: (): Promise<PaymentHistoryItem[]> => request('/api/payments/history'),

  /** 설정된 소셜 로그인 제공자 목록(소문자: google/kakao/naver). 미설정이면 빈 배열. */
  oauthProviders: (): Promise<string[]> => request('/api/auth/oauth/providers'),
}

/** 소셜 로그인 시작 URL(브라우저 전체 이동 - fetch 아님). 제공자 인증 페이지로 302. */
export function oauthAuthorizeUrl(provider: string): string {
  return `${API_BASE}/api/auth/oauth/${provider}/authorize`
}
