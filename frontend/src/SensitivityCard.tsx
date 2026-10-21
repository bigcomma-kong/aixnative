import { useEffect, useState } from 'react'
import { api, type SensitivityResult, type UnderwriteInput } from './api'

/**
 * 민감도 - 토네이도(변수별 IRR 영향 폭) + 27개 시나리오 분포(p10·p50·p90).
 * 무료 ProForma 재계산 결과에 포함된 값을 그린다(AI 미사용). 입력이 바뀔 때만 다시 계산한다.
 */
export function SensitivityCard({ inputs }: { inputs: UnderwriteInput }) {
  const [data, setData] = useState<SensitivityResult | null>(null)
  const key = JSON.stringify(inputs)

  useEffect(() => {
    let alive = true
    api.proforma(inputs)
      .then((r) => { if (alive) setData(r.sensitivity ?? null) })
      .catch(() => { if (alive) setData(null) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  if (!data) return null
  const base = data.baseIrrPct
  const maxDev = Math.max(0.1, ...data.tornado.flatMap((t) => [Math.abs(t.lowIrrPct - base), Math.abs(t.highIrrPct - base)]))
  const pos = (v: number) => 50 + ((v - base) / maxDev) * 46

  return (
    <section className="sens-card" aria-label="민감도 분석">
      <div className="section-title">민감도 <span className="rule-tag">코드 계산 · 27개 시나리오</span></div>
      <div className="sens-dist">
        <div><span>비관(p10)</span><b className={data.p10IrrPct < 0 ? 'neg' : undefined}>{data.p10IrrPct}%</b></div>
        <div><span>중앙(p50)</span><b>{data.p50IrrPct}%</b></div>
        <div><span>낙관(p90)</span><b>{data.p90IrrPct}%</b></div>
        <div><span>손실 시나리오</span><b className={data.negativeCount > 0 ? 'neg' : undefined}>{data.negativeCount}/27</b></div>
      </div>
      <p className="field-hint">Exit Cap ±10% · 임대 성장 ±1%p · 대출금리 ±10%를 조합한 27개 경우의 레버리지 IRR 분포입니다. 아래는 변수 하나만 흔들었을 때의 영향 폭(큰 순)입니다.</p>
      <ul className="tornado">
        {data.tornado.map((t) => {
          const l = pos(Math.min(t.lowIrrPct, t.highIrrPct))
          const r = pos(Math.max(t.lowIrrPct, t.highIrrPct))
          return (
            <li key={t.variable}>
              <span className="tor-label">{t.variable}</span>
              <div className="tor-track">
                <i className="tor-axis" style={{ left: '50%' }} />
                <i className="tor-bar tor-low" style={{ left: `${l}%`, width: `${Math.max(0.5, 50 - l)}%` }} title={`${t.lowLabel}: ${t.lowIrrPct}%`} />
                <i className="tor-bar tor-high" style={{ left: '50%', width: `${Math.max(0.5, r - 50)}%` }} title={`${t.highLabel}: ${t.highIrrPct}%`} />
              </div>
              <span className="tor-range">{t.lowIrrPct}% ~ {t.highIrrPct}%</span>
            </li>
          )
        })}
      </ul>
      <p className="field-hint">기준 IRR {base}% · 빨강 = 불리한 쪽, 초록 = 유리한 쪽</p>
    </section>
  )
}
