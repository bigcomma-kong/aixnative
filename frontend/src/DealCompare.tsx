import { useEffect, useMemo, useState } from 'react'
import { api, ApiError, type RunDetail, type RunSummary } from './api'
import { toolLabel } from './ResultModal'

/** 비교 가능한 메트릭 정의(전부 높을수록 양호 — 정규화 단순화). */
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

export function DealCompare({ onClose }: { onClose: () => void }) {
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [selected, setSelected] = useState<number[]>([])
  const [details, setDetails] = useState<Record<number, RunDetail>>({})
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.runs()
      .then((list) => setRuns(list.filter((r) => r.status === 'SUCCESS')))
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
              <p className="hint">SUCCESS 상태의 분석 이력이 없습니다.</p>
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
                {/* 범례 — 어떤 색이 어떤 딜이고 어떤 자산유형인지 명확히 */}
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

                <div className="compare-table-wrap">
                  <table className="compare-table">
                    <thead>
                      <tr>
                        <th>지표</th>
                        {chosen.map((d, i) => (
                          <th key={d.id}>
                            <span className="ct-head">
                              <span className="ct-name"><span className="ct-dot" style={{ background: SERIES_COLORS[i] }} />{d.dealName ?? `#${d.id}`}</span>
                              <span className="ct-type">{d.request?.assetType ?? toolLabel(d.tool)}</span>
                            </span>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {METRICS.map((m) => (
                        <tr key={m.key}>
                          <td className="ct-metric">{m.label}</td>
                          {chosen.map((d) => {
                            const v = m.get(d)
                            return <td key={d.id} className="num">{v != null && Number.isFinite(v) ? `${v.toFixed(m.key === 'em' || m.key === 'dscr' ? 2 : 1)}${m.unit}` : '–'}</td>
                          })}
                        </tr>
                      ))}
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
