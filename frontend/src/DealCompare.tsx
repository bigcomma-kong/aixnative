import { useEffect, useMemo, useState } from 'react'
import { api, ApiError, type RunDetail, type RunSummary } from './api'
import { toolLabel } from './ResultModal'

/** 비교 가능한 메트릭 정의(전부 높을수록 양호 - 정규화 단순화). */
const METRICS = [
  { key: 'irr', label: 'Levered IRR', unit: '%', get: (d: RunDetail) => d.result?.proForma.leveredIrrPct },
  { key: 'em', label: 'Equity Multiple', unit: '×', get: (d: RunDetail) => d.result?.proForma.equityMultiple },
  { key: 'yoc', label: 'Yield on Cost', unit: '%', get: (d: RunDetail) => d.result?.proForma.yieldOnCostPct },
  { key: 'cap', label: 'Going-in Cap', unit: '%', get: (d: RunDetail) => d.result?.proForma.goingInCapPct },
  { key: 'dscr', label: 'Min DSCR', unit: '', get: (d: RunDetail) => minDscr(d) },
] as const

function minDscr(d: RunDetail): number | undefined {
  const s = d.result?.scenarios ?? []
  if (s.length === 0) return undefined
  return Math.min(...s.map((x) => x.minDscr).filter((n) => Number.isFinite(n)))
}

const SERIES_COLORS = ['oklch(56% 0.17 266)', 'oklch(62% 0.15 150)', 'oklch(66% 0.16 40)']
const MAX_SELECT = 3

/**
 * 비교 가능한 도구 = ProForma 를 결과에 담는 언더라이팅 파이프라인 4단계뿐.
 * 심화(심층 시장 리포트·BOV·개발타당성 등)는 ProForma 가 없어 비교 지표가 없으므로 목록에서 제외한다.
 */
const PIPELINE_TOOLS = new Set(['DEAL_SCREENING', 'MARKET_STUDY', 'UNDERWRITING_NARRATIVE', 'IC_MEMO'])

export function DealCompare({ onClose }: { onClose: () => void }) {
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [selected, setSelected] = useState<number[]>([])
  const [details, setDetails] = useState<Record<number, RunDetail>>({})
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.runs()
      .then((list) => {
        // 같은 딜끼리는 ProForma 가 동일해 비교가 무의미 → 딜(PK)당 최신 1건만.
        // api.runs 는 최신순이므로 첫 등장이 대표. 딜 id 없는 런은 개별 유지.
        // 파이프라인(ProForma 보유) 런만 대상 — 심화 런이 대표를 가로채 'ProForma 없음'이 뜨던 문제 차단.
        const seen = new Set<number>()
        const deduped = list
          .filter((r) => r.status === 'SUCCESS')
          .filter((r) => PIPELINE_TOOLS.has(r.tool))
          .filter((r) => {
            if (r.dealId == null) return true
            if (seen.has(r.dealId)) return false
            seen.add(r.dealId)
            return true
          })
        setRuns(deduped)
      })
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '이력 조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  async function toggle(id: number) {
    setError(null)
    if (selected.includes(id)) {
      setSelected((s) => s.filter((x) => x !== id))
      return
    }
    if (selected.length >= MAX_SELECT) { setError(`최대 ${MAX_SELECT}개까지 비교할 수 있습니다.`); return }
    if (!details[id]) {
      try {
        const d = await api.run(id)
        if (!d.result?.proForma) { setError('이 분석에는 비교할 ProForma 지표가 없습니다.'); return }
        setDetails((m) => ({ ...m, [id]: d }))
      } catch (e: unknown) {
        setError(e instanceof ApiError ? e.message : '상세 조회 실패'); return
      }
    }
    setSelected((s) => [...s, id])
  }

  const chosen = useMemo(() => selected.map((id) => details[id]).filter(Boolean) as RunDetail[], [selected, details])

  // 지표별 최고값(강조·미니바 정규화 분모) + 딜별 '우세'(단독 1등) 집계.
  const rowMax = useMemo(
    () => METRICS.map((m) => {
      const finite = chosen.map((d) => m.get(d)).filter((v): v is number => v != null && Number.isFinite(v))
      return finite.length ? Math.max(...finite) : undefined
    }),
    [chosen],
  )
  const wins = useMemo(() => {
    const w = chosen.map(() => 0)
    METRICS.forEach((m, mi) => {
      const max = rowMax[mi]
      if (max == null) return
      const leaders = chosen
        .map((d, i) => ({ v: m.get(d), i }))
        .filter((x) => x.v != null && Number.isFinite(x.v) && x.v === max)
      if (leaders.length === 1) w[leaders[0].i] += 1 // 동점은 우세로 치지 않음
    })
    return w
  }, [chosen, rowMax])
  const decimals = (key: string) => (key === 'em' || key === 'dscr' ? 2 : 1)

  return (
    <div className="analyze-overlay" role="dialog" aria-modal="true" aria-label="딜 비교" onClick={onClose}>
      <div className="compare-modal" onClick={(e) => e.stopPropagation()}>
        <div className="compare-head">
          <strong>딜 비교 · 핵심 지표 + 레이더</strong>
          <button className="deep-close" onClick={onClose} aria-label="닫기">×</button>
        </div>
        {error && <p className="error">{error}</p>}

        <div className="compare-body">
          <div className="compare-pick">
            <div className="compare-pick-label">분석 선택 (최대 {MAX_SELECT})</div>
            {loading ? <p className="hint">불러오는 중…</p> : runs.length === 0 ? (
              <p className="hint">비교할 언더라이팅 분석이 없습니다. (스크리닝·시장조사·언더라이팅·투심 단계만 비교되며, 심화 리포트는 제외됩니다)</p>
            ) : (
              <ul className="compare-list">
                {runs.map((r) => {
                  const on = selected.includes(r.id)
                  return (
                    <li key={r.id}>
                      <button className={`compare-item${on ? ' on' : ''}`} onClick={() => void toggle(r.id)}>
                        <span className="ci-check">{on ? '✓' : ''}</span>
                        <span className="ci-main">
                          <span className="ci-name">{r.dealName ?? '(이름없음)'}</span>
                          <span className="ci-meta">
                            <span className="ci-tool">{toolLabel(r.tool)}</span>
                            {r.createdAt && <span className="ci-date">{new Date(r.createdAt).toLocaleDateString('ko-KR')}</span>}
                          </span>
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>

          <div className="compare-view">
            {chosen.length < 2 ? (
              <p className="compare-empty">비교할 분석을 <b>2개 이상</b> 선택하세요.</p>
            ) : (
              <>
                {/* 범례 - 어떤 색이 어떤 딜이고 어떤 자산유형인지 명확히 */}
                <div className="compare-legend">
                  {chosen.map((d, i) => (
                    <span key={d.id} className="cl-item">
                      <span className="ct-dot" style={{ background: SERIES_COLORS[i] }} />
                      <b className="cl-name">{d.dealName ?? `#${d.id}`}</b>
                      {d.request?.assetType && <span className="cl-type">{d.request.assetType}</span>}
                    </span>
                  ))}
                </div>

                <RadarChart deals={chosen} />

                <p className="compare-caption">↑ 값이 높을수록 유리 · 각 지표의 <b>최고값</b>을 강조합니다</p>
                <div className="compare-table-wrap">
                  <table className="compare-table">
                    <thead>
                      <tr>
                        <th>지표</th>
                        {chosen.map((d, i) => (
                          <th key={d.id}>
                            <span className="ct-head">
                              <span className="ct-name"><span className="ct-dot" style={{ background: SERIES_COLORS[i] }} />{d.dealName ?? `#${d.id}`}</span>
                              <span className="ct-sub">
                                <span className="ct-type">{d.request?.assetType ?? toolLabel(d.tool)}</span>
                                {wins[i] > 0 && <span className="ct-wins" title="단독 1등 지표 수">우세 {wins[i]}</span>}
                              </span>
                            </span>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {METRICS.map((m, mi) => {
                        const max = rowMax[mi]
                        return (
                          <tr key={m.key}>
                            <td className="ct-metric">{m.label}{m.unit && <span className="ct-unit">{m.unit}</span>}</td>
                            {chosen.map((d, ci) => {
                              const v = m.get(d)
                              const isNum = v != null && Number.isFinite(v)
                              const best = isNum && max != null && v === max
                              const ratio = isNum && max ? Math.max(0.04, Math.min(1, v / max)) : 0
                              return (
                                <td key={d.id} className={`num cc${best ? ' best' : ''}`}>
                                  {isNum && <span className="cc-bar" style={{ width: `${ratio * 100}%`, background: SERIES_COLORS[ci] }} />}
                                  <span className="cc-val">
                                    {isNum ? `${v.toFixed(decimals(m.key))}${m.unit}` : '–'}
                                    {best && <span className="cc-crown" aria-label="최고">▲</span>}
                                  </span>
                                </td>
                              )
                            })}
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

/** 손수 그린 SVG 레이더(차트 라이브러리 의존 없음). 각 지표를 선택 집합 내 최대값 기준 정규화. */
function RadarChart({ deals }: { deals: RunDetail[] }) {
  const size = 300, cx = size / 2, cy = size / 2, R = 110
  const axes = METRICS
  const n = axes.length

  // 각 지표의 최대값(정규화 분모). 0 이거나 음수면 1 로.
  const maxByMetric = axes.map((m) => {
    const vals = deals.map((d) => m.get(d)).filter((v): v is number => v != null && Number.isFinite(v))
    const mx = vals.length ? Math.max(...vals) : 1
    return mx > 0 ? mx : 1
  })

  const angleAt = (i: number) => -Math.PI / 2 + (i * 2 * Math.PI) / n
  const point = (i: number, ratio: number) => {
    const a = angleAt(i)
    const r = R * Math.max(0.05, Math.min(1, ratio))
    return [cx + r * Math.cos(a), cy + r * Math.sin(a)] as const
  }

  return (
    <svg className="radar" viewBox={`0 0 ${size} ${size}`} role="img" aria-label="딜 지표 레이더 차트">
      {[0.25, 0.5, 0.75, 1].map((g) => (
        <polygon
          key={g}
          className="radar-grid"
          points={axes.map((_, i) => point(i, g).join(',')).join(' ')}
        />
      ))}
      {axes.map((m, i) => {
        const [x, y] = point(i, 1.12)
        const [lx, ly] = point(i, 1)
        return (
          <g key={m.key}>
            <line className="radar-axis" x1={cx} y1={cy} x2={lx} y2={ly} />
            <text className="radar-label" x={x} y={y} textAnchor="middle" dominantBaseline="middle">{m.label}</text>
          </g>
        )
      })}
      {deals.map((d, di) => {
        const pts = axes.map((m, i) => {
          const v = m.get(d)
          const ratio = v != null && Number.isFinite(v) ? v / maxByMetric[i] : 0
          return point(i, ratio).join(',')
        }).join(' ')
        return <polygon key={d.id} className="radar-series" points={pts} style={{ stroke: SERIES_COLORS[di], fill: SERIES_COLORS[di] }} />
      })}
    </svg>
  )
}
