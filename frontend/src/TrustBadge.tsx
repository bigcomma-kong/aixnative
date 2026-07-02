import { useState } from 'react'

/**
 * 신뢰 배지 - 제품 차별점(환각 차단) 안내. 경고가 아니라 신뢰 메시지이므로 붉은색이 아닌
 * 차분한 액센트로 표시한다. 방법론 상세는 접이식("왜 믿을 수 있나?")이라 매번 잔소리처럼 보이지 않는다.
 */
export function TrustBadge() {
  const [open, setOpen] = useState(false)
  return (
    <div className="trust-badge">
      <span className="tb-icon" aria-hidden="true">🛡</span>
      <div>
        <span>
          핵심 사실은 <b>코드·공공데이터로 확정</b>하고, AI는 그 확정 사실을 근거로 <b>서술·판정만</b> 합니다(환각 차단).
        </span>{' '}
        <button type="button" className="tb-toggle" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? '접기' : '왜 믿을 수 있나?'}
        </button>
        {open && (
          <div className="trust-badge-more">
            매입가·NOI·DSCR·세무 기준 같은 수치는 입력값과 코드 계산, 공공 실거래·공시 데이터로 고정됩니다.
            <br />
            AI는 이 고정된 사실 위에서 해석·리스크·판정만 생성하므로 숫자를 지어내지 않습니다.
          </div>
        )}
      </div>
    </div>
  )
}
