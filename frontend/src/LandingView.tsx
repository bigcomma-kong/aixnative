import { useState } from 'react'
import { AuthView } from './AuthView'
import { SampleShowcase } from './SampleShowcase'
import { LocationReportView } from './LocationReportView'
import { SiteFooter } from './SiteFooter'
import type { AuthResult } from './api'

interface LandingViewProps {
  onAuthed: (result: AuthResult) => void
  /** 소셜 로그인 실패 시 메시지(App 해시 파싱). */
  oauthError?: string | null
}

/* ── 작은 인라인 아이콘(외부 라이브러리 없이) ── */
function Spark() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" width="20" height="20">
      <path d="M12 2l1.7 6.3L20 10l-6.3 1.7L12 18l-1.7-6.3L4 10l6.3-1.7L12 2z" fill="currentColor" />
    </svg>
  )
}
function Check() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" width="15" height="15">
      <path d="M20 6L9 17l-5-5" fill="none" stroke="currentColor" strokeWidth="3"
        strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/* ── 히어로 제품 목업: 글래스 '딜 분석' 카드 + 미니 차트 ── */
function DealMock() {
  // 데모용 현금흐름 막대 높이(%) + DSCR 라인 좌표
  const bars = [38, 52, 61, 70, 84]
  return (
    <div className="mock" aria-hidden="true">
      <div className="mock-float mock-float-a">
        <span className="mf-dot" /> AI 내러티브 생성 완료
      </div>
      <div className="mock-float mock-float-b">
        <Check /> 민감도 9-시나리오
      </div>

      <div className="mock-card">
        <div className="mock-head">
          <div>
            <span className="mock-eyebrow">딜 언더라이팅</span>
            <strong className="mock-name">강남 오피스 - Core</strong>
          </div>
          <span className="mock-verdict">GO - 투자 적격</span>
        </div>

        <div className="mock-metrics">
          <div className="mock-metric">
            <span className="mm-k">IRR</span>
            <span className="mm-v num">12.9%</span>
          </div>
          <div className="mock-metric">
            <span className="mm-k">Equity Multiple</span>
            <span className="mm-v num">1.73x</span>
          </div>
          <div className="mock-metric">
            <span className="mm-k">DSCR</span>
            <span className="mm-v num">3.14</span>
          </div>
        </div>

        <div className="mock-chart">
          <div className="mc-bars">
            {bars.map((h, i) => (
              <span key={i} className="mc-bar" style={{ height: `${h}%` }} />
            ))}
          </div>
          <div className="mc-axis"><span>Y1</span><span>Y5</span></div>
        </div>

        <p className="mock-ai">
          “안정적 NOI와 보수적 LTV로 DSCR 여력이 충분합니다. 출구 Cap 25bp 상승 시나리오에서도 IRR 두 자릿수 유지…”
        </p>
      </div>
    </div>
  )
}

const FEATURES = [
  {
    title: 'ProForma 지표',
    desc: '입력 한 번이면 핵심 수익성 지표를 순수 계산 로직으로 즉시 산출합니다.',
    tags: ['IRR', 'Equity Multiple', 'DSCR', '민감도'],
  },
  {
    title: 'AI 언더라이팅',
    desc: '매입가와 자본구조를 읽고 투자 판단을 문장과 신호로 정리합니다.',
    tags: ['스크리닝 판정', '투자 내러티브', '리스크 플래그'],
  },
  {
    title: '심화 분석 9종',
    desc: '딜 한 건을 다각도로 검증하는 전문 분석 도구를 한 화면에서.',
    tags: ['BOV 가치평가', '개발 타당성', '세무 진단', '시장조사', '거래상대방 실사'],
  },
] as const

const STEPS = [
  { n: '01', title: '딜 입력', desc: '매입가 - NOI - Cap - LTV - 금리 - 출구 Cap 한 번 입력.' },
  { n: '02', title: '원클릭 분석', desc: 'ProForma는 무료, AI 심사는 버튼 한 번 = 분석별 1~5크레딧.' },
  { n: '03', title: '보고서 확보', desc: '지표 - 차트 - AI 내러티브 - 투심 메모를 즉시 확인하고 공유.' },
] as const

const STATS = [
  { v: '1분', k: '딜 한 건 심사' },
  { v: '4종', k: 'AI 분석 파이프라인' },
  { v: '9종', k: '심화 분석 도구' },
  { v: '무료', k: '가입 즉시 크레딧' },
] as const

const TRUST = ['자산운용', '디벨로퍼', 'REIT', 'PE / 펀드', '중개법인']

/** 전문가 의뢰 vs aixnative - 가격 정당화(숫자는 예시 범위). */
const COMPARE = [
  { item: '언더라이팅', detail: 'IRR · DSCR · 민감도', them: '컨설팅 자문 수십만~수백만 원 · 수일', us: '1분 · 3크레딧 (약 3천 원)' },
  { item: '투심(IC) 메모', detail: 'IC 상정용 종합', them: '애널리스트 수 시간 작업', us: '1분 · 5크레딧' },
  { item: '매각 BOV 평가', detail: '3-Method 가치범위', them: '감정 · 매각자문 수십만 원+ · 수일', us: '1분 · 5크레딧' },
  { item: '시장 리서치', detail: '권역 · 매크로 · 하우스뷰', them: '리서치하우스 리포트 · 구독', us: '무료 브리핑 + 심층 5크레딧' },
] as const

/** 두 핵심 메뉴(언더라이팅 vs 심화 분석)가 무엇인지 - 메뉴 이름만으론 헷갈리므로 명시. */
const MODES = [
  {
    tag: '언더라이팅',
    title: '매입 한 건, 깊게 심사',
    desc: '매입가·NOI·LTV·금리·Exit Cap 숫자를 넣으면 ProForma 지표(무료)와 4단계 AI 심사를 한 흐름으로.',
    items: [
      'ProForma - IRR·EM·DSCR·민감도 (코드 계산, 무료)',
      '스크리닝 → 시장조사 → 언더라이팅 → 투심 메모',
      '같은 딜로 쌓으면 보고서 자동 합본',
    ],
    when: '“이 딜 살까?” 매입 의사결정',
  },
  {
    tag: '심화 분석',
    title: '상황별 전문 분석 도구함',
    desc: '문서·자료나 특수 입력으로 매입 외 매각·개발·보유·실사까지 개별 전문 분석을.',
    items: [
      '매각 BOV(3-Method) · 개발 타당성 · 가격 예측',
      '세무·가격 진단 · 거래상대방 실사',
      '심화 시장 리서치 · 분기 자산보고 · 보유·매각·리파이',
    ],
    when: '필요할 때 꺼내 쓰는 전문 분석',
  },
] as const

export function LandingView({ onAuthed, oauthError }: LandingViewProps) {
  // 어느 탭(로그인/가입)으로 열지 + 포커스 트리거. nonce 증가로 매 클릭마다 AuthView 가 반응.
  const [authMode, setAuthMode] = useState<'login' | 'signup'>('login')
  const [authFocus, setAuthFocus] = useState(0)

  function focusAuth(mode: 'login' | 'signup') {
    setAuthMode(mode)
    setAuthFocus((n) => n + 1)
    document.getElementById('auth')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  function scrollToSample() {
    document.getElementById('sample')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <div className="landing">
      <div className="landing-bg" aria-hidden="true" />

      <header className="landing-nav">
        <div className="brand">Aix<span>Native</span></div>
        <div className="landing-nav-actions">
          <button className="btn-link nav-tool" onClick={scrollToSample}>샘플 결과 보기</button>
          <button className="btn-link" onClick={() => focusAuth('login')}>로그인</button>
          <button className="btn-primary nav-cta" onClick={() => focusAuth('signup')}>무료로 시작</button>
        </div>
      </header>

      <section className="hero reveal" aria-labelledby="hero-h1">
        <div className="hero-copy">
          <span className="hero-eyebrow"><Spark /> AI 부동산 딜 언더라이팅</span>
          <h1 id="hero-h1" className="hero-title">
            매물 한 건,<br /><em>1분 심사.</em>
          </h1>
          <p className="hero-sub">
            매입가와 자본구조만 넣으면 ProForma 지표 계산과 AI 투자 심사, 리스크 플래그를 한 번에.
            <br />
            스프레드시트 없이, 투자 의사결정을 더 빠르게.
          </p>
          <div className="hero-cta">
            <button className="btn-primary hero-cta-main" onClick={() => focusAuth('signup')}>무료로 시작 →</button>
            <button className="btn-ghost" type="button" onClick={scrollToSample}>샘플 분석 결과 보기</button>
          </div>
          <div className="hero-proof">
            <div className="avatar-stack">
              <span className="av">K</span><span className="av">L</span><span className="av">P</span>
            </div>
            <span className="hero-proof-text">카드 등록 없이 - 가입 즉시 무료 분석</span>
          </div>
        </div>

        <div className="hero-visual">
          <DealMock />
        </div>
      </section>

      <section className="trust reveal" aria-label="사용 대상">
        <span className="trust-label">실무에서 쓰는 사람들</span>
        <div className="trust-tags">
          {TRUST.map((t) => <span className="trust-tag" key={t}>{t}</span>)}
        </div>
      </section>

      <section className="stat-strip reveal" aria-label="요약">
        {STATS.map((s) => (
          <div className="stat" key={s.k}>
            <span className="stat-v num">{s.v}</span>
            <span className="stat-k">{s.k}</span>
          </div>
        ))}
      </section>

      <section className="sample reveal" id="sample" aria-labelledby="sample-h">
        <div className="feat-head">
          <span className="eyebrow">로그인 없이 미리보기</span>
          <h2 id="sample-h" className="feat-h">실제 분석 결과를 먼저 보세요</h2>
          <p className="modes-sub">
            가입 전에 실제 AI 분석을 같은 화면 그대로 렌더한 <strong>축약 미리보기</strong>입니다.
            탭을 눌러 스크리닝·시장조사·투심 메모·AI 심층 시장 리포트까지 둘러보세요.
          </p>
        </div>
        <SampleShowcase />
      </section>

      <section className="locrep-landing reveal" id="location" aria-labelledby="loc-h">
        <div className="feat-head">
          <span className="eyebrow">무료 체험 <span className="soon-tag">준비중</span></span>
          <h2 id="loc-h" className="feat-h">주소만 넣어보세요 - 무료 동네 리포트</h2>
          <p className="modes-sub">
            인근 단지 스펙과 최근 아파트 실거래를 주소 한 줄로. 주변 교통·학교·편의시설은 순차 오픈 예정입니다.
            상업용 딜이라면 그대로 AI 언더라이팅까지.
          </p>
        </div>
        <LocationReportView embedded onWantMore={() => focusAuth('signup')} />
      </section>

      <section className="feat reveal" id="features" aria-labelledby="feat-h">
        <div className="feat-head">
          <span className="eyebrow">무엇을 해주나</span>
          <h2 id="feat-h" className="feat-h">계산과 판단을 한 번에</h2>
        </div>
        <div className="feat-grid">
          {FEATURES.map((f, i) => (
            <article className="feat-card" key={f.title}>
              <div className="feat-card-top">
                <span className="feat-ico"><Spark /></span>
                <span className="feat-n num">{String(i + 1).padStart(2, '0')}</span>
              </div>
              <h3 className="feat-title">{f.title}</h3>
              <p className="feat-desc">{f.desc}</p>
              <ul className="feat-tags">
                {f.tags.map((t) => <li className="feat-tag" key={t}>{t}</li>)}
              </ul>
            </article>
          ))}
        </div>
      </section>

      <section className="steps reveal" aria-labelledby="steps-h">
        <div className="feat-head">
          <span className="eyebrow">작동 방식</span>
          <h2 id="steps-h" className="feat-h">세 단계면 충분합니다</h2>
        </div>
        <div className="steps-grid">
          {STEPS.map((s) => (
            <div className="step" key={s.n}>
              <span className="step-n num">{s.n}. {s.title}</span>
              <p className="step-desc">{s.desc}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="modes reveal" aria-labelledby="modes-h">
        <div className="feat-head">
          <span className="eyebrow">메뉴 안내</span>
          <h2 id="modes-h" className="feat-h">두 가지 분석, 언제 무엇을</h2>
          <p className="modes-sub">‘언더라이팅’은 매입 한 건 심사, ‘심화 분석’은 딜 전반 전문 도구함입니다.</p>
        </div>
        <div className="mode-grid">
          {MODES.map((m) => (
            <article className="mode-card" key={m.tag}>
              <span className="mode-tag">{m.tag}</span>
              <h3 className="mode-title">{m.title}</h3>
              <p className="mode-desc">{m.desc}</p>
              <ul className="mode-list">
                {m.items.map((it) => <li key={it}><Check /> {it}</li>)}
              </ul>
              <div className="mode-when">{m.when}</div>
            </article>
          ))}
        </div>
      </section>

      <section className="vcompare reveal" aria-labelledby="vc-h">
        <div className="feat-head">
          <span className="eyebrow">비용 비교</span>
          <h2 id="vc-h" className="feat-h">전문가 vs AixNative</h2>
          <span className="vc-badge">수일 → 1분 · 비용 한 자릿수 천 원대</span>
        </div>
        <div className="vc-table" role="table" aria-label="전문가 의뢰와 AixNative 비교">
          <div className="vc-row vc-row-head" role="row">
            <span className="vc-cell vc-item" role="columnheader">산출물</span>
            <span className="vc-cell vc-them" role="columnheader">전문가 / 컨설팅</span>
            <span className="vc-cell vc-us" role="columnheader">AixNative</span>
          </div>
          {COMPARE.map((r) => (
            <div className="vc-row" role="row" key={r.item}>
              <span className="vc-cell vc-item" role="cell">
                <strong>{r.item}</strong><span className="vc-detail">{r.detail}</span>
              </span>
              <span className="vc-cell vc-them" role="cell">{r.them}</span>
              <span className="vc-cell vc-us" role="cell"><Check /> {r.us}</span>
            </div>
          ))}
        </div>
        <p className="vc-foot">
          전문가 한 건 비용으로 수십 건을 돌려봅니다.
          <span className="vc-disc"> * 비용·소요 시간은 예시이며 실제는 사례마다 다르며, 단계 별 비용은 추후 변경될 수 있습니다.</span>
        </p>
      </section>

      <section className="auth-section reveal" aria-labelledby="auth-h">
        <div className="auth-section-copy">
          <h2 id="auth-h" className="auth-section-h">지금 첫 딜을<br />심사해 보세요</h2>
          <p className="auth-section-sub">가입 즉시 무료 크레딧. 카드 등록은 필요 없습니다.</p>
          <ul className="auth-bullets">
            <li><Check /> ProForma 지표는 언제나 무료</li>
            <li><Check /> AI 분석 = 버튼 한 번, 분석별 1~5크레딧</li>
            <li><Check /> 보고서 즉시 확인하고 공유</li>
          </ul>
        </div>
        <div className="hero-auth">
          <AuthView onAuthed={onAuthed} initialError={oauthError} requestMode={authMode} focusSignal={authFocus} />
        </div>
      </section>

      <SiteFooter />
    </div>
  )
}
