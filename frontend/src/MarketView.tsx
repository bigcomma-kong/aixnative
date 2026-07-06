import { useState } from 'react'
import { MarketFeedView } from './MarketFeedView'
import { HeadlinesView } from './HeadlinesView'

type Section = 'market' | 'headlines'

interface MarketViewProps {
  isAdmin: boolean
  onCreditBalance: (balance: number) => void
  onNeedCredits: () => void
  toolCosts?: Record<string, number>
}

/**
 * 시장 통합 뷰: 딜/브리핑(시장 인텔리전스)과 업계 헤드라인을 한 메뉴에서 세그먼트로 전환.
 * 헤드라인 수집도 시장 인제스트에 얹혀 있어 성격상 같은 surface. 헤더 구조는 마이페이지와 동일.
 */
export function MarketView({ isAdmin, onCreditBalance, onNeedCredits, toolCosts }: MarketViewProps) {
  const [section, setSection] = useState<Section>('market')
  const isMarket = section === 'market'

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">{isMarket ? 'AI MARKET INTELLIGENCE' : 'INDUSTRY HEADLINES'}</span>
          <h1>{isMarket ? '시장 인텔리전스' : '업계 헤드라인'}</h1>
          <p className="page-sub">
            {isMarket
              ? '매일 자동 수집한 시장 브리핑과 매각·우선협상 등 거래 신호를 한눈에.'
              : '상업용 부동산 매체의 최신 기사 제목을 한곳에서. 클릭하면 원문으로 이동합니다.'}
          </p>
        </div>
        <div className="seg head-seg" role="group" aria-label="시장 섹션">
          <button type="button" aria-pressed={isMarket} onClick={() => setSection('market')}>딜·브리핑</button>
          <button type="button" aria-pressed={!isMarket} onClick={() => setSection('headlines')}>헤드라인</button>
        </div>
      </div>

      {isMarket
        ? <MarketFeedView embedded isAdmin={isAdmin} onCreditBalance={onCreditBalance} onNeedCredits={onNeedCredits} toolCosts={toolCosts} />
        : <HeadlinesView embedded />}
    </>
  )
}
