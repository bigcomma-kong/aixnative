import { useState } from 'react'
import { api, type UnderwriteInput, type ProFormaResponse } from './api'

interface PublicCalcProps {
  /** 결과 아래 업셀에서 회원가입(무료 크레딧 → AI 분석)으로 유도. */
  onSignup: () => void
}

interface Fields {
  askingPriceEok: string
  noiEok: string
  ltvPct: string
  loanRatePct: string
  exitCapPct: string
}

const DEFAULTS: Fields = { askingPriceEok: '', noiEok: '', ltvPct: '55', loanRatePct: '4.5', exitCapPct: '5.0' }

const FIELD_META: { key: keyof Fields; label: string; suffix: string; placeholder: string }[] = [
  { key: 'askingPriceEok', label: '매입가', suffix: '억', placeholder: '1800' },
  { key: 'noiEok', label: 'NOI (연 순영업이익)', suffix: '억', placeholder: '81' },
  { key: 'ltvPct', label: 'LTV (대출비율)', suffix: '%', placeholder: '55' },
  { key: 'loanRatePct', label: '대출금리', suffix: '%', placeholder: '4.5' },
  { key: 'exitCapPct', label: 'Exit Cap', suffix: '%', placeholder: '5.0' },
]

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

function fmt(n: number | undefined, digits = 1): string {
  return n === undefined || !Number.isFinite(n) ? '-' : n.toFixed(digits)
}

/**
 * 무인증 공개 ProForma 계산기(리드마그넷). 가입·크레딧·저장 없이 IRR·EM·DSCR 즉시 계산.
 * 결과 하단에 이메일 리드 캡처 + 'AI 심층 분석은 가입' 업셀. 백엔드=/api/public/proforma·/api/public/lead.
 */
export function PublicCalc({ onSignup }: PublicCalcProps) {
  const [fields, setFields] = useState<Fields>(DEFAULTS)
  const [result, setResult] = useState<ProFormaResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [email, setEmail] = useState('')
  const [leadState, setLeadState] = useState<'idle' | 'sending' | 'sent'>('idle')

  function set(key: keyof Fields, value: string) {
    setFields((s) => ({ ...s, [key]: value }))
  }

  async function calculate() {
    setError(null)
    const price = parseFloat(fields.askingPriceEok)
    const noi = parseFloat(fields.noiEok)
    if (!(price > 0) || !(noi > 0)) {
      setError('매입가와 NOI를 입력해 주세요.')
      return
    }
    const input: UnderwriteInput = {
      askingPriceEok: price,
      noiEok: noi,
      ltvPct: parseFloat(fields.ltvPct) || 0,
      loanRatePct: parseFloat(fields.loanRatePct) || 0,
      exitCapPct: parseFloat(fields.exitCapPct) || (noi / price) * 100,
      holdYears: 5,
    }
    setLoading(true)
    try {
      setResult(await api.publicProforma(input))
    } catch {
      setError('계산에 실패했습니다. 입력값을 확인해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  async function sendLead() {
    if (!EMAIL_RE.test(email)) {
      setError('올바른 이메일을 입력해 주세요.')
      return
    }
    setError(null)
    setLeadState('sending')
    try {
      await api.captureLead(email, 'FREE_PROFORMA', true)
      setLeadState('sent')
    } catch {
      setLeadState('idle')
      setError('전송에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    }
  }

  const pf = result?.proForma
  const minDscr = pf && pf.proForma.length
    ? Math.min(...pf.proForma.map((r) => r.dscr).filter((x) => Number.isFinite(x)))
    : undefined

  return (
    <div className="pcalc">
      <div className="pcalc-form">
        <div className="pcalc-grid">
          {FIELD_META.map((m) => (
            <label className="pcalc-field" key={m.key}>
              <span>{m.label}</span>
              <div className="pcalc-input">
                <input
                  inputMode="decimal"
                  value={fields[m.key]}
                  placeholder={m.placeholder}
                  onChange={(e) => set(m.key, e.target.value)}
                />
                <i>{m.suffix}</i>
              </div>
            </label>
          ))}
        </div>
        <button className="btn-primary pcalc-run" type="button" onClick={calculate} disabled={loading}>
          {loading ? '계산 중…' : '무료로 계산하기'}
        </button>
        {error && <p className="pcalc-error" role="alert">{error}</p>}
      </div>

      {pf && (
        <div className="pcalc-result">
          <div className="pcalc-kpis">
            <div className="pcalc-kpi"><span>IRR</span><b className="num">{fmt(pf.leveredIrrPct)}%</b></div>
            <div className="pcalc-kpi"><span>Equity Multiple</span><b className="num">{fmt(pf.equityMultiple, 2)}x</b></div>
            <div className="pcalc-kpi"><span>최소 DSCR</span><b className="num">{fmt(minDscr, 2)}</b></div>
            <div className="pcalc-kpi"><span>Going-in Cap</span><b className="num">{fmt(pf.goingInCapPct)}%</b></div>
          </div>

          <div className="pcalc-upsell">
            <p><strong>AI 심층 분석</strong>으로 스크리닝·시장조사·언더라이팅·투심 내러티브까지 받아보세요. 가입 시 무료 크레딧을 드립니다.</p>
            <button className="btn-primary" type="button" onClick={onSignup}>무료 가입하고 AI 분석 받기 →</button>
          </div>

          <div className="pcalc-lead">
            {leadState === 'sent' ? (
              <p className="pcalc-lead-done">✓ 이메일로 결과와 시장 브리핑을 보내드릴게요.</p>
            ) : (
              <>
                <input
                  type="email"
                  placeholder="이메일로 결과 받기"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
                <button className="btn-ghost" type="button" onClick={sendLead} disabled={leadState === 'sending'}>
                  {leadState === 'sending' ? '전송 중…' : '결과 받기'}
                </button>
              </>
            )}
          </div>

          {result?.disclaimer && <p className="pcalc-disc">{result.disclaimer}</p>}
        </div>
      )}
    </div>
  )
}
