import { useState } from 'react'
import { FileDropzone } from './FileDropzone'
import { FollowUpPanel } from './FollowUpPanel'
import { Markdown } from './Markdown'
import { TrustBadge } from './TrustBadge'
import {
  api, ApiError, track,
  type ContractResponse, type ExtractReviewAnalysis, type ExtractReviewKind, type RuleFlag, type VersionCompareAnalysis,
} from './api'

interface PanelProps {
  onCreditBalance: (balance: number) => void
  onNeedCredits: () => void
  toolCosts?: Record<string, number>
}

const MIN_TEXT_LEN = 30
const MAX_VERSIONS = 4

function costLabel(cost?: number): string {
  return cost != null ? `${cost}크레딧` : '분석'
}

function severityClass(severity?: string | null): string {
  const s = (severity ?? '').toUpperCase()
  if (s === 'HIGH') return 'no'
  if (s === 'MEDIUM') return 'cond'
  return 'go'
}

/** 점 표기 경로로 중첩 값 꺼내기(정형 추출 결과 표시용). */
function at(obj: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce<unknown>((cur, k) => (cur && typeof cur === 'object' ? (cur as Record<string, unknown>)[k] : undefined), obj)
}

function display(path: string, v: unknown): string | null {
  if (v == null || v === '') return null
  if (typeof v === 'boolean') return v ? '예' : '아니오'
  if (typeof v === 'number') return path.endsWith('_krw') ? `${v.toLocaleString()}원` : v.toLocaleString()
  if (Array.isArray(v)) return v.length ? v.map(String).join(' / ') : null
  return String(v)
}

/** 문서 종류별 표시 항목(경로·라벨). 스키마 키는 서버 규칙 엔진과 같다. */
const FIELDS: Record<ExtractReviewKind, [string, string][]> = {
  LOI: [
    ['buyer.name', '매수인'], ['seller.name', '매도인'], ['target_property.address', '대상 부동산'],
    ['purchase_price.total_krw', '매입가(총액)'], ['purchase_price.vat_included', 'VAT 포함'],
    ['payment.down_krw', '계약금'], ['payment.interim_krw', '중도금'], ['payment.balance_krw', '잔금'],
    ['payment.balance_date', '잔금일'], ['validity.valid_until', '유효기간'], ['validity.binding', '구속력'],
    ['exclusive_negotiation.granted', '우선협상권'], ['exclusive_negotiation.period', '우선협상 기간'],
    ['termination.conditions', '해제 조건'], ['termination.penalty', '위약'], ['indemnity.scope', '면책 범위'],
    ['transaction_conditions', '거래 조건'], ['special_terms', '특약'],
  ],
  LEASE: [
    ['lessor.name', '임대인'], ['lessee.name', '임차인'], ['premise.address', '임대 목적물'], ['premise.area_m2', '면적(㎡)'],
    ['term.start', '임대 개시'], ['term.end', '임대 종료'], ['term.months', '기간(개월, 계약서 표기)'],
    ['rent.deposit_krw', '보증금'], ['rent.monthly_krw', '월 임대료'], ['cam.monthly_krw', '월 관리비'],
    ['rent_free.months', '렌트프리(개월)'], ['increase.rate_pct_per_year', '연 인상률(%)'], ['increase.cap_pct', '인상 상한(%)'],
    ['termination.notice_months', '해지 통보(개월)'], ['termination.penalty', '중도해지 위약'],
    ['renewal.auto', '자동갱신'], ['renewal.max_terms', '갱신 횟수'], ['restoration.scope', '원상복구 범위'],
    ['special_clauses', '특약'],
  ],
}

const KIND_LABEL: Record<ExtractReviewKind, string> = { LOI: '매입의향서(LOI)', LEASE: '임대차계약' }
const KIND_TOOL: Record<ExtractReviewKind, string> = { LOI: 'LOI_REVIEW', LEASE: 'LEASE_REVIEW' }

/** 매입의향서·임대차계약 - 핵심 조항 정형 추출 + 코드 규칙 플래그. */
export function ExtractReviewPanel({ onCreditBalance, onNeedCredits, toolCosts }: PanelProps) {
  const [kind, setKind] = useState<ExtractReviewKind>('LOI')
  const [dealName, setDealName] = useState('')
  const [text, setText] = useState('')
  const [fileName, setFileName] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ContractResponse<ExtractReviewAnalysis> | null>(null)
  const [resultKind, setResultKind] = useState<ExtractReviewKind>('LOI')

  const canSubmit = text.trim().length >= MIN_TEXT_LEN && !busy

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null); setResult(null)
    try {
      track('contract_review_submit', { meta: KIND_TOOL[kind] })
      const res = await api.contractExtractReview(kind, {
        dealName: dealName.trim() || undefined, text: text.trim(), sourceFileName: fileName,
      })
      setResult(res)
      setResultKind(kind)
      onCreditBalance(res.creditBalance)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      setError(err instanceof ApiError ? err.message : '검토에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <form className="card" onSubmit={submit}>
        <div className="form-grid">
          <div className="full">
            <label>문서 종류</label>
            <div className="seg" role="group" aria-label="문서 종류">
              {(['LOI', 'LEASE'] as const).map((k) => (
                <button key={k} type="button" aria-pressed={kind === k} onClick={() => setKind(k)}>{KIND_LABEL[k]}</button>
              ))}
            </div>
            <span className="field-hint">
              {kind === 'LOI'
                ? '매입가·분납·유효기간·우선협상권·해제 조건을 뽑고, 계약금 비율(5~20%)·분납 합계 같은 거래 통념을 코드가 점검합니다.'
                : '보증금·임대료·인상률·렌트프리·해지·원상복구 등 12개 조항을 뽑고, 인상률·보증금 배수 같은 시장 표준을 코드가 점검합니다.'}
            </span>
          </div>
          <div className="full">
            <label htmlFor="xDeal">딜/자산 이름 <span className="opt">(선택)</span></label>
            <input id="xDeal" value={dealName} onChange={(e) => setDealName(e.target.value)} placeholder="예: 판교 오피스 매입" />
          </div>
          <div className="full">
            <label htmlFor="xText">{KIND_LABEL[kind]} 원문 <span className="req">*</span></label>
            <FileDropzone disabled={busy} label="파일을 끌어다 놓거나 클릭해서 올리기"
              onExtracted={(doc) => { setText(doc.text); setFileName(doc.fileName) }} />
            <textarea id="xText" rows={8} value={text}
              onChange={(e) => { setText(e.target.value); setFileName(undefined) }}
              placeholder="파일을 올리거나 원문을 붙여넣으세요." />
          </div>
        </div>
        <div className="actions">
          <button type="submit" className="btn-primary" disabled={!canSubmit}>
            {busy ? '검토 중…' : `${KIND_LABEL[kind]} 검토 · ${costLabel(toolCosts?.[KIND_TOOL[kind]])}`}
          </button>
          <p className="hint">금액·기간은 코드가 원문에서 실측해 AI 에 사실로 넘깁니다. 실무 검토 보조 의견이며 법률자문이 아닙니다.</p>
        </div>
        <TrustBadge />
        {error && <p className="error">{error}</p>}
      </form>

      {result && <ExtractReviewResult res={result} kind={resultKind} />}
      {result?.analysis && (
        <FollowUpPanel key={result.runId} runId={result.runId} toolCosts={toolCosts}
          onCreditBalance={onCreditBalance} onNeedCredits={onNeedCredits} />
      )}
    </>
  )
}

function ExtractReviewResult({ res, kind }: { res: ContractResponse<ExtractReviewAnalysis>; kind: ExtractReviewKind }) {
  const a = res.analysis
  if (!a) {
    return (
      <div className="card">
        <h3>검토 결과</h3>
        <p className="muted">정형 결과를 만들지 못해 원문 그대로 표시합니다.</p>
        <Markdown md={res.analysisRaw ?? ''} />
      </div>
    )
  }
  const rows = FIELDS[kind]
    .map(([path, label]) => ({ path, label, value: display(path, at(a, path)), quote: a._evidence?.[path]?.quote }))
    .filter((r) => r.value != null)
  const ruleFlags = a.ruleFlags ?? []
  const risks = a.risks ?? []
  return (
    <div className="card">
      <div className="section-title-row">
        <div>
          <h3>{KIND_LABEL[kind]} 검토</h3>
          <p className="muted">추출 {rows.length}개 항목 · 코드 규칙 {ruleFlags.length}건 · AI 리스크 {risks.length}건</p>
        </div>
      </div>
      {a.summary && <p>{a.summary}</p>}

      <dl className="kv-grid">
        {rows.map((r) => (
          <div key={r.path} className="kv-pair" title={r.quote ? `원문: ${r.quote}` : undefined}>
            <dt>{r.label}</dt><dd>{r.value}</dd>
          </div>
        ))}
      </dl>

      <RuleFlagList flags={ruleFlags} />

      {risks.length > 0 && (
        <section className="scr-section doc-block">
          <h4>AI 리스크 {risks.length}건</h4>
          <ul className="risk-list">
            {risks.map((r, i) => (
              <li key={`${r.field}-${i}`}>
                <div className="risk-head">
                  <span className={`sev-badge ${severityClass(r.severity)}`}>{r.severity}</span>
                  <strong>{r.field}</strong>
                </div>
                <p>{r.message}</p>
              </li>
            ))}
          </ul>
        </section>
      )}
      <p className="disclaimer">{res.disclaimer}</p>
    </div>
  )
}

export function RuleFlagList({ flags }: { flags: RuleFlag[] }) {
  return (
    <section className="scr-section doc-block">
      <h4>코드 규칙 점검 {flags.length}건</h4>
      {flags.length === 0
        ? <p className="muted">거래 통념 기준에서 벗어난 조건이 없습니다.</p>
        : (
          <ul className="risk-list">
            {flags.map((f, i) => (
              <li key={`${f.field}-${i}`}>
                <div className="risk-head">
                  <span className={`sev-badge ${severityClass(f.severity)}`}>{f.severity}</span>
                  <span className="rule-tag">규칙</span>
                </div>
                <p>{f.message}</p>
              </li>
            ))}
          </ul>
        )}
    </section>
  )
}

/* ────────────────────────── 판(버전) 비교 ────────────────────────── */

interface VersionDraft { label: string; text: string; fileName?: string }

const CHANGE_TONE: Record<string, string> = { 신설: 'go', 삭제: 'no', 수정: 'cond', 이동: 'cond', 오탈자: 'go' }

/** 같은 계약서의 판 2~4건 비교 - 달라진 조항은 코드가, 변경 유형·사유 초안은 AI 가. */
export function VersionComparePanel({ onCreditBalance, onNeedCredits, toolCosts }: PanelProps) {
  const [dealName, setDealName] = useState('')
  const [versions, setVersions] = useState<VersionDraft[]>([
    { label: '1차안', text: '' }, { label: '회신안', text: '' },
  ])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ContractResponse<VersionCompareAnalysis> | null>(null)

  const filled = versions.filter((v) => v.text.trim().length >= MIN_TEXT_LEN)
  const canSubmit = filled.length >= 2 && !busy

  const update = (i: number, patch: Partial<VersionDraft>) =>
    setVersions((vs) => vs.map((v, j) => (j === i ? { ...v, ...patch } : v)))

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null); setResult(null)
    try {
      track('contract_review_submit', { meta: 'CONTRACT_VERSION_COMPARE' })
      const res = await api.contractVersionCompare({
        dealName: dealName.trim() || undefined,
        versions: filled.map((v) => ({ label: v.label.trim() || undefined, text: v.text.trim() })),
      })
      setResult(res)
      onCreditBalance(res.creditBalance)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      setError(err instanceof ApiError ? err.message : '판 비교에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <form className="card" onSubmit={submit}>
        <div className="form-grid">
          <div className="full">
            <label htmlFor="vDeal">계약/딜 이름 <span className="opt">(선택)</span></label>
            <input id="vDeal" value={dealName} onChange={(e) => setDealName(e.target.value)} placeholder="예: 도담빌딩 매매계약" />
            <span className="field-hint">협상 진행 순서대로 올리세요. 조·항 단위로 코드가 대조하고, 조항 구조가 없는 문서는 2건에 한해 문단 단위로 비교합니다.</span>
          </div>
          {versions.map((v, i) => (
            <div key={i} className="full ver-slot">
              <div className="ver-slot-head">
                <input aria-label={`${i + 1}번째 판 이름`} value={v.label} onChange={(e) => update(i, { label: e.target.value })} />
                {versions.length > 2 && (
                  <button type="button" className="btn-ghost btn-xs" onClick={() => setVersions((vs) => vs.filter((_, j) => j !== i))}>빼기</button>
                )}
              </div>
              <FileDropzone disabled={busy} label={`${v.label || `${i + 1}번째 판`} 파일 올리기`}
                onExtracted={(doc) => update(i, { text: doc.text, fileName: doc.fileName })} />
              <textarea rows={4} value={v.text} onChange={(e) => update(i, { text: e.target.value, fileName: undefined })}
                placeholder="파일을 올리거나 원문을 붙여넣으세요." aria-label={`${v.label} 원문`} />
            </div>
          ))}
          {versions.length < MAX_VERSIONS && (
            <div className="full">
              <button type="button" className="btn-ghost btn-xs"
                onClick={() => setVersions((vs) => [...vs, { label: `${vs.length + 1}차안`, text: '' }])}>+ 판 추가</button>
            </div>
          )}
        </div>
        <div className="actions">
          <button type="submit" className="btn-primary" disabled={!canSubmit}>
            {busy ? '비교 중…' : `판 비교 · ${costLabel(toolCosts?.CONTRACT_VERSION_COMPARE)}`}
          </button>
          <p className="hint">달라진 조항이 없으면 AI 를 부르지 않고 크레딧도 쓰지 않습니다.</p>
        </div>
        {error && <p className="error">{error}</p>}
      </form>

      {result?.analysis && <VersionCompareResult a={result.analysis} disclaimer={result.disclaimer} />}
      {result?.analysis && result.analysis.rows.length > 0 && (
        <FollowUpPanel key={result.runId} runId={result.runId} toolCosts={toolCosts}
          onCreditBalance={onCreditBalance} onNeedCredits={onNeedCredits} />
      )}
    </>
  )
}

function VersionCompareResult({ a, disclaimer }: { a: VersionCompareAnalysis; disclaimer: string }) {
  const s = a.summary
  return (
    <div className="card">
      <div className="section-title-row">
        <div>
          <h3>{a.title}</h3>
          <p className="muted">{a.versions.join(' → ')} · {s.mode} 단위 비교</p>
        </div>
      </div>
      <div className="ver-stats">
        <div><b>{s.total}</b><span>비교 대상</span></div>
        <div className="ver-stat-changed"><b>{s.changed}</b><span>변경</span></div>
        <div><b>{s.identical}</b><span>동일</span></div>
        <div><b>{s.minor}</b><span>띄어쓰기·부호만</span></div>
      </div>
      {a.rows.length === 0
        ? <p className="muted">달라진 조항이 없습니다.</p>
        : (
          <ol className="ver-rows">
            {a.rows.map((r) => (
              <li key={r.no}>
                <div className="risk-head">
                  <span className={`sev-badge ${CHANGE_TONE[r.changeType] ?? 'cond'}`}>{r.changeType}</span>
                  <strong>{r.clauseRef}</strong>
                  {r.topic && <span className="muted">{r.topic}</span>}
                  {r.section && <span className="rule-tag">{r.section}</span>}
                </div>
                <div className="ver-texts" style={{ gridTemplateColumns: `repeat(${Math.min(a.versions.length, 2)}, minmax(0, 1fr))` }}>
                  {a.versions.map((label) => (
                    <div key={label} className="ver-text">
                      <span className="redline-tag">{label}</span>
                      <p className={r.texts[label] === '(해당 문구 없음)' ? 'muted' : undefined}>{r.texts[label]}</p>
                    </div>
                  ))}
                </div>
                <p className="risk-reco">→ {r.rationale}</p>
              </li>
            ))}
          </ol>
        )}
      <p className="disclaimer">{disclaimer}</p>
    </div>
  )
}
