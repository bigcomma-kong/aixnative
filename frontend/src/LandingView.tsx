import { AuthView } from './AuthView'
import type { AuthResult } from './api'

interface LandingViewProps {
  onAuthed: (result: AuthResult) => void
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
            <strong className="mock-name">강남 오피스 · Core</strong>
          </div>
          <span className="mock-verdict">GO · 투자 적격</span>
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
  { title: 'ProForma 지표', desc: 'IRR · Equity Multiple · DSCR · 민감도까지 순수 계산 로직으로 즉시 산출. 입력만 하면 끝.' },
  { title: 'AI 언더라이팅', desc: '매입가·NOI·Cap·자본구조를 읽고 스크리닝 판정과 투자 내러티브, 리스크 플래그를 생성.' },
  { title: '심화 분석 9종', desc: 'BOV 가치평가 · 개발 타당성 · 세무 진단 · 시장조사 · 거래상대방 실사를 한 화면에서.' },
] as const

const STEPS = [
  { n: '01', title: '딜 입력', desc: '매입가 · NOI · Cap · LTV · 금리 · 출구 Cap 한 번 입력.' },
  { n: '02', title: '1클릭 분석', desc: 'ProForma는 무료, AI 심사는 버튼 한 번 = 1크레딧.' },
  { n: '03', title: '보고서 확보', desc: '지표 · 차트 · AI 내러티브 · 투심 메모를 즉시 확인 · 공유.' },
] as const

const STATS = [
  { v: '1분', k: '딜 한 건 심사' },
  { v: '4종', k: 'AI 분석 파이프라인' },
  { v: '9종', k: '심화 분석 도구' },
  { v: '무료', k: '가입 즉시 크레딧' },
] as const

const TRUST = ['자산운용', '디벨로퍼', 'REIT', 'PE / 펀드', '중개법인']

export function LandingView({ onAuthed }: LandingViewProps) {
  function focusAuth() {
    document.getElementById('auth')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setTimeout(() => document.getElementById('email')?.focus(), 450)
  }

  return (
    <div className="landing">
      <div className="landing-bg" aria-hidden="true" />

      <header className="landing-nav">
        <div className="brand">aix<span>native</span></div>
        <div className="landing-nav-actions">
          <button className="btn-link" onClick={focusAuth}>로그인</button>
          <button className="btn-primary nav-cta" onClick={focusAuth}>무료로 시작</button>
        </div>
      </header>

      <section className="hero reveal" aria-labelledby="hero-h1">
        <div className="hero-copy">
          <span className="hero-eyebrow"><Spark /> AI 부동산 딜 언더라이팅</span>
          <h1 id="hero-h1" className="hero-title">
            매물 한 건,<br /><em>1분 심사.</em>
          </h1>
          <p className="hero-sub">
            매입가와 자본구조만 넣으면 ProForma 지표 계산과 AI 투자 심사·리스크 플래그를
            한 번에. 스프레드시트 없이, 투자 의사결정을 더 빠르게.
          </p>
          <div className="hero-cta">
            <button className="btn-primary hero-cta-main" onClick={focusAuth}>무료로 시작 →</button>
            <a className="btn-ghost" href="#features">작동 방식 보기</a>
          </div>
          <div className="hero-proof">
            <div className="avatar-stack">
              <span className="av">K</span><span className="av">L</span><span className="av">P</span>
            </div>
            <span className="hero-proof-text">카드 등록 없이 · 가입 즉시 무료 분석</span>
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

      <section className="feat reveal" id="features" aria-labelledby="feat-h">
        <div className="feat-head">
          <span className="eyebrow">무엇을 해주나</span>
          <h2 id="feat-h" className="feat-h">계산과 판단을 한 번에</h2>
        </div>
        <div className="feat-grid">
          {FEATURES.map((f) => (
            <article className="feat-card" key={f.title}>
              <span className="feat-ico"><Spark /></span>
              <h3 className="feat-title">{f.title}</h3>
              <p className="feat-desc">{f.desc}</p>
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
              <span className="step-n num">{s.n}</span>
              <h3 className="step-title">{s.title}</h3>
              <p className="step-desc">{s.desc}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="auth-section reveal" aria-labelledby="auth-h">
        <div className="auth-section-copy">
          <h2 id="auth-h" className="auth-section-h">지금 첫 딜을<br />심사해 보세요</h2>
          <p className="auth-section-sub">가입 즉시 무료 크레딧. 카드 등록은 필요 없습니다.</p>
          <ul className="auth-bullets">
            <li><Check /> ProForma 지표는 언제나 무료</li>
            <li><Check /> AI 분석 = 버튼 한 번, 1크레딧</li>
            <li><Check /> 보고서 즉시 확인 · 공유</li>
          </ul>
        </div>
        <div className="hero-auth">
          <AuthView onAuthed={onAuthed} />
        </div>
      </section>

      <footer className="landing-footer">
        <div className="brand">aix<span>native</span></div>
        <p className="landing-disc">
          * 본 서비스는 정보 제공 목적이며 투자자문이 아닙니다. 모든 투자 판단의 책임은 이용자에게 있습니다.
        </p>
        <p className="landing-copy">© aixnative</p>
      </footer>
    </div>
  )
}
