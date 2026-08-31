import { useState } from 'react'
import { FileDropzone } from './FileDropzone'
import { Markdown } from './Markdown'
import { TrustBadge } from './TrustBadge'
import {
  api, ApiError, track,
  type ContractAnalysis, type ContractResponse, type ContractReviseAnalysis,
  type NoticeExtraction, type ReviewPerspective,
} from './api'

interface DocReviewViewProps {
  onCreditBalance: (balance: number) => void
  /** 크레딧 소진(402) 시 중앙 페이월 안내 노출. */
  onNeedCredits: () => void
  /** 분석유형 id → 크레딧 단가(서버 단일 소스). */
  toolCosts?: Record<string, number>
}

type Mode = 'contract' | 'notice'

const PERSPECTIVES: { id: ReviewPerspective; label: string }[] = [
  { id: 'NEUTRAL', label: '중립' },
  { id: 'LESSOR', label: '임대인' },
  { id: 'LESSEE', label: '임차인' },
  { id: 'BUYER', label: '매수인' },
  { id: 'SELLER', label: '매도인' },
]

const MIN_TEXT_LEN = 30

/** 크레딧 단가 표기 - 단가표 로딩 전이면 숫자를 생략한다. */
function costLabel(cost?: number): string {
  return cost != null ? `${cost}크레딧` : '분석'
}

/** 심각도 → 기존 `.sev-badge` 어휘(no=빨강/cond=주황/go=초록). 새 색 체계를 만들지 않는다. */
function severityClass(severity?: string | null): string {
  const s = (severity ?? '').toUpperCase()
  if (s === 'HIGH') return 'no'
  if (s === 'MEDIUM') return 'cond'
  return 'go'
}

/**
 * 문서 검토 - 계약서와 공고를 한 화면의 두 세그먼트로 묶었다.
 *
 * 둘 다 "문서 원문 → AI 정형 산출물" 이라는 같은 모양이고, 상단 메뉴가 이미 많아
 * 탭을 두 개 더 늘리는 대신 하나로 합쳤다.
 *
 * 파일 업로드는 별도 요청(`/api/documents/extract`)이라 여기서는 텍스트만 다룬다 -
 * 덕분에 사용자가 추출 결과를 눈으로 확인하고 고친 뒤 과금 분석을 돌릴 수 있다.
 */
export function DocReviewView({ onCreditBalance, onNeedCredits, toolCosts }: DocReviewViewProps) {
  const [mode, setMode] = useState<Mode>('contract')

  return (
    <>
      {/* 헤더·사용안내 구조는 다른 탭(언더라이팅·심화분석·자산관리)과 동일하게 맞춘다.
          탭마다 다른 상단을 쓰면 같은 제품이 아닌 것처럼 읽힌다. */}
      <div className="page-head">
        <div>
          <span className="eyebrow">AI DOCUMENT REVIEW</span>
          <h1>계약서와 공고, 올리면 바로 짚어 드립니다.</h1>
          <p className="page-sub">
            불리한 조항·미기재 공란·조문 모순을 조항 번호와 함께, 공매·매각 공고는 가격·일정·응찰 조건을 정형으로 정리합니다.
          </p>
        </div>
      </div>

      <ol className="use-steps" aria-label="사용 방법">
        <li><span className="us-n">1</span><span className="us-t"><b>문서 올리기</b> 파일을 올리거나 원문을 붙여넣습니다</span></li>
        <li><span className="us-n">2</span><span className="us-t"><b>관점 선택</b> 계약서는 임대인·임차인 등 어느 편에서 볼지 고릅니다</span></li>
        <li><span className="us-n">3</span><span className="us-t"><b>검토 실행</b> 리스크·공란·협상 포인트가 조항 번호와 함께 정리됩니다</span></li>
      </ol>

      <div className="doc-mode-row">
        <div className="seg" role="group" aria-label="문서 종류">
          <button type="button" aria-pressed={mode === 'contract'} onClick={() => setMode('contract')}>계약서 검토</button>
          <button type="button" aria-pressed={mode === 'notice'} onClick={() => setMode('notice')}>공고 분석</button>
        </div>
      </div>

      {mode === 'contract'
        ? <ContractPanel onCreditBalance={onCreditBalance} onNeedCredits={onNeedCredits} toolCosts={toolCosts} />
        : <NoticePanel onCreditBalance={onCreditBalance} onNeedCredits={onNeedCredits} toolCosts={toolCosts} />}
    </>
  )
}

/* ────────────────────────── 계약서 검토 ────────────────────────── */

function ContractPanel({ onCreditBalance, onNeedCredits, toolCosts }: DocReviewViewProps) {
  const [dealName, setDealName] = useState('')
  const [text, setText] = useState('')
  const [fileName, setFileName] = useState<string | undefined>()
  const [perspective, setPerspective] = useState<ReviewPerspective>('NEUTRAL')
  const [busy, setBusy] = useState(false)
  const [reviseBusy, setReviseBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ContractResponse | null>(null)
  const [revision, setRevision] = useState<ContractReviseAnalysis | null>(null)

  const canSubmit = text.trim().length >= MIN_TEXT_LEN && !busy

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null); setResult(null); setRevision(null)
    try {
      track('contract_review_submit')
      const res = await api.contractReview({
        dealName: dealName.trim() || undefined,
        text: text.trim(),
        perspective,
        sourceFileName: fileName,
      })
      setResult(res)
      onCreditBalance(res.creditBalance)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      setError(err instanceof ApiError ? err.message : '검토에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  async function makeRevision() {
    if (!result) return
    setReviseBusy(true); setError(null)
    try {
      const res = await api.contractRevise(result.runId, result.perspective)
      setRevision(res.analysis)
      onCreditBalance(res.creditBalance)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      setError(err instanceof ApiError ? err.message : '수정안 생성에 실패했습니다.')
    } finally {
      setReviseBusy(false)
    }
  }

  return (
    <>
      <form className="card" onSubmit={submit}>
        <div className="form-grid">
          <div className="full">
            <label htmlFor="cDeal">딜/자산 이름 <span className="opt">(선택)</span></label>
            <input id="cDeal" value={dealName} onChange={(e) => setDealName(e.target.value)} placeholder="예: 역삼 오피스 매매" />
          </div>

          <div className="full">
            <label>검토 관점</label>
            <div className="seg" role="group" aria-label="검토 관점">
              {PERSPECTIVES.map((p) => (
                <button key={p.id} type="button" aria-pressed={perspective === p.id} onClick={() => setPerspective(p.id)}>
                  {p.label}
                </button>
              ))}
            </div>
            <span className="field-hint">
              같은 조항도 어느 편에서 보느냐에 따라 유불리가 뒤집힙니다. 관점을 고르면 그쪽을 보호할 협상 포인트를 우선 찾습니다.
            </span>
          </div>

          <div className="full">
            <label htmlFor="cText">계약서 원문 <span className="req">*</span></label>
            <FileDropzone
              disabled={busy}
              label="계약서 파일을 끌어다 놓거나 클릭해서 올리기"
              onExtracted={(doc) => { setText(doc.text); setFileName(doc.fileName) }}
            />
            <textarea
              id="cText" rows={8} value={text}
              onChange={(e) => { setText(e.target.value); setFileName(undefined) }}
              placeholder="파일을 올리거나 계약서 전문을 붙여넣으세요. 조항 번호가 남아 있어야 검토 결과가 조항을 인용할 수 있습니다."
            />
          </div>
        </div>

        <div className="actions">
          <button type="submit" className="btn-primary" disabled={!canSubmit}>
            {busy ? '검토 중… (문서가 길면 수 분 걸립니다)' : `계약서 검토 · ${costLabel(toolCosts?.CONTRACT_REVIEW)}`}
          </button>
          <p className="hint">
            조항별 리스크·미기재 공란·조문 정합성·협상 포인트를 정리합니다.
            <br />
            실무 검토 보조 의견이며 법률자문이 아닙니다.
          </p>
        </div>
        <TrustBadge />
        {error && <p className="error">{error}</p>}
      </form>

      {result && <ContractResult res={result} />}

      {result?.analysis && (
        <div className="card">
          <div className="actions">
            <button type="button" className="btn-primary" disabled={reviseBusy} onClick={() => void makeRevision()}>
              {reviseBusy ? '수정안 작성 중…' : `조항별 수정안 만들기 · ${costLabel(toolCosts?.CONTRACT_REVISE)}`}
            </button>
            <p className="hint">검토가 지목한 조항만 [현행]→[수정] 문구로 고쳐 씁니다. 계약서 전문을 다시 쓰지 않습니다.</p>
          </div>
          {revision && <RevisionResult data={revision} />}
        </div>
      )}
    </>
  )
}

function ContractResult({ res }: { res: ContractResponse }) {
  const a: ContractAnalysis | null = res.analysis
  if (!a) {
    return (
      <div className="card">
        <h3>검토 결과</h3>
        <p className="muted">정형 결과를 만들지 못해 원문 그대로 표시합니다.</p>
        <Markdown md={res.analysisRaw ?? ''} />
      </div>
    )
  }
  return (
    <div className="card">
      <div className="section-title-row">
        <div>
          <h3>{a.contractTitle ?? '계약서 검토'}</h3>
          <p className="muted">
            {[a.contractType, a.assetType, res.perspectiveLabel && `${res.perspectiveLabel} 관점`]
              .filter(Boolean).join(' · ')}
          </p>
        </div>
        {a.overallRisk && <span className={`sev-badge ${severityClass(a.overallRisk)}`}>전체 위험도 {a.overallRisk}</span>}
      </div>

      {a.summary && <p>{a.summary}</p>}

      <dl className="kv-grid">
        {a.parties && a.parties.length > 0 && (
          <>
            <dt>당사자</dt>
            <dd>{a.parties.map((p) => [p.role, p.name].filter(Boolean).join(' ')).join(' / ')}</dd>
          </>
        )}
        {a.totalAmount && (<><dt>계약 금액</dt><dd>{a.totalAmount}</dd></>)}
        {a.paymentTerms && (<><dt>지급 조건</dt><dd>{a.paymentTerms}</dd></>)}
        {a.contractDate && (<><dt>계약일</dt><dd>{a.contractDate}</dd></>)}
        {a.disputeResolution && (<><dt>분쟁 해결</dt><dd>{a.disputeResolution}</dd></>)}
      </dl>

      {a.riskAssessment && a.riskAssessment.length > 0 && (
        <section className="scr-section doc-block">
          <h4>리스크 조항 {a.riskAssessment.length}건</h4>
          <ul className="risk-list">
            {a.riskAssessment.map((r, i) => (
              <li key={`${r.clause}-${i}`}>
                <div className="risk-head">
                  <span className={`sev-badge ${severityClass(r.severity)}`}>{r.severity}</span>
                  <strong>{r.clause}</strong>
                </div>
                {r.issue && <p>{r.issue}</p>}
                {r.evidence && <blockquote className="risk-evidence">{r.evidence}</blockquote>}
                {r.recommendation && <p className="risk-reco">→ {r.recommendation}</p>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <StringList title="체결 전 채워야 할 공란" items={a.blanks} empty="공란은 발견되지 않았습니다." />
      <StringList title="조문 오류·정합성" items={a.inconsistencies} empty="조문 정합성 문제는 발견되지 않았습니다." />
      <StringList title="누락된 보호장치" items={a.missingProtections} />
      <StringList title="협상 포인트" items={a.negotiationPoints} />

      {a.reviewOpinion && (
        <section className="scr-section doc-block">
          <h4>검토 총평</h4>
          <p>{a.reviewOpinion}</p>
        </section>
      )}
      <p className="disclaimer">{res.disclaimer}</p>
    </div>
  )
}

function RevisionResult({ data }: { data: ContractReviseAnalysis }) {
  return (
    <section className="scr-section doc-block">
      <h4>{data.title ?? '조항별 수정안'}</h4>
      {data.summary && <p>{data.summary}</p>}
      {data.revisions?.map((r, i) => (
        <div key={`${r.ref}-${i}`} className="redline">
          <div className="risk-head">
            <span className={`sev-badge ${severityClass(r.severity)}`}>{r.severity}</span>
            <strong>{r.ref}</strong>
          </div>
          {r.issue && <p>{r.issue}</p>}
          {r.original && <p className="redline-old"><span className="redline-tag">현행</span>{r.original}</p>}
          {r.revised && <p className="redline-new"><span className="redline-tag">수정</span>{r.revised}</p>}
          {r.rationale && <p className="risk-reco">→ {r.rationale}</p>}
        </div>
      ))}
      {data.additions && data.additions.length > 0 && (
        <>
          <h4>신설 제안</h4>
          {data.additions.map((x, i) => (
            <div key={`${x.ref}-${i}`} className="redline">
              <strong>{x.ref}</strong>
              {x.clauseText && <p className="redline-new">{x.clauseText}</p>}
              {x.rationale && <p className="risk-reco">→ {x.rationale}</p>}
            </div>
          ))}
        </>
      )}
      {data.blanksToFill && data.blanksToFill.length > 0 && (
        <StringList title="채워야 할 공란" items={data.blanksToFill.map((b) => [b.ref, b.note].filter(Boolean).join(' - '))} />
      )}
    </section>
  )
}

/* ────────────────────────── 공고 분석 ────────────────────────── */

function NoticePanel({ onCreditBalance, onNeedCredits, toolCosts }: DocReviewViewProps) {
  const [dealName, setDealName] = useState('')
  const [text, setText] = useState('')
  const [fileName, setFileName] = useState<string | undefined>()
  const [monthlyRent, setMonthlyRent] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [extraction, setExtraction] = useState<NoticeExtraction | null>(null)
  const [raw, setRaw] = useState<string | null>(null)
  const [disclaimer, setDisclaimer] = useState('')

  const canSubmit = text.trim().length >= MIN_TEXT_LEN && !busy

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null); setExtraction(null); setRaw(null)
    try {
      track('notice_extract_submit')
      const rent = Number(monthlyRent.replace(/[^0-9]/g, ''))
      const res = await api.noticeExtract({
        dealName: dealName.trim() || undefined,
        text: text.trim(),
        sourceFileName: fileName,
        monthlyRentKrw: rent > 0 ? rent : undefined,
      })
      setExtraction(res.extraction)
      setRaw(res.analysisRaw)
      setDisclaimer(res.disclaimer)
      onCreditBalance(res.creditBalance)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      setError(err instanceof ApiError ? err.message : '공고 분석에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <form className="card" onSubmit={submit}>
        <div className="form-grid">
          <div className="full">
            <label htmlFor="nDeal">딜/자산 이름 <span className="opt">(선택)</span></label>
            <input id="nDeal" value={dealName} onChange={(e) => setDealName(e.target.value)} placeholder="예: 성수동 근생 공매" />
          </div>
          <div className="full">
            <label htmlFor="nRent">월 임대료 <span className="opt">(선택, 원)</span></label>
            <input id="nRent" value={monthlyRent} onChange={(e) => setMonthlyRent(e.target.value)} placeholder="예: 3000000" inputMode="numeric" />
            <span className="field-hint">알고 있으면 총수익률을 코드가 계산합니다. 공고문에는 대개 없는 정보입니다.</span>
          </div>
          <div className="full">
            <label htmlFor="nText">공고문 원문 <span className="req">*</span></label>
            <FileDropzone
              disabled={busy}
              label="공고문 파일(.hwp·PDF)을 끌어다 놓거나 클릭해서 올리기"
              onExtracted={(doc) => { setText(doc.text); setFileName(doc.fileName) }}
            />
            <textarea
              id="nText" rows={8} value={text}
              onChange={(e) => { setText(e.target.value); setFileName(undefined) }}
              placeholder="파일을 올리거나 공고문 전문을 붙여넣으세요. 회차별 저감표가 있으면 함께 넣어 주세요."
            />
          </div>
        </div>

        <div className="actions">
          <button type="submit" className="btn-primary" disabled={!canSubmit}>
            {busy ? '분석 중…' : `공고 분석 · ${costLabel(toolCosts?.NOTICE_EXTRACT)}`}
          </button>
          <p className="hint">
            대상 물건·감정가/최저입찰가·보증금·일정·자격 제한을 정리하고 응찰 리스크를 표시합니다.
            <br />
            평단가·수익률은 AI 가 아니라 코드가 계산합니다.
          </p>
        </div>
        <TrustBadge />
        {error && <p className="error">{error}</p>}
      </form>

      {raw && !extraction && (
        <div className="card">
          <h3>분석 결과</h3>
          <p className="muted">정형 결과를 만들지 못해 원문 그대로 표시합니다.</p>
          <Markdown md={raw} />
        </div>
      )}
      {extraction && <NoticeResult data={extraction} disclaimer={disclaimer} />}
    </>
  )
}

function NoticeResult({ data, disclaimer }: { data: NoticeExtraction; disclaimer: string }) {
  const krw = (v?: number | null) => (v != null && v > 0 ? `${v.toLocaleString()}원` : '자료없음')
  return (
    <div className="card">
      <div className="section-title-row">
        <div>
          <h3>{data.target?.name ?? '공고 분석'}</h3>
          <p className="muted">{[data.notice_type, data.issuer, data.target?.address].filter(Boolean).join(' · ')}</p>
        </div>
      </div>

      {data.summary && <p>{data.summary}</p>}

      <dl className="kv-grid">
        <dt>감정가</dt><dd>{krw(data.price?.appraisal_krw)}</dd>
        <dt>최저입찰가</dt><dd>{krw(data.price?.min_bid_krw)}</dd>
        <dt>보증금</dt><dd>{krw(data.price?.deposit_krw)}{data.price?.deposit_pct ? ` (${data.price.deposit_pct}%)` : ''}</dd>
        <dt>입찰 마감</dt><dd>{data.schedule?.bid_end ?? '자료없음'}</dd>
        <dt>개찰</dt><dd>{data.schedule?.open_date ?? '자료없음'}</dd>
        <dt>잔금 납부</dt><dd>{data.schedule?.payment_by ?? '자료없음'}</dd>
        {data.qualification && (<><dt>응찰 자격</dt><dd>{data.qualification}</dd></>)}
      </dl>

      {data.derived && (
        <section className="scr-section doc-block">
          <h4>코드 산출 지표</h4>
          <dl className="kv-grid">
            <dt>평단가</dt><dd>{krw(data.derived.pyeong_price_krw)}{data.derived.pyeong_price_krw ? '/평' : ''}</dd>
            <dt>기준가</dt><dd>{krw(data.derived.base_price_krw)}</dd>
            <dt>총수익률</dt><dd>{data.derived.gross_yield_pct != null ? `${data.derived.gross_yield_pct}%` : '월 임대료 미입력'}</dd>
          </dl>
          <p className="field-hint">{data.derived.note}</p>
        </section>
      )}

      {data.items && data.items.length > 1 && (
        <section className="scr-section doc-block">
          <h4>개별 물건 {data.items.length}건</h4>
          <div className="table-scroll">
            <table className="doc-table">
              <thead>
                <tr><th>번호</th><th>표시</th><th>용도</th><th>면적(㎡)</th><th>감정가</th><th>평단가</th></tr>
              </thead>
              <tbody>
                {data.items.map((it, i) => (
                  <tr key={`${it.no}-${i}`}>
                    <td>{it.no ?? i + 1}</td>
                    <td>{it.unit ?? '-'}</td>
                    <td>{it.use ?? '-'}</td>
                    <td>{it.area_m2 ?? '-'}</td>
                    <td>{krw(it.appraisal_krw)}</td>
                    <td>{krw(it.pyeong_price_krw)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {data.risks && data.risks.length > 0 && (
        <section className="scr-section doc-block">
          <h4>응찰 리스크 {data.risks.length}건</h4>
          <ul className="risk-list">
            {data.risks.map((r, i) => (
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

      <StringList title="특약·부대조건" items={data.special_terms} />
      {disclaimer && <p className="disclaimer">{disclaimer}</p>}
    </div>
  )
}

/* ────────────────────────── 공용 ────────────────────────── */

function StringList({ title, items, empty }: { title: string; items?: string[]; empty?: string }) {
  if (!items || items.length === 0) {
    return empty ? <section className="scr-section doc-block"><h4>{title}</h4><p className="muted">{empty}</p></section> : null
  }
  return (
    <section className="scr-section doc-block">
      <h4>{title} {items.length}건</h4>
      <ul className="bullet-list">
        {items.map((s, i) => <li key={`${s}-${i}`}>{s}</li>)}
      </ul>
    </section>
  )
}
