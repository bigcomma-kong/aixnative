import { useEffect, useState } from 'react'
import {
  api, ApiError, isBovCalc, isBizHealthCalc,
  type BizHealthCalc, type BovInput, type DevFeasibilityInput, type DocAnalysisType, type DocAnalyzeInput,
  type DocAnalyzeResponse, type DocCalc, type DocSection, type RunSummary, type TaxGuide,
} from './api'

interface DocAnalysisViewProps {
  onCreditBalance: (balance: number) => void
}

interface DocTypeMeta {
  type: DocAnalysisType
  label: string
  hint: string
  placeholder: string
}

const ASSET_TYPES = ['오피스', '물류', '호텔', '리테일'] as const

/** 신규/추가 분석 단계 — 자유 텍스트 입력 기반(각 1 크레딧). */
const DOC_TYPES: DocTypeMeta[] = [
  { type: 'UNDERWRITING_GUIDE', label: '언더라이팅 입력가이드', hint: '권장 가정 선제안',
    placeholder: '예: 강남 GBD 중형 오피스 매입 검토, 연면적 약 2,000평. 알고 있는 값(매입가·NOI·Cap)이 있으면 함께 적어주세요.' },
  { type: 'BUILDING_RESEARCH', label: '건물 검색(예비 IM)', hint: '공개 벤치마크 예비 IM',
    placeholder: '예: 서울 영등포구 여의도동 소재 오피스, 연면적 약 3,000평, 2015년 준공, 호가 미상.' },
  { type: 'TAX_PRICE_DIAGNOSIS', label: '세무·가격 진단', hint: '절세 포인트·가격 적정성',
    placeholder: '예: 취득가 2,000억, 양도가 2,400억, 보유 5년, 법인 보유. 취득세·중개보수·법무비·자본적지출 등 실제 지출 내역.' },
  { type: 'BOV', label: '매각 BOV', hint: '평가·가격범위·매각방식',
    placeholder: '예: GBD 코어오피스, 현 NOI 95억, 시장 Cap 4.75%, 잔여대출 1,100억(조기상환 페널티 2%), 매각 우선순위=확실성.' },
  { type: 'AM_QUARTERLY', label: '분기 자산보고', hint: '운영 실적·KPI·Variance',
    placeholder: '예: 3분기 GPR 30억/EGI 28억/OpEx 9억/NOI 19억(예산 대비 +3%), Occupancy 94%, WALT 3.2년, Top1 임차 28%.' },
  { type: 'HOLD_SELL_REFI', label: '보유·매각·리파이', hint: '4-시나리오 결정',
    placeholder: '예: 보유 3년차, 현 NAV 2,200억, 잔여대출 1,100억(금리 4.5%), 현 NOI 95억, 취득가 2,000억.' },
  { type: 'DEV_FEASIBILITY', label: '개발 타당성', hint: '사업비·마진·인허가·PF',
    placeholder: '예: 역삼동 토지 500평, 용적률 800%, 오피스 개발, 토지매입가 800억, 예상 연면적 4,000평.' },
  { type: 'MARKET_RESEARCH_DEEP', label: '심화 시장리서치', hint: '권역·매크로·하우스뷰',
    placeholder: '예: 서울 GBD 오피스 시장. 향후 3년 공급, 금리 환경, 최근 거래 사례 기준 House View 요청.' },
  { type: 'COUNTERPARTY_DD', label: '거래상대방 실사', hint: '사업자상태·제재·규모',
    placeholder: '정성 정보(선택): 거래 맥락·우려사항 등. 핵심 사실은 사업자번호로 공공데이터가 확정합니다.' },
]

const DOC_LABEL: Record<string, string> = Object.fromEntries(
  DOC_TYPES.map((d) => [d.type, d.label]).concat(
    // 백엔드 tool 식별자 ↔ 라벨 (이력 표시용)
    [['UNDERWRITING_GUIDE', '언더라이팅 입력가이드'], ['BUILDING_RESEARCH', '건물 검색(예비 IM)'],
     ['TAX_PRICE_DIAGNOSIS', '세무·가격 진단'], ['BOV_NARRATIVE', '매각 BOV'], ['AM_QUARTERLY', '분기 자산보고'],
     ['HOLD_SELL_REFI', '보유·매각·리파이'], ['DEV_FEASIBILITY', '개발 타당성'], ['MARKET_RESEARCH_DEEP', '심화 시장리서치'],
     ['COUNTERPARTY_DD', '거래상대방 실사']] as [string, string][],
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
  { k: 'salesCompValueEok', label: '비교거래 추정가 (억)', hint: '선택 — 입력 시 3법 가중' },
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

function isCalcType(t: DocAnalysisType): boolean {
  return t === 'BOV' || t === 'DEV_FEASIBILITY'
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

export function DocAnalysisView({ onCreditBalance }: DocAnalysisViewProps) {
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
  const [historyVersion, setHistoryVersion] = useState(0)

  const meta = DOC_TYPES.find((d) => d.type === type) ?? DOC_TYPES[0]
  const calcMode = isCalcType(type)
  const ddMode = type === 'COUNTERPARTY_DD'
  const parcelMode = type === 'TAX_PRICE_DIAGNOSIS' || type === 'DEV_FEASIBILITY' // 공시지가·용도지역 선택 입력

  function selectType(next: DocAnalysisType) {
    setType(next)
    setResult(null)
    setError(null)
    setCalcValues({})
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

  async function run() {
    const input: DocAnalyzeInput = {
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
        return
      }
      if (digits.length === 10) input.bizNo = digits
      if (counterpartyName.trim()) input.counterpartyName = counterpartyName.trim()
    } else if (calcMode) {
      const built = buildCalcInput()
      if (typeof built === 'string') { setError(built); return }
      Object.assign(input, built)
    } else if (!documentText.trim()) {
      setError('분석 대상 정보를 입력하세요.')
      return
    }
    setError(null)
    setBusy(true)
    try {
      const res = await api.analyzeDoc(type, input)
      setResult(res)
      onCreditBalance(res.creditBalance)
      setHistoryVersion((v) => v + 1)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) {
        setError('남은 크레딧이 없습니다. (결제는 추후 지원 예정)')
      } else if (err instanceof ApiError && err.status === 503) {
        setError(err.message || 'AI 분석 서비스를 사용할 수 없습니다.')
      } else {
        setError(err instanceof ApiError ? err.message : '분석 중 오류가 발생했습니다.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">심화 분석</span>
          <h1>매입·매각·운용·개발, 한 곳에서.</h1>
        </div>
      </div>

      <div className="layout">
        <form className="card input-panel" onSubmit={(e) => { e.preventDefault(); run() }}>
          <div className="form-head">
            <span className="section-title" style={{ margin: 0 }}>분석 선택</span>
          </div>

          <div className="doc-type-grid">
            {DOC_TYPES.map((d) => (
              <button key={d.type} type="button" className="doc-type-btn" aria-pressed={type === d.type}
                onClick={() => selectType(d.type)}>
                <span className="dt-label">{d.label}</span>
                <span className="dt-hint">{d.hint}</span>
              </button>
            ))}
          </div>

          <div className="form-grid">
            <div className="full">
              <label htmlFor="docDeal">딜/자산 이름 (선택)</label>
              <input id="docDeal" value={dealName} onChange={(e) => setDealName(e.target.value)} placeholder="예: 강남 오피스" />
            </div>
            <div className="full">
              <label>자산유형</label>
              <div className="seg" role="group" aria-label="자산유형">
                {ASSET_TYPES.map((t) => (
                  <button key={t} type="button" aria-pressed={assetType === t} onClick={() => setAssetType(t)}>{t}</button>
                ))}
              </div>
            </div>
            {!ddMode && (
              <div className="full">
                <label htmlFor="docLoc">위치 / 권역 (선택 · 넣으면 실측 검증)</label>
                <input id="docLoc" value={location} onChange={(e) => setLocation(e.target.value)} placeholder="예: 서울 강남구, 판교" />
              </div>
            )}
            {parcelMode && (
              <div className="full">
                <label htmlFor="parcelAddr">필지 주소 (선택 · 공시지가·용도지역 조회)</label>
                <input id="parcelAddr" value={parcelAddress} onChange={(e) => setParcelAddress(e.target.value)}
                  placeholder="번지까지: 예 서울 강남구 역삼동 736-1" />
              </div>
            )}
            {ddMode && (
              <>
                <div className="full">
                  <label htmlFor="ddBizNo">사업자등록번호 *</label>
                  <input id="ddBizNo" value={bizNo} onChange={(e) => setBizNo(e.target.value)}
                    placeholder="10자리 (예: 124-81-00998)" inputMode="numeric" />
                </div>
                <div className="full">
                  <label htmlFor="ddName">상호 (선택 · 기업정보·규모 조회)</label>
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
                      <label htmlFor={`calc-${f.k}`}>{f.label}{f.req ? ' *' : ''}</label>
                      <input id={`calc-${f.k}`} type="number" inputMode="decimal" step="any"
                        value={calcValues[f.k] ?? ''} placeholder={f.def ?? (f.hint ?? '')}
                        onChange={(e) => setCalcValues((s) => ({ ...s, [f.k]: e.target.value }))} />
                      {f.hint && <span className="calc-hint">{f.hint}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}
            <div className="full">
              <label htmlFor="docText">{calcMode || ddMode ? '추가 컨텍스트 (선택)' : '분석 대상 정보 *'}</label>
              <textarea id="docText" rows={calcMode || ddMode ? 3 : 6} value={documentText} onChange={(e) => setDocumentText(e.target.value)}
                placeholder={calcMode || ddMode ? '정성 정보(포지셔닝·매도자 우선순위·인허가 상황 등)를 자유롭게 덧붙이면 서술 품질이 올라갑니다. 핵심 사실은 위 입력으로 확정합니다.' : meta.placeholder} />
            </div>
          </div>

          <div className="actions">
            <button type="submit" className="btn-primary" disabled={busy}>
              {busy ? '분석 중…' : `${meta.label} 분석 · 1크레딧`}
            </button>
            <p className="hint">{calcMode || ddMode
              ? '핵심 사실은 코드·공공데이터로 확정하고(환각 차단), AI 는 그 확정 사실을 근거로 서술·판정만 합니다. 같은 딜 이름으로 쌓으면 이력에서 함께 보입니다.'
              : '자유 텍스트로 자산·운영·토지 정보를 입력하면 단계별 전문 분석을 생성합니다. 같은 딜 이름으로 쌓으면 이력에서 함께 보입니다.'}</p>
          </div>
          {error && <p className="error">{error}</p>}
        </form>

        <div className="card">
          {result ? (
            <DocResult res={result} label={DOC_LABEL[result.analysisType] ?? result.analysisType} />
          ) : (
            <div className="result-empty">
              <div className="placeholder">
                <div className="ph-mark">◇</div>
                <strong>아직 결과가 없습니다.</strong>
                분석을 선택하고 대상 정보를 입력해 실행하세요.
              </div>
            </div>
          )}
        </div>
      </div>

      <DocHistoryPanel version={historyVersion} />
    </>
  )
}

function DocResult({ res, label }: { res: DocAnalyzeResponse; label: string }) {
  const a = res.analysis
  return (
    <div className="ai-block">
      <div className="result-head">
        <span className="stage-pill">{label}</span>
        {res.provider && <span className="muted">· {res.provider}</span>}
      </div>

      {res.calc && <CalcCard calc={res.calc} />}

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

          {a.recommend && <RecommendGrid recommend={a.recommend} rationale={a.rationale} />}
          {a.guides && a.guides.length > 0 && <TaxGuides guides={a.guides} priceVerdict={a.priceVerdict} />}
          {a.im_markdown && <Markdown md={a.im_markdown} />}
          {a.sections && a.sections.length > 0 && <Sections sections={a.sections} />}
        </>
      ) : res.analysisRaw ? (
        <section><div className="section-title">AI 분석</div><p className="narrative">{res.analysisRaw}</p></section>
      ) : null}

      <p className="disclaimer">{a?.disclaimer ?? res.disclaimer}</p>
    </div>
  )
}

/** 코드 확정 수치 카드 — BOV·개발수익성·거래상대방 실사(결정론적 공공데이터). AI 서술 위에 표기. */
function CalcCard({ calc }: { calc: DocCalc }) {
  if (isBizHealthCalc(calc)) return <BizHealthCard calc={calc} />
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

/** 거래상대방 실사 — 공공데이터 확정 사실 카드(상태·제재·기업정보·규모). */
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
      {rationale && <p className="guideline">{rationale}</p>}
    </section>
  )
}

function TaxGuides({ guides, priceVerdict }: { guides: TaxGuide[]; priceVerdict?: string }) {
  return (
    <section>
      <div className="section-title">진단 {priceVerdict ? `· 가격 ${priceVerdict}` : ''}</div>
      {guides.map((g, i) => (
        <div key={i} className="risk" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: 4 }}>
          <span className="r-name">{g.kind ? `[${g.kind}] ` : ''}{g.title}</span>
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
          {s.table && s.table.headers && (
            <table>
              <thead><tr>{s.table.headers.map((h, j) => <th key={j}>{h}</th>)}</tr></thead>
              <tbody>
                {s.table.rows.map((row, j) => (
                  <tr key={j}>{row.map((c, k) => <td key={k}>{c}</td>)}</tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      ))}
    </>
  )
}

/** 최소 마크다운 렌더 — ## 제목 / - 불릿 / | 표 | / 문단. (외부 의존성 없이) */
function Markdown({ md }: { md: string }) {
  const lines = md.split('\n')
  const nodes: React.ReactNode[] = []
  let bullets: string[] = []
  let tableRows: string[][] = []
  let key = 0

  const flushBullets = () => {
    if (bullets.length) { nodes.push(<ul key={key++}>{bullets.map((b, i) => <li key={i}>{b}</li>)}</ul>); bullets = [] }
  }
  const flushTable = () => {
    if (tableRows.length) {
      const [head, ...rest] = tableRows
      const body = rest.filter((r) => !r.every((c) => /^-+$/.test(c.trim()) || c.trim() === ''))
      nodes.push(
        <table key={key++}>
          <thead><tr>{head.map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
          <tbody>{body.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
        </table>,
      )
      tableRows = []
    }
  }

  for (const raw of lines) {
    const line = raw.trimEnd()
    if (line.startsWith('|') && line.includes('|')) {
      flushBullets()
      tableRows.push(line.replace(/^\||\|$/g, '').split('|').map((c) => c.trim()))
      continue
    }
    flushTable()
    if (line.startsWith('## ')) { flushBullets(); nodes.push(<div className="section-title" key={key++}>{line.slice(3)}</div>) }
    else if (line.startsWith('# ')) { flushBullets(); nodes.push(<div className="section-title" key={key++}>{line.slice(2)}</div>) }
    else if (line.startsWith('- ')) { bullets.push(line.slice(2)) }
    else if (line.trim() === '') { flushBullets() }
    else { flushBullets(); nodes.push(<p className="narrative" key={key++}>{line}</p>) }
  }
  flushBullets(); flushTable()
  return <section>{nodes}</section>
}

function DocHistoryPanel({ version }: { version: number }) {
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
          <thead><tr><th>딜</th><th>유형</th><th>상태</th><th>일시</th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td>{r.dealName ?? '(이름없음)'}</td>
                <td>{DOC_LABEL[r.tool] ?? r.tool}</td>
                <td>{r.status}</td>
                <td className="num">{r.createdAt ? new Date(r.createdAt).toLocaleString('ko-KR') : '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
