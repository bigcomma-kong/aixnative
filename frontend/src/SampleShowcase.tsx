import { useState } from 'react'
import type { Analysis, AnalysisType } from './api'
import { StageAnalysis } from './StageAnalysis'

/**
 * 로그인 전 샘플 분석 프리뷰 — 실제 제품의 렌더러(StageAnalysis)로 리치한 결과(표·신호등·리스크
 * 매트릭스)를 그대로 보여줘 제품 수준을 증명한다. 정적 샘플 데이터라 API·크레딧 미사용.
 */
interface SampleShowcaseProps {
  /** '가입하고 내 딜 분석' — 가입 폼으로 유도. */
  onSignup?: () => void
}

const KPIS: { k: string; v: string; unit: string }[] = [
  { k: '레버리지 IRR', v: '12.9', unit: '%' },
  { k: '투자 배수(EM)', v: '1.73', unit: 'x' },
  { k: '최소 DSCR', v: '1.78', unit: 'x' },
  { k: '매입 수익률', v: '4.4', unit: '%' },
  { k: '총투자비', v: '715', unit: '억' },
]

const SCREENING: Analysis = {
  verdict: 'GO',
  verdict_reason: '안정적 NOI와 보수적 LTV로 DSCR 여력이 충분하고, 권역 평당가 대비 매입가가 합리적입니다.',
  confidence: 'HIGH',
  metrics: {
    asking_price_eok: 680, price_per_pyeong_manwon: 3400, noi_eok: 30, cap_rate_pct: 4.4,
    occupancy_pct: 96, walt_yr: 4.2, top1_tenant_pct: 28, loss_to_lease_pct: 6, opex_ratio_pct: 34,
  },
  investment_thesis:
    'GBD 권역의 구조적 공급 제약과 대기업 본사 수요를 배경으로 한 Core 오피스. 우량 임차인 구성과 장기 WALT가 현금흐름 안정성을 뒷받침하며, 보수적 자본구조로 하방을 제한합니다.',
  benchmark_eval: [
    { metric: 'Cap Rate', value: '4.4%', guideline: 'GBD Core 4.0~4.8%', rating: 'GREEN' },
    { metric: '평당가', value: '3,400만원', guideline: '권역 3,200~3,600만원', rating: 'GREEN' },
    { metric: '임대율', value: '96%', guideline: '≥ 92%', rating: 'GREEN' },
    { metric: '최대임차인 비중', value: '28%', guideline: '≤ 30%', rating: 'YELLOW' },
    { metric: 'WALT', value: '4.2년', guideline: '≥ 4.0년', rating: 'GREEN' },
  ],
  green_flags: ['GBD 프라임 입지', '96% 임대율', '우량 임차인(신용 A 이상)'],
  red_flags: [
    { flag: '최대임차인 만기 3년차 집중', impact: 'MEDIUM', verify: '재계약 의향서·리텐션 이력 확인' },
    { flag: 'OPEX 상승 추세', impact: 'LOW', verify: '최근 3년 관리비 내역 대조' },
  ],
  conditions: ['앵커 임차인 재계약 조건 확인', '실사 단계 물리·환경 점검'],
}

const MARKET: Analysis = {
  confidence: 'HIGH',
  region: '서울 GBD (강남권역)',
  house_view: 'Bullish',
  house_view_reason:
    '프라임 오피스 공실률이 역사적 저점 부근이고 신규 공급 파이프라인이 제한적입니다. 임대료 상승과 낮은 공실이 향후 3년 현금흐름을 지지합니다.',
  fundamentals: [
    'GBD 프라임 공실률 2%대 — 5년 평균 하회',
    '실질 임대료 전년 대비 상승세 지속',
    '대형 오피스 신규 준공 예정 물량 소수 — 공급 제약',
  ],
  assumption_check: [
    { assumption: '임대성장률 3.0%', market: '시장 실질 상승률과 부합', verdict: 'GREEN' },
    { assumption: 'Exit Cap 4.8%', market: '현 거래 Cap 대비 보수적', verdict: 'GREEN' },
    { assumption: '안정 임대율 96%', market: '권역 평균 상회 — 달성 가능', verdict: 'YELLOW' },
  ],
  comps: [
    { name: 'A타워', region: 'GBD', price_per_pyeong_manwon: 3550, cap_rate_pct: 4.3 },
    { name: 'B스퀘어', region: 'GBD', price_per_pyeong_manwon: 3300, cap_rate_pct: 4.6 },
    { name: 'C센터', region: 'GBD', price_per_pyeong_manwon: 3480, cap_rate_pct: 4.4 },
  ],
  macro: '기준금리 인하 사이클 진입으로 조달비용 완화가 기대되나, 대출 스프레드는 여전히 밸류에이션의 핵심 변수입니다.',
  conclusion: '권역 펀더멘털과 거래 사례가 매입 가정을 지지합니다. Exit Cap 가정이 보수적이라 하방 방어력이 확보됩니다.',
}

const IC_MEMO: Analysis = {
  confidence: 'HIGH',
  thesis:
    'GBD Core 오피스로 안정적 배당수익과 완만한 자본이득을 동시에 추구하는 딜. 보수적 레버리지와 우량 임차인 구성이 하방을 제한합니다.',
  exec_summary: {
    asset: 'GBD 프라임 오피스 (연면적 2,000평)',
    price: '680억 · 평당 3,400만원',
    strategy: 'Core · 5년 보유 후 매각',
    expected_return: 'IRR 12.9% · EM 1.73x',
    recommendation: 'GO — 투자 적격',
  },
  highlights: [
    '보수적 LTV 55%로 최소 DSCR 1.78 확보',
    '96% 임대율 · 4.2년 WALT의 안정적 현금흐름',
    'Exit Cap 25bp 상승에도 IRR 두 자릿수 유지',
  ],
  risk_matrix: [
    { risk: '앵커 임차인 이탈', likelihood: '중간', impact: '높음', mitigation: '재계약 인센티브 · 백업 수요 확보' },
    { risk: '금리 재상승', likelihood: '중간', impact: '중간', mitigation: '고정금리 헤지 · 상환 스케줄 조정' },
    { risk: 'Exit Cap 확대', likelihood: '낮음', impact: '높음', mitigation: '보수적 Exit 가정 · 매각 시점 유연화' },
  ],
  conditions: ['앵커 재계약 확인 후 집행', 'LP 승인 및 실사 완료'],
  lp_alignment: 'Core 전략 · 목표 IRR 10~13% 밴드에 부합하여 LP 투자 목적과 정렬됩니다.',
  recommendation_reason: '리스크 대비 안정적 수익 프로파일로 IC 상정을 권고합니다.',
}

const TABS: { type: AnalysisType; label: string; analysis: Analysis }[] = [
  { type: 'SCREENING', label: '1차 스크리닝', analysis: SCREENING },
  { type: 'MARKET_STUDY', label: '시장조사', analysis: MARKET },
  { type: 'IC_MEMO', label: '투심 메모', analysis: IC_MEMO },
]

export function SampleShowcase({ onSignup }: SampleShowcaseProps) {
  const [active, setActive] = useState<AnalysisType>('SCREENING')
  const current = TABS.find((t) => t.type === active) ?? TABS[0]

  return (
    <div className="sample-showcase">
      <div className="ss-deal card">
        <div className="ss-deal-head">
          <div>
            <span className="ss-eyebrow">샘플 딜 · 축약 미리보기</span>
            <strong className="ss-name">강남 GBD 오피스 · Core</strong>
          </div>
          <span className="ss-verdict go">GO · 투자 적격</span>
        </div>
        <div className="kpi-table ss-kpis">
          {KPIS.map((m) => (
            <div className="kpi-cell" key={m.k}>
              <span className="k">{m.k}</span>
              <span className="v num">{m.v}<i>{m.unit}</i></span>
            </div>
          ))}
        </div>
        <p className="ss-note">아래 수치는 결정론적 ProForma 계산값이며, 표·신호등·리스크 매트릭스는 AI 분석 결과입니다.</p>
      </div>

      <div className="ss-tabs" role="tablist" aria-label="샘플 분석 단계">
        {TABS.map((t) => (
          <button
            key={t.type}
            role="tab"
            aria-selected={active === t.type}
            className="ss-tab"
            onClick={() => setActive(t.type)}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="ss-body card">
        <StageAnalysis type={current.type} analysis={current.analysis} />
      </div>

      <div className="ss-cta">
        <button className="btn-primary" type="button" onClick={onSignup}>내 딜로 이 분석 받기 — 무료로 시작 →</button>
        <span className="ss-cta-note">가입 즉시 무료 크레딧 · 카드 등록 없이 · 실제는 더 상세한 전체 분석</span>
      </div>
    </div>
  )
}
