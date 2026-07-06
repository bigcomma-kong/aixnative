import { MarketFeedView } from './MarketFeedView'

interface MarketViewProps {
  isAdmin: boolean
  onCreditBalance: (balance: number) => void
  onNeedCredits: () => void
  toolCosts?: Record<string, number>
}

/**
 * 시장 인텔리전스 뷰: 마켓 브리핑 + 딜/뉴스 피드를 단일 surface 로 제공.
 * 헤더 구조는 마이페이지와 동일(page-head). 별도 헤드라인 탭은 없앰(피드에 일원화).
 */
export function MarketView({ isAdmin, onCreditBalance, onNeedCredits, toolCosts }: MarketViewProps) {
  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">AI MARKET INTELLIGENCE</span>
          <h1>시장 인텔리전스</h1>
          <p className="page-sub">매일 자동 수집한 시장 브리핑과 매각·우선협상 등 거래 신호를 한눈에.</p>
        </div>
      </div>

      <MarketFeedView
        embedded
        isAdmin={isAdmin}
        onCreditBalance={onCreditBalance}
        onNeedCredits={onNeedCredits}
        toolCosts={toolCosts}
      />
    </>
  )
}
