import { useState } from 'react'

/** 정책/안내 페이지 키. */
export type InfoPage = 'terms' | 'privacy' | 'security' | 'data' | 'disclaimer'

interface InfoBlock { h: string; p?: string; list?: string[] }
interface InfoDoc { title: string; updated?: string; blocks: InfoBlock[] }

const UPDATED = '2026-07-01'

/** 모든 정책/안내 콘텐츠의 단일 소스. (법률 자문 아님 — 일반 서비스 약관 수준) */
const DOCS: Record<InfoPage, InfoDoc> = {
  terms: {
    title: '이용약관',
    updated: UPDATED,
    blocks: [
      { h: '제1조 (서비스)', p: 'AixNative(이하 "서비스")는 상업용 부동산 딜의 ProForma 지표 계산과 AI 기반 투자 심사·보고서 생성을 제공합니다. 서비스는 정보 제공을 목적으로 하며, 투자자문·중개·평가를 제공하지 않습니다.' },
      { h: '제2조 (계정)', p: '이용자는 정확한 정보로 가입하고 계정 보안을 직접 관리합니다. 1계정은 1이용자에게 귀속되며, 계정 공유로 인한 손해의 책임은 이용자에게 있습니다.' },
      { h: '제3조 (크레딧·결제)', list: [
        'AI 분석은 분석 유형별로 크레딧을 차감합니다(ProForma 지표 계산은 무료).',
        '크레딧은 결제(충전)로 취득하며, 결제는 토스페이먼츠를 통해 처리됩니다.',
        '이미 사용(차감)된 크레딧은 환불되지 않습니다. 미사용 충전분의 환불은 관련 법령과 결제대행사 정책을 따릅니다.',
      ] },
      { h: '제4조 (금지행위)', list: [
        '서비스의 역공학·자동화 크롤링·과도한 트래픽 유발',
        '타인의 계정·딜 정보에 대한 무단 접근 시도',
        '법령 위반 또는 제3자 권리를 침해하는 용도의 이용',
      ] },
      { h: '제5조 (책임의 한계)', p: '서비스가 제공하는 모든 수치·서술·판정은 추정·참고 자료이며, 이를 근거로 한 투자·매각·개발 의사결정의 결과에 대해 서비스는 법적 책임을 지지 않습니다. 최종 판단과 책임은 이용자에게 있습니다.' },
      { h: '제6조 (변경)', p: '서비스 내용과 약관은 사전 고지 후 변경될 수 있으며, 변경 사항은 본 페이지에 게시합니다.' },
    ],
  },
  privacy: {
    title: '개인정보처리방침',
    updated: UPDATED,
    blocks: [
      { h: '수집 항목', list: [
        '계정: 이메일, 비밀번호(해시 저장), 소셜 로그인 식별자',
        '분석 입력: 이용자가 입력한 딜 정보(매입가·NOI·위치·메모 등)와 분석 결과',
        '결제: 결제 승인 메타(주문번호·금액·결제수단). 카드 정보는 저장하지 않으며 토스페이먼츠가 처리합니다.',
        '이용 기록: 분석 실행 이력, 접속 로그(보안·장애 대응 목적)',
      ] },
      { h: '이용 목적', p: '서비스 제공(분석 실행·이력 조회·보고서 생성), 크레딧·결제 처리, 보안·부정사용 방지, 서비스 개선.' },
      { h: '제3자 처리(처리위탁)', list: [
        '결제: 토스페이먼츠(결제 승인·정산)',
        'AI 분석: 분석에 필요한 입력 텍스트가 AI 모델(Anthropic Claude 등)로 전송되어 처리됩니다.',
        '시장 데이터 조회: 한국은행·국토교통부·한국부동산원·V-World 등 공공 API(딜 식별정보가 아닌 위치·유형 등 조회 파라미터만 사용)',
      ] },
      { h: '보유·파기', p: '서비스 이용 기간 동안 보유하며, 계정 삭제 시 분석 이력·크레딧 원장·관심 딜 등 연관 데이터를 파기합니다. 법령상 보존 의무가 있는 경우 해당 기간 동안 보관합니다.' },
      { h: '이용자 권리', p: '이용자는 본인 데이터의 열람을 사용 내역 화면에서 할 수 있으며, 운영 콘솔/문의를 통해 계정 삭제(파기)를 요청할 수 있습니다.' },
      { h: '문의', p: '개인정보 관련 문의: admin@aixnative.com' },
    ],
  },
  security: {
    title: '보안 정책',
    updated: UPDATED,
    blocks: [
      { h: '딜 정보 보호 (핵심)', p: '딜 정보는 극비라는 점을 전제로 설계했습니다. 모든 조회는 현재 이용자(테넌트)로 스코프되어, 다른 이용자의 딜·분석·크레딧에 접근할 수 없습니다(IDOR 차단). 분석 입력·결과는 분석 제공 목적 외로 제3자에게 공유·판매하지 않습니다.' },
      { h: '전송·저장', list: [
        '전 구간 HTTPS/TLS 암호화',
        '비밀번호는 단방향 해시로만 저장(평문 미보관)',
        'API 키·DB 비밀번호 등 비밀값은 코드가 아닌 비밀 관리자(Secret Manager)에 분리 보관',
      ] },
      { h: '결제 보안', p: '결제 금액·크레딧은 서버의 상품 정의를 권위로 삼으며(클라이언트 값 불신), 결제 승인은 서버에서 토스페이먼츠 API로 검증합니다. 주문번호 유니크·상태 전이로 중복충전을 차단합니다.' },
      { h: '인프라', p: '클라우드(Google Cloud) 단일 컨테이너 + 관리형 데이터베이스 구성. 접근 권한 최소화 원칙을 따릅니다.' },
      { h: '기업 고객', p: '전용 클라우드·온프레미스 격리 등 강화된 분리가 필요한 기관 고객은 admin@aixnative.com 으로 문의해 주세요.' },
    ],
  },
  data: {
    title: '데이터 출처·분석 방법론',
    updated: UPDATED,
    blocks: [
      { h: '계산은 코드, 서술은 AI — 환각 분리', p: 'IRR·Equity Multiple·DSCR·Cash-on-Cash·민감도·시나리오 등 핵심 수치는 결정론적 코드(ProForma 엔진)가 계산합니다. AI는 이 확정 수치를 근거로 서술·심사·플래그만 작성하며, 숫자를 창작하지 않습니다. → 범용 챗봇과 달리 수치 환각을 구조적으로 차단합니다.' },
      { h: '실측 시장 데이터 출처', list: [
        '한국은행 ECOS — 기준금리·국고채 등 매크로',
        '국토교통부 RTMS — 상업·업무용 실거래가',
        '한국부동산원 R-ONE — 공실률·임대료·소득수익률(Cap)',
        'V-World(공간정보) — 공시지가·용도지역',
      ] },
      { h: '실측 우선·추정 표기', p: '위 실측값이 있으면 추정 대신 인용하고 출처·기준 시점을 명시하며 신뢰도를 높입니다. 실측이 없으면 시장 벤치마크로 신중히 추정하고 본문에 "(추정)" 과 신뢰도(HIGH/MEDIUM/LOW)를 함께 표기합니다.' },
      { h: 'ProForma 계산식 (블랙박스 아님)', list: [
        '총투자비 = 매입가 × (1 + 취득부대비용률)',
        '대출 = 매입가 × LTV / 자기자본 = 총투자비 − 대출 / 연이자 = 대출 × 대출금리',
        'Going-in Cap = 1년차 NOI ÷ 매입가 / Yield-on-Cost = NOI ÷ 총투자비',
        'DSCR = NOI ÷ 이자 / Cash-on-Cash = 레버리지 현금흐름 ÷ 자기자본',
        'Exit Value = 보유 종료 시점 Forward NOI ÷ Exit Cap / 순매각 = Exit Value × (1 − 매각비용률)',
        'Levered IRR = 자기자본 현금흐름의 내부수익률 / Equity Multiple = 총회수 ÷ 투입 자기자본',
      ] },
      { h: '자산유형별 기본 가정 (2026 상반기 기준)', list: [
        '취득부대비용 5.1%(취득세 4.6% + 등기 0.5%), 매각비용 2~3%',
        'Cap Rate 밴드 — 오피스 4.0~5.5% / 물류 4.5~5.5% / 호텔 6.0~7.5% / 리테일 5.0~6.5%',
        '유형 고유지표 — 호텔 ADR·Occ·RevPAR, 물류 삼중순임대·평당 임대료, 리테일 매출연동임대',
        '기준값은 시장 변동 시 갱신되며, 입력값으로 덮어쓸 수 있습니다.',
      ] },
      { h: '한계', p: '실측 데이터의 기준 시점·범위 한계, 비정형 딜 구조, 미제출 운영자료 등은 분석 정확도에 영향을 줍니다. 본 분석은 실사(DD)를 대체하지 않습니다.' },
    ],
  },
  disclaimer: {
    title: '면책 고지',
    updated: UPDATED,
    blocks: [
      { h: '투자자문이 아닙니다', p: '본 서비스가 제공하는 모든 분석·수치·보고서는 정보 제공 목적의 추정치이며, 자본시장법상 투자자문·투자권유에 해당하지 않습니다.' },
      { h: 'AI 분석의 한계', p: 'AI 서술은 제공된 입력과 시장 벤치마크에 기반한 추정으로 오류·누락이 있을 수 있습니다. 수치 계산은 입력 가정에 전적으로 의존하며, 잘못된 입력은 잘못된 결과로 이어집니다.' },
      { h: '최종 책임', p: '투자·매각·개발 등 모든 의사결정과 그 결과의 책임은 이용자에게 있습니다. 실제 거래 전 전문가(회계·세무·법무·감정평가) 검토와 정식 실사를 권장합니다.' },
    ],
  },
}

const FOOTER_LINKS: { key: InfoPage; label: string }[] = [
  { key: 'terms', label: '이용약관' },
  { key: 'privacy', label: '개인정보처리방침' },
  { key: 'security', label: '보안' },
  { key: 'data', label: '데이터·방법론' },
  { key: 'disclaimer', label: '면책' },
]

/** 사이트 공통 푸터 + 정책/안내 모달. 로그인/랜딩 양쪽에서 사용. */
export function SiteFooter() {
  const [open, setOpen] = useState<InfoPage | null>(null)
  return (
    <>
      <footer className="site-footer">
        <div className="sf-top">
          <div className="brand">Aix<span>Native</span></div>
          <nav className="sf-links" aria-label="정책·안내">
            {FOOTER_LINKS.map((l) => (
              <button key={l.key} type="button" className="sf-link" onClick={() => setOpen(l.key)}>{l.label}</button>
            ))}
          </nav>
        </div>
        <p className="sf-disc">본 서비스는 정보 제공 목적이며 투자자문이 아닙니다. 투자 판단의 책임은 이용자에게 있습니다.</p>
        <p className="sf-meta">
          문의 <a href="mailto:admin@aixnative.com">admin@aixnative.com</a> · © AixNative
        </p>
      </footer>
      {open && <InfoModal page={open} onClose={() => setOpen(null)} />}
    </>
  )
}

export function InfoModal({ page, onClose }: { page: InfoPage; onClose: () => void }) {
  const doc = DOCS[page]
  return (
    <div className="analyze-overlay" role="dialog" aria-modal="true" aria-label={doc.title} onClick={onClose}>
      <div className="info-modal" onClick={(e) => e.stopPropagation()}>
        <div className="info-head">
          <div>
            <strong className="info-title">{doc.title}</strong>
            {doc.updated && <span className="info-updated">최종 업데이트 {doc.updated}</span>}
          </div>
          <button className="deep-close" onClick={onClose} aria-label="닫기">×</button>
        </div>
        <div className="info-body">
          {doc.blocks.map((b, i) => (
            <section key={i} className="info-block">
              <h3 className="info-h">{b.h}</h3>
              {b.p && <p className="info-p">{b.p}</p>}
              {b.list && <ul className="info-list">{b.list.map((it, j) => <li key={j}>{it}</li>)}</ul>}
            </section>
          ))}
        </div>
      </div>
    </div>
  )
}
