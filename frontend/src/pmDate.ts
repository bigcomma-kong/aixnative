// 자산관리(PM) 날짜 유틸 - 만기·인상 일정 표시용. 외부 라이브러리 없이 최소 구현.

/** ISO(yyyy-MM-dd) → 'YYYY.MM.DD'. 없으면 '-'. */
export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

/** 남은 일수 → 'D-30' | 'D+5'(지남) | 'D-DAY'. */
export function dDayLabel(days: number): string {
  if (days === 0) return 'D-DAY'
  return days > 0 ? `D-${days}` : `D+${-days}`
}

/** 남은 일수 → 톤(임박 순). 0~30 위험, 31~90 주의, 그 외 보통, 음수(지남) 위험. */
export function dDayTone(days: number): 'no' | 'cond' | 'go' {
  if (days < 0 || days <= 30) return 'no'
  if (days <= 90) return 'cond'
  return 'go'
}

/** 오늘(로컬) ISO yyyy-MM-dd. 입력 폼 기본값 등에 사용. */
export function todayIso(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}
