import type { PmCalendarEvent } from './api'
import { fmtDate, dDayLabel, dDayTone } from './pmDate'

/** 이벤트 유형 한글 라벨. */
const EVENT_LABEL: Record<string, string> = {
  EXPIRY: '만기',
  ESCALATION: '임대료 인상',
  RENT_FREE_END: '렌트프리 종료',
}

/**
 * 다가오는 임대 일정(만기·인상·렌트프리 종료)을 임박순으로 보여주는 리스트.
 * 각 항목에 D-day 뱃지(임박=빨강, 주의=주황, 여유=초록)를 붙인다.
 */
export function LeaseCalendar({ events }: { events: PmCalendarEvent[] }) {
  if (events.length === 0) {
    return <p className="hint">다가오는 일정이 없습니다. 임대차의 만기·인상일을 입력하면 여기에 표시됩니다.</p>
  }
  return (
    <ul className="lease-cal">
      {events.map((e, i) => (
        <li className="lc-item" key={i}>
          <span className={`lc-dday ${dDayTone(e.daysUntil)}`}>{dDayLabel(e.daysUntil)}</span>
          <div className="lc-main">
            <div className="lc-title">
              <span className={`lc-type t-${e.eventType.toLowerCase()}`}>{EVENT_LABEL[e.eventType] ?? e.eventType}</span>
              {e.tenantName}
            </div>
            <div className="lc-date">{fmtDate(e.dueDate)}</div>
          </div>
        </li>
      ))}
    </ul>
  )
}
