import type { YearRow } from './api'

/**
 * 연차별 Levered Cash Flow(막대) + DSCR(라인)를 한 평면에 겹쳐 그리는 SVG 차트.
 * 외부 차트 라이브러리 없이 디자인 시스템 토큰(CSS 변수)으로 렌더한다.
 * - 막대: leveredCf (음수면 빨강)
 * - 라인: DSCR, 오른쪽 가상 스케일. DSCR 1.2 임계선을 점선으로 표시.
 */
const DSCR_THRESHOLD = 1.2

interface CashflowChartProps {
  rows: YearRow[]
}

export function CashflowChart({ rows }: CashflowChartProps) {
  if (rows.length === 0) return null

  const W = 620
  const H = 260
  const padL = 16
  const padR = 16
  const padT = 18
  const padB = 28
  const plotW = W - padL - padR
  const plotH = H - padT - padB

  // ── CF 스케일 (0 기준선 포함, 유한값만으로 도메인 산정) ──
  const cfValues = rows.map((r) => r.leveredCf).filter((v) => Number.isFinite(v))
  const cfMax = Math.max(0, ...cfValues)
  const cfMin = Math.min(0, ...cfValues)
  const cfSpan = cfMax - cfMin || 1
  const cfY = (v: number) => padT + ((cfMax - v) / cfSpan) * plotH
  const zeroY = cfY(0)

  // ── DSCR 스케일 ──
  const dscrVals = rows.map((r) => r.dscr).filter((d) => Number.isFinite(d) && d > 0)
  const dscrMax = Math.max(1.6, ...dscrVals, DSCR_THRESHOLD)
  const dscrMin = Math.min(0.8, ...dscrVals)
  const dscrSpan = dscrMax - dscrMin || 1
  const dscrY = (v: number) => padT + ((dscrMax - v) / dscrSpan) * plotH

  const n = rows.length
  const slot = plotW / n
  const barW = Math.min(48, slot * 0.5)
  const cx = (i: number) => padL + slot * i + slot / 2

  // DSCR 라인/점은 유한값(차입 있는 연차)만 그린다. 무차입이면 이자=0 → DSCR 무한대.
  const dscrPoints = rows
    .map((r, i) => ({ i, d: r.dscr }))
    .filter((p) => Number.isFinite(p.d) && p.d > 0)
  const dscrPath = dscrPoints
    .map((p, k) => `${k === 0 ? 'M' : 'L'} ${cx(p.i).toFixed(1)} ${dscrY(p.d).toFixed(1)}`)
    .join(' ')

  // 가로 그리드 (CF 기준 4분할)
  const gridLines = [0, 0.25, 0.5, 0.75, 1].map((t) => padT + t * plotH)

  return (
    <div className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="연차별 레버드 현금흐름과 DSCR 추이">
        {gridLines.map((y, i) => (
          <line key={i} className="grid-line" x1={padL} y1={y} x2={W - padR} y2={y} />
        ))}

        {/* 0 기준선 (CF) */}
        <line className="grid-line" x1={padL} y1={zeroY} x2={W - padR} y2={zeroY} strokeWidth={1.5} />

        {/* DSCR 1.2 임계선 (라벨은 막대·라인 위에 그려 가림 방지 → 하단에서 렌더) */}
        <line className="thresh" x1={padL} y1={dscrY(DSCR_THRESHOLD)} x2={W - padR} y2={dscrY(DSCR_THRESHOLD)} />

        {/* CF 막대 (유한값만) */}
        {rows.map((r, i) => {
          if (!Number.isFinite(r.leveredCf)) return null
          const top = r.leveredCf >= 0 ? cfY(r.leveredCf) : zeroY
          const h = Math.max(1, Math.abs(cfY(r.leveredCf) - zeroY))
          return (
            <rect
              key={r.year}
              className={`bar${r.leveredCf < 0 ? ' neg' : ''}`}
              x={cx(i) - barW / 2}
              y={top}
              width={barW}
              height={h}
              rx={3}
            >
              <title>{`Y${r.year} · Levered CF ${r.leveredCf} · DSCR ${r.dscr}`}</title>
            </rect>
          )
        })}

        {/* DSCR 라인 + 점 (유한값만) */}
        <path className="dscr-line" d={dscrPath} />
        {dscrPoints.map((p) => (
          <circle key={rows[p.i].year} className="dscr-dot" cx={cx(p.i)} cy={dscrY(p.d)} r={3.5} />
        ))}

        {/* DSCR 임계선 라벨 - 막대·라인 위(paint order 마지막)에 그리고 흰 헤일로로 가독성 확보 */}
        <text
          className="thresh-label"
          x={W - padR}
          y={dscrY(DSCR_THRESHOLD) - 5}
          textAnchor="end"
          style={{ paintOrder: 'stroke', stroke: 'var(--surface)', strokeWidth: 3.5, strokeLinejoin: 'round' }}
        >
          DSCR {DSCR_THRESHOLD.toFixed(1)}
        </text>

        {/* x축 라벨 */}
        {rows.map((r, i) => (
          <text key={r.year} className="axis-label" x={cx(i)} y={H - 8} textAnchor="middle">
            Y{r.year}
          </text>
        ))}
      </svg>
    </div>
  )
}
