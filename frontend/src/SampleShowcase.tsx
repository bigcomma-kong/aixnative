import { useState } from 'react'
import type { Analysis, AnalysisType, MarketDeepReport } from './api'
import { StageAnalysis } from './StageAnalysis'
import { DeepReportContent } from './DeepReportPanel'

/**
 * 로그인 전 샘플 분석 프리뷰 - 실제 제품의 렌더러(StageAnalysis·DeepReportContent)로 리치한 결과
 * (표·신호등·리스크 매트릭스·시장 스코어보드)를 그대로 보여줘 제품 수준을 증명한다.
 * 정적 샘플 데이터라 API·크레딧 미사용.
 */


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
    'GBD 프라임 공실률 2%대 - 5년 평균 하회',
    '실질 임대료 전년 대비 상승세 지속',
    '대형 오피스 신규 준공 예정 물량 소수 - 공급 제약',
  ],
  assumption_check: [
    { assumption: '임대성장률 3.0%', market: '시장 실질 상승률과 부합', verdict: 'GREEN' },
    { assumption: 'Exit Cap 4.8%', market: '현 거래 Cap 대비 보수적', verdict: 'GREEN' },
    { assumption: '안정 임대율 96%', market: '권역 평균 상회 - 달성 가능', verdict: 'YELLOW' },
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
    recommendation: 'GO - 투자 적격',
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

/**
 * 샘플 AI 심층 시장 리포트 - 시장 탭·결과 모달과 동일한 DeepReportContent 렌더러로 표시.
 * 실제 '심층 분석'(과금) 산출물과 같은 형태(온도·섹터 스코어보드·시나리오·실행 픽·컨트래리안).
 */
const DEEP_MARKET: MarketDeepReport = {
  headline: '규제 압박과 국민연금 매물 속, 반도체·AI 인프라 배후 자산에 선별 비중확대',
  summary:
    '거시 불확실성과 기관 매도 물량이 밸류에이션을 누르는 구간이지만, 공급이 제한된 프라임 오피스와 수도권 핵심 물류는 임대 펀더멘털이 견조합니다. 반도체·AI 데이터센터 투자 확대의 배후 수혜 자산에 선별적으로 비중을 확대하되, 금리·Exit Cap 민감도가 큰 자산은 보수적으로 접근합니다.',
  marketTempScore: 56,
  marketTempLabel: '중립 - 선별적 위험선호',
  sectors: [
    { name: '프라임 오피스', stance: '비중확대', score: 74, note: 'GBD·YBD 공실 역사적 저점, 신규 공급 제한. 우량 임차 자산 선별.' },
    { name: '수도권 물류', stance: '비중확대', score: 69, note: '이커머스·데이터센터 수요 견조. 남부권 과잉공급 구역은 제외.' },
    { name: '리테일', stance: '중립', score: 48, note: '핵심 상권 회복 vs 외곽 부진 양극화. 앵커 임차 안정성 중시.' },
    { name: '호텔·숙박', stance: '비중축소', score: 37, note: '관광 회복에도 운영 변동성·인건비 부담. 밸류 매력 제한.' },
  ],
  scenarios: [
    { name: '기준', narrative: '금리 완만한 인하, 프라임 임대료 연 3% 상승. 핵심 자산 캡레이트 횡보로 IRR 두 자릿수 유지.' },
    { name: '상방', narrative: '조달비용 하락 가속 + 기관 매물 소화. 프라임 캡레이트 25~50bp 압축, 밸류 리레이팅.' },
    { name: '하방', narrative: '장기금리 재상승·매도 물량 확대로 Exit Cap 50bp+ 확대. 고레버리지 자산 IRR 급락.' },
  ],
  sections: [
    {
      title: '매크로 · 금리',
      body: '기준금리 인하 사이클 진입으로 조달비용 완화가 기대되나, 대출 스프레드와 장기금리 경로가 밸류에이션의 핵심 변수입니다.',
      bullets: ['기준금리 인하 기조 - 조달비용 점진 완화', '장기금리 변동성 지속 - Exit Cap 가정 보수적 유지', '대출 스프레드가 실질 레버리지 수익을 좌우'],
    },
    {
      title: '수급 · 매물',
      body: '국민연금 등 기관의 리밸런싱 매도가 프라임 매물을 늘려 매수자 우위 환경을 형성합니다.',
      bullets: ['기관 리밸런싱 매도 - 프라임 매물 증가', '현금 보유 매수자의 협상력 - 진입 캡레이트 개선 여지', '실물 거래 본격 회복은 하반기로 이연 관측'],
    },
    {
      title: '거래 · 유동성',
      body: '거래량은 저점을 지나 회복 초입입니다. 프라임·핵심입지에 자금이 선별 집중되며 자산 간 양극화가 심화됩니다.',
    },
  ],
  picks: [
    { title: 'GBD·YBD 프라임 오피스 (우량 임차)', why: '공급 제약 + 낮은 공실로 임대 현금흐름 견조. 기관 매물로 진입 밸류 개선 여지.', conviction: '높음', risk: '앵커 임차인 만기 집중 - 재계약 리스크 점검' },
    { title: '수도권 핵심 물류 (데이터센터 인접)', why: 'AI·클라우드 투자 확대의 배후 수요. 임대료 상승 여력.', conviction: '중간', risk: '남부권 공급 과잉 구역과 명확히 구분 필요' },
    { title: '핵심 상권 리테일 (앵커 안정)', why: '유동 회복 상권의 안정 임차 자산은 방어적 배당 매력.', conviction: '중간', risk: '외곽·비핵심 상권은 공실 장기화 우려' },
  ],
  contrarian:
    '시장이 호텔·숙박을 일괄 회피하지만, 운영 역량이 검증된 핵심 입지 자산은 관광 회복 레버리지가 저평가되어 있을 수 있습니다.',
  provider: 'claude',
  creditBalance: 0,
  disclaimer:
    '본 리포트는 정보 제공 목적이며 투자자문이 아닙니다. 시장 데이터 기반 AI 분석으로, 투자 결정과 결과의 책임은 이용자 본인에게 있습니다.',
}

type ActiveTab = AnalysisType | 'DEEP_MARKET'

const STAGE_TABS: { type: AnalysisType; label: string; analysis: Analysis }[] = [
  { type: 'SCREENING', label: '1차 스크리닝', analysis: SCREENING },
  { type: 'MARKET_STUDY', label: '시장조사', analysis: MARKET },
  { type: 'IC_MEMO', label: '투심 메모', analysis: IC_MEMO },
]

export function SampleShowcase() {
  const [active, setActive] = useState<ActiveTab>('SCREENING')
  const isDeep = active === 'DEEP_MARKET'
  const stage = STAGE_TABS.find((t) => t.type === active)

  return (
    <div className="sample-showcase">
      {isDeep ? (
        <div className="ss-deal card">
          <div className="ss-deal-head">
            <div>
              <span className="ss-eyebrow">샘플 · AI 심층 시장 리포트</span>
              <strong className="ss-name">이번 주 상업용 부동산 시장</strong>
            </div>
            <span className="ss-verdict go">무료 실행 가능</span>
          </div>
          <p className="ss-note">실제 <b>심층 분석</b> 버튼으로 생성되는 리포트와 동일한 화면입니다. 시장 데이터로 매주 갱신됩니다.</p>
        </div>
      ) : (
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
      )}

      <div className="ss-tabs" role="tablist" aria-label="샘플 분석">
        {STAGE_TABS.map((t) => (
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
        <button
          key="DEEP_MARKET"
          role="tab"
          aria-selected={isDeep}
          className="ss-tab"
          onClick={() => setActive('DEEP_MARKET')}
        >
          AI 심층 시장 리포트
        </button>
      </div>

      <div className="ss-body card">
        {isDeep
          ? <DeepReportContent report={DEEP_MARKET} />
          : stage && <StageAnalysis type={stage.type} analysis={stage.analysis} />}
      </div>

    </div>
  )
}
