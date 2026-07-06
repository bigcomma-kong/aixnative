/**
 * 임차인별 월 임대료(만원) 가로 막대 차트. 외부 차트 라이브러리 없이 디자인 토큰(CSS 변수)으로 렌더.
 * [Chart.tsx](./Chart.tsx) 의 SVG 토큰 패턴을 따른다. 값이 없으면 렌더하지 않는다.
 */
interface RentDatum {
  label: string
  value: number
}

interface RentBarChartProps {
  data: RentDatum[]
  /** 값 단위 접미사(예: '만원'). */
  unit?: string
}

export function RentBarChart({ data, unit = '만원' }: RentBarChartProps) {
  const rows = data.filter((d) => Number.isFinite(d.value) && d.value > 0)
  if (rows.length === 0) return null

  const max = Math.max(...rows.map((d) => d.value))
  const fmt = (n: number) => n.toLocaleString('ko-KR')

  return (
    <div className="rent-bars">
      {rows.map((d, i) => (
        <div className="rb-row" key={i}>
          <div className="rb-label" title={d.label}>{d.label}</div>
          <div className="rb-track">
            <div className="rb-fill" style={{ width: `${Math.max(3, (d.value / max) * 100)}%` }} />
          </div>
          <div className="rb-value">{fmt(d.value)}{unit}</div>
        </div>
      ))}
    </div>
  )
}
