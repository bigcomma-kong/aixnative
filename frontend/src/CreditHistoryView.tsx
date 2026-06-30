import { useEffect, useState } from 'react'
import { api, ApiError, type BillingHistory, type CreditReason, type RunSummary } from './api'
import { Paywall } from './Paywall'
import { ResultModal, toolLabel } from './ResultModal'

const REASON_LABEL: Record<CreditReason, string> = {
  SIGNUP_GRANT: '가입 무료 지급',
  AI_ANALYSIS: 'AI 분석',
  PURCHASE: '크레딧 충전',
  ADMIN_ADJUST: '관리자 조정',
}

interface CreditHistoryViewProps {
  /** 잔액 변동을 상위 세션과 동기화 (헤더 표시용). */
  onSync?: (plan: 'FREE' | 'PAID', creditBalance: number) => void
}

/** 크레딧 내역(원장) 화면: 플랜·잔액 요약 + 지급/차감 기록. */
export function CreditHistoryView({ onSync }: CreditHistoryViewProps) {
  const [data, setData] = useState<BillingHistory | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    api.history()
      .then((d) => {
        if (!active) return
        setData(d)
        onSync?.(d.plan, d.creditBalance)
      })
      .catch((err: unknown) => { if (active) setError(err instanceof ApiError ? err.message : '내역 조회 실패') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
    // onSync 는 매 렌더 새 함수일 수 있어 의존성에서 제외(최초 1회 로드).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (loading) return <div className="card"><p className="hist-empty">불러오는 중…</p></div>
  if (error) return <div className="card"><p className="error">{error}</p></div>
  if (!data) return null

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">사용 내역</span>
          <h1>크레딧 사용 현황</h1>
        </div>
      </div>

      <div className="credit-summary card">
        <div className="cs-item">
          <span className="cs-k">현재 플랜</span>
          <span className="cs-v"><span className={`plan-pill ${data.plan.toLowerCase()}`}>{data.plan === 'FREE' ? '무료' : '유료'}</span></span>
        </div>
        <div className="cs-item">
          <span className="cs-k">남은 크레딧</span>
          <span className="cs-v num">{data.creditBalance}</span>
        </div>
      </div>

      {data.creditBalance <= 1 && <Paywall creditBalance={data.creditBalance} variant="banner" />}

      <div className="card">
        <div className="section-title">크레딧 원장</div>
        {data.entries.length === 0 ? (
          <p className="hist-empty">아직 크레딧 변동 내역이 없습니다.</p>
        ) : (
          <table>
            <thead><tr><th>일시</th><th>내역</th><th>증감</th></tr></thead>
            <tbody>
              {data.entries.map((e) => (
                <tr key={e.id}>
                  <td>{new Date(e.createdAt).toLocaleString('ko-KR')}</td>
                  <td>
                    {REASON_LABEL[e.reason] ?? e.reason}
                    {e.ref && <span className="ledger-ref">{e.ref}</span>}
                  </td>
                  <td className={`num ${e.delta >= 0 ? 'pos' : 'neg'}`}>{e.delta >= 0 ? `+${e.delta}` : e.delta}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <UsageResults />
    </>
  )
}

/** 내가 크레딧을 쓴 분석들의 실제 결과 — 클릭하면 모달로 다시 본다(무료). */
function UsageResults() {
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [open, setOpen] = useState<{ run: RunSummary; result: unknown; request: unknown } | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  useEffect(() => {
    api.runs()
      .then((list) => setRuns(list.filter((r) => r.status === 'SUCCESS')))
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '분석 이력 조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  async function view(run: RunSummary) {
    setBusyId(run.id); setError(null)
    try {
      const detail = await api.run(run.id)
      setOpen({ run, result: detail.result, request: detail.request })
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '결과를 불러오지 못했습니다.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="card">
      <div className="section-title">내 분석 결과</div>
      {error && <p className="error">{error}</p>}
      {loading ? (
        <p className="hist-empty">불러오는 중…</p>
      ) : runs.length === 0 ? (
        <p className="hist-empty">아직 분석 결과가 없습니다. 분석을 실행하면 여기서 다시 볼 수 있어요.</p>
      ) : (
        <table>
          <thead><tr><th>딜</th><th>유형</th><th>일시</th><th></th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td>{r.dealName ?? '(이름 없음)'}</td>
                <td>{toolLabel(r.tool)}</td>
                <td className="num">{r.createdAt ? new Date(r.createdAt).toLocaleString('ko-KR') : '-'}</td>
                <td><button className="btn-link" disabled={busyId === r.id} onClick={() => void view(r)}>{busyId === r.id ? '여는 중…' : '결과 보기'}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {open && <ResultModal run={open.run} result={open.result} request={open.request} onClose={() => setOpen(null)} />}
    </div>
  )
}
