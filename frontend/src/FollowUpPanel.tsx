import { useEffect, useRef, useState } from 'react'
import { api, ApiError, parseCritique, track, type CritiqueResult, type FollowUpThread, type FollowUpTurn } from './api'
import { Markdown } from './Markdown'

interface FollowUpPanelProps {
  runId: number
  /** 분석유형 id → 크레딧 단가(서버 단일 소스). 미로딩 시 숫자 생략. */
  toolCosts?: Record<string, number>
  onCreditBalance?: (balance: number) => void
  /** 크레딧 소진(402) - 주어지면 중앙 페이월, 없으면 패널 안 안내. */
  onNeedCredits?: () => void
}

type Busy = 'none' | 'ask' | 'suggest' | 'critique'

const VERDICT: Record<string, { label: string; tone: string }> = {
  ENDORSE: { label: '검토의견 동의', tone: 'go' },
  REVISE: { label: '보완 권고', tone: 'cond' },
  CHALLENGE: { label: '재검토 필요', tone: 'no' },
}

const SEVERITY_LABEL: Record<string, string> = { HIGH: '높음', MEDIUM: '중간', LOW: '낮음' }

function costLabel(cost?: number): string {
  return cost != null ? `${cost}크레딧` : '크레딧'
}

/**
 * 분석 결과 후속 논의 - 질문하기·검토 포인트 제안·반박 검토.
 * 저장된 결과(+같은 딜의 다른 단계 결과)만 근거로 답하고, 원본 결과는 바꾸지 않는다.
 */
export function FollowUpPanel({ runId, toolCosts, onCreditBalance, onNeedCredits }: FollowUpPanelProps) {
  const [turns, setTurns] = useState<FollowUpTurn[]>([])
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState<Busy>('none')
  const [error, setError] = useState<string | null>(null)
  // 응답이 오는 사이 다른 결과로 바뀌었으면 이전 결과의 스레드를 덮어쓰지 않는다.
  const currentRun = useRef(runId)
  currentRun.current = runId

  useEffect(() => {
    let alive = true
    setTurns([])
    setError(null)
    api.followUpThread(runId)
      .then((t) => { if (alive) setTurns(t.turns) })
      .catch(() => { /* 스레드 조회 실패는 빈 스레드로 둔다(분석 결과 표시는 막지 않음) */ })
    return () => { alive = false }
  }, [runId])

  async function run(kind: Exclude<Busy, 'none'>, call: () => Promise<FollowUpThread>) {
    setBusy(kind)
    setError(null)
    try {
      const res = await call()
      onCreditBalance?.(res.creditBalance)
      if (res.runId !== currentRun.current) return
      setTurns(res.turns)
      track('analysis_done', { path: 'followup', meta: kind })
      if (kind === 'ask') setQuestion('')
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) {
        if (onNeedCredits) onNeedCredits()
        else setError('크레딧이 부족합니다. 크레딧을 충전한 뒤 다시 시도해 주세요.')
      } else {
        setError(err instanceof ApiError ? err.message : '요청 중 오류가 발생했습니다.')
      }
    } finally {
      setBusy('none')
    }
  }

  const canAsk = question.trim().length > 0 && busy === 'none'
  const submitAsk = () => { if (canAsk) void run('ask', () => api.followUpAsk(runId, question.trim())) }

  return (
    <section className="fu-panel" aria-label="후속 논의">
      <div className="fu-head">
        <div>
          <div className="section-title">이 결과로 더 묻기</div>
          <p className="fu-sub">저장된 분석 결과와 같은 딜의 다른 단계 결과만 근거로 답합니다. 원래 결과는 바뀌지 않습니다.</p>
        </div>
        <div className="fu-actions">
          <button type="button" className="btn-ghost btn-xs" disabled={busy !== 'none'}
            onClick={() => void run('suggest', () => api.followUpSuggest(runId))}>
            {busy === 'suggest' ? '제안 만드는 중…' : `검토 포인트 제안 · ${costLabel(toolCosts?.FOLLOWUP_SUGGEST)}`}
          </button>
          <button type="button" className="btn-ghost btn-xs fu-critique-btn" disabled={busy !== 'none'}
            onClick={() => void run('critique', () => api.followUpCritique(runId))}
            title="독립 검토위원 관점에서 결론의 약점·무효화 조건을 찾습니다">
            {busy === 'critique' ? '반박 검토 중…' : `반박 검토 · ${costLabel(toolCosts?.CRITIQUE)}`}
          </button>
        </div>
      </div>

      {turns.length > 0 && (
        <ol className="fu-thread">
          {turns.map((t) => <TurnItem key={t.id} turn={t} />)}
        </ol>
      )}

      <form className="fu-ask" onSubmit={(e) => { e.preventDefault(); submitAsk() }}>
        <textarea
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submitAsk() } }}
          placeholder="예: 어느 가격대부터 검토할 만한가요? 금리가 1%p 오르면 결론이 바뀌나요?"
          maxLength={2000}
          rows={2}
          aria-label="후속 질문"
          disabled={busy !== 'none'}
        />
        <button type="submit" className="btn-primary" disabled={!canAsk}>
          {busy === 'ask' ? '답변 작성 중…' : `질문하기 · ${costLabel(toolCosts?.FOLLOWUP_ASK)}`}
        </button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  )
}

function TurnItem({ turn }: { turn: FollowUpTurn }) {
  if (turn.role === 'USER') {
    return <li className="fu-turn fu-user"><p>{turn.content}</p></li>
  }
  if (turn.role === 'CRITIQUE') {
    const c = parseCritique(turn.content)
    return <li className="fu-turn fu-ai">{c ? <CritiqueCard c={c} /> : <p>{turn.content}</p>}</li>
  }
  return (
    <li className="fu-turn fu-ai">
      {turn.role === 'SUGGEST' && <span className="fu-tag">AI 검토 포인트</span>}
      <div className="fu-md"><Markdown md={turn.content} /></div>
    </li>
  )
}

function CritiqueCard({ c }: { c: CritiqueResult }) {
  const v = VERDICT[c.verdict ?? ''] ?? { label: c.verdict ?? '검토 의견', tone: 'cond' }
  const issues = c.issues ?? []
  const invalidation = c.invalidation ?? []
  return (
    <div className="fu-critique">
      <div className="fu-critique-head">
        <span className="fu-tag">반박 검토</span>
        <span className={`fu-verdict tone-${v.tone}`}>{v.label}</span>
        {c.confidence && <span className="fu-conf">신뢰도 {SEVERITY_LABEL[c.confidence] ?? c.confidence}</span>}
      </div>
      {c.summary && <p className="fu-critique-summary">{c.summary}</p>}
      {issues.length > 0 && (
        <ul className="fu-issues">
          {issues.map((it, i) => (
            <li key={i}>
              <span className={`fu-sev sev-${(it.severity ?? '').toLowerCase()}`}>{SEVERITY_LABEL[it.severity ?? ''] ?? it.severity ?? '-'}</span>
              <div>
                <b>{it.point}</b>
                {it.evidence && <span className="fu-evidence">{it.evidence}</span>}
              </div>
            </li>
          ))}
        </ul>
      )}
      {invalidation.length > 0 && (
        <div className="fu-invalid">
          <span className="fu-invalid-title">이 조건이 확인되면 결론을 다시 보세요</span>
          <ul>{invalidation.map((x, i) => <li key={i}>{x}</li>)}</ul>
        </div>
      )}
    </div>
  )
}
