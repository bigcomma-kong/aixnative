import { useEffect, useState } from 'react'
import { api, tokenStore, type AuthResult, type UserRole } from './api'
import { LandingView } from './LandingView'
import { UnderwriteView } from './UnderwriteView'
import { DocAnalysisView } from './DocAnalysisView'
import { MarketFeedView } from './MarketFeedView'
import { CreditHistoryView } from './CreditHistoryView'
import { AdminView } from './AdminView'
import { ResetPasswordView } from './ResetPasswordView'
import { Paywall } from './Paywall'
import { Checkout } from './Checkout'
import { PaymentResult, readPaymentCallback, type PaymentCallback } from './PaymentResult'

/** 메일의 `/?reset=<token>` 링크로 진입했는지 — 부팅 시 한 번 읽는다. */
function readResetToken(): string | null {
  const t = new URLSearchParams(window.location.search).get('reset')
  return t && t.trim() ? t : null
}

/**
 * 소셜 로그인 콜백 해시(`/#token=` 또는 `/#oauth_error=`) 소비. 부팅 시 한 번.
 * 해시(프래그먼트)는 서버로 전송되지 않아 토큰이 로그/Referer 에 남지 않는다. 읽은 뒤 즉시 URL 정리.
 */
function consumeOAuthHash(): { token?: string; error?: string } {
  const raw = window.location.hash.replace(/^#/, '')
  if (!raw) return {}
  const h = new URLSearchParams(raw)
  const token = h.get('token')
  const error = h.get('oauth_error')
  if (token || error) {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  }
  return { token: token ?? undefined, error: error ?? undefined }
}

type Plan = 'FREE' | 'PAID'
type Tab = 'feed' | 'underwrite' | 'advanced' | 'credits' | 'admin'

interface Session {
  email: string
  creditBalance: number
  plan: Plan
  role: UserRole
  emailVerified: boolean
}

function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [booting, setBooting] = useState(true)
  const [tab, setTab] = useState<Tab>('feed')
  // 분석별 크레딧 단가(서버 단일 소스). 로그인 후 1회 로드해 버튼 라벨에 사용.
  const [toolCosts, setToolCosts] = useState<Record<string, number>>({})
  // 메일의 비밀번호 재설정 링크로 들어온 경우, 인증 상태와 무관하게 재설정 화면을 띄운다.
  const [resetToken, setResetToken] = useState<string | null>(() => readResetToken())
  // 시장 피드 '이 딜 분석하기' → 심화 분석으로 넘길 딜 원문(진입 신호).
  const [dealSeed, setDealSeed] = useState<string | undefined>(undefined)
  // 소셜 로그인 콜백 해시 소비(부팅 시 1회). 토큰이 있으면 세션 복원 흐름이 그대로 받아간다.
  const [oauthHash] = useState(() => consumeOAuthHash())
  const oauthError: string | null = oauthHash.error ?? null

  // 크레딧 소진 시(어느 분석 버튼이든 402) 화면 중앙에 페이월 안내를 띄운다.
  const [showPaywall, setShowPaywall] = useState(false)
  // 크레딧 충전 결제 모달.
  const [showCheckout, setShowCheckout] = useState(false)
  // 토스 결제 후 리다이렉트 콜백(?pay=1&...) — 부팅 시 한 번 읽는다.
  const [paymentCb, setPaymentCb] = useState<PaymentCallback | null>(() => readPaymentCallback())

  function openCheckout() {
    setShowPaywall(false)
    setShowCheckout(true)
  }

  // 결제 결과 화면 닫기: URL 의 ?pay= 쿼리를 제거하고 잔액을 서버값으로 재동기화.
  function closePaymentResult() {
    setPaymentCb(null)
    window.history.replaceState(null, '', window.location.pathname)
    if (tokenStore.get()) {
      api.me().then((me) => patchSession({ creditBalance: me.creditBalance })).catch(() => {})
    }
  }

  function analyzeDeal(sourceText: string) {
    setDealSeed(sourceText)
    setTab('advanced')
  }

  // 402 발생 시: 잔여 크레딧 0 으로 보정(상단 배너) + 중앙 안내 모달 노출.
  function handleNeedCredits() {
    patchSession({ creditBalance: 0 })
    setShowPaywall(true)
  }

  // 앱 시작 시 저장된 토큰이 있으면 세션 복원. (plan 은 결제 도입 전까지 FREE; 사용 내역에서 서버값으로 보정)
  useEffect(() => {
    // 소셜 로그인 콜백으로 받은 토큰을 먼저 저장 → 아래 복원 흐름이 그대로 사용.
    if (oauthHash.token) tokenStore.set(oauthHash.token)
    if (!tokenStore.get()) {
      setBooting(false)
      return
    }
    api.me()
      .then((me) => setSession({ email: me.email, creditBalance: me.creditBalance, plan: 'FREE', role: me.role ?? 'USER', emailVerified: me.emailVerified }))
      .catch(() => tokenStore.clear())
      .finally(() => setBooting(false))
  }, [])

  // 인증 배너 자가 치유: 미인증 상태에서 탭으로 돌아오면(메일 링크 클릭 후 복귀 등) me() 를
  // 다시 조회해 verified 면 배너를 자동으로 내린다. 수동 '인증 완료' 클릭에 의존하지 않음.
  const needsVerifyRefresh = !!session && session.role !== 'ADMIN' && !session.emailVerified
  useEffect(() => {
    if (!needsVerifyRefresh) return
    function refresh() {
      if (document.visibilityState === 'hidden') return
      api.me()
        .then((me) => { if (me.emailVerified) patchSession({ emailVerified: true, creditBalance: me.creditBalance }) })
        .catch(() => {})
    }
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [needsVerifyRefresh])

  // 세션이 생기면 가격표를 1회 로드(정적 — 모든 유저 동일). 실패 시 라벨은 숫자 생략 폴백.
  useEffect(() => {
    if (!session || Object.keys(toolCosts).length > 0) return
    api.pricing().then((p) => setToolCosts(p.toolCosts)).catch(() => {})
  }, [session, toolCosts])

  function onAuthed(result: AuthResult) {
    setSession({
      email: result.email,
      creditBalance: result.creditBalance,
      plan: (result.plan as Plan) ?? 'FREE',
      role: result.role ?? 'USER',
      emailVerified: result.emailVerified,
    })
  }

  function logout() {
    tokenStore.clear()
    setSession(null)
    setTab('underwrite')
  }

  function patchSession(patch: Partial<Session>) {
    setSession((s) => (s ? { ...s, ...patch } : s))
  }

  // 재설정 화면을 닫을 때: URL 의 ?reset= 쿼리를 제거하고 일반 흐름으로 복귀.
  function closeReset() {
    setResetToken(null)
    window.history.replaceState(null, '', window.location.pathname)
  }

  if (resetToken) return <ResetPasswordView token={resetToken} onDone={closeReset} />
  if (booting) return <div className="spinner">불러오는 중…</div>
  // 토스 결제 리다이렉트로 돌아온 경우: 승인검증·결과 화면을 최우선 표시(로그인 여부 무관).
  if (paymentCb) {
    return (
      <PaymentResult
        cb={paymentCb}
        onConfirmed={(balance) => patchSession({ creditBalance: balance })}
        onDone={closePaymentResult}
      />
    )
  }
  if (!session) return <LandingView onAuthed={onAuthed} oauthError={oauthError} />

  const initial = session.email.trim().charAt(0).toUpperCase() || '?'
  const isAdmin = session.role === 'ADMIN'

  return (
    <>
      <header className="topbar">
        <div className="brand">aix<span>native</span></div>
        <nav className="topnav" aria-label="주요 메뉴">
          <button aria-current={tab === 'feed'} onClick={() => setTab('feed')}>시장</button>
          <button aria-current={tab === 'underwrite'} onClick={() => setTab('underwrite')}>언더라이팅</button>
          <button aria-current={tab === 'advanced'} onClick={() => setTab('advanced')}>심화 분석</button>
          <button aria-current={tab === 'credits'} onClick={() => setTab('credits')}>사용 내역</button>
          {isAdmin && (
            <button aria-current={tab === 'admin'} onClick={() => setTab('admin')}>관리자</button>
          )}
        </nav>
        <div className="topbar-right">
          <span className="credits">
            {isAdmin ? (
              <>
                <span className="plan-pill admin">ADMIN</span>
                크레딧 <b className="num">무제한</b>
              </>
            ) : (
              <>
                {session.plan === 'FREE' && <span className="plan-pill free">무료</span>}
                크레딧 <b className="num">{session.creditBalance}</b>
                <button type="button" className="btn-topup" onClick={openCheckout}>충전</button>
              </>
            )}
          </span>
          <div className="who">
            <span className="avatar" aria-hidden="true">{initial}</span>
            <span className="muted">{session.email}</span>
          </div>
          <button className="btn-link" onClick={logout}>로그아웃</button>
        </div>
      </header>

      {!isAdmin && !session.emailVerified && (
        <VerifyBanner onVerified={(creditBalance) => patchSession({ emailVerified: true, creditBalance })} />
      )}

      {!isAdmin && session.emailVerified && session.creditBalance <= 0 && (
        <div className="paywall-bar">
          <Paywall creditBalance={0} variant="banner" onTopUp={openCheckout} />
        </div>
      )}

      <main className={tab === 'feed' ? 'wide' : undefined}>
        {tab === 'feed' && (
          <MarketFeedView
            isAdmin={isAdmin}
            onAnalyzeDeal={analyzeDeal}
            onCreditBalance={(balance) => patchSession({ creditBalance: balance })}
            onNeedCredits={handleNeedCredits}
            toolCosts={toolCosts}
          />
        )}
        {tab === 'underwrite' && (
          <UnderwriteView
            onCreditBalance={(balance) => patchSession({ creditBalance: balance })}
            onNeedCredits={handleNeedCredits}
            toolCosts={toolCosts}
          />
        )}
        {tab === 'advanced' && (
          <DocAnalysisView
            onCreditBalance={(balance) => patchSession({ creditBalance: balance })}
            onNeedCredits={handleNeedCredits}
            initialDealText={dealSeed}
            toolCosts={toolCosts}
          />
        )}
        {tab === 'credits' && (
          <CreditHistoryView onSync={(plan, creditBalance) => patchSession({ plan, creditBalance })} />
        )}
        {tab === 'admin' && isAdmin && (
          <AdminView currentEmail={session.email} />
        )}
      </main>

      {showPaywall && (
        <div
          className="analyze-overlay"
          role="dialog"
          aria-modal="true"
          aria-label="크레딧 소진 안내"
          onClick={() => setShowPaywall(false)}
        >
          <div className="paywall-modal" onClick={(e) => e.stopPropagation()}>
            <Paywall creditBalance={0} variant="card" onTopUp={openCheckout} />
            <button type="button" className="btn-ghost paywall-modal-close" onClick={() => setShowPaywall(false)}>
              닫기
            </button>
          </div>
        </div>
      )}

      {showCheckout && (
        <Checkout creditBalance={session.creditBalance} customerEmail={session.email} onClose={() => setShowCheckout(false)} />
      )}
    </>
  )
}

/** 미인증 사용자 상단 배너 — 인증 메일 재전송 + 인증 완료 확인(새로고침). */
function VerifyBanner({ onVerified }: { onVerified: (creditBalance: number) => void }) {
  const [status, setStatus] = useState<'idle' | 'sending' | 'checking'>('idle')
  const [msg, setMsg] = useState<string | null>(null)

  async function resend() {
    setStatus('sending')
    setMsg(null)
    try {
      await api.resendVerification()
      setMsg('인증 메일을 다시 보냈습니다. 메일함(스팸함 포함)을 확인하세요.')
    } catch {
      setMsg('재발송에 실패했습니다. 잠시 후 다시 시도하세요.')
    } finally {
      setStatus('idle')
    }
  }

  async function check() {
    setStatus('checking')
    setMsg(null)
    try {
      const me = await api.me()
      if (me.emailVerified) onVerified(me.creditBalance)
      else setMsg('아직 인증이 확인되지 않았습니다. 메일의 링크를 클릭한 뒤 다시 눌러주세요.')
    } catch {
      setMsg('확인 중 오류가 발생했습니다.')
    } finally {
      setStatus('idle')
    }
  }

  return (
    <div className="paywall-bar">
      <div className="verify-banner">
        <div className="vb-main">
          <strong className="vb-title">이메일 인증을 완료하면 무료 분석 크레딧이 지급됩니다</strong>
          <p className="vb-sub">
            가입하신 이메일로 보낸 인증 링크를 클릭한 뒤 ‘인증 완료’를 눌러주세요.{msg ? ` · ${msg}` : ''}
          </p>
        </div>
        <div className="vb-actions">
          <button className="btn-ghost" onClick={resend} disabled={status === 'sending'}>
            {status === 'sending' ? '보내는 중…' : '인증 메일 재전송'}
          </button>
          <button className="btn-primary" onClick={check} disabled={status === 'checking'}>
            {status === 'checking' ? '확인 중…' : '인증 완료'}
          </button>
        </div>
      </div>
    </div>
  )
}

export default App
