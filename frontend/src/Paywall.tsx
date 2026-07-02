import { useState } from 'react'

interface PaywallProps {
  /** 잔여 크레딧. 0 이면 소진 문구, 그 외엔 "곧 소진" 안내. */
  creditBalance: number
  variant?: 'card' | 'banner'
  /** 충전 모달 열기. 미지정 시(결제 미설정 등) "출시 알림 신청" 폴백 노출. */
  onTopUp?: () => void
}

/**
 * 프리티어 페이월. 무료 크레딧 소진 시 노출. onTopUp 이 있으면 실제 충전 결제로 연결하고,
 * 없으면 "출시 알림 신청" 폴백(결제 미도입/미설정 환경 대비)을 보여준다.
 */
export function Paywall({ creditBalance, variant = 'card', onTopUp }: PaywallProps) {
  const [notified, setNotified] = useState(false)
  const empty = creditBalance <= 0

  return (
    <div className={`paywall ${variant} ${empty ? 'is-empty' : 'is-low'}`}>
      <div className="paywall-body">
        <div className="paywall-title">
          {empty ? '무료 분석을 모두 사용했습니다' : `무료 분석이 ${creditBalance}회 남았습니다`}
        </div>
        <p className="paywall-desc">
          {empty
            ? <>AI 분석 크레딧이 소진되었습니다.<br />ProForma 계산(무료)은 계속 사용할 수 있어요.</>
            : '크레딧이 곧 소진됩니다.'}
          <br />
          {onTopUp ? '크레딧을 충전하면 바로 다시 분석할 수 있어요.' : '유료 결제는 곧 지원될 예정입니다.'}
        </p>
      </div>
      <div className="paywall-action">
        {onTopUp ? (
          <button type="button" className="btn-primary" onClick={onTopUp}>
            크레딧 충전
          </button>
        ) : notified ? (
          <span className="paywall-thanks">신청 완료 · 출시되면 알려드릴게요</span>
        ) : (
          <button type="button" className="btn-primary" onClick={() => setNotified(true)}>
            출시 알림 신청
          </button>
        )}
      </div>
    </div>
  )
}
