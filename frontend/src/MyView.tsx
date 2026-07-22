import { useState } from 'react'
import { MyDealsView } from './MyDealsView'
import { CreditHistoryView } from './CreditHistoryView'
import { MyBriefsView } from './MyBriefsView'

type Section = 'deals' | 'briefs' | 'credits'

const TITLES: Record<Section, { h1: string; sub: string }> = {
  deals: { h1: '내가 분석한 딜', sub: '지금까지 분석한 딜을 한눈에 모아 보고, 다시 열어 비교합니다.' },
  briefs: { h1: '내 동네 브리핑', sub: '저장된 AI 동네 브리핑을 다시 열어 봅니다.' },
  credits: { h1: '크레딧 사용 현황', sub: '크레딧 적립·사용 내역과 잔여 크레딧을 확인합니다.' },
}

interface MyViewProps {
  /** 딜 카드 '언더라이팅 이어서' → 언더라이팅 탭 이동(딜 PK). */
  onContinue: (dealId: number) => void
  /** 딜 카드 '심화 이어서' → 심화분석 탭 이동(딜 PK). */
  onContinueAdvanced: (dealId: number) => void
  /** 크레딧 잔액/플랜을 상위 세션에 동기화. */
  onSync: (plan: 'FREE' | 'PAID', creditBalance: number) => void
}

/** 개인 활동 통합 뷰: '내가 분석한 딜'과 '크레딧 사용 내역'을 한 메뉴에서 세그먼트로 전환. */
export function MyView({ onContinue, onContinueAdvanced, onSync }: MyViewProps) {
  const [section, setSection] = useState<Section>('deals')

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">MY PAGE</span>
          <h1>{TITLES[section].h1}</h1>
          <p className="page-sub">{TITLES[section].sub}</p>
        </div>
        <div className="seg head-seg" role="group" aria-label="마이페이지 섹션">
          <button type="button" aria-pressed={section === 'deals'} onClick={() => setSection('deals')}>딜</button>
          <button type="button" aria-pressed={section === 'briefs'} onClick={() => setSection('briefs')}>동네</button>
          <button type="button" aria-pressed={section === 'credits'} onClick={() => setSection('credits')}>크레딧</button>
        </div>
      </div>

      {section === 'deals' && <MyDealsView onContinue={onContinue} onContinueAdvanced={onContinueAdvanced} embedded />}
      {section === 'briefs' && <MyBriefsView />}
      {section === 'credits' && <CreditHistoryView onSync={onSync} embedded />}
    </>
  )
}
