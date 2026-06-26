// 백엔드 REST 클라이언트. ApiResponse 엔벨로프({success,data,error})를 언랩하고
// JWT 토큰을 자동 첨부한다. 개발 시 /api 는 Vite 프록시로 Spring(8080)에 전달된다.

export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
}

export interface AuthResult {
  token: string
  email: string
  plan: string
  creditBalance: number
}

export interface Me {
  userId: number
  tenantId: number
  email: string
  creditBalance: number
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

export interface ProFormaResponse {
  proForma: ProForma
  scenarios: Scenario[]
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

  proforma: (input: UnderwriteInput): Promise<ProFormaResponse> =>
    request('/api/underwriting/proforma', { method: 'POST', body: JSON.stringify(input) }),

  analyze: (input: UnderwriteInput): Promise<AnalyzeResponse> =>
    request('/api/underwriting/analyze', { method: 'POST', body: JSON.stringify(input) }),

  analyzeStage: (type: AnalysisType, input: UnderwriteInput): Promise<AnalyzeResponse> =>
    request(`/api/underwriting/analyze/${type}`, { method: 'POST', body: JSON.stringify(input) }),

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
}
