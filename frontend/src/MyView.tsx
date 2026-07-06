import { useState } from 'react'
import { MyDealsView } from './MyDealsView'
import { CreditHistoryView } from './CreditHistoryView'

type Section = 'deals' | 'credits'

interface MyViewProps {
  /** 딜 카드 '이어서 분석' → 언더라이팅 탭 이동. */
  onContinue: (dealName: string) => void
  /** 크레딧 잔액/플랜을 상위 세션에 동기화. */
  onSync: (plan: 'FREE' | 'PAID', creditBalance: number) => void
}

/** 개인 활동 통합 뷰: '내가 분석한 딜'과 '크레딧 사용 내역'을 한 메뉴에서 세그먼트로 전환. */
export function MyView({ onContinue, onSync }: MyViewProps) {
  const [section, setSection] = useState<Section>('deals')
  const isDeals = section === 'deals'

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">MY PAGE</span>
          <h1>{isDeals ? '내가 분석한 딜' : '크레딧 사용 현황'}</h1>
          <p className="page-sub">
            {isDeals
              ? '지금까지 분석한 딜을 한눈에 모아 보고, 다시 열어 비교합니다.'
              : '크레딧 적립·사용 내역과 잔여 크레딧을 확인합니다.'}
          </p>
        </div>
        <div className="seg head-seg" role="group" aria-label="마이페이지 섹션">
          <button type="button" aria-pressed={isDeals} onClick={() => setSection('deals')}>딜</button>
          <button type="button" aria-pressed={!isDeals} onClick={() => setSection('credits')}>크레딧</button>
        </div>
      </div>

      {isDeals
        ? <MyDealsView onContinue={onContinue} embedded />
        : <CreditHistoryView onSync={onSync} embedded />}
    </>
  )
}
